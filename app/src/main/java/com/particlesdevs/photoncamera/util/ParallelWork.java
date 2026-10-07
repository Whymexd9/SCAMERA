package com.particlesdevs.photoncamera.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Shot speed (wave 1): a small shared pool for the CPU pieces of the shot that split into independent items (tile bands of
 * the colour-block detector, the sharpness of each frame, the statistics of each denoise level). Every caller sums integer
 * counts or writes its own slot per item, so the result does not depend on the split or the order. The calling thread works
 * too; a call from inside the pool runs sequentially (no nested waits on the same threads).
 */
public final class ParallelWork {
    private ParallelWork() {}

    /** Helper threads: one less than the big + medium cores of the phones (8 cores: 7 helpers and the caller). */
    static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);
    private static volatile ExecutorService pool;

    private static ExecutorService pool() {
        ExecutorService p = pool;
        if (p == null) {
            synchronized (ParallelWork.class) {
                p = pool;
                if (p == null) {
                    final AtomicInteger number = new AtomicInteger();
                    p = Executors.newFixedThreadPool(Math.max(1, THREADS - 1), r -> {
                        Thread t = new Thread(() -> { INSIDE.set(true); r.run(); }, "SCAMERA-parallel-" + number.incrementAndGet());
                        t.setDaemon(true);
                        // Not the creator's priority (the shot's processing thread runs at NORM_PRIORITY - 1): it waits for them.
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    });
                    pool = p;
                }
            }
        }
        return p;
    }

    /** Threads a split may use (the caller included). */
    public static int threads() { return THREADS; }

    /**
     * Runs body(0 .. count-1), each index exactly once, on up to {@link #threads()} threads, and returns when all are done.
     * A RuntimeException or Error of an item is rethrown on the caller (the first one; the other items may still have run).
     */
    public static void forEach(int count, IntConsumer body) {
        if (count <= 0) return;
        final int helpers = Math.min(count, THREADS) - 1;
        if (helpers <= 0 || INSIDE.get()) {
            for (int i = 0; i < count; i++) body.accept(i);
            return;
        }
        final AtomicInteger next = new AtomicInteger();
        final Runnable drain = () -> {
            for (int i = next.getAndIncrement(); i < count; i = next.getAndIncrement()) body.accept(i);
        };
        final List<Future<?>> futures = new ArrayList<>(helpers);
        final ExecutorService p = pool();
        for (int h = 0; h < helpers; h++) futures.add(p.submit(drain));
        Throwable failure = null;
        try {
            drain.run();
        } catch (RuntimeException | Error e) {
            failure = e;
            next.set(count); // the helpers stop taking items
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                if (failure == null) failure = e.getCause();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (failure == null) failure = new IllegalStateException("interrupted while waiting for parallel work", e);
            }
        }
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new IllegalStateException(failure);
    }
}
