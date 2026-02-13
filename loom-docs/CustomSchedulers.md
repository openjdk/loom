# Custom Schedulers

The following is a summary of the experimental support for custom virtual thread schedulers.

The purpose of the experimental support is to allow exploration and get feedback to help
inform this project on whether to expose anything.

The experimental support may change or be removed at any time.

## Prototype 1: Use a custom scheduler as the virtual thread scheduler

The JDK's built-in virtual thread scheduler is a `ForkJoinPool` instance that is
configured in FIFO mode.

This prototype allows the virtual thread scheduler to be set to different scheduler by
setting a system property on the command line:

```
-Djdk.virtualThreadScheduler.implClass=<scheduler-class>
```

where `<scheduler-class>` is fully qualified name of a class that implements
`java.lang.Thread.VirtualThreadScheduler`. The interface defines the `onStart` and
`onContinue` methods that the custom scheduler must implement. The `onStart` method is
invoked when `Thread::start` is used to start a virtual thread. The `onContinue` method
is invoked when a virtual thread is scheduled to continue after being parked or blocked.
The two methods are called with the task to execute.

The custom scheduler may use its own pool of platform threads to execute the tasks,
may assign virtual threads to be carried by specific platform threads, or may delegate
to the built-in virtual thread scheduler.

The `VirtualThreadScheduler` implementation class must be public, with a public no-arg
or one-arg constructor, and deployed on the class path or in an exported package of a
module on the module path. If the class has a one-arg constructor then the parameter is
a `java.lang.Thread.VirtualThreadScheduler` that is a reference to the built-in default
scheduler (this allows the custom scheduler to delegate to the built-in default
scheduler if required).

If the scheduler class implements `jdk.management.VirtualThreadSchedulerMXBean` then
the management interface will be registered with the platform `MBeanServer`.

## Prototype 2: Use API to select a custom scheduler when creating a virtual thread

(This prototype has been removed from the fibers branch to allow for more testing with
prototype 1)

The `Thread.Builder.OfVirtual.scheduler(Thread.VirtualThreadScheduler)` API can be used
to set the scheduler when creating a virtual thread. The following example uses a thread
pool with 8 threads as the scheduler.

```
ExecutorService pool = Executors.newFixedThreadPool(8);
var scheduler = new VirtualThreadScheduler() {
    @Override
    public void onStart(VirtualThreadTask task) {
        pool.execute(task);
    }
    @Override
    public void onContinue(VirtualThreadTask task) {
        pool.execute(task);
    }
};
Thread thread = Thread.ofVirtual().scheduler(scheduler).start(() -> { });
thread.join();
```

The prototype API allows different parts of a system to use different schedulers.

Custom schedulers are _not inherited_. If a virtual thread assigned to a custom
scheduler invokes `Thread.startVirtualThread(Runnable)` then it starts a virtual
thread assigned to the default scheduler.

Custom schedulers are _not closable_. They are managed by the garbage collector so that
a custom scheduler can be collected when all virtual thread assigned to the scheduler
have terminated and the scheduler is otherwise unreachable. The lifecycle of carrier
threads is managed by the scheduler.

## Time Sharing

Early prototype support for <em>forced preemption</em>, based on thread local handshakes,
exists in a branch in the loom repo. The prototypes in the `fibers` branch for custom
schedulers do not support forced preemption at this time.

## Poller modes

Socket I/O in the context of virtual threads uses a platform specific I/O event notification
facility such as `kqueue`, `epoll`, or `io_uring`. The implementation uses of set of internal
_poller threads_ that consume events from the I/O event notification facility to unpark
virtual threads that are blocked in socket operations. The implementation has a number
of _poller modes_. On macOS and Windows there is a set of platform threads that wait for
events. On Linux, the poller threads that consume the events from the I/O event notification
facility are virtual threads assigned to the default scheduler.

When using a custom scheduler it may be useful to use the poller mode that creates an I/O
event notification system and a poller virtual thread per carrier thread. The poller
thread terminates if the carrier terminates; any outstanding socket I/O operations
are moved to a different carrier's I/O event notification facility. This poller mode is
currently implemented on Linux and macOS and is used when running with `-Djdk.pollerMode=3`.
