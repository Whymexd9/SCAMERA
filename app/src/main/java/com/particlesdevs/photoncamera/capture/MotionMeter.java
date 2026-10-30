package com.particlesdevs.photoncamera.capture;

import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P111 How fast the scene moves in the «Движение» viewfinder, from the ring's RAW frames (every phone with a RAW ring; the
 * stock vivo snapshot mode gets the same from its HAL's preview analysis, vivo.feedback.motionResult, levels 1..5).
 * <p>
 * Each frame becomes a thumbnail (one sample per 16 RAW px: two complete CFA periods), normalised by its
 * mean (exposure changes do not count as motion). Two consecutive thumbnails are block-matched (a checkerboard of tiles of 12
 * thumbnail px, +-4 px = +-64 RAW px) on a low-priority thread, a frame skipped while the previous pair is still compared: the median shift of the textured tiles is the camera's motion (hand shake
 * the OIS left, a pan), the second-largest residual or a reliable focus/face tile is the subject's. Both in RAW
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

    /** One comparison: subject and camera motion in RAW px / s, the tiles used, source-frame acquisition time (nanoTime). */
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
    private static final AtomicLong generation = new AtomicLong();
    private static volatile Region region;
    private static ExecutorService worker;

    static final class Region {
        final float left, top, right, bottom;
        Region(float left, float top, float right, float bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        boolean contains(float x, float y) { return x >= left && x <= right && y >= top && y <= bottom; }
    }

    public static void region(float left, float top, float right, float bottom) {
        region = right > left && bottom > top ? new Region(left, top, right, bottom) : null;
    }

    private MotionMeter() {}

    /** The newest measurement, null when none. */
    public static Sample sample() {
        return latest;
    }

    /** The newest row profile, null when none. */
    public static Profile profile() {
        return rows;
    }

    public static synchronized void clear() {
        generation.incrementAndGet();
        latest = null;
        rows = null;
        prev = null;
        region = null;
    }

    /** One ring frame (the camera's RAW reader thread; plain 16-bit RAW). */
    static void observe(ShortBuffer s, int width, int height, int rowPixels, float black, long timestampNs) {
        observe(s, width, height, rowPixels, black, timestampNs, System.nanoTime(), 1);
    }

    static void observe(ShortBuffer s, int width, int height, int rowPixels, float black, long timestampNs,
                        long observedAtNs, int mosaicBlock) {
        if (busy.get()) return; // the previous pair is still being compared: this frame is skipped
        final long epoch = generation.get();
        final Region roi = region;
        final Thumb cur = thumb(s, width, height, rowPixels, black, timestampNs, mosaicBlock);
        if (cur == null) return;
        final float[] bins = rowProfile(cur);
        final Thumb before;
        synchronized (MotionMeter.class) {
            if (generation.get() != epoch) return;
            if (bins != null) rows = new Profile(cur.ts, bins);
            before = prev;
            prev = cur;
        }
        if (before == null || before.w != cur.w || before.h != cur.h) return;
        final long dt = cur.ts - before.ts;
        if (dt <= 0 || dt > MAX_DT_NS) return;
        if (!busy.compareAndSet(false, true)) return;
        try {
            executor().execute(() -> {
                try {
                    Sample r = compare(before, cur, dt, observedAtNs, roi);
                    synchronized (MotionMeter.class) {
                        if (generation.get() == epoch) latest = r;
                    }
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

    /** Two CFA periods at (x, y) and (x + 8, y + 8), above black and normalised by the mean; null when unusable. */
    static Thumb thumb(ShortBuffer s, int width, int height, int rowPixels, float black, long ts) {
        return thumb(s, width, height, rowPixels, black, ts, 1);
    }

    static Thumb thumb(ShortBuffer s, int width, int height, int rowPixels, float black, long ts, int mosaicBlock) {
        final int cell = 2 * (mosaicBlock == 2 || mosaicBlock == 4 ? mosaicBlock : 1);
        final int w = (width - 8 - cell) / STEP, h = (height - 8 - cell) / STEP;
        if (w < 2 * SEARCH + 2 * TILE || h < 2 * SEARCH + 2 * TILE || rowPixels < width) return null;
        final long last = (long) ((h - 1) * STEP + 7 + cell) * rowPixels + (w - 1) * STEP + 7 + cell;
        if (last >= s.limit()) return null;
        final float[] v = new float[w * h];
        double sum = 0;
        for (int ty = 0; ty < h; ty++) {
            final int y = ty * STEP;
            for (int tx = 0; tx < w; tx++) {
                final int x = tx * STEP;
                float q = 0;
                for (int k = 0; k <= 8; k += 8) {
                    for (int cy = 0; cy < cell; cy++) {
                        final int row = (y + k + cy) * rowPixels + x + k;
                        for (int cx = 0; cx < cell; cx++) q += s.get(row + cx) & 0xFFFF;
                    }
                }
                q = Math.max(0, q - 2 * cell * cell * black);
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
        return compare(a, b, dtNs, nowNs, null);
    }

    static Sample compare(Thumb a, Thumb b, long dtNs, long nowNs, Region roi) {
        if (dtNs <= 0 || dtNs > MAX_DT_NS || a.w != b.w || a.h != b.h) return null;
        final int n = 2 * SEARCH + 1, tilesX = (a.w - 2 * SEARCH) / TILE, tilesY = (a.h - 2 * SEARCH) / TILE;
        final float[] dx = new float[tilesX * tilesY], dy = new float[tilesX * tilesY];
        final boolean[] moved = new boolean[tilesX * tilesY];
        final boolean[] inRegion = new boolean[tilesX * tilesY];
        final double[] sad = new double[n * n];
        int textured = 0;
        for (int j = 0; j < tilesY; j++) {
            for (int i = 0; i < tilesX; i++) {
                final int ox = SEARCH + i * TILE, oy = SEARCH + j * TILE;
                final boolean selectedRegion = roi != null && roi.contains((ox + TILE * 0.5f) / a.w, (oy + TILE * 0.5f) / a.h);
                if (((i + j) & 1) != 0 && !selectedRegion) continue;
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
                final float[] fine = refine(a, b, ox, oy, bx, by);
                final boolean integer = (bx != 0 || by != 0) && best < MOVED_RATIO * here;
                final boolean fractional = bx == 0 && by == 0 && fine != null
                        && Math.hypot(fine[0], fine[1]) >= 0.06 && fine[2] < 0.85f;
                moved[t] = integer || fractional;
                dx[t] = moved[t] ? bx + (fine != null ? fine[0] : subpixel(sad, n, bi, 1)) : 0;
                dy[t] = moved[t] ? by + (fine != null ? fine[1] : subpixel(sad, n, bi, n)) : 0;
                inRegion[t] = selectedRegion;
            }
        }
        if (textured == 0) return null;
        final float gx = median(dx, textured), gy = median(dy, textured);
        final double[] residual = new double[textured];
        int movedCount = 0;
        double roiMotion = 0;
        for (int t = 0; t < textured; t++) if (moved[t]) {
            double v = Math.hypot(dx[t] - gx, dy[t] - gy);
            residual[movedCount++] = v;
            if (inRegion[t]) roiMotion = Math.max(roiMotion, v);
        }
        Arrays.sort(residual, 0, movedCount);
        // the second-largest: one tile alone (an aliased pattern, a passing glint) is not a moving subject
        final double subject = Math.max(roiMotion, movedCount >= 2 ? residual[movedCount - 2] : 0);
        final double perSecond = STEP * 1e9 / dtNs;
        return new Sample(subject * perSecond, Math.hypot(gx, gy) * perSecond, textured, movedCount, nowNs);
    }

    private static float[] refine(Thumb a, Thumb b, int ox, int oy, int bx, int by) {
        if (Math.abs(bx) == SEARCH || Math.abs(by) == SEARCH) return null;
        double xx = 0, xy = 0, yy = 0, ex = 0, ey = 0;
        double sx = 0, sy = 0, se = 0;
        for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
            int p = (oy + y) * a.w + ox + x, q = (oy + y + by) * b.w + ox + x + bx;
            double gx = (a.v[p + 1] - a.v[p - 1]) * 0.5;
            double gy = (a.v[p + a.w] - a.v[p - a.w]) * 0.5;
            double error = b.v[q] - a.v[p];
            xx += gx * gx; xy += gx * gy; yy += gy * gy;
            ex += error * gx; ey += error * gy;
            sx += gx; sy += gy; se += error;
        }
        double n = TILE * TILE;
        xx -= sx * sx / n; xy -= sx * sy / n; yy -= sy * sy / n;
        ex -= se * sx / n; ey -= se * sy / n;
        double det = xx * yy - xy * xy;
        if (!(det > 1e-9)) return null;
        float dx = (float) Math.max(-0.5, Math.min(0.5, (xy * ey - yy * ex) / det));
        float dy = (float) Math.max(-0.5, Math.min(0.5, (xy * ex - xx * ey) / det));
        double before = 0, after = 0, bias = se / n;
        for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
            int p = (oy + y) * a.w + ox + x, q = (oy + y + by) * b.w + ox + x + bx;
            before += Math.abs(b.v[q] - a.v[p] - bias);
            after += Math.abs(bilinear(b, ox + x + bx + dx, oy + y + by + dy) - a.v[p] - bias);
        }
        return new float[]{dx, dy, (float) (after / Math.max(before, 1e-9))};
    }

    private static float bilinear(Thumb t, float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        int p = iy * t.w + ix;
        return (1 - fy) * ((1 - fx) * t.v[p] + fx * t.v[p + 1])
                + fy * ((1 - fx) * t.v[p + t.w] + fx * t.v[p + t.w + 1]);
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
