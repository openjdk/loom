# Custom Schedulers

The following is a summary of the experimental support for custom virtual thread schedulers
in the loom repo.

The purpose of the experimental support is to allow exploration and provide feedback to help
inform the project on whether to expose anything.



## Using a custom scheduler as the default scheduler

The default virtual thread scheduler is selected when starting a new virtual thread with
`Thread.startVirtualThread`, or creating a virtual thread with the `Thread.Builder` API and
not specifying a scheduler The default scheduler is a `ForkJoinPool` instance that is
configured in FIFO mode.  A different default scheduler can be used by setting a system
property on the command line.

```
-Djdk.virtualThreadScheduler.implClass=$EXECUTOR
```

where $EXECUTOR is fully qualified name of a class that implements `java.util.concurrent.Executor`.

The class must be public, with a public no-arg constructor, and deployed on the class path
or in an exported package of a module on the module path.

The scheduler's `execute` method will be invoked with tasks of type `Thread.VirtualThreadTask`.
The scheduler must arrange to execute the tasks on a platform thread,.



## Selecting a custom scheduler when creating a virtual thread

`Thread.Builder.OfVirtual.scheduler(Executor)` can be used to set the scheduler when creating
a virtual thread. The following uses a thread pool with 8 threads as the scheduler.

```
ExecuorService pool = Executors.newFixedThreadPool(8);

Thread thread = Thread.ofVirtual().scheduler(pool).start(() -> { });
thread.join();
```

As with a custom default scheduler, the scheduler's `execute` method will be invoked with
tasks of type `Thread.VirtualThreadTask`. The scheduler must arrange to execute the tasks o
n a platform thread.



## Thread.VirtualThreadTask

A custom scheduler can cast the `Runnable` task to `Thread.VirtualThreadTask`.  This allows
the scheduler to know which virtual thread needs to execute. It also allows the scheduler
to attach arbitrary context. The following is an example uses an attached object to give
the mounted virtual thread a reference to is carrier thread, and to the scheduler.

```
record Context(Executor scheduler, Thread carrier) { }

@Override
public void execute(Runnable task) {
    pool.submit(() -> {
        var vthreadTask = (Thread.VirtualThreadTask) task;
        vthreadTask.attach(new Context(this, Thread.currentThread()));
        try {
            vthreadTask.run();
        } finally {
            vthreadTask.attach(null);
        }
    });
}

Thread.ofVirtual()
      .scheduler(customScheduler)
      .start(() -> {

          if (Thread.VirtualThreadTask.currentVirtualThreadTaskAttachment()
                  instanceof Context ctxt) {
              Executor scheduler = ctxt.scheduler();
              Thread carrier = ctxt.carrier();
          }

      });
```







