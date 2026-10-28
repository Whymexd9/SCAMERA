package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * P371 (ok_read): the merge client reads the result at the worker's completion line and reaps its exit (~0.1 s of teardown)
 * off the shot's path; a worker that ends without the line is still judged by its exit.
 */
public class WorkerCompletionTest {
    /** A worker that exits {@code exitMs} after its start. */
    private static final class FakeWorker implements WorkerWatchdog.Worker {
        final long start = System.currentTimeMillis(), exitMs;
        final AtomicBoolean destroyed = new AtomicBoolean();
        FakeWorker(long exitMs) { this.exitMs = exitMs; }
        @Override public boolean waitFor(long ms) throws InterruptedException {
            final long left = start + exitMs - System.currentTimeMillis();
            if (left <= 0) return true;
            if (ms <= 0) return false;
            Thread.sleep(Math.min(ms, left));
            return System.currentTimeMillis() >= start + exitMs;
        }
        @Override public void destroy() { destroyed.set(true); }
    }

    private static void later(long ms, Runnable r) {
        new Thread(() -> {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
            r.run();
        }).start();
    }

    @Test public void theWaitEndsAtTheCompletionLineNotAtTheExit() throws Exception {
        final WorkerCompletion c = new WorkerCompletion();
        final FakeWorker worker = new FakeWorker(1500);
        later(50, () -> { c.line("SCAM OUTPUT: 96 MB mapped in 44 ms"); c.line(WorkerCompletion.OK_LINE); });
        final long t0 = System.currentTimeMillis();
        assertTrue(c.watched(worker, true).waitFor(5000));
        assertTrue(System.currentTimeMillis() - t0 < 1000);
        assertTrue(c.ok());
        assertFalse(worker.destroyed.get());
    }

    @Test public void withoutEarlyReadTheExitDecides() throws Exception {
        final WorkerCompletion c = new WorkerCompletion();
        final FakeWorker worker = new FakeWorker(400);
        later(20, () -> c.line(WorkerCompletion.OK_LINE));
        final WorkerWatchdog.Worker w = c.watched(worker, false);
        assertSame(worker, w);
        final long t0 = System.currentTimeMillis();
        assertTrue(w.waitFor(5000));
        assertTrue(System.currentTimeMillis() - t0 >= 350);
    }

    @Test public void aWorkerWithoutTheLineIsWaitedForUntilItExits() throws Exception {
        final WorkerCompletion c = new WorkerCompletion();
        final FakeWorker worker = new FakeWorker(300);
        later(30, c::outputEnded); // its output closed (a crash): no completion line
        final WorkerWatchdog.Worker w = c.watched(worker, true);
        assertFalse(w.waitFor(100)); // still running
        assertTrue(w.waitFor(2000));
        assertFalse(c.ok());
        // a worker that neither prints nor exits: the slice runs out (the watchdog's own timeout and stall test go on)
        final WorkerCompletion silent = new WorkerCompletion();
        final long t0 = System.currentTimeMillis();
        assertFalse(silent.watched(new FakeWorker(60000), true).waitFor(120));
        assertTrue(System.currentTimeMillis() - t0 >= 100);
        assertFalse(silent.watched(new FakeWorker(60000), true).waitFor(0));
    }

    @Test public void theWatchdogReturnsAtTheLine() throws Exception {
        final WorkerCompletion c = new WorkerCompletion();
        final AtomicInteger clock = new AtomicInteger();
        final WorkerWatchdog dog = new WorkerWatchdog(WorkerWatchdog.ACTIVE, () -> System.currentTimeMillis());
        later(40, () -> c.line(WorkerCompletion.OK_LINE));
        final long t0 = System.currentTimeMillis();
        assertTrue(dog.await(c.watched(new FakeWorker(10000), true), 5000, line -> clock.incrementAndGet()));
        assertTrue(System.currentTimeMillis() - t0 < 2000);
    }

    @Test public void theReaperLogsOnlyAnUncleanExit() {
        final int[] code = {0};
        final AtomicBoolean killed = new AtomicBoolean();
        final boolean[] ends = {true};
        final WorkerCompletion.Exit exit = new WorkerCompletion.Exit() {
            @Override public boolean waitFor(long ms) { return ends[0]; }
            @Override public int exitValue() { return code[0]; }
            @Override public void destroy() { killed.set(true); }
        };
        assertNull(WorkerCompletion.reap(exit, 1000));
        code[0] = 139;
        assertEquals("WORKER EXIT after the result: signal 11 (the result was complete)", WorkerCompletion.reap(exit, 1000));
        code[0] = 1;
        assertEquals("WORKER EXIT after the result: code 1 (the result was complete)", WorkerCompletion.reap(exit, 1000));
        assertFalse(killed.get());
        ends[0] = false;
        assertEquals("WORKER EXIT after the result: still running after 30 s, killed", WorkerCompletion.reap(exit, 30000));
        assertTrue(killed.get());
    }

    /** A real child: the line comes first, the exit (a crash after it) 0.4 s later and is only reaped. */
    @Test public void aRealProcessIsReadAtItsLineAndReapedLater() throws Exception {
        final com.particlesdevs.photoncamera.util.WorkerSpawn.Child child = com.particlesdevs.photoncamera.util.WorkerSpawn.Child.of(
                new ProcessBuilder("sh", "-c", "echo 'SCAM OUTPUT: x'; echo 'SCAM CAPTURE OK'; sleep 0.4; echo late; exit 139")
                        .redirectErrorStream(true).start());
        final WorkerCompletion c = new WorkerCompletion();
        final StringBuilder seen = new StringBuilder();
        final Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(child.output))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    synchronized (seen) { seen.append(line).append('\n'); }
                    c.line(line);
                }
            } catch (java.io.IOException ignored) {
            } finally {
                c.outputEnded();
            }
        });
        reader.start();
        final long t0 = System.currentTimeMillis();
        final WorkerWatchdog.Worker w = c.watched(new WorkerWatchdog.Worker() {
            @Override public boolean waitFor(long ms) throws InterruptedException { return child.waitFor(ms); }
            @Override public void destroy() { child.destroy(); }
        }, true);
        assertTrue(w.waitFor(10000));
        final long atLine = System.currentTimeMillis() - t0;
        assertTrue(c.ok());
        assertTrue("taken at the line, not at the exit: " + atLine + " ms", atLine < 350);
        final String exit = WorkerCompletion.reap(new WorkerCompletion.Exit() {
            @Override public boolean waitFor(long ms) throws InterruptedException { return child.waitFor(ms); }
            @Override public int exitValue() { return child.exitValue(); }
            @Override public void destroy() { child.destroy(); }
        }, 10000);
        assertEquals("WORKER EXIT after the result: signal 11 (the result was complete)", exit);
        reader.join(5000);
        synchronized (seen) { assertTrue(seen.toString().contains("late")); }
    }
}
