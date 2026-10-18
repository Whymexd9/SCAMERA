package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Lang;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P220 (roadmap stability 8): the merge worker judged by its progress instead of a 14-minute alarm. Before, the app waited
 * 900-3600 s ({@link ScamHybridBurst#workerTimeoutSeconds}), the worker's own alarm(840) ended a hung merge, and that SIGALRM
 * exit then ran the conservative retry for up to 840 s more: ~28 minutes with the FULLRES shutter held (P200). A single pass
 * can legitimately be silent for minutes (a 245 s native merge before P204, owner's log 2026-10-17 18:25:45 - 18:29:51), so
 * the gaps between log lines say nothing. The worker prints a heartbeat every 5 s from its real counters (scam-progress.h):
 * {@code HYBRID PROGRESS pass=<name> done=<k>/<n> seq=<events> slowest=<ms>[ fence=<ms>]}. A worker whose seq has not moved
 * for max({@link #MIN_STALL_MS}, {@link #SLOWEST_FACTOR} x the slowest step so far) is stuck: stopped in {@link #ACTIVE}, only
 * logged in {@link #LOG} (scam_dev worker_watchdog). The 840 s alarm and the outer timeout stay as caps.
 */
public final class WorkerWatchdog {
    /** No progress for at least this long is a hang (ninety seconds: 18 heartbeats). */
    public static final long MIN_STALL_MS = 90_000L;
    /** ... or this many times the slowest step seen so far (a merge whose strips take a minute each). */
    public static final int SLOWEST_FACTOR = 4;
    /** The wait for the worker is sliced so that a stall is noticed within this. */
    static final long SLICE_MS = 1_000L;
    /** A slice that returns this much later than asked: the app did not run meanwhile ({@link #paused}). */
    static final long PAUSE_MS = 5_000L;
    /** scam_dev worker_watchdog: 0 off, 1 log only, 2 the worker is stopped (default). */
    public static final int OFF = 0, LOG = 1, ACTIVE = 2;

    /** One heartbeat line of the worker. */
    public static final class Beat {
        public final String pass;
        public final long done, total, seq, slowestMs;
        /** How long a thread has been waiting for the GPU (the strip readback's fence and map), -1 when none is. */
        public final long fenceMs;

        Beat(String pass, long done, long total, long seq, long slowestMs, long fenceMs) {
            this.pass = pass;
            this.done = done;
            this.total = total;
            this.seq = seq;
            this.slowestMs = slowestMs;
            this.fenceMs = fenceMs;
        }

        /** The share of the pass done (0 for a pass without units). */
        public float share() {
            return total > 0 ? Math.max(0f, Math.min(1f, (float) done / total)) : 0f;
        }

        @Override public String toString() {
            return "pass=" + pass + " done=" + done + "/" + total + " seq=" + seq;
        }
    }

    private static final Pattern BEAT = Pattern.compile(
            "^HYBRID PROGRESS pass=(\\S+) done=(\\d+)/(\\d+) seq=(\\d+) slowest=(\\d+)(?: fence=(\\d+))?");

    /** The heartbeat of a worker line, or null. */
    public static Beat parse(String line) {
        if (line == null || !line.startsWith("HYBRID PROGRESS ")) return null;
        Matcher m = BEAT.matcher(line);
        if (!m.find()) return null;
        try {
            return new Beat(m.group(1), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)), Long.parseLong(m.group(4)),
                    Long.parseLong(m.group(5)), m.group(6) != null ? Long.parseLong(m.group(6)) : -1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The worker made no progress for the threshold and was stopped. */
    public static final class StalledException extends IOException {
        /** It was waiting for the GPU (a hung GPU: ScamHybridBurst then skips its conservative retry, which would wait as long). */
        public final boolean gpuFence;
        public final long silentMs;

        StalledException(String message, boolean gpuFence, long silentMs) {
            super(message);
            this.gpuFence = gpuFence;
            this.silentMs = silentMs;
        }
    }

    /** The worker process as the watchdog sees it (WorkerSpawn.Child in the app, a fake in the tests). */
    public interface Worker {
        /** True when the worker has ended within {@code ms}. */
        boolean waitFor(long ms) throws InterruptedException;

        void destroy();
    }

    private final int mode;
    private final LongSupplier clock;
    private long lastMoveMs, lastSeq = -1, workerSlowestMs, observedMs;
    private Beat last;
    private boolean warned;

    /** {@code clock}: milliseconds of a monotonic clock (SystemClock.elapsedRealtime in the app). */
    public WorkerWatchdog(int mode, LongSupplier clock) {
        this.mode = mode < OFF || mode > ACTIVE ? ACTIVE : mode;
        this.clock = clock;
        this.lastMoveMs = clock.getAsLong();
    }

    public int mode() {
        return mode;
    }

    /** A line of the worker (its reader thread): a heartbeat updates the state and is returned; any other line is null. */
    public synchronized Beat onLine(String line) {
        final Beat beat = parse(line);
        if (beat == null) return null;
        final long now = clock.getAsLong();
        if (beat.seq != lastSeq) {
            // the gap between two movements as the app saw them (at the heartbeat's 5 s grain): with the worker's own slowest step
            // it can only lengthen the threshold
            if (lastSeq >= 0) observedMs = Math.max(observedMs, now - lastMoveMs);
            lastSeq = beat.seq;
            lastMoveMs = now;
            warned = false;
        }
        workerSlowestMs = Math.max(workerSlowestMs, beat.slowestMs);
        last = beat;
        return beat;
    }

    /** The silence that makes the worker stuck: max(90 s, 4 x the slowest step so far). */
    public synchronized long thresholdMs() {
        return thresholdMs(Math.max(workerSlowestMs, observedMs));
    }

    static long thresholdMs(long slowestMs) {
        return Math.max(MIN_STALL_MS, SLOWEST_FACTOR * Math.max(0L, slowestMs));
    }

    /** How long the worker has not moved. */
    public synchronized long silentMs() {
        return clock.getAsLong() - lastMoveMs;
    }

    /** The last heartbeat (null before the first). */
    public synchronized Beat last() {
        return last;
    }

    /**
     * True when the stall is the GPU's: the last heartbeat shows a thread waiting for the GPU for at least half of the
     * silence (the merge thread blocks there when the GPU hangs; a CPU stall that only reached a fence lately is not).
     */
    static boolean gpuFenceStall(Beat last, long silentMs) {
        return last != null && last.fenceMs >= 0 && 2 * last.fenceMs >= silentMs;
    }

    /**
     * {@code ms} in which the app itself did not run (its process frozen or the phone asleep: the wait's slice returned that much
     * late) count as no one's silence: the worker was frozen with it or its heartbeats wait unread in the pipe.
     */
    synchronized void paused(long ms) {
        if (ms > 0) lastMoveMs += ms;
    }

    /**
     * The stall now (silent beyond the threshold), or null; logs once per stall in LOG mode. Armed by the first heartbeat (5 s
     * into the job, whatever the merge thread does): a worker whose heartbeat thread could not start is left to its 840 s alarm.
     */
    synchronized StalledException check(Consumer<String> log) {
        if (mode == OFF || last == null) return null;
        final long silent = clock.getAsLong() - lastMoveMs, threshold = thresholdMs();
        if (silent < threshold) return null;
        final boolean gpu = gpuFenceStall(last, silent);
        final String where = (last == null ? "before the first heartbeat" : "at " + last) + (gpu ? ", waiting for the GPU " + last.fenceMs / 1000 + " s" : "");
        if (mode == LOG) {
            if (!warned && log != null) log.accept("CLIENT WATCHDOG: no progress for " + silent / 1000 + " s (threshold " + threshold / 1000
                    + " s) " + where + "; log only (worker_watchdog 1), the worker goes on");
            warned = true;
            return null;
        }
        if (log != null) log.accept("CLIENT WATCHDOG: no progress for " + silent / 1000 + " s (threshold " + threshold / 1000 + " s) " + where
                + "; the worker is stopped" + (gpu ? " (GPU hang: no conservative retry)" : ""));
        return new StalledException(Lang.t("Обработчик завис (нет хода " + silent / 1000 + " с); склейка остановлена",
                "The merge worker stopped making progress (" + silent / 1000 + " s); the merge was stopped"), gpu, silent);
    }

    /**
     * Waits for the worker up to {@code timeoutMs} in slices of {@link #SLICE_MS}: true when it ended, false when the time ran
     * out (the caller's timeout). {@link #ACTIVE}: a worker that stalls is destroyed and a {@link StalledException} thrown.
     */
    public boolean await(Worker worker, long timeoutMs, Consumer<String> log) throws InterruptedException, StalledException {
        final long started = clock.getAsLong();
        long before = started;
        for (;;) {
            final long left = timeoutMs - (before - started);
            if (left <= 0) return false;
            final long slice = Math.min(SLICE_MS, left);
            if (worker.waitFor(slice)) return true;
            final long now = clock.getAsLong();
            if (now - before > slice + PAUSE_MS) paused(now - before - slice);
            before = now;
            final StalledException stall = check(log);
            if (stall != null) {
                worker.destroy();
                throw stall;
            }
        }
    }
}
