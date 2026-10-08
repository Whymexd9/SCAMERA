package com.particlesdevs.photoncamera.control.subject;

/**
 * Single-target visual tracker on a downscaled luma frame (P42), after ArkCamera v50's VisualPatchTracker:
 * <ul>
 *   <li>a square luma patch around the target is the template; the score of a candidate position is
 *       {@code 0.65 * ZNCC(running template) + 0.35 * ZNCC(initial template)} — the initial template keeps the
 *       running one from drifting off the subject;</li>
 *   <li>search around the position predicted from the smoothed velocity ({@code v = 0.6 v + 0.4 Δ}): a coarse pass
 *       every 2 pixels with a 2x2-subsampled patch, a ±2 pixel refinement with the full patch, and a parabolic
 *       sub-pixel offset (at most ±0.6 pixel) on each axis;</li>
 *   <li>the running template learns {@code 0.92 old + 0.08 new} only on confident matches (score above 0.6);</li>
 *   <li>a score under 0.42 is a miss: the position holds and the velocity decays; {@code maxLost} misses in a row
 *       end the track (ArkCam: 12 frames at ~30 fps; here a time-equivalent count at the throttled rate).</li>
 * </ul>
 * Unlike ArkCam's fixed 40-pixel patch on a 640x480 YUV stream, the patch and the search radius scale with the frame
 * (10 % and 85 % of it), because the frames here are the displayed viewfinder at a quarter of the view size.
 * <p>
 * Frames are 8-bit luma, row-major, stride = width, top row first. Pure Java, allocation-free after construction;
 * not thread-safe (one worker thread owns it).
 */
public final class PatchTracker {
    public static final int IDLE = 0, PENDING = 1, TRACKING = 2, LOST = 3;

    static final float CONFIDENCE = 0.42f;
    static final float LEARN_ABOVE = 0.6f;
    static final float LEARN_RATE = 0.08f;
    static final float PATCH_FRACTION = 0.10f;
    static final float RADIUS_FRACTION = 0.85f;
    static final int MIN_PATCH = 12;

    private final int maxPatch;
    private final int maxLost;
    private final float[] initial, running, candidate;

    private int state = IDLE;
    private float startU, startV;
    private int frameW, frameH, patch, half, radius;
    private float initMean, initStd = 1f;
    private float cx, cy, vx, vy, score;
    private int lostFrames;

    /**
     * @param maxPatch largest patch side in pixels (sizes the buffers)
     * @param maxLost  consecutive misses that end the track
     */
    public PatchTracker(int maxPatch, int maxLost) {
        this.maxPatch = Math.max(MIN_PATCH, maxPatch & ~1);
        this.maxLost = Math.max(1, maxLost);
        int n = this.maxPatch * this.maxPatch;
        initial = new float[n];
        running = new float[n];
        candidate = new float[n];
    }

    /** Start (or restart) on the next frame at a normalized frame point. */
    public void start(float u, float v) {
        startU = Math.max(0f, Math.min(1f, u));
        startV = Math.max(0f, Math.min(1f, v));
        state = PENDING;
        lostFrames = 0;
        vx = vy = 0;
        score = 1f;
    }

    public void stop() {
        state = IDLE;
        lostFrames = 0;
    }

    public int state() {
        return state;
    }

    public boolean active() {
        return state == PENDING || state == TRACKING;
    }

    /** Normalized centre of the target in the frame. */
    public float u() {
        return frameW > 1 ? cx / (frameW - 1) : startU;
    }

    public float v() {
        return frameH > 1 ? cy / (frameH - 1) : startV;
    }

    /** Last match score (1 right after the start). */
    public float score() {
        return score;
    }

    /** Patch side as a fraction of the frame width / height (for drawing the tracking box). */
    public float patchFractionX() {
        return frameW > 0 ? patch / (float) frameW : PATCH_FRACTION;
    }

    public float patchFractionY() {
        return frameH > 0 ? patch / (float) frameH : PATCH_FRACTION;
    }

    /** Patch side the tracker would use on a frame of this size. */
    public int patchFor(int w, int h) {
        int p = Math.round(Math.min(w, h) * PATCH_FRACTION) & ~1;
        return Math.max(MIN_PATCH, Math.min(maxPatch, p));
    }

    /**
     * Feeds one frame.
     *
     * @return the state after the frame ({@link #TRACKING}, {@link #LOST}, or unchanged when idle)
     */
    public int update(byte[] luma, int w, int h) {
        if (state == PENDING) {
            init(luma, w, h);
            return state;
        }
        if (state != TRACKING) return state;
        if (w != frameW || h != frameH) {
            // The viewfinder changed size (aspect / camera switch): the template no longer applies.
            state = LOST;
            return state;
        }
        track(luma);
        return state;
    }

