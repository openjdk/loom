
# Structured Concurrency updates

The following is a summary of the changes to `StructuredTaskScope` in the `fibers` branch
of the loom repo:

- The `configFunction` parameter to the 3-arg `open` is changed from
`Function<Configuration, Configuration>` to `UnaryOperator<Configuration>`.

- The `join` method is changed to invoke `Joiner::onTimeout` if a timeout is configured
and the timeout expires before or while waiting. The `onTimeout` throws `TimeoutException`
or may do nothing. This allows for `Joiner` implementation that are capable of returning
a result from the subtasks that complete before the timeout expires.

- `Joiner.allUntil(Predicate)` is changed to allow `join` return the stream of all forked
subtasks when the timeout expires.

- The `join` is now specified so that it may be called again if interrupted.

- `Joiner` is no longer a `@FunctionalInterface`