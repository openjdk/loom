# io_uring prototypes

## 1. Poller implementation

Simple Poller implementation that uses `IORING_OP_POLL_ADD` to poll a file descriptor.

Functional and works well with pollerMode=3 (`io_uring` instance per carrier thread)
but will not perform as well as the `epoll` based Poller. Batching of submits
reduce calls to `io_uring_enter`, but adds latency and reduces performance overall.


## 2. Poller implementation with submission queue polling

(not in loom repo at this time)

Builds on prototype 1 but uses `IORING_SETUP_SQPOLL` to use a kernel thread to poll
the submission queue.

## 3. Blocking read/write implemented on async readv/writev

(not in loom repo at this time)

Uses `IORING_OP_READV` and `IORING_OP_WRITEV` with caller owned buffer and iovec.


## 4. Blocking read/write implemented on async readv/writev with registered buffers

(not in loom repo at this time)

Uses `IORING_OP_READ_FIXED` and `IORING_OP_WRITE_FIXED` with buffers that are
registered with kernel.