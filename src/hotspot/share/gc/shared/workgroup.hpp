/*
 * Copyright (c) 2002, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_GC_SHARED_WORKGROUP_HPP
#define SHARE_GC_SHARED_WORKGROUP_HPP

#include "memory/allocation.hpp"
#include "runtime/globals.hpp"
#include "runtime/thread.hpp"
#include "gc/shared/gcId.hpp"
#include "logging/log.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"

// Task class hierarchy:
//   AbstractGangTask
//
// Gang/Group class hierarchy:
//   AbstractWorkGang
//     WorkGang
//     YieldingFlexibleWorkGang (defined in another file)
//
// Worker class hierarchy:
//   AbstractGangWorker (subclass of WorkerThread)
//     GangWorker
//     YieldingFlexibleGangWorker   (defined in another file)

// Forward declarations of classes defined here

class AbstractGangWorker;
class Semaphore;
class ThreadClosure;
class WorkGang;
class GangTaskDispatcher;

// An abstract task to be worked on by a gang.
// You subclass this to supply your own work() method
class AbstractGangTask {
  const char* _name;
  const uint _gc_id;

 public:
  explicit AbstractGangTask(const char* name) :
    _name(name),
    _gc_id(GCId::current_or_undefined())
  {}

  // The abstract work method.
  // The argument tells you which member of the gang you are.
  virtual void work(uint worker_id) = 0;

  // Debugging accessor for the name.
  const char* name() const { return _name; }
  const uint gc_id() const { return _gc_id; }
};

struct WorkData {
  AbstractGangTask* _task;
  uint              _worker_id;
  WorkData(AbstractGangTask* task, uint worker_id) : _task(task), _worker_id(worker_id) {}
};

// The work gang is the collection of workers to execute tasks.
// The number of workers run for a task is "_active_workers"
// while "_total_workers" is the number of available of workers.
class AbstractWorkGang : public CHeapObj<mtInternal> {
 protected:
  // The array of worker threads for this gang.
  AbstractGangWorker** _workers;
  // The count of the number of workers in the gang.
  uint _total_workers;
  // The currently active workers in this gang.
  uint _active_workers;
  // The count of created workers in the gang.
  uint _created_workers;
  // Printing support.
  const char* _name;

  ~AbstractWorkGang() {}

 private:
  // Initialize only instance data.
  const bool _are_GC_task_threads;
  const bool _are_ConcurrentGC_threads;

  void set_thread(uint worker_id, AbstractGangWorker* worker) {
    _workers[worker_id] = worker;
  }

 public:
  AbstractWorkGang(const char* name, uint workers, bool are_GC_task_threads, bool are_ConcurrentGC_threads);
  // Initialize workers in the gang.  Return true if initialization succeeded.
  void initialize_workers();

  bool are_GC_task_threads()      const { return _are_GC_task_threads; }
  bool are_ConcurrentGC_threads() const { return _are_ConcurrentGC_threads; }

  uint total_workers() const { return _total_workers; }

  uint created_workers() const {
    return _created_workers;
  }

  virtual uint active_workers() const {
    assert(_active_workers <= _total_workers,
           "_active_workers: %u > _total_workers: %u", _active_workers, _total_workers);
    return _active_workers;
  }

  uint update_active_workers(uint v) {
    assert(v <= _total_workers,
           "Trying to set more workers active than there are");
    _active_workers = MIN2(v, _total_workers);
    add_workers(false /* exit_on_failure */);
    assert(v != 0, "Trying to set active workers to 0");
    log_trace(gc, task)("%s: using %d out of %d workers", name(), _active_workers, _total_workers);
    return _active_workers;
  }

  // Add GC workers as needed.
  void add_workers(bool initializing);

  // Add GC workers as needed to reach the specified number of workers.
  void add_workers(uint active_workers, bool initializing);

  // Return the Ith worker.
  AbstractGangWorker* worker(uint i) const;

  // Base name (without worker id #) of threads.
  const char* group_name() { return name(); }

  void threads_do(ThreadClosure* tc) const;

  // Create a GC worker and install it into the work gang.
  virtual AbstractGangWorker* install_worker(uint which);

  // Debugging.
  const char* name() const { return _name; }

 protected:
  virtual AbstractGangWorker* allocate_worker(uint which) = 0;
};

