package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P220: the merge worker judged by its heartbeat (HYBRID PROGRESS every 5 s, scam-progress.h) instead of the 14-minute alarm:
 * a fake worker on a fake clock that falls silent is stopped after max(90 s, 4 x its slowest step), one that keeps reporting
 * survives however long it runs, a GPU-fence stall skips the conservative retry, log-only and off never stop it, and the time
 * the app itself did not run is no one's silence.
 */
public class WorkerWatchdogTest {
    /** A worker on a fake clock: every slice of the wait advances the clock and lets the worker print what it would have. */
    private static final class FakeWorker implements WorkerWatchdog.Worker {
        final long[] clock = {1_000_000L};
        final long endsAtMs;
        /** The heartbeat line at a time since the start (null: none due). */
        final LongFunction<String> beatAt;
        WorkerWatchdog watchdog;
        long started, nextBeat, lateBy;
        boolean destroyed;

        FakeWorker(long endsAfterMs, LongFunction<String> beatAt) {
            this.started = clock[0];
            this.endsAtMs = clock[0] + endsAfterMs;
            this.beatAt = beatAt;
            this.nextBeat = clock[0] + 5000;
        }

        @Override public boolean waitFor(long ms) {
            final long until = clock[0] + ms;
            while (nextBeat <= Math.min(until, endsAtMs)) {
                clock[0] = nextBeat;
                final String line = beatAt.apply(nextBeat - started);
                if (line != null) watchdog.onLine(line);
                nextBeat += 5000;
            }
            if (until >= endsAtMs) {
                clock[0] = endsAtMs;
                return true;
            }
            clock[0] = until + lateBy;
            lateBy = 0;
            return false;
        }

        @Override public void destroy() {
            destroyed = true;
        }
    }

    private static String beat(String pass, long done, long total, long seq, long slowestMs, long fenceMs) {
        return "HYBRID PROGRESS pass=" + pass + " done=" + done + "/" + total + " seq=" + seq + " slowest=" + slowestMs
                + (fenceMs >= 0 ? " fence=" + fenceMs : "");
    }

    private static WorkerWatchdog watch(FakeWorker w, int mode) {
        w.watchdog = new WorkerWatchdog(mode, () -> w.clock[0]);
        return w.watchdog;
    }

    @Test public void parsesTheWorkersLine() {
        // as the worker prints it (OPPO fb27034c, replay of hand.nch with the P220 worker: the strip readback was waiting for the
        // GPU at that moment, as it mostly does in a GPU-bound merge)
        WorkerWatchdog.Beat b = WorkerWatchdog.parse("HYBRID PROGRESS pass=merge done=1920/3072 seq=174 slowest=772 fence=129");
        assertNotNull(b);
        assertEquals("merge", b.pass);
        assertEquals(1920, b.done);
        assertEquals(3072, b.total);
        assertEquals(174, b.seq);
        assertEquals(772, b.slowestMs);
        assertEquals(129, b.fenceMs);
        assertEquals(.625f, b.share(), 1e-6f);
        WorkerWatchdog.Beat f = WorkerWatchdog.parse("HYBRID PROGRESS pass=align done=6/19 seq=40 slowest=900");
        assertEquals(-1, f.fenceMs);
        assertEquals(6, f.done);
        assertEquals(0f, WorkerWatchdog.parse("HYBRID PROGRESS pass=prepare done=0/0 seq=5 slowest=12").share(), 0f);
        assertNull(WorkerWatchdog.parse("HYBRID GPU: Adreno (TM) 750 frames=20"));
        assertNull(WorkerWatchdog.parse("HYBRID PROGRESS pass=merge done=x/6144 seq=1 slowest=2"));
        assertNull(WorkerWatchdog.parse(null));
    }

