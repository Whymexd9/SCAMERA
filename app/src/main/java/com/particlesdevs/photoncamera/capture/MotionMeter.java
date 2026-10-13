package com.particlesdevs.photoncamera.capture;

import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P111 How fast the scene moves in the «Движение» viewfinder, from the ring's RAW frames (every phone with a RAW ring; the
 * stock vivo snapshot mode gets the same from its HAL's preview analysis, vivo.feedback.motionResult, levels 1..5).
 * <p>
 * Each frame becomes a thumbnail (one sample per 16 RAW px: two Bayer quads, so all colours and less noise), normalised by its
 * mean (exposure changes do not count as motion). Two consecutive thumbnails are block-matched (a checkerboard of tiles of 12
 * thumbnail px, +-4 px = +-64 RAW px) on a low-priority thread, a frame skipped while the previous pair is still compared: the median shift of the textured tiles is the camera's motion (hand shake
 * the OIS left, a pan), the second-largest shift against it among the tiles that clearly moved is the subject's. Both in RAW
 * px per second. ParamController turns that into the shutter ceiling (MotionShutter.Levels).
 */
public final class MotionMeter {
    /** RAW px between thumbnail samples; thumbnail px per tile; +- thumbnail px searched. */
    static final int STEP = 16, TILE = 12, SEARCH = 4;
    /** Frames further apart than this are not compared (a stall, a skipped frame run). */
    static final long MAX_DT_NS = 150_000_000L;
    /** A tile is textured when its mean SAD over the search is this much above its best match. */
    static final double TEXTURE_RATIO = 1.6;
    /** A tile moved when its best match is this much below its SAD in place. */
    static final double MOVED_RATIO = 0.7;

    /** One comparison: subject and camera motion in RAW px / s, the tiles used, when (System.nanoTime). */
    public static final class Sample {
        public final double subjectPxPerS, cameraPxPerS;
        public final int textured, moved;
        public final long atNs;

        Sample(double subjectPxPerS, double cameraPxPerS, int textured, int moved, long atNs) {
            this.subjectPxPerS = subjectPxPerS;
            this.cameraPxPerS = cameraPxPerS;
            this.textured = textured;
            this.moved = moved;
            this.atNs = atNs;
        }

        /** What smears the frame: the faster of the two. */
        public double pxPerS() {
            return Math.max(subjectPxPerS, cameraPxPerS);
        }
    }

    /** A thumbnail of one frame. */
    static final class Thumb {
        final float[] v;
        final int w, h;
        final long ts;

        Thumb(float[] v, int w, int h, long ts) {
            this.v = v;
            this.w = w;
            this.h = h;
            this.ts = ts;
        }
    }

    /** P111: a frame's row profile for the lamp-band check (MotionShutter): its sensor timestamp and the bins, top to bottom. */
    public static final class Profile {
        public final long timestampNs;
        public final float[] bins;

        Profile(long timestampNs, float[] bins) {
            this.timestampNs = timestampNs;
            this.bins = bins;
        }
    }

    /** Row bins of a profile; column segments whose median makes a bin (a subject in a few columns does not count). */
    static final int PROFILE_BINS = 32, PROFILE_SEGMENTS = 8;

    private static volatile Sample latest;
    private static volatile Profile rows;
    private static volatile Thumb prev;
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static ExecutorService worker;

    private MotionMeter() {}

    /** The newest measurement, null when none. */
    public static Sample sample() {
        return latest;
    }

    /** The newest row profile, null when none. */
    public static Profile profile() {
        return rows;
    }

    public static void clear() {
        latest = null;
        rows = null;
        prev = null;
    }

