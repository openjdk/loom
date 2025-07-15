# Custom Schedulers

The following is a summary of the experimental support for custom virtual thread schedulers
in the loom repo.

The purpose of the experimental support is to allow exploration and provide feedback to help
inform this project on whether to expose anything.

The experimental support may change or be removed at any time.



## Using a custom scheduler as the default scheduler

The default virtual thread scheduler is selected when starting a new virtual thread with
`Thread.startVirtualThread`, or creating a virtual thread with the `Thread.Builder` API and
not specifying a scheduler The default scheduler is a `ForkJoinPool` instance that is
configured in FIFO mode.  A different default scheduler can be used by setting a system
property on the command line.

```
-Djdk.virtualThreadScheduler.implClass=$EXECUTOR
```

where $EXECUTOR is fully qualified name of a class that implements `Thread.VirtualThreadScheduler`.

The class must be public, with a public no-arg or one-arg constructor, and deployed on the
class path or in an exported package of a module on the module path. If the class has a
one-arg constructor then the parameter is a `Thread.VirtualThreadScheduler` that is a
reference to the built-in default scheduler. This allows a custom scheduler to wrap or
delegate the built-in default scheduler.


## Selecting a custom scheduler when creating a virtual thread

`Thread.Builder.OfVirtual.scheduler(Thread.VirtualThreadScheduler)` can be used to set the
scheduler when creating a virtual thread. The following uses a thread pool with 8 threads
as the scheduler.

```
ExecuorService pool = Executors.newFixedThreadPool(8);
var scheduler = Thread.VirtualThreadScheduler.adapt(pool);

Thread thread = Thread.ofVirtual().scheduler(scheduler).start(() -> { });
thread.join();
```