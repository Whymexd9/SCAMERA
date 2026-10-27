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
 * The boost only rises while the exposure has room: once the AE is past 4x the base ISO (indoors, night: a boost would raise the
 * ISO, not the light) it goes back to 0. A change waits for
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
        float next;
        if (!room) next = Math.min(ev, 0f);
        else if (p999 >= 0.995f) next = ev - 1f; // clipped: down a stop at once
        else next = ev + (float) (Math.log(TARGET / Math.max(p999, 0.001f)) / Math.log(2));
        next = Math.max(0f, Math.min(MAX_EV, next));
        if (Math.abs(next - ev) < MIN_STEP_EV && !(next == 0f && ev > 0f && !room)) return false;
        ev = next;
        settle = SETTLE_FRAMES;
        return true;
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
