package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P371 (speed, ok_read): a merge job's end as its reader thread sees it. The worker prints «SCAM CAPTURE OK» after its output
 * is filled, unmapped and closed (scam-neural-worker.cpp), and after any burst it keeps is written; the client waited for the
 * process to exit before it read the result - the worker's teardown (destructors, GPU contexts, the kernel freeing 1.4-2.8 GB):
 * 89 ms on the X8U, 105 ms on the X200U on every shot. The watchdog now waits for the completion line or the exit, whichever
 * comes first; the exit after the line is reaped off the shot's path ({@link #reap}). The same bytes are read from the same
 * memfd. A worker that ends without the line is judged by its exit as before.
 */
final class WorkerCompletion {
    static final String OK_LINE = "SCAM CAPTURE OK";
    /** How often the wait looks at the process while it waits for the line (a worker that dies says nothing). */
    static final long POLL_MS = 20;

    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicBoolean ok = new AtomicBoolean();

    /** The reader thread, once it has handled {@code line}. */
    void line(String line) {
        if (OK_LINE.equals(line)) {
            ok.set(true);
            ended.countDown();
        }
    }

    /** The reader thread at the end of the worker's output (EOF or a read error). */
    void outputEnded() {
        ended.countDown();
    }

    /** The worker printed its completion line. */
    boolean ok() {
        return ok.get();
    }

    /**
     * The worker as the watchdog waits for it: {@code process} itself, or with {@code early} ended at its completion line too. A
     * worker whose output ended without the line is waited for until it exits, as before.
     */
    WorkerWatchdog.Worker watched(WorkerWatchdog.Worker process, boolean early) {
        if (!early) return process;
        return new WorkerWatchdog.Worker() {
            @Override public boolean waitFor(long ms) throws InterruptedException {
                long left = Math.max(0L, ms);
                for (;;) {
                    if (ok.get()) return true;
                    if (ended.getCount() == 0) return process.waitFor(left); // the output is over without the line: its exit
                    final long slice = Math.min(left, POLL_MS);
                    if (ended.await(slice, TimeUnit.MILLISECONDS)) continue;
                    if (process.waitFor(0)) return true;
                    left -= slice;
                    if (left <= 0) return false;
                }
            }

            @Override public void destroy() {
                process.destroy();
            }
        };
    }

    /** The exit of a worker that already delivered its result. */
    interface Exit {
        boolean waitFor(long ms) throws InterruptedException;
        int exitValue();
        void destroy();
    }

    /**
     * Waits up to {@code timeoutMs} for a worker past its completion line to exit (killed after that); the log line of an exit
     * that was not clean (a crash in a destructor no longer fails a shot whose result was complete), null for code 0.
     */
    static String reap(Exit worker, long timeoutMs) {
        boolean ended;
        try {
            ended = worker.waitFor(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ended = false;
        }
        if (!ended) {
            worker.destroy();
            return "WORKER EXIT after the result: still running after " + timeoutMs / 1000 + " s, killed";
        }
        final int code = worker.exitValue();
        if (code == 0) return null;
        return "WORKER EXIT after the result: " + (code > 128 ? "signal " + (code - 128) : "code " + code) + " (the result was complete)";
    }
}