    /** One ring frame (the camera's RAW reader thread; plain 16-bit RAW). */
    static void observe(ShortBuffer s, int width, int height, int rowPixels, float black, long timestampNs) {
        if (busy.get()) return; // the previous pair is still being compared: this frame is skipped
        final Thumb cur = thumb(s, width, height, rowPixels, black, timestampNs);
        if (cur == null) return;
        final float[] bins = rowProfile(cur);
        if (bins != null) rows = new Profile(cur.ts, bins);
        final Thumb before = prev;
        prev = cur;
        if (before == null || before.w != cur.w || before.h != cur.h) return;
        final long dt = cur.ts - before.ts;
        if (dt <= 0 || dt > MAX_DT_NS) return;
        if (!busy.compareAndSet(false, true)) return;
        try {
            executor().execute(() -> {
                try {
                    Sample r = compare(before, cur, dt, System.nanoTime());
                    if (r != null) latest = r;
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            busy.set(false);
        }
    }

    private static synchronized ExecutorService executor() {
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scam-motion-meter");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        return worker;
    }

    /** The thumbnail: per sample the quads at (x, y) and (x + 8, y + 8) above black, divided by the frame's mean; null when unusable. */
    static Thumb thumb(ShortBuffer s, int width, int height, int rowPixels, float black, long ts) {
        final int w = (width - 10) / STEP, h = (height - 10) / STEP;
        if (w < 2 * SEARCH + 2 * TILE || h < 2 * SEARCH + 2 * TILE || rowPixels < width) return null;
        final long last = (long) ((h - 1) * STEP + 9) * rowPixels + (w - 1) * STEP + 9;
        if (last >= s.limit()) return null;
        final float[] v = new float[w * h];
        double sum = 0;
        for (int ty = 0; ty < h; ty++) {
            final int y = ty * STEP;
            for (int tx = 0; tx < w; tx++) {
                final int x = tx * STEP;
                float q = 0;
                for (int k = 0; k <= 8; k += 8) {
                    final int r0 = (y + k) * rowPixels + x + k, r1 = r0 + rowPixels;
                    q += (s.get(r0) & 0xFFFF) + (s.get(r0 + 1) & 0xFFFF) + (s.get(r1) & 0xFFFF) + (s.get(r1 + 1) & 0xFFFF);
                }
                q = Math.max(0, q - 8 * black);
                v[ty * w + tx] = q;
                sum += q;
            }
        }
        final double mean = sum / v.length;
        if (!(mean > 0)) return null;
        final float inv = (float) (1 / mean);
        for (int i = 0; i < v.length; i++) v[i] *= inv;
        return new Thumb(v, w, h, ts);
    }

    /**
     * Row profile of a thumbnail: {@link #PROFILE_BINS} bands of rows, each the median of its {@link #PROFILE_SEGMENTS} column
     * segments' means; null when the thumbnail is too small.
     */
    static float[] rowProfile(Thumb t) {
        if (t.h < PROFILE_BINS || t.w < PROFILE_SEGMENTS) return null;
        final float[] out = new float[PROFILE_BINS], seg = new float[PROFILE_SEGMENTS];
        for (int b = 0; b < PROFILE_BINS; b++) {
            final int y0 = b * t.h / PROFILE_BINS, y1 = (b + 1) * t.h / PROFILE_BINS;
            for (int k = 0; k < PROFILE_SEGMENTS; k++) {
                final int x0 = k * t.w / PROFILE_SEGMENTS, x1 = (k + 1) * t.w / PROFILE_SEGMENTS;
                double sum = 0;
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) sum += t.v[y * t.w + x];
                seg[k] = (float) (sum / ((double) (y1 - y0) * (x1 - x0)));
            }
            Arrays.sort(seg);
            out[b] = 0.5f * (seg[PROFILE_SEGMENTS / 2 - 1] + seg[PROFILE_SEGMENTS / 2]);
        }
        return out;
    }

    /** Block matching of {@code a} (older) against {@code b}; null when no tile has texture. */
    static Sample compare(Thumb a, Thumb b, long dtNs, long nowNs) {
        final int n = 2 * SEARCH + 1, tilesX = (a.w - 2 * SEARCH) / TILE, tilesY = (a.h - 2 * SEARCH) / TILE;
        final float[] dx = new float[tilesX * tilesY], dy = new float[tilesX * tilesY];
        final boolean[] moved = new boolean[tilesX * tilesY];
        final double[] sad = new double[n * n];
        int textured = 0;
        for (int j = 0; j < tilesY; j++) {
            for (int i = (j & 1); i < tilesX; i += 2) { // a checkerboard of tiles: half the work, the whole frame covered
                final int ox = SEARCH + i * TILE, oy = SEARCH + j * TILE;
                double total = 0;
                for (int sy = -SEARCH; sy <= SEARCH; sy++) {
                    for (int sx = -SEARCH; sx <= SEARCH; sx++) {
                        double d = 0;
                        for (int y = 0; y < TILE; y++) {
                            final int ra = (oy + y) * a.w + ox, rb = (oy + y + sy) * b.w + ox + sx;
                            for (int x = 0; x < TILE; x++) d += Math.abs(a.v[ra + x] - b.v[rb + x]);
                        }
                        final int k = (sy + SEARCH) * n + sx + SEARCH;
                        sad[k] = d;
                        total += d;
                    }
                }
                // the best match, in place on a tie
                int bi = SEARCH * n + SEARCH;
                double best = sad[bi];
                for (int k = 0; k < n * n; k++) if (sad[k] < best) { best = sad[k]; bi = k; }
                if (!(total / (n * n) > TEXTURE_RATIO * best)) continue; // flat or noise only: no reliable match
                final int bx = bi % n - SEARCH, by = bi / n - SEARCH;
                final double here = sad[SEARCH * n + SEARCH];
                final int t = textured++;
                moved[t] = (bx != 0 || by != 0) && best < MOVED_RATIO * here;
                dx[t] = moved[t] ? bx + subpixel(sad, n, bi, 1) : 0;
                dy[t] = moved[t] ? by + subpixel(sad, n, bi, n) : 0;
            }
        }
        if (textured == 0) return null;
        final float gx = median(dx, textured), gy = median(dy, textured);
        final double[] residual = new double[textured];
        int movedCount = 0;
        for (int t = 0; t < textured; t++) if (moved[t]) residual[movedCount++] = Math.hypot(dx[t] - gx, dy[t] - gy);
        Arrays.sort(residual, 0, movedCount);
        // the second-largest: one tile alone (an aliased pattern, a passing glint) is not a moving subject
        final double subject = movedCount >= 2 ? residual[movedCount - 2] : 0;
        final double perSecond = STEP * 1e9 / dtNs;
        return new Sample(subject * perSecond, Math.hypot(gx, gy) * perSecond, textured, movedCount, nowNs);
    }

    /** Parabola through the best SAD and its neighbours along one axis ({@code stride} 1 = x, n = y), -0.5..0.5. */
    private static float subpixel(double[] sad, int n, int k, int stride) {
        final int c = stride == 1 ? k % n : k / n;
        if (c == 0 || c == n - 1) return 0;
        final double l = sad[k - stride], m = sad[k], r = sad[k + stride];
        final double den = l - 2 * m + r;
        if (den <= 0) return 0;
        return (float) Math.max(-0.5, Math.min(0.5, 0.5 * (l - r) / den));
    }

    private static float median(float[] v, int count) {
        final float[] c = Arrays.copyOf(v, count);
        Arrays.sort(c);
        return count % 2 == 1 ? c[count / 2] : 0.5f * (c[count / 2 - 1] + c[count / 2]);
    }
}
