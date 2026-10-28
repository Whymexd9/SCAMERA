package com.particlesdevs.photoncamera.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * P356 ETTR (owner, 2026-10-27: «Добавь как отдельный переключатель ETTR»; a switch in the shade, the capture exposed to the right):
 * the viewfinder's exposure (the ring's N frames) gets a boost that brings the brightest part of the RAW frame (the {@link #tail}
 * of the brightest channel of the 2x2 quads) up to {@link #TARGET} of the sensor's range, by at most {@link #MAX_EV}. The N frames
 * of the merge then collect up to 4x the light (less noise in the shadows); the photo's own AE (WHY) brings it back to the same
 * brightness and the ISP viewfinder shows it at the usual brightness (ViewfinderGain's display EV).
 * <p>
 * P377 (owner, 2026-10-28: «ETTR вообще не работает, разницы никакой нет что в ручных, что в авто»; X200 Ultra, 8.33 ms at ISO
 * 1883 indoors): the boost was an exposure compensation, which the vivo HAL's AE turned into gain at its 1/120 s (no light), held
 * at 0 by every lamp in the frame (its p99.9 clipped) and by any hand-set ISO, all without a word in the log. Now the boost is
 * light: in the automatic exposure the shutter of the AE ({@link #refNs}) grows by it (shutter priority at {@link #shutterNs},
 * whole mains half periods where the AE chose them, at most one ring frame), the compensation adds it so the AE keeps its ISO;
 * with the ISO set by hand the compensation goes into the panel's free shutter, within one ring frame ({@link #shutterHeadroomEv}).
 * A hand-set shutter, the «Движение» mode, the own native AE, a vivo SDK session and the forced stabilisation hold it at 0
 * ({@link #hold}). The ultrashort frames (Bento) carry the highlights the N frames clip, so with them the brightest 0.5 % of the
 * quads may pass the target, without them the brightest 0.1 %.
 * <p>
 * A change waits for {@link #SETTLE_FRAMES} frames (the AE follows) and moves by at least {@link #MIN_STEP_EV}.
 */
final class Ettr {
    /** The tail percentile of the brightest channel aimed at (a fraction of white over black). */
    static final float TARGET = 0.85f;
    static final float MAX_EV = 2f, MIN_STEP_EV = 0.3f;
    /** A tail at this level or above: clipped. */
    static final float CLIPPED = 0.995f;
    static final int SETTLE_FRAMES = 8;
    /** Frames between two meterings (~4 a second at 30 fps). */
    static final int METER_EVERY = 8;
    /** The mains half periods (50 / 60 Hz) a shutter of the AE's may be a whole multiple of. */
    static final long HALF_50HZ_NS = 10_000_000L, HALF_60HZ_NS = 8_333_333L;

    private float ev;
    private int settle, sinceMeter;
    /** P377: the AE's own shutter the boost lengthens (0: none learned) and its ISO (the exposure the boost is measured from). */
    private long refNs;
    private int refIso;

    /** The boost now requested (EV, 0 .. MAX_EV). */
    float ev() {
        return ev;
    }

    /** P377: the AE's own shutter the boost is measured from (0: none). */
    long refNs() {
        return refNs;
    }

    /** P377: the AE's shutter and ISO of a frame without a boost (ignored while one is in force). */
    void learnRef(long aeShutterNs, int aeIso) {
        if (ev > 0f || aeShutterNs <= 0) return;
        refNs = aeShutterNs;
        refIso = aeIso;
    }

    /** P377 (review): the AE's exposure the boost is measured from (ISO x s), 0 when none is learned. */
    double refProduct() {
        return refNs > 0 && refIso > 0 ? refIso * (refNs / 1e9) : 0;
    }

    /** Off: no boost from now on; true when that changed it. */
    boolean reset() {
        final boolean changed = ev != 0f;
        ev = 0f;
        settle = 0;
        sinceMeter = 0;
        refNs = 0;
        refIso = 0;
        return changed;
    }

    /** Whether this frame is metered (every {@link #METER_EVERY} frames, not while the AE settles). */
    boolean due() {
        if (settle > 0) {
            settle--;
            return false;
        }
        if (++sinceMeter < METER_EVERY) return false;
        sinceMeter = 0;
        return true;
    }

    /**
     * The boost after a frame metered at the current one: {@code p999} its tail (fraction of white), {@code room}
     * false when the exposure is at its ceiling. True when the boost changed (the request is rebuilt).
     */
    boolean update(float p999, boolean room) {
        return update(p999, room ? Float.POSITIVE_INFINITY : 0f);
    }

    /**
     * The boost after a frame metered at the current one: {@code p999} its tail (fraction of white), {@code headroomEv}
     * the stops the exposure has below its ceiling without any boost ({@link #shutterHeadroomEv}; 0 or less: none). The boost never
     * goes past the headroom: judged on the boosted exposure the ceiling dropped the boost, the exposure fell back and the boost
     * came again, 0 / +1.1 EV every 16 frames (bug capture-ettr-oscillation). True when the boost changed (the request is rebuilt).
     */
    boolean update(float p999, float headroomEv) {
        final boolean room = headroomEv > 0f;
        float next;
        if (!room) next = 0f;
        else if (p999 >= CLIPPED) next = ev - 1f; // clipped: down a stop at once
        else next = ev + log2(TARGET / Math.max(p999, 0.001f));
        next = Math.max(0f, Math.min(room ? Math.min(MAX_EV, headroomEv) : 0f, next));
        if (Math.abs(next - ev) < MIN_STEP_EV && !(next == 0f && ev > 0f && !room)) return false;
        return deliver(next);
    }

    /**
     * P377: the boost the shutter path wants after a settled frame metered with the boost {@code hadEv} in force ({@code tail} its
     * tail, {@code headroomEv} the stops from the AE's shutter to the ceiling): a clipped tail starts over from the AE (0: the AE
     * meters the new scene and gives the next reference), no room none. Review: only settled frames are metered (the AE converged,
     * the viewfinder loop still): a frame on its way to the boost asked for more again (pumping), and judged by its own exposure
     * the AE's answer to a darker scene went into the boost as well (the shutter and the compensation both, then clipped).
     */
    float wanted(float tail, float headroomEv, float hadEv) {
        if (!(headroomEv > 0f) || tail >= CLIPPED) return 0f;
        final float next = hadEv + log2(TARGET / Math.max(tail, 0.001f));
        return Math.max(0f, Math.min(Math.min(MAX_EV, headroomEv), next));
    }

    /** {@link #wanted(float, float, float)} for a frame that had the boost in force. */
    float wanted(float tail, float headroomEv) {
        return wanted(tail, headroomEv, ev);
    }

    /**
     * P377: {@code next} is the boost in force from now on (the AE settles first); true when it changed. Back to 0 the reference
     * goes as well (review): the next boost starts from a settled frame of the AE, not from the scene it had before.
     */
    boolean deliver(float next) {
        if (Math.abs(next - ev) < 1e-3f) return false;
        ev = next;
        settle = SETTLE_FRAMES;
        if (next <= 0f) {
            refNs = 0;
            refIso = 0;
        }
        return true;
    }

    /**
     * P377: the stops a shutter of {@code shutterNs} exposed with the boost {@code boostEv} has below {@code capNs} without it:
     * log2(cap / (shutter / 2^boost)); 0 when the shutter or the ceiling is unknown.
     */
    static float shutterHeadroomEv(@androidx.annotation.Nullable Long shutterNs, long capNs, float boostEv) {
        if (shutterNs == null || shutterNs <= 0 || capNs <= 0) return 0f;
        return log2(capNs * Math.pow(2.0, Math.max(0f, boostEv)) / shutterNs);
    }

    /** P377: the mains half period {@code ns} is a whole multiple of (the AE's anti-banding), 0 for none. */
    static long halfPeriodOf(long ns) {
        if (multiple(ns, HALF_50HZ_NS)) return HALF_50HZ_NS;
        if (multiple(ns, HALF_60HZ_NS)) return HALF_60HZ_NS;
        return 0;
    }

    private static boolean multiple(long ns, long halfNs) {
        final double k = (double) ns / halfNs;
        final long whole = Math.round(k);
        return whole >= 1 && Math.abs(k - whole) <= 0.005 * whole; // the HAL's 8333326 ns is one 60 Hz half period
    }

    /**
     * P377: the shutter that gives the AE's {@code refNs} the boost {@code ev}: at most {@code capNs} (one ring frame, the HAL's
     * motion-safe exposure), never shorter than the reference; whole half periods of the mains where the reference is one (the
     * AE kept the lamps' bands out), rounded down.
     */
    static long shutterNs(long refNs, float ev, long capNs) {
        if (refNs <= 0 || capNs <= refNs || ev <= 0f) return refNs;
        long s = Math.min(capNs, Math.round(refNs * Math.pow(2.0, ev)));
        final long half = halfPeriodOf(refNs);
        if (half > 0) {
            // the HAL's 8333326 ns is one 60 Hz half period: 4 x it is 4 half periods, not 3.99
            long k = (long) Math.floor((double) s / half + 1e-3);
            if (k * half > capNs) k = capNs / half;
            s = k * half;
        }
        return Math.max(refNs, s);
    }

    /** P377: the boost a shutter of {@code ns} gives over the reference {@code refNs} (EV). */
    static float boostOf(long ns, long refNs) {
        return ns > refNs && refNs > 0 ? log2((double) ns / refNs) : 0f;
    }

    /**
     * Why the boost is held (null: it runs): the switch off, the own native AE or a vivo SDK session (their own exposure), the
     * «Движение» mode (short shutters: its ceiling's request is AE off, the shot takes the ring alone), a hand-set shutter (the
     * light is the shutter: a boost would only raise the ISO), the forced stabilisation (the viewfinder request keeps AE on).
     */
    @androidx.annotation.Nullable
    static String hold(boolean on, boolean nativeAe, boolean vivoSdk, boolean motion, boolean shutterByHand, boolean aeOffUnsafe) {
        return !on ? "off" : nativeAe ? "own native AE" : vivoSdk ? "vivo SDK"
                : motion ? "the motion mode (short shutters)" : shutterByHand ? "hand-set shutter"
                : aeOffUnsafe ? "forced stabilisation" : null;
    }

    /** P377: the stamp's code for a hold reason (MergeStamp's ETTR line). */
    static String holdCode(String held) {
        switch (held) {
            case "own native AE": return "nae";
            case "vivo SDK": return "sdk";
            case "the motion mode (short shutters)": return "mm";
            case "hand-set shutter": return "man";
            case "forced stabilisation": return "stab";
            default: return held;
        }
    }

    /** The compensation index for {@code userIndex} plus the boost, inside the camera's range. */
    static int index(int userIndex, float boostEv, float stepEv, int min, int max) {
        final int extra = stepEv > 0 ? Math.round(boostEv / stepEv) : 0;
        return Math.max(min, Math.min(max, userIndex + extra));
    }

    /** P377: the share of the brightest quads that may pass the target: 0.5 % with the ultrashort frames, else 0.1 %. */
    static float tail(boolean ultrashort) {
        return ultrashort ? 0.005f : 0.001f;
    }

    /** The 99.9th percentile ({@link #tail}(false)) of {@link #tail(ByteBuffer, int, int, int, float, float, int, float)}. */
    static float p999(ByteBuffer buffer, int width, int height, int rowStride, float black, float white, int step) {
        return tail(buffer, width, height, rowStride, black, white, step, tail(false));
    }

    /**
     * The level the brightest {@code share} of the brightest sites of the 2x2 quads of a RAW16 frame (every {@code step}th quad each
     * way) is at or above, as a fraction of white over black; NaN for an empty frame.
     */
    static float tail(ByteBuffer buffer, int width, int height, int rowStride, float black, float white, int step, float share) {
        if (buffer == null || width < 4 || height < 4 || !(white > black)) return Float.NaN;
        final ShortBuffer s = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        final int rowShorts = rowStride / 2;
        final int[] bins = new int[1024];
        int n = 0;
        final float scale = 1023f / (white - black);
        for (int y = 0; y + 1 < height; y += 2 * step) {
            final int r0 = y * rowShorts, r1 = r0 + rowShorts;
            for (int x = 0; x + 1 < width; x += 2 * step) {
                final int a = s.get(r0 + x) & 0xFFFF, b = s.get(r0 + x + 1) & 0xFFFF, c = s.get(r1 + x) & 0xFFFF, d = s.get(r1 + x + 1) & 0xFFFF;
                final int m = Math.max(Math.max(a, b), Math.max(c, d));
                bins[Math.max(0, Math.min(1023, Math.round((m - black) * scale)))]++;
                n++;
            }
        }
        if (n == 0) return Float.NaN;
        final int skip = (int) Math.ceil(n * share);
        int seen = 0;
        for (int i = 1023; i >= 0; i--) {
            seen += bins[i];
            if (seen > skip) return i / 1023f;
        }
        return 0f;
    }

    private static float log2(double v) {
        return (float) (Math.log(v) / Math.log(2));
    }
}