    @Test public void thresholdIsNinetySecondsOrFourSlowestSteps() {
        assertEquals(90_000L, WorkerWatchdog.thresholdMs(0));
        assertEquals(90_000L, WorkerWatchdog.thresholdMs(1_400));          // 50 MP strips after P204
        assertEquals(90_000L, WorkerWatchdog.thresholdMs(22_500));
        assertEquals(120_000L, WorkerWatchdog.thresholdMs(30_000));
        assertEquals(980_000L, WorkerWatchdog.thresholdMs(245_000));       // a whole 245 s pass in one step
        // the worker's slowest step and the gaps the app saw both lengthen it
        FakeWorker w = new FakeWorker(1, t -> null);
        WorkerWatchdog wd = watch(w, WorkerWatchdog.ACTIVE);
        wd.onLine(beat("merge", 10, 100, 1, 40_000, -1));
        assertEquals(160_000L, wd.thresholdMs());
        w.clock[0] += 60_000;
        wd.onLine(beat("merge", 20, 100, 2, 1_000, -1)); // the worker's own slowest never shrinks the threshold
        assertEquals(240_000L, wd.thresholdMs());           // 60 s seen between two movements
    }

    @Test public void aSilentWorkerIsStoppedAfterNinetySeconds() throws Exception {
        // progress for 30 s, then the merge thread stops; the heartbeat goes on with the same seq
        FakeWorker w = new FakeWorker(900_000, t -> {
            long seq = Math.min(t, 30_000) / 1000;
            return beat("merge", seq * 64, 6144, seq, 1_400, -1);
        });
        WorkerWatchdog wd = watch(w, WorkerWatchdog.ACTIVE);
        List<String> log = new ArrayList<>();
        try {
            wd.await(w, 3_600_000, log::add);
            fail("a silent worker must be stopped");
        } catch (WorkerWatchdog.StalledException e) {
            assertTrue(w.destroyed);
            assertFalse("a CPU stall keeps the conservative retry", e.gpuFence);
            assertTrue(ScamHybridBurst.conservativeRetry(e));
            final long stoppedAfter = w.clock[0] - w.started;
            assertTrue("stopped " + stoppedAfter, stoppedAfter >= 30_000 + 90_000 && stoppedAfter <= 30_000 + 90_000 + 2_000);
            assertTrue(e.silentMs >= 90_000);
        }
        assertEquals(1, log.size());
        assertTrue(log.get(0), log.get(0).startsWith("CLIENT WATCHDOG: no progress for 9") && log.get(0).contains("pass=merge done=1920/6144"));
    }

    @Test public void aWorkerThatKeepsReportingSurvivesALongMerge() throws Exception {
        // 600 s of merge, a step every 30 s on average (slowest 35 s): never 90 s without progress
        FakeWorker w = new FakeWorker(600_000, t -> beat("merge", t / 30_000, 20, t / 30_000, 35_000, -1));
        WorkerWatchdog wd = watch(w, WorkerWatchdog.ACTIVE);
        List<String> log = new ArrayList<>();
        assertTrue(wd.await(w, 3_600_000, log::add));
        assertFalse(w.destroyed);
        assertTrue(log.isEmpty());
    }

    @Test public void slowStepsLengthenTheThreshold() throws Exception {
        // a merge whose strips took 40 s each: 150 s of silence is still within 4 x 40 s, 165 s is not
        FakeWorker ok = new FakeWorker(400_000, t -> {
            long seq = t < 200_000 ? t / 40_000 : t < 350_000 ? 5 : 6;
            return beat("merge", seq, 10, seq, 40_000, -1);
        });
        assertTrue(watch(ok, WorkerWatchdog.ACTIVE).await(ok, 3_600_000, null));
        assertFalse(ok.destroyed);
        FakeWorker stuck = new FakeWorker(900_000, t -> {
            long seq = Math.min(t, 200_000) / 40_000;
            return beat("merge", seq, 10, seq, 40_000, -1);
        });
        try {
            watch(stuck, WorkerWatchdog.ACTIVE).await(stuck, 3_600_000, null);
            fail();
        } catch (WorkerWatchdog.StalledException e) {
            final long after = stuck.clock[0] - stuck.started;
            assertTrue("stopped " + after, after >= 200_000 + 160_000 && after <= 200_000 + 165_000);
        }
    }

