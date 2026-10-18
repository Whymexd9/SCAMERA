package com.particlesdevs.photoncamera.processing;

import java.util.function.Consumer;

/**
 * P220 (owner: a processing progress ring and a notification; roadmap speed-ux C1): the merge worker's progress for the user.
 * ScamNeuralClient feeds it from the worker's heartbeat (HYBRID PROGRESS pass / done every 5 s, WorkerWatchdog.Beat);
 * ScamProcessor forwards it to the processing ring round the gallery card ({@link ProcessingProgress#merge}) and
 * ProcessingKeepAlive shows it in its notification while the app is behind the screen. A merge shorter than the first
 * heartbeat (5 s) reports nothing: the ring then runs as before.
 */
public final class MergeProgress {
    private MergeProgress() {}

    /**
     * The worker's passes in the order they run and the share of the merge at which each one starts; a pass ends where the next
     * one listed starts (the GPU strips are most of a 50 MP merge: 245 of 247 s before P204, 13 of 15 s after it).
     */
    static final String[] PASSES = {"start", "cre", "burst", "map", "keep-burst", "prepare", "mosaic-detect", "mosaic",
            "mosaic-split", "mosaic-binned", "mosaic-bin", "align", "bento", "local-align", "gpu", "mochi", "merge", "finish",
            "write"};
    static final float[] STARTS = {0f, 0f, .01f, .02f, .02f, .06f, .06f, .07f, .07f, .07f, .08f, .12f, .18f, .19f, .22f, .23f,
            .26f, .95f, .98f};
    /** A merge that runs this long behind the screen gets the progress notification. */
    public static final long NOTIFY_AFTER_MS = 10_000L;

    /** The share of the merge (0..1) at {@code share} of the worker's pass {@code pass}; -1 for a pass this table does not know. */
    public static float fraction(String pass, float share) {
        for (int i = 0; i < PASSES.length; i++) {
            if (!PASSES[i].equals(pass)) continue;
            final float start = STARTS[i], end = i + 1 < STARTS.length ? STARTS[i + 1] : 1f;
            return start + (end - start) * Math.max(0f, Math.min(1f, share));
        }
        return -1f;
    }

    /** The ring of the shot being processed (ScamProcessor, around its merge): the merge's share each time it grows. */
    public static volatile Consumer<Float> listener;
    private static volatile long startedMs;
    private static volatile float done;

    /** A merge job starts (a retry starts over). */
    public static void begin(long nowMs) {
        done = 0f;
        startedMs = Math.max(1L, nowMs);
    }

    /** The worker's heartbeat: pass {@code pass} at {@code share}. The merge's share never goes back within a job. */
    public static void update(String pass, float share) {
        if (startedMs == 0) return;
        final float f = fraction(pass, share);
        if (f < 0f || f <= done) return;
        done = f;
        final Consumer<Float> l = listener;
        if (l != null) {
            try {
                l.accept(f);
            } catch (RuntimeException ignored) {
                // the ring is optional; the merge goes on
            }
        }
    }

    /** The merge job ended (any way). */
    public static void end() {
        startedMs = 0;
    }

    public static boolean running() {
        return startedMs != 0;
    }

    /** How long the running merge has taken (0 without one). */
    public static long runningMs(long nowMs) {
        final long s = startedMs;
        return s == 0 ? 0 : Math.max(0, nowMs - s);
    }

    /** The merge's share done (0..1). */
    public static float done() {
        return done;
    }

    /**
     * The keep-alive notification shows the merge's progress: a merge running for at least {@link #NOTIFY_AFTER_MS}, the app
     * behind the screen, and only with the notification permission already granted (the app never asks for it).
     */
    public static boolean showNotification(boolean merging, long mergingMs, boolean foreground, boolean allowed) {
        return merging && mergingMs >= NOTIFY_AFTER_MS && !foreground && allowed;
    }
}