    private void init(byte[] luma, int w, int h) {
        frameW = w;
        frameH = h;
        patch = patchFor(w, h);
        half = patch / 2;
        radius = Math.max(4, Math.round(patch * RADIUS_FRACTION));
        if (w < patch + 2 || h < patch + 2) {
            state = LOST;
            return;
        }
        cx = clamp(startU * (w - 1), half, w - half);
        cy = clamp(startV * (h - 1), half, h - half);
        extract(luma, Math.round(cx), Math.round(cy), initial);
        int n = patch * patch;
        float sum = 0;
        for (int i = 0; i < n; i++) sum += initial[i];
        initMean = sum / n;
        float var = 0;
        for (int i = 0; i < n; i++) {
            float d = initial[i] - initMean;
            var += d * d;
        }
        initStd = Math.max(1f, (float) Math.sqrt(var / n));
        System.arraycopy(initial, 0, running, 0, n);
        vx = vy = 0;
        score = 1f;
        lostFrames = 0;
        state = TRACKING;
    }

    private void track(byte[] luma) {
        int n = patch * patch;
        float sum = 0;
        for (int i = 0; i < n; i++) sum += running[i];
        float runMean = sum / n, var = 0;
        for (int i = 0; i < n; i++) {
            float d = running[i] - runMean;
            var += d * d;
        }
        float runStd = Math.max(1f, (float) Math.sqrt(var / n));

        int px = Math.round(clamp(cx + vx, half, frameW - half));
        int py = Math.round(clamp(cy + vy, half, frameH - half));

        // Coarse: every second offset, subsampled patch.
        float best = -2f;
        int bdx = 0, bdy = 0;
        for (int dy = -radius; dy <= radius; dy += 2) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                float s = zncc(luma, px + dx, py + dy, 2, runMean, runStd);
                if (s > best) {
                    best = s;
                    bdx = dx;
                    bdy = dy;
                }
            }
        }
        // Refine: ±2 around the coarse peak, full patch.
        float fine = -2f;
        int fdx = bdx, fdy = bdy;
        for (int dy = bdy - 2; dy <= bdy + 2; dy++) {
            for (int dx = bdx - 2; dx <= bdx + 2; dx++) {
                float s = zncc(luma, px + dx, py + dy, 1, runMean, runStd);
                if (s > fine) {
                    fine = s;
                    fdx = dx;
                    fdy = dy;
                }
            }
        }
        score = fine;
        if (fine < CONFIDENCE) {
            vx *= 0.5f;
            vy *= 0.5f;
            if (++lostFrames >= maxLost) state = LOST;
            return;
        }
        lostFrames = 0;
        int bx = px + fdx, by = py + fdy;
        float sx = parabola(zncc(luma, bx - 1, by, 1, runMean, runStd), fine, zncc(luma, bx + 1, by, 1, runMean, runStd));
        float sy = parabola(zncc(luma, bx, by - 1, 1, runMean, runStd), fine, zncc(luma, bx, by + 1, 1, runMean, runStd));
        float nx = bx + sx, ny = by + sy;
        vx = 0.6f * vx + 0.4f * (nx - cx);
        vy = 0.6f * vy + 0.4f * (ny - cy);
        cx = nx;
        cy = ny;
        if (fine > LEARN_ABOVE) {
            extract(luma, Math.round(cx), Math.round(cy), candidate);
            for (int i = 0; i < n; i++) running[i] = running[i] * (1f - LEARN_RATE) + candidate[i] * LEARN_RATE;
        }
    }

    /** Parabolic peak offset from three samples, limited to ±0.6 (ArkCam). */
    static float parabola(float left, float centre, float right) {
        float denom = (left - 2f * centre + right) * 2f;
        if (Math.abs(denom) <= 1e-4f || left < -1f || right < -1f) return 0f;
        float off = (left - right) / denom;
        return off >= -0.6f && off <= 0.6f ? off : 0f;
    }

    /**
     * Blended ZNCC of the patch centred at (x, y) against both templates; {@code step} 2 samples every second pixel
     * of every second row. -1 when the patch leaves the frame.
     */
    private float zncc(byte[] luma, int x, int y, int step, float runMean, float runStd) {
        int x0 = x - half, y0 = y - half;
        if (x0 < 0 || y0 < 0 || x0 + patch > frameW || y0 + patch > frameH) return -1f;
        float sum = 0;
        int count = 0;
        for (int j = 0; j < patch; j += step) {
            int row = (y0 + j) * frameW + x0;
            for (int i = 0; i < patch; i += step) {
                sum += luma[row + i] & 0xFF;
                count++;
            }
        }
        float mean = sum / count;
        float var = 0, covRun = 0, covInit = 0;
        for (int j = 0; j < patch; j += step) {
            int row = (y0 + j) * frameW + x0;
            int t = j * patch;
            for (int i = 0; i < patch; i += step) {
                float d = (luma[row + i] & 0xFF) - mean;
                var += d * d;
                covRun += (running[t + i] - runMean) * d;
                covInit += (initial[t + i] - initMean) * d;
            }
        }
        float std = Math.max(1f, (float) Math.sqrt(var / count));
        return 0.65f * (covRun / count) / (std * runStd) + 0.35f * (covInit / count) / (std * initStd);
    }

    private void extract(byte[] luma, int x, int y, float[] out) {
        int x0 = Math.max(0, Math.min(frameW - patch, x - half));
        int y0 = Math.max(0, Math.min(frameH - patch, y - half));
        int k = 0;
        for (int j = 0; j < patch; j++) {
            int row = (y0 + j) * frameW + x0;
            for (int i = 0; i < patch; i++) out[k++] = luma[row + i] & 0xFF;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
