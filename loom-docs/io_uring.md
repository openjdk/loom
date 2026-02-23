# io_uring prototypes

## 1. Poller implementation

Simple Poller implementation that uses `IORING_OP_POLL_ADD` to poll a file descriptor.
Selected when run with `-Djdk.io_uring=true`.

Functional, and suited to pollerMode=3 (`io_uring` instance per carrier thread),
but will not perform as well as the `epoll` based Poller. Batching of submits, to
reduce calls to `io_uring_enter`, was prototyped but the batching added latency
and reduced performance overall.


## 2. Poller implementation with submission queue polling

Builds on prototype 1 but uses `IORING_SETUP_SQPOLL` to use a kernel thread to poll the
submission queue. Enabled with `-Djdk.io_uring.sqpoll_idle=<N>` where N is the duration
(in milliseconds) for the kernel thread to spin before sleeping.

For the majority case where the thread is still running, this is only a cheap memory access.
The default value for this parameter is zero, which means submission queue polling is not
enabled.

## 3. Blocking read/write implemented on async read/write

Extends Poller implementation to support read and write operations using `IORING_OP_READ`
and `IORING_OP_WRITE`.

Enable for `java.net.Socket` read/write with `-Djdk.io_uring.read=true` and
`-Djdk.io_uring.write=true`.

## 4. Blocking read/write implemented on async readv/writev with registered buffers

(not in loom repo at this time)

Uses `IORING_OP_READ_FIXED` and `IORING_OP_WRITE_FIXED` with buffers that are
registered with kernel.
