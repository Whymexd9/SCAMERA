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
 * <p>
 * P366 (owner, 2026-10-27: «вот такие фото получаю с исз, исправь» - X200 Ultra camera 4 ISZ, green at noon by the sea): the check on
 * the owner's log-2026-10-27 (build 31130), its 18 "WB TEACHER ... predicted before" lines (a forward check: each prediction made
 * before its pair was learned) against MosaicNeutral's projection of the same statistic (camera 4: its logged curve; camera 3: a
 * curve of its 30 plain ISP neutrals in the log, its own session left out): the teacher's median error 0.0385 ln over all 18,
 * 0.034 over the 8 with support ≥ 3 and the 6 with support ≥ 10 (0.010-0.040); the projection's 0.103 / 0.104 / 0.103 on the same
 * pairs (the statistic as it is: 0.071 / 0.065 / 0.059); a leave-one-session-out over the logged pairs alone 0.043 (n 17). The
 * teacher is 67 % below the projection but not ≤ 0.02 ln: the criterion is NOT met. So a photo takes the teacher's neutral only
 * where the projection is all there is - a RAW-only session whose ISP runs no 3A (MosaicNeutralResolver.ispIdle: the identity
 * colour transform and gains frozen on every result of the burst; the owner's case: R 2.434 / B 1.569 on all 14 results) - with
 * scam_dev.txt "mosaic_awb_teacher_use" 1 (default; 2: every mosaic photo without a route, 0: log only): {@link #use}, blended
 * with the locus by {@link Use#weight} (MosaicNeutral.taught). Its errors stop falling at a support of about 10 (11.7-25.2: 0.010-0.040;
 * 6.0 / 7.9: 0.059 / 0.011; below 3: up to 0.415), so the weight is full from {@link #FULL_USE}, none below {@link #USE_MIN} of it,
 * times the teacher's share against the projection by their median errors ({@link #W_OWN}). A camera whose own grid has nothing
 * near the statistic takes the grid of another camera of the device with the same sensor ({@link #sensorKey}; X200U cameras 3
 * and 4) at {@link #W_SIBLING}: ln n - ln m is the same for two modules of one sensor up to where on the grid the statistic of a
 * light falls (their daylight neutrals 0.3945 / 0.6396 and 0.3896 / 0.6709, 0.049 ln apart, about one KERNEL).
 */
public final class MosaicTeacher {
    private MosaicTeacher() {}

    static final double CELL = 0.02, KERNEL = 0.05;
    static final int MAX_CELLS = 2000, COUNT_CAP = 50;
    static final double MIN_SUPPORT = 3, FULL_SUPPORT = 20;
    /** P366: the support from which a photo takes the teacher at full weight; a photo takes none below USE_MIN of it (support 5). */
    static final double FULL_USE = 10, USE_MIN = 0.5;
    /**
     * P366: the teacher's share against the projection, σL² / (σL² + σT²) of the forward check's median errors (σT 0.034, σL 0.103:
     * 0.90); a same-sensor camera's grid σT = hypot(0.034, 0.017) - the residual's slope over the daylight cells of camera 3 (0.35
     * ln / ln, its pairs 0.388 / 0.693 and 0.354 / 0.713) times the two modules' daylight offset (0.049 ln) - 0.88.
     */
    static final double W_OWN = 0.90, W_SIBLING = 0.88;
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

    /** P366: a photo takes the teacher's neutral in no session (0), a RAW-only session without the ISP's 3A (1), any route-less one (2). */
    public static final int USE_NEVER = 0, USE_ISP_IDLE = 1, USE_ALWAYS = 2;

    /** P366: scam_dev.txt "mosaic_awb_teacher_use" (default 1: a RAW-only session without the ISP's 3A); nothing without {@link #on}. */
    public static int useMode() {
        if (!on()) return USE_NEVER;
        return Math.round(com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("mosaic_awb_teacher_use", USE_ISP_IDLE));
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

    /** P366: what a mosaic photo takes from the teacher: the prediction, whose grid (the camera's own or a same-sensor one's), the weight. */
    public static final class Use {
        public final Prediction prediction;
        public final String camera;
        public final boolean sibling;
        /** min(1, support / FULL_USE) x (W_OWN or W_SIBLING): the teacher's share of the photo's neutral (MosaicNeutral.taught). */
        public final double weight;

        Use(Prediction prediction, String camera, boolean sibling) {
            this.prediction = prediction;
            this.camera = camera;
            this.sibling = sibling;
            this.weight = supportShare(prediction.support) * (sibling ? W_SIBLING : W_OWN);
        }

        /** True when the photo takes it (support of USE_MIN x FULL_USE and more). */
        public boolean usable() {
            return supportShare(prediction.support) >= USE_MIN;
        }

        public String describe() {
            return String.format(Locale.ROOT, "%s grid, support %.1f, weight %.2f", sibling ? "camera " + camera + "'s (same sensor)" : "own",
                    prediction.support, weight);
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%.3f / %.3f (%s)", prediction.r, prediction.b, describe());
        }
    }

    static double supportShare(double support) {
        return Math.min(1.0, support / FULL_USE);
    }

    /**
     * P366: the teacher's neutral for our statistic m of camera {@code id}: its own grid's prediction when that one has the support
     * a photo takes (USE_MIN x FULL_USE), else the best supported of the grids of {@code sameSensor} (cameras of the device with the
     * same sensor, {@link #sameSensor}) at W_SIBLING; null when none has any prediction. {@link Use#usable} tells whether a photo
     * takes it.
     */
    @Nullable
    public static synchronized Use use(String id, float mr, float mb, @Nullable java.util.List<String> sameSensor) {
        if (id == null || id.isEmpty() || !valid(mr) || !valid(mb)) return null;
        final Prediction own = predictLocked(id, mr, mb);
        Use best = own == null ? null : new Use(own, id, false);
        if (best != null && best.usable()) return best;
        if (sameSensor != null)
            for (String other : sameSensor) {
                if (other == null || other.equals(id)) continue;
                final Prediction p = predictLocked(other, mr, mb);
                if (p == null) continue;
                final Use u = new Use(p, other, true);
                if (best == null || u.weight > best.weight) best = u;
            }
        return best;
    }

    /** P366: the cameras with a grid (in this process or stored). */
    public static synchronized java.util.Set<String> cameras() {
        final java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (Map.Entry<String, LinkedHashMap<Long, Cell>> e : GRIDS.entrySet()) if (!e.getValue().isEmpty()) out.add(e.getKey());
        if (context != null)
            for (Map.Entry<String, ?> e : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet())
                if (e.getValue() instanceof String && !((String) e.getValue()).isEmpty()) out.add(e.getKey());
        return out;
    }

    /**
     * P366: what makes two cameras of a device the same sensor for the teacher: the pixel array (and the maximum-resolution one), the
     * physical size (to 0.05 mm) and the white level of their characteristics, a Bayer filter. The order of the Bayer colours is
     * not compared: it follows the readout's orientation (X200U camera 3 BGGR, camera 4 RGGB, the same IMX06C), and the statistic
     * is per colour (R / G, B / G) whatever its place in the 2x2 (a colour block's arrangement is the same 2x2 after the block).
     * Null when the characteristics do not tell.
     */
    @Nullable
    public static String sensorKey(@Nullable android.hardware.camera2.CameraCharacteristics c) {
        if (c == null) return null;
        try {
            final android.util.Size pixels = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            final Integer white = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            final Integer cfa = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
            final android.util.SizeF physical = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (pixels == null || white == null || white <= 0 || cfa == null) return null;
            android.util.Size max = null;
            if (android.os.Build.VERSION.SDK_INT >= 31)
                max = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
            return String.format(Locale.ROOT, "%dx%d/%s/%s/%d/%s", pixels.getWidth(), pixels.getHeight(),
                    max == null ? "-" : max.getWidth() + "x" + max.getHeight(),
                    physical == null ? "-" : Math.round(physical.getWidth() * 20) + "x" + Math.round(physical.getHeight() * 20),
                    white, cfa >= 0 && cfa <= 3 ? "bayer" : "cfa" + cfa);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Map<String, String> SENSOR_KEYS = new HashMap<>();

    /**
     * P366: the cameras with a grid ({@link #cameras}) whose sensor is camera {@code id}'s ({@link #sensorKey}; {@code own} its
     * characteristics, {@code lookup} another camera's, null when it cannot be read).
     */
    public static java.util.List<String> sameSensor(String id, @Nullable android.hardware.camera2.CameraCharacteristics own,
                                                    java.util.function.Function<String, android.hardware.camera2.CameraCharacteristics> lookup) {
        final java.util.ArrayList<String> out = new java.util.ArrayList<>();
        final String key = sensorKey(own);
        if (key == null || id == null) return out;
        for (String other : cameras()) {
            if (other.equals(id)) continue;
            String k;
            synchronized (SENSOR_KEYS) { k = SENSOR_KEYS.get(other); }
            if (k == null) {
                android.hardware.camera2.CameraCharacteristics c;
                try {
                    c = lookup.apply(other);
                } catch (RuntimeException e) {
                    c = null;
                }
                k = sensorKey(c);
                if (k == null) k = "";
                synchronized (SENSOR_KEYS) { SENSOR_KEYS.put(other, k); }
            }
            if (key.equals(k)) out.add(other);
        }
        return out;
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
        synchronized (SENSOR_KEYS) { SENSOR_KEYS.clear(); }
    }
}
