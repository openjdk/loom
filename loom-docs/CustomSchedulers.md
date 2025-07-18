# Custom Schedulers

The following is a summary of the experimental support for custom virtual thread schedulers
in the loom repo.

The purpose of the experimental support is to allow exploration and get feedback to help
inform this project on whether to expose anything.

The experimental support may change or be removed at any time.

## Using a custom scheduler as the default scheduler

The JDK's built-in virtual thread scheduler is a `ForkJoinPool` instance that is
configured in FIFO mode.

A default scheduler can be used by setting a system
property on the command line.

```
-Djdk.virtualThreadScheduler.implClass=<scheduler-class>
```

where `<scheduler-class>` is fully qualified name of a class that implements
`java.lang.Thread.VirtualThreadScheduler`. The scheduler's `execute` method is invoked
to continue execution of a virtual thread.

A custom scheduler may use its own pool of platform threads, may assign virtual threads to
be carried by specific platform threads, or may delegate to the built-in default scheduler.

The implementation class must be public, with a public no-arg or one-arg constructor, and
deployed on the class path or in an exported package of a module on the module path. If the
class has a one-arg constructor then the parameter is a `java.lang.Thread.VirtualThreadScheduler`
that is a reference to the built-in default scheduler.


## Use the API to select a custom scheduler when creating a virtual thread

`Thread.Builder.OfVirtual.scheduler(Thread.VirtualThreadScheduler)` can be used to set the
scheduler when creating a virtual thread. The experimental supports allows several
schedulers to be in use at the same time.

The following example uses a thread pool with 8 threads as the scheduler.

```
ExecuorService pool = Executors.newFixedThreadPool(8);
var scheduler = Thread.VirtualThreadScheduler.adapt(pool);

Thread thread = Thread.ofVirtual().scheduler(scheduler).start(() -> { });
thread.join();
```