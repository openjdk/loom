# Custom Schedulers

The following is a summary of the experimental support for custom virtual thread schedulers
in the `fibers` branch of the loom repo.

The purpose of the experimental support is to allow exploration and get feedback to help
inform this project on whether to expose anything.

The experimental support may change or be removed at any time.

## Using a custom scheduler as the virtual thread scheduler

The JDK's built-in virtual thread scheduler is a `ForkJoinPool` instance that is
configured in FIFO mode.

The virtual thread scheduler can be configured to be a custom scheduler by setting
a system property on the command line:


```
-Djdk.virtualThreadScheduler.implClass=<scheduler-class>
```

where `<scheduler-class>` is fully qualified name of a class that implements
`java.lang.Thread.VirtualThreadScheduler`.

The custom scheduler may use its own pool of platform threads, may assign virtual threads
to be carried by specific platform threads, or may delegate to the built-in virtual thread
scheduler.

The implementation class must be public, with a public no-arg or one-arg constructor, and
deployed on the class path or in an exported package of a module on the module path. If the
class has a one-arg constructor then the parameter is a `java.lang.Thread.VirtualThreadScheduler`
that is a reference to the built-in scheduler (this allows the custom scheduler
to delegate to the built-in scheduler if required).


## API to select a custom scheduler when creating a virtual thread

The `Thread.Builder.OfVirtual.scheduler(Thread.VirtualThreadScheduler)` API can be used
to set the scheduler when creating a virtual thread. The following example uses a thread
pool with 8 threads as the scheduler.

```
ExecuorService pool = Executors.newFixedThreadPool(8);
var scheduler = Thread.VirtualThreadScheduler.adapt(pool);

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