    @Test public void aGpuFenceStallSkipsTheConservativeRetry() throws Exception {
        // the strip readback waits for the GPU from 20 s on: fence= grows with the silence
        FakeWorker w = new FakeWorker(900_000, t -> t <= 20_000 ? beat("merge", t / 1000, 6144, t / 1000, 900, -1)
                : beat("merge", 20, 6144, 20, 900, t - 20_000));
        try {
            watch(w, WorkerWatchdog.ACTIVE).await(w, 3_600_000, null);
            fail();
        } catch (WorkerWatchdog.StalledException e) {
            assertTrue(e.gpuFence);
            assertFalse(ScamHybridBurst.conservativeRetry(e));
        }
        // a stall that reached a GPU wait only lately is the CPU's
        assertFalse(WorkerWatchdog.gpuFenceStall(WorkerWatchdog.parse(beat("merge", 1, 9, 9, 1, 30_000)), 95_000));
        assertTrue(WorkerWatchdog.gpuFenceStall(WorkerWatchdog.parse(beat("merge", 1, 9, 9, 1, 60_000)), 95_000));
        assertFalse(WorkerWatchdog.gpuFenceStall(WorkerWatchdog.parse(beat("merge", 1, 9, 9, 1, -1)), 95_000));
        assertFalse(WorkerWatchdog.gpuFenceStall(null, 95_000));
        // the outer timeout skips it as before; a crash keeps it
        assertFalse(ScamHybridBurst.conservativeRetry(new ScamNeuralClient.WorkerTimeoutException("t")));
        assertTrue(ScamHybridBurst.conservativeRetry(new java.io.IOException("SHDR: merge not finished")));
    }

    @Test public void logOnlyAndOffNeverStopTheWorker() throws Exception {
        LongFunction<String> stalled = t -> beat("align", 3, 19, 3, 200, -1);
        FakeWorker logged = new FakeWorker(400_000, stalled);
        List<String> log = new ArrayList<>();
        assertTrue(watch(logged, WorkerWatchdog.LOG).await(logged, 3_600_000, log::add));
        assertFalse(logged.destroyed);
        assertEquals("one line per stall", 1, log.size());
        assertTrue(log.get(0), log.get(0).contains("log only (worker_watchdog 1)"));
        FakeWorker off = new FakeWorker(400_000, stalled);
        log.clear();
        assertTrue(watch(off, WorkerWatchdog.OFF).await(off, 3_600_000, log::add));
        assertFalse(off.destroyed);
        assertTrue(log.isEmpty());
        // an unknown value is the default (stops it)
        assertEquals(WorkerWatchdog.ACTIVE, new WorkerWatchdog(7, () -> 0L).mode());
    }

    @Test public void withoutAnyHeartbeatOnlyTheTimeoutEndsTheWait() throws Exception {
        // a worker whose heartbeat thread could not start: left to its 840 s alarm and the outer timeout, as before P220
        FakeWorker w = new FakeWorker(10_000_000, t -> null);
        assertFalse(watch(w, WorkerWatchdog.ACTIVE).await(w, 900_000, null));
        assertFalse(w.destroyed);
        assertEquals(900_000, w.clock[0] - w.started);
    }

    @Test public void timeTheAppDidNotRunIsNoOnesSilence() throws Exception {
        // at 20 s the app is frozen for 120 s (its wait's slice returns that late): the worker resumes and reports on
        FakeWorker w = new FakeWorker(300_000, t -> beat("merge", t / 2000, 200, t / 2000, 2_000, -1));
        WorkerWatchdog wd = watch(w, WorkerWatchdog.ACTIVE);
        final long[] froze = {0};
        WorkerWatchdog.Worker frozenOnce = new WorkerWatchdog.Worker() {
            @Override public boolean waitFor(long ms) {
                if (froze[0] == 0 && w.clock[0] - w.started >= 20_000) {
                    froze[0] = 1;
                    w.nextBeat += 120_000;   // its heartbeats were frozen too
                    w.lateBy = 120_000;
                }
                return w.waitFor(ms);
            }

            @Override public void destroy() {
                w.destroy();
            }
        };
        assertTrue(wd.await(frozenOnce, 3_600_000, null));
        assertFalse(w.destroyed);
    }
}
