/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */
package java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * This class consists exclusively of static methods to support the default
 * methods defined by ExecutorService.
 */
class ExecutorServiceHelper {
    private ExecutorServiceHelper() { }

    /**
     * Submits the given value-returning tasks for execution and returns a
     * lazily populated stream of completed Future objects with the result
     * of each task.
     */
    static <T> Stream<Future<T>> submit(ExecutorService executor,
                                        Collection<? extends Callable<T>> tasks) {
        if (tasks.size() == 0)
            return Stream.empty();
        var queue = new LinkedTransferQueue<Future<T>>();
        var futures = List.copyOf(submit(executor, tasks, queue));
        Runnable cancelAll = () -> cancelAll(futures);
        var spliterator = new BlockingQueueSpliterator<>(queue, futures.size(), cancelAll);
        return StreamSupport.stream(spliterator, false).onClose(cancelAll);
    }

    /**
     * Executes the given tasks, returning a list of Futures holding their
     * status and results when all complete. The parameter {@code cancelOnException}
     * determines if this method should wait for unfinished tasks to complete when
     * a task completes with an exception.
     */
    static <T> List<Future<T>> invokeAll(ExecutorService executor,
                                         Collection<? extends Callable<T>> tasks,
                                         boolean cancelOnException)
            throws InterruptedException {

        if (cancelOnException) {
            var queue = new LinkedTransferQueue<Future<T>>();
            List<Future<T>> futures = submit(executor, tasks, queue);
            var spliterator = new BlockingQueueSpliterator<>(queue, futures.size());
            try {
                // wait for a task to complete with exception or all tasks to complete
                StreamSupport.stream(spliterator, false)
                        .filter(f -> isCompletedExceptionally(f))
                        .findAny();
            } catch (CancellationException e) {
                if (Thread.interrupted())
                    throw new InterruptedException();
                throw e;
            } finally {
                cancelAll(futures);
            }
            return futures;
        } else {
            List<Future<T>> futures = new ArrayList<>();
            int j = 0;
            try {
                for (Callable<T> t : tasks) {
                    Future<T> f = executor.submit(t);
                    futures.add(f);
                }
                for (int size = futures.size(); j < size; j++) {
                    Future<T> f = futures.get(j);
                    if (!f.isDone()) {
                        try {
                            f.get();
                        } catch (ExecutionException | CancellationException ignore) { }
                    }
                }
            } finally {
                cancelAll(futures, j);
            }
            return futures;
        }
    }

    /**
     * Submits the given tasks for execution, returning a list of Futures for
     * the results. The Futures are added to the given queue when the tasks
     * complete.
     */
    private static <T> List<Future<T>> submit(ExecutorService executor,
                                              Collection<? extends Callable<T>> tasks,
                                              BlockingQueue<Future<T>> queue) {
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                var future = new FutureTask<>(task) {
                    @Override
                    protected void done() {
                        queue.add(this);
                    }
                };
                executor.execute(future);
                futures.add(future);
            }
        } catch (Throwable e) {
            cancelAll(futures);
            throw e;
        }
        return futures;
    }

    private static <T> void cancelAll(List<Future<T>> futures) {
        for (Future<T> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
    }

    private static <T> void cancelAll(List<Future<T>> futures, int j) {
        for (int size = futures.size(); j < size; j++) {
            Future<T> f = futures.get(j);
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
    }

    /**
     * Returns true if the Future completed exceptionally.
     */
    private static boolean isCompletedExceptionally(Future<?> future) {
        try {
            future.join();
            return false;
        } catch (CompletionException | CancellationException e) {
            return true;
        }
    }

    /**
     * Simple Spliterator with a BlockingQueue as its source. The spliterator
     * has an optional interrupt handler that is invoked when a thread is
     * interrupted while waiting for an element.
     */
    private static class BlockingQueueSpliterator<T> implements Spliterator<Future<T>> {
        final BlockingQueue<Future<T>> queue;
        final int size;
        final Runnable interruptHandler;
        int taken;   // running count of the number of elements taken

        BlockingQueueSpliterator(BlockingQueue<Future<T>> queue,
                                 int size,
                                 Runnable interruptHandler) {
            this.queue = queue;
            this.size = size;
            this.interruptHandler = interruptHandler;
        }

        BlockingQueueSpliterator(BlockingQueue<Future<T>> queue, int size) {
            this(queue, size, null);
        }

        @Override
        public boolean tryAdvance(Consumer<? super Future<T>> action) {
            Objects.requireNonNull(action);
            if (taken >= size) {
                return false;
            } else {
                Future<T> f;
                try {
                    f = queue.take();
                } catch (InterruptedException e) {
                    if (interruptHandler != null)
                        interruptHandler.run();
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Thread interrupted");
                }
                taken++;
                action.accept(f);
                return true;
            }
        }

        @Override
        public Spliterator<Future<T>> trySplit() {
            return null;
        }

        @Override
        public int characteristics() {
            return Spliterator.SIZED + Spliterator.NONNULL;
        }

        @Override
        public long estimateSize() {
            return size;
        }
    }
}