// An class representing a gang of workers.
class WorkGang: public AbstractWorkGang {
  // To get access to the GangTaskDispatcher instance.
  friend class GangWorker;

  GangTaskDispatcher* const _dispatcher;
  GangTaskDispatcher* dispatcher() const {
    return _dispatcher;
  }

public:
  WorkGang(const char* name,
           uint workers,
           bool are_GC_task_threads,
           bool are_ConcurrentGC_threads);

  ~WorkGang();

  // Run a task using the current active number of workers, returns when the task is done.
  virtual void run_task(AbstractGangTask* task);
  // Run a task with the given number of workers, returns
  // when the task is done. The number of workers must be at most the number of
  // active workers.  Additional workers may be created if an insufficient
  // number currently exists. If the add_foreground_work flag is true, the current thread
  // is used to run the task too.
  void run_task(AbstractGangTask* task, uint num_workers, bool add_foreground_work = false);

protected:
  virtual AbstractGangWorker* allocate_worker(uint which);
};

// Temporarily try to set the number of active workers.
// It's not guaranteed that it succeeds, and users need to
// query the number of active workers.
class WithUpdatedActiveWorkers : public StackObj {
private:
  AbstractWorkGang* const _gang;
  const uint              _old_active_workers;

public:
  WithUpdatedActiveWorkers(AbstractWorkGang* gang, uint requested_num_workers) :
      _gang(gang),
      _old_active_workers(gang->active_workers()) {
    uint capped_num_workers = MIN2(requested_num_workers, gang->total_workers());
    gang->update_active_workers(capped_num_workers);
  }

  ~WithUpdatedActiveWorkers() {
    _gang->update_active_workers(_old_active_workers);
  }
};

// Several instances of this class run in parallel as workers for a gang.
class AbstractGangWorker: public WorkerThread {
public:
  AbstractGangWorker(AbstractWorkGang* gang, uint id);

  // The only real method: run a task for the gang.
  virtual void run();
  // Predicate for Thread
  virtual bool is_GC_task_thread() const;
  virtual bool is_ConcurrentGC_thread() const;
  // Printing
  void print_on(outputStream* st) const;
  virtual void print() const;

protected:
  AbstractWorkGang* _gang;

  virtual void initialize();
  virtual void loop() = 0;

  AbstractWorkGang* gang() const { return _gang; }
};

class GangWorker: public AbstractGangWorker {
public:
  GangWorker(WorkGang* gang, uint id) : AbstractGangWorker(gang, id) {}

protected:
  virtual void loop();

private:
  WorkData wait_for_task();
  void run_task(WorkData work);
  void signal_task_done();

  WorkGang* gang() const { return (WorkGang*)_gang; }
};

// A class that acts as a synchronisation barrier. Workers enter
// the barrier and must wait until all other workers have entered
// before any of them may leave.

class WorkGangBarrierSync : public StackObj {
protected:
  Monitor _monitor;
  uint    _n_workers;
  uint    _n_completed;
  bool    _should_reset;
  bool    _aborted;

  Monitor* monitor()        { return &_monitor; }
  uint     n_workers()      { return _n_workers; }
  uint     n_completed()    { return _n_completed; }
  bool     should_reset()   { return _should_reset; }
  bool     aborted()        { return _aborted; }

  void     zero_completed() { _n_completed = 0; }
  void     inc_completed()  { _n_completed++; }
  void     set_aborted()    { _aborted = true; }
  void     set_should_reset(bool v) { _should_reset = v; }

public:
  WorkGangBarrierSync();
  WorkGangBarrierSync(uint n_workers, const char* name);

