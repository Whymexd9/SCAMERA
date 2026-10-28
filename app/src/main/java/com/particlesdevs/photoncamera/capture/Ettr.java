package com.particlesdevs.photoncamera.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * P356 ETTR (owner, 2026-10-27: «Добавь как отдельный переключатель ETTR»; a switch in the shade, the capture exposed to the right):
 * the camera's auto exposure gets an exposure compensation on top of the user's that brings the brightest part of the RAW frame
 * (the 99.9th percentile of the brightest channel of the 2x2 quads) up to {@link #TARGET} of the sensor's range, by at most
 * {@link #MAX_EV}. The N frames of the merge then collect up to 4x the light (less noise in the shadows); the photo's own AE (WHY)
 * brings it back to the same brightness and the ISP viewfinder shows it at the usual brightness (ViewfinderGain's display EV).
 * <p>
 * The boost only rises while the exposure has room: it stays within the stops the un-boosted exposure has below 4x the base ISO
 * (indoors, night: a boost would raise the ISO, not the light), 0 once there are none. A change waits for
 * {@link #SETTLE_FRAMES} frames (the AE follows) and moves by at least {@link #MIN_STEP_EV}; a clipped frame lowers it at once.
 */
final class Ettr {
    /** The 99.9th percentile of the brightest channel aimed at (a fraction of white over black). */
    static final float TARGET = 0.85f;
    static final float MAX_EV = 2f, MIN_STEP_EV = 0.3f;
    static final int SETTLE_FRAMES = 8;
    /** Frames between two meterings (~4 a second at 30 fps). */
    static final int METER_EVERY = 8;

    private float ev;
    private int settle, sinceMeter;

    /** The boost now requested (EV, 0 .. MAX_EV). */
    float ev() {
        return ev;
    }

    /** Off: no boost from now on; true when that changed it. */
    boolean reset() {
        final boolean changed = ev != 0f;
        ev = 0f;
        settle = 0;
        sinceMeter = 0;
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
     * The boost after a frame metered at the current one: {@code p999} its 99.9th percentile (fraction of white), {@code room}
     * false when the exposure is at its ceiling. True when the boost changed (the request is rebuilt).
     */
    boolean update(float p999, boolean room) {
        return update(p999, room ? Float.POSITIVE_INFINITY : 0f);
    }

    /**
     * The boost after a frame metered at the current one: {@code p999} its 99.9th percentile (fraction of white), {@code headroomEv}
     * the stops the exposure has below its ceiling without any boost ({@link #headroomEv}; 0 or less: none). The boost never goes
     * past the headroom: judged on the boosted ISO the ceiling dropped the boost, the ISO fell back and the boost came again,
     * 0 / +1.1 EV every 16 frames (bug capture-ettr-oscillation). True when the boost changed (the request is rebuilt).
     */
    boolean update(float p999, float headroomEv) {
        final boolean room = headroomEv > 0f;
        float next;
        if (!room) next = 0f;
        else if (p999 >= 0.995f) next = ev - 1f; // clipped: down a stop at once
        else next = ev + (float) (Math.log(TARGET / Math.max(p999, 0.001f)) / Math.log(2));
        next = Math.max(0f, Math.min(room ? Math.min(MAX_EV, headroomEv) : 0f, next));
        if (Math.abs(next - ev) < MIN_STEP_EV && !(next == 0f && ev > 0f && !room)) return false;
        ev = next;
        settle = SETTLE_FRAMES;
        return true;
    }

    /**
     * The stops below the exposure's ceiling (4x the base ISO: above it a boost raises the ISO, not the light) without the boost
     * {@code boostEv} the frame was exposed with: log2(4 x base / (iso / 2^boost)); +infinity when the ISO or the base is unknown.
     */
    static float headroomEv(@androidx.annotation.Nullable Integer iso, @androidx.annotation.Nullable Integer baseIso, float boostEv) {
        if (iso == null || baseIso == null || iso <= 0 || baseIso <= 0) return Float.POSITIVE_INFINITY;
        return (float) (Math.log(4.0 * baseIso * Math.pow(2.0, Math.max(0f, boostEv)) / iso) / Math.log(2));
    }

    /**
     * Why the boost is held (null: it runs): the switch off, the own native AE or a vivo SDK session (their own exposure), the
     * «Движение» shutter ceiling (its request is AE off: the compensation does nothing there, the boost would climb to MAX_EV and
     * land on the AE at once when the ceiling hands it back), a hand-set ISO or shutter.
     */
    @androidx.annotation.Nullable
    static String hold(boolean on, boolean nativeAe, boolean vivoSdk, boolean motionCeiling, boolean handSet) {
        return !on ? "off" : nativeAe ? "own native AE" : vivoSdk ? "vivo SDK"
                : motionCeiling ? "the motion shutter ceiling (AE off)" : handSet ? "hand-set exposure" : null;
    }

    /** The compensation index for {@code userIndex} plus the boost, inside the camera's range. */
    static int index(int userIndex, float boostEv, float stepEv, int min, int max) {
        final int extra = stepEv > 0 ? Math.round(boostEv / stepEv) : 0;
        return Math.max(min, Math.min(max, userIndex + extra));
    }

    /**
     * The 99.9th percentile of the brightest site of the 2x2 quads of a RAW16 frame (every {@code step}th quad each way), as a
     * fraction of white over black; NaN for an empty frame.
     */
    static float p999(ByteBuffer buffer, int width, int height, int rowStride, float black, float white, int step) {
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
        final int skip = (int) Math.ceil(n * 0.001);
        int seen = 0;
        for (int i = 1023; i >= 0; i--) {
            seen += bins[i];
            if (seen > skip) return i / 1023f;
        }
        return 0f;
    }
}
