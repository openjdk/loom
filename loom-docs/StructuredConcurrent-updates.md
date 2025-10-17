
# Structured Concurrency updates

The following is a summary of the changes to `StructuredTaskScope` in the `fibers` branch
of the loom repo:

- The `configFunction` parameter to the 3-arg `open` is changed from
  `Function<Configuration, Configuration>` to `UnaryOperator<Configuration>`.

- `Subtask::get` and `Subtask::exception` changed to consistently throw if called from
  any thread before the scope owner has joined.

- `StructuredTaskScope::join` is now specified so that it may be called again if interrupted.

- `Joiner:onTimeout` is added  with `join` method changed to invoke this joiner method if
  a timeout is configured and the timeout expires before or while waiting. The `onTimeout`
  throws `TimeoutException` or may do nothing. This allows for `Joiner` implementation that
  are capable of returning a result from the subtasks that complete before the timeout expires.

- The parameter on `Joiner::onFork` and `Joiner::onComplete` is changed from
  `Subtask<? extends T` to `Subtask<T>`.

- `Joiner.allSuccessOrThrow` is changed to return a list of results instead of a stream of
  subtasks.

- `Joiner.anySuccessfulResultOrThrow` is renamed to `anySuccessfulOrThrow`.

- `Joiner.allUntil(Predicate)` is changed to allow `join` return the stream of all forked
subtasks when the timeout expires.

- `Joiner` is no longer a `@FunctionalInterface`.