  // Set the number of workers that will use the barrier.
  // Must be called before any of the workers start running.
  void set_n_workers(uint n_workers);

  // Enter the barrier. A worker that enters the barrier will
  // not be allowed to leave until all other threads have
  // also entered the barrier or the barrier is aborted.
  // Returns false if the barrier was aborted.
  bool enter();

  // Aborts the barrier and wakes up any threads waiting for
  // the barrier to complete. The barrier will remain in the
  // aborted state until the next call to set_n_workers().
  void abort();
};

// A class to manage claiming of subtasks within a group of tasks.  The
// subtasks will be identified by integer indices, usually elements of an
// enumeration type.

class SubTasksDone: public CHeapObj<mtInternal> {
  volatile uint* _tasks;
  uint _n_tasks;
  volatile uint _threads_completed;
#ifdef ASSERT
  volatile uint _claimed;
#endif

  // Set all tasks to unclaimed.
  void clear();

  NONCOPYABLE(SubTasksDone);

public:
  // Initializes "this" to a state in which there are "n" tasks to be
  // processed, none of the which are originally claimed.  The number of
  // threads doing the tasks is initialized 1.
  SubTasksDone(uint n);

  // True iff the object is in a valid state.
  bool valid();

  // Attempt to claim the task "t", returning true if successful,
  // false if it has already been claimed.  The task "t" is required
  // to be within the range of "this".
  bool try_claim_task(uint t);

  // The calling thread asserts that it has attempted to claim all the
  // tasks that it will try to claim.  Every thread in the parallel task
  // must execute this.  (When the last thread does so, the task array is
  // cleared.)
  //
  // n_threads - Number of threads executing the sub-tasks.
  void all_tasks_completed(uint n_threads);

  // Destructor.
  ~SubTasksDone();
};

// As above, but for sequential tasks, i.e. instead of claiming
// sub-tasks from a set (possibly an enumeration), claim sub-tasks
// in sequential order. This is ideal for claiming dynamically
// partitioned tasks (like striding in the parallel remembered
// set scanning). Note that unlike the above class this is
// a stack object - is there any reason for it not to be?

class SequentialSubTasksDone : public StackObj {
protected:
  uint _n_tasks;     // Total number of tasks available.
  volatile uint _n_claimed;   // Number of tasks claimed.
  // _n_threads is used to determine when a sub task is done.
  // See comments on SubTasksDone::_n_threads
  uint _n_threads;   // Total number of parallel threads.
  volatile uint _n_completed; // Number of completed threads.

  void clear();

public:
  SequentialSubTasksDone() {
    clear();
  }
  ~SequentialSubTasksDone() {}

  // True iff the object is in a valid state.
  bool valid();

  // number of tasks
  uint n_tasks() const { return _n_tasks; }

  // Get/set the number of parallel threads doing the tasks to t.
  // Should be called before the task starts but it is safe
  // to call this once a task is running provided that all
  // threads agree on the number of threads.
  uint n_threads() { return _n_threads; }
  void set_n_threads(uint t) { _n_threads = t; }

  // Set the number of tasks to be claimed to t. As above,
  // should be called before the tasks start but it is safe
  // to call this once a task is running provided all threads
  // agree on the number of tasks.
  void set_n_tasks(uint t) { _n_tasks = t; }

  // Attempt to claim the next unclaimed task in the sequence,
  // returning true if successful, with t set to the index of the
  // claimed task.  Returns false if there are no more unclaimed tasks
  // in the sequence.
  bool try_claim_task(uint& t);

  // The calling thread asserts that it has attempted to claim
  // all the tasks it possibly can in the sequence. Every thread
  // claiming tasks must promise call this. Returns true if this
  // is the last thread to complete so that the thread can perform
  // cleanup if necessary.
  bool all_tasks_completed();
};

#endif // SHARE_GC_SHARED_WORKGROUP_HPP
