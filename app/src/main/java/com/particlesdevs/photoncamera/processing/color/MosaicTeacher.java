package com.particlesdevs.photoncamera.processing.color;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * P212f (W5 = M13 phase 2, LOG ONLY; scam_dev.txt "mosaic_awb_teacher", default on - it collects and logs, it never changes a
 * photo): the ISP as the teacher of the mosaic white balance. The curve of MosaicNeutral holds the ISP's chosen neutrals while
 * the photo measures a grey-world statistic of the raw frame: the two are different things (the same probe frame: ISP 0.408 /
 * 0.614, the P121 statistic 0.426 / 0.535; the OPPO daylight offset about -8 % R/G, +5 % B/G), and no projection removes the
 * offset along the locus. In plain sessions (block 1, AWB AUTO converged, no native session) a RAW frame now and then gives our
 * statistic m (MosaicNeutral.measure2, block 1) beside the ISP's neutral n of the same result; d = ln n - ln m is kept in a
 * sparse per-camera grid over ln m ({@link #CELL} ln cells, a count-capped mean, at most {@link #MAX_CELLS}), and the prediction
 * for a mosaic statistic is n^ = m exp(Σ w d / Σ w) with a Gaussian kernel of {@link #KERNEL} ln (w also by the count), its
 * support S = Σ w and confidence min(1, S / {@link #FULL_SUPPORT}) from {@link #MIN_SUPPORT} on. Each plain observation logs the
 * prediction made before it was learned (a forward check: the log alone gives the teacher's error against the projection), the
 * photo logs its prediction beside the neutral it used. To be switched on only after a leave-one-session-out check on a phone log
 * (median error ≤ 0.02 ln and ≥ 30 % below the projection's).
 */
public final class MosaicTeacher {
    private MosaicTeacher() {}

    static final double CELL = 0.02, KERNEL = 0.05;
    static final int MAX_CELLS = 2000, COUNT_CAP = 50;
    static final double MIN_SUPPORT = 3, FULL_SUPPORT = 20;
    static final long SAVE_EVERY_MS = 30_000L;
    private static final String PREFS = "mosaic_awb_teacher";
    private static Context context;
    static LongSupplier clock = System::currentTimeMillis;

    /** One grid cell: its index (ln m / CELL, rounded), the mean residual (ln), the count. */
    static final class Cell {
        final int i, j;
        double dr, db;
        int count;
        Cell(int i, int j) { this.i = i; this.j = j; }
    }

    private static final Map<String, LinkedHashMap<Long, Cell>> GRIDS = new HashMap<>();
    private static final Map<String, Long> SAVED = new HashMap<>();
    private static final Map<String, Integer> SEEN = new HashMap<>();

    public static synchronized void init(Context c) {
        if (context == null && c != null) context = c.getApplicationContext();
    }

    /** scam_dev.txt "mosaic_awb_teacher" (default on: collected and logged; off: nothing). */
    public static boolean on() {
        return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("mosaic_awb_teacher", true);
    }

    /** A prediction: the neutral (r / g, b / g), the kernel's support and the confidence (0 below MIN_SUPPORT). */
    public static final class Prediction {
        public final float r, b;
        public final double support, confidence;
        Prediction(float r, float b, double support) {
            this.r = r;
            this.b = b;
            this.support = support;
            this.confidence = support >= MIN_SUPPORT ? Math.min(1.0, support / FULL_SUPPORT) : 0.0;
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%.3f / %.3f (support %.1f, conf %.2f)", r, b, support, confidence);
        }
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xffffffffL);
    }

    private static LinkedHashMap<Long, Cell> grid(String id) {
        LinkedHashMap<Long, Cell> g = GRIDS.get(id);
        if (g != null) return g;
        g = new LinkedHashMap<>();
        if (context != null) {
            final String s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, "");
            for (String p : s.split(";")) {
                final String[] v = p.split(",");
                if (v.length != 5) continue;
                try {
                    final Cell c = new Cell(Integer.parseInt(v[0]), Integer.parseInt(v[1]));
                    c.dr = Double.parseDouble(v[2]);
                    c.db = Double.parseDouble(v[3]);
                    c.count = Math.max(1, Math.min(COUNT_CAP, Integer.parseInt(v[4])));
                    if (Double.isFinite(c.dr) && Double.isFinite(c.db)) g.put(key(c.i, c.j), c);
                } catch (NumberFormatException ignored) {}
            }
        }
        GRIDS.put(id, g);
        return g;
    }

    /**
     * A plain-session pair of camera {@code id}: our statistic m (r / g, b / g) and the ISP's neutral n of the same frame. Returns
     * the prediction for m made before the pair was learned (null: none yet), for the forward check in the log.
     */
    @Nullable
    public static synchronized Prediction observe(String id, float mr, float mb, float nr, float nb) {
        if (id == null || id.isEmpty() || !valid(mr) || !valid(mb) || !valid(nr) || !valid(nb)) return null;
        final Prediction before = predictLocked(id, mr, mb);
        final double lr = Math.log(mr), lb = Math.log(mb);
        final int i = (int) Math.round(lr / CELL), j = (int) Math.round(lb / CELL);
        final LinkedHashMap<Long, Cell> g = grid(id);
        Cell c = g.get(key(i, j));
        final double dr = Math.log(nr) - lr, db = Math.log(nb) - lb;
        if (c == null) {
            c = new Cell(i, j);
            g.put(key(i, j), c);
            if (g.size() > MAX_CELLS) evict(g, c);
        }
        final int n = Math.min(c.count, COUNT_CAP - 1);
        c.dr = (c.dr * n + dr) / (n + 1);
        c.db = (c.db * n + db) / (n + 1);
        c.count = Math.min(COUNT_CAP, c.count + 1);
        SEEN.merge(id, 1, Integer::sum);
        final long now = clock.getAsLong();
        final Long last = SAVED.get(id);
        if (last == null || now - last >= SAVE_EVERY_MS) { // at most every 30 s (a pair or two a second come in)
            SAVED.put(id, now);
            save(id, g);
        }
        return before;
    }

    /** The cell with the fewest samples goes (never the one just added). */
    private static void evict(LinkedHashMap<Long, Cell> g, Cell keep) {
        Long worst = null;
        int fewest = Integer.MAX_VALUE;
        for (Map.Entry<Long, Cell> e : g.entrySet())
            if (e.getValue() != keep && e.getValue().count < fewest) { fewest = e.getValue().count; worst = e.getKey(); }
        if (worst != null) g.remove(worst);
    }

    /** The teacher's neutral for our statistic m of camera {@code id} (null: nothing learned near it). */
    @Nullable
    public static synchronized Prediction predict(String id, float mr, float mb) {
        if (id == null || id.isEmpty() || !valid(mr) || !valid(mb)) return null;
        return predictLocked(id, mr, mb);
    }

    @Nullable
    private static Prediction predictLocked(String id, float mr, float mb) {
        final LinkedHashMap<Long, Cell> g = grid(id);
        if (g.isEmpty()) return null;
        final double lr = Math.log(mr), lb = Math.log(mb);
        double sw = 0, sr = 0, sb = 0;
        for (Cell c : g.values()) {
            final double dx = c.i * CELL - lr, dy = c.j * CELL - lb, d2 = dx * dx + dy * dy;
            if (d2 > 9 * KERNEL * KERNEL) continue;
            final double w = c.count * Math.exp(-d2 / (2 * KERNEL * KERNEL));
            sw += w;
            sr += w * c.dr;
            sb += w * c.db;
        }
        if (!(sw > 1e-9)) return null;
        return new Prediction((float) (mr * Math.exp(sr / sw)), (float) (mb * Math.exp(sb / sw)), sw);
    }

    /** Pairs learned for camera {@code id} in this process (for the log's pacing). */
    public static synchronized int seen(String id) {
        return SEEN.getOrDefault(id, 0);
    }

    /** Grid cells of camera {@code id}. */
    public static synchronized int cells(String id) {
        return grid(id).size();
    }

    private static boolean valid(float v) {
        return v > 0.05f && v < 20f;
    }

    private static void save(String id, LinkedHashMap<Long, Cell> g) {
        if (context == null) return;
        final StringBuilder b = new StringBuilder();
        for (Cell c : g.values())
            b.append(b.length() == 0 ? "" : ";").append(String.format(Locale.ROOT, "%d,%d,%.5f,%.5f,%d", c.i, c.j, c.dr, c.db, c.count));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, b.toString()).apply();
    }

    /** Tests: a new process (no store until init). */
    static synchronized void resetForTest() {
        context = null;
        forgetForTest();
        clock = System::currentTimeMillis;
    }

    /** Tests: the store of {@code c}, nothing cached. */
    static synchronized void useContextForTest(Context c) {
        context = c;
        forgetForTest();
    }

    /** Tests: a new process reading the same store. */
    static synchronized void forgetForTest() {
        GRIDS.clear();
        SAVED.clear();
        SEEN.clear();
    }
}
