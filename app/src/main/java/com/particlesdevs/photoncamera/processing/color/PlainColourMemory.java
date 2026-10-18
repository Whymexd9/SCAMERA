package com.particlesdevs.photoncamera.processing.color;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P212g (W7 = M12): the colour matrix the ISP renders a light with, per physical camera, learned from its plain-Bayer sessions, for
 * the mosaic photos (ISZ / FULLRES) that render their own neutral. A mosaic result reports no usable matrix - vivo's is the identity
 * (reports/pd2454-30830-main-native-fullres-ui-20261016/shot.txt:497; X300 Ultra SCAMERA-debug.log (7).txt:506 against the plain
 * [167/128, -37/128, ...] at :1157), and P121's own neutral blocked the ISP branch anyway (Parameters.ownNeutral) - so those photos
 * were developed with the generic DNG matrices, which do not describe these sensors (SceneIlluminant). Here every converged plain
 * result (AWB AUTO and CONVERGED, about once a second) leaves its neutral, its matrix - COLOR_CORRECTION_TRANSFORM when
 * Parameters.ispCcmUsable takes it, else on OPPO the ISP matrix com.oplus.custom.ccm of the same frame (OplusCcm; the X7 Ultra's
 * transform is the identity) - and the AWB colour temperature label org.quic.camera2.statsconfigs.AWBFrameControlCCT (optional;
 * live in plain AE on the PHY110: 5546 / 5588 / 5618 / 5661; 3745 is the init signature of a session start and every manual probe,
 * never a measurement) in a bin of {@link #BIN} of ln(r / b) (up to {@link #MAX_BINS} bins, each the count-weighted mean of its
 * samples; the least seen gives way). Stored per camera in SharedPreferences {@link #PREFS} with Build.FINGERPRINT (another
 * firmware forgets them), at most one write every {@link #STORE_EVERY_MS}.
 * <p>
 * {@link #lookup}: the matrix at a photo's neutral - between the two bins on either side of its ln(r / b) (linear in ln(r / b), which
 * runs about linearly in mired along the Planckian locus; with both labels the colour temperature is that mired interpolation), by
 * the GCam CcmTable rule (research/gcam11/map/09_color_awb.md §4.5: the outer columns interpolated, the middle one rebuilt so
 * every row sums to 1 - a neutral stays exactly neutral); the nearest bin alone beyond the ends or when the nearest is more than
 * {@link #NEAR} away; nothing beyond {@link #FAR} or from bins of fewer than {@link #MIN_COUNT} samples (the DNG matrices then, as
 * before). scam_dev.txt "mosaic_ccm_table 0": nothing is learned or used.
 */
public final class PlainColourMemory {
    private PlainColourMemory() {}

    static final String PREFS = "plain_colour_memory";
    /** Bin width (ln(r / b)), bins per camera, the count a bin's mean is weighted at most with. */
    static final float BIN = 0.05f;
    static final int MAX_BINS = 24, MAX_COUNT = 64;
    /** Samples a bin needs before a photo uses it. */
    static final int MIN_COUNT = 3;
    /** ln(r / b): interpolate when the nearest bin is this close, use none beyond FAR. */
    static final float NEAR = 0.15f, FAR = 0.4f;
    /** One sample a camera at most this often (elapsedRealtime), one write a camera at most this often. */
    static final long SAMPLE_EVERY_MS = 1_000L, STORE_EVERY_MS = 5_000L;
    /** The AWB label's init signature and the range a label is believed in (K). */
    static final int CCT_INIT = 3745, CCT_MIN = 1500, CCT_MAX = 15000;

    /** A bin: the mean ln r / ln b of its samples, their mean matrix (row-major, rows summing to 1), the mean mired of the labelled. */
    static final class Bin {
        final int index;
        double lr, lb, mired;
        final double[] m = new double[9];
        int count, labelled;

        Bin(int index) {
            this.index = index;
        }

        double light() {
            return lr - lb;
        }
    }

    private static final Map<String, ArrayList<Bin>> CACHE = new HashMap<>(); // guarded by PlainColourMemory.class
    private static final Map<String, Long> SAMPLED = new HashMap<>(), WRITTEN = new HashMap<>();
    private static final Map<String, Boolean> DIRTY = new HashMap<>();
    private static volatile SharedPreferences store;
    private static volatile String fingerprint = android.os.Build.FINGERPRINT;

    /** scam_dev.txt "mosaic_ccm_table" (default on). */
    public static boolean on() {
        return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("mosaic_ccm_table", true);
    }

    /**
     * True when camera {@code camera} takes a sample now (elapsedRealtime {@code nowMs}; the slot is taken whether the caller then
     * finds a usable result or not, so a camera without a matrix costs one look a second): the caller then reads the result.
     */
    public static synchronized boolean due(@Nullable String camera, long nowMs) {
        if (camera == null || camera.isEmpty() || !on()) return false;
        final Long last = SAMPLED.get(camera);
        if (last != null && nowMs - last < SAMPLE_EVERY_MS && nowMs >= last) return false;
        SAMPLED.put(camera, nowMs);
        return true;
    }

    /** A usable AWB label (K), else 0: none, the init signature, or out of range. */
    public static int label(@Nullable int[] cct) {
        if (cct == null || cct.length == 0) return 0;
        final int k = cct[0];
        return k == CCT_INIT || k < CCT_MIN || k > CCT_MAX ? 0 : k;
    }

    /**
     * A converged plain result of {@code camera}: its neutral (r / g, b / g), the ISP's matrix (row-major 3x3, white-balanced camera
     * RGB to the output) and its AWB label (K, 0: none). True when it was taken into a bin.
     */
    public static synchronized boolean offer(@Nullable String camera, float r, float b, @Nullable float[] ccm, int cctK, long nowMs) {
        if (camera == null || camera.isEmpty() || !on() || !(r > 0.05f && r < 20f && b > 0.05f && b < 20f)) return false;
        final double[] m = normalised(ccm);
        if (m == null) return false;
        final ArrayList<Bin> bins = bins(camera);
        if (bins == null) return false;
        final double lr = Math.log(r), lb = Math.log(b);
        final int index = (int) Math.round((lr - lb) / BIN);
        Bin bin = null;
        for (Bin x : bins) if (x.index == index) bin = x;
        if (bin == null) {
            if (bins.size() >= MAX_BINS) {
                Bin least = bins.get(0);
                for (Bin x : bins) if (x.count < least.count) least = x;
                bins.remove(least);
            }
            bin = new Bin(index);
            bins.add(bin);
        }
        final double c = Math.min(bin.count, MAX_COUNT - 1);
        bin.lr = (c * bin.lr + lr) / (c + 1);
        bin.lb = (c * bin.lb + lb) / (c + 1);
        for (int i = 0; i < 9; i++) bin.m[i] = (c * bin.m[i] + m[i]) / (c + 1);
        bin.count = Math.min(Integer.MAX_VALUE - 1, bin.count + 1);
        if (cctK > 0) {
            final double l = Math.min(bin.labelled, MAX_COUNT - 1);
            bin.mired = (l * bin.mired + 1e6 / cctK) / (l + 1);
            bin.labelled++;
        }
        DIRTY.put(camera, true);
        final Long written = WRITTEN.get(camera);
        if (written == null || nowMs - written >= STORE_EVERY_MS || nowMs < written) save(camera, bins, nowMs);
        return true;
    }

    /** The matrix (row-major) with every row scaled to sum to 1; null unless it is a finite full matrix whose rows sum to 0.5..1.5. */
    @Nullable
    static double[] normalised(@Nullable float[] ccm) {
        if (ccm == null || ccm.length < 9) return null;
        final double[] m = new double[9];
        for (int row = 0; row < 3; row++) {
            double sum = 0;
            for (int c = 0; c < 3; c++) {
                final float v = ccm[row * 3 + c];
                if (Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) > 8f) return null;
                sum += v;
            }
            if (!(sum > 0.5 && sum < 1.5)) return null;
            for (int c = 0; c < 3; c++) m[row * 3 + c] = ccm[row * 3 + c] / sum;
        }
        return OplusCcm.identity(toFloat(m)) ? null : m;
    }

    /** What {@link #lookup} gives: the matrix (row-major, rows summing to 1), the colour temperature (K, 0: no label), the log text. */
    public static final class Lookup {
        public final float[] ccm;
        public final float cct;
        public final String how;

        Lookup(float[] ccm, float cct, String how) {
            this.ccm = ccm;
            this.cct = cct;
            this.how = how;
        }

        /** The matrix as a ColorSpaceTransform (row-major, in 1/1024: as Parameters.oplusCcmInstead builds one). */
        public android.hardware.camera2.params.ColorSpaceTransform transform() {
            final int[] q = new int[18];
            for (int i = 0; i < 9; i++) {
                q[2 * i] = Math.round(ccm[i] * 1024f);
                q[2 * i + 1] = 1024;
            }
            return new android.hardware.camera2.params.ColorSpaceTransform(q);
        }
    }

    /** The plain-session matrix of camera {@code camera} at {@code neutral} (r, g, b); null when its table has none for that light. */
    @Nullable
    public static synchronized Lookup lookup(@Nullable String camera, @Nullable float[] neutral) {
        if (camera == null || neutral == null || neutral.length < 3 || !on()) return null;
        if (!(neutral[0] > 0f && neutral[1] > 0f && neutral[2] > 0f) || Float.isInfinite(neutral[0]) || Float.isInfinite(neutral[2])) return null;
        final ArrayList<Bin> all = bins(camera);
        if (all == null) return null;
        final ArrayList<Bin> bins = new ArrayList<>();
        for (Bin b : all) if (b.count >= MIN_COUNT) bins.add(b);
        if (bins.isEmpty()) return null;
        final double x = Math.log(neutral[0] / neutral[2]);
        Bin lo = null, hi = null, nearest = null;
        for (Bin b : bins) {
            final double k = b.light();
            if (k <= x && (lo == null || k > lo.light())) lo = b;
            if (k >= x && (hi == null || k < hi.light())) hi = b;
            if (nearest == null || Math.abs(k - x) < Math.abs(nearest.light() - x)) nearest = b;
        }
        final double near = Math.abs(nearest.light() - x);
        if (near > FAR) return null;
        if (lo == null || hi == null || lo == hi || near > NEAR) {
            final float cct = nearest.labelled > 0 ? (float) (1e6 / nearest.mired) : 0f;
            return new Lookup(toFloat(nearest.m), cct, String.format(Locale.ROOT,
                    "the plain-session matrix of camera %s at ln(r/b) %.3f: its bin %.3f alone (%.3f away, %d samples%s; %d bins)",
                    camera, x, nearest.light(), near, nearest.count, label(nearest), bins.size()));
        }
        final double t = (x - lo.light()) / (hi.light() - lo.light());
        final float cct = lo.labelled > 0 && hi.labelled > 0 ? (float) (1e6 / (lo.mired + t * (hi.mired - lo.mired))) : 0f;
        return new Lookup(mix(lo.m, hi.m, t), cct, String.format(Locale.ROOT,
                "the plain-session matrix of camera %s at ln(r/b) %.3f: between its bins %.3f (%d samples%s) and %.3f (%d samples%s), t %.2f%s; %d bins",
                camera, x, lo.light(), lo.count, label(lo), hi.light(), hi.count, label(hi), t,
                cct > 0 ? String.format(Locale.ROOT, ", %.0f K", cct) : "", bins.size()));
    }

    private static String label(Bin b) {
        return b.labelled > 0 ? String.format(Locale.ROOT, ", %.0f K", 1e6 / b.mired) : "";
    }

    /**
     * The GCam CcmTable interpolation (row-major, out = M x camera RGB): the outer columns elementwise, the middle column rebuilt as
     * 1 - the other two, so every row sums to 1.
     */
    static float[] mix(double[] a, double[] b, double t) {
        final float[] out = new float[9];
        for (int r = 0; r < 3; r++) {
            final double c0 = a[r * 3] + t * (b[r * 3] - a[r * 3]), c2 = a[r * 3 + 2] + t * (b[r * 3 + 2] - a[r * 3 + 2]);
            out[r * 3] = (float) c0;
            out[r * 3 + 2] = (float) c2;
            out[r * 3 + 1] = (float) (1 - c0 - c2);
        }
        return out;
    }

    /**
     * P212g (W7 = M12 item 4): the labelled bins of camera {@code camera} whose label lies in [minK, maxK]: {ln(r / b), mired,
     * labelled samples} each (OppoTunedColor's 6500 K anchor of a sensor it has not measured).
     */
    public static synchronized List<double[]> labelled(@Nullable String camera, float minK, float maxK) {
        final ArrayList<double[]> out = new ArrayList<>();
        if (camera == null || !on()) return out;
        final ArrayList<Bin> bins = bins(camera);
        if (bins == null) return out;
        for (Bin b : bins) {
            if (b.labelled <= 0) continue;
            final double k = 1e6 / b.mired;
            if (k >= minK && k <= maxK) out.add(new double[]{b.light(), b.mired, b.labelled});
        }
        return out;
    }

    /** Bins of a camera (diagnostics, tests). */
    static synchronized int size(String camera) {
        final ArrayList<Bin> bins = bins(camera);
        return bins == null ? 0 : bins.size();
    }

    private static float[] toFloat(double[] m) {
        final float[] f = new float[m.length];
        for (int i = 0; i < m.length; i++) f[i] = (float) m[i];
        return f;
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // the store: "fingerprint|index,lr,lb,count,mired,labelled,m0..m8;..." a camera

    @Nullable
    private static ArrayList<Bin> bins(String camera) {
        ArrayList<Bin> bins = CACHE.get(camera);
        if (bins != null) return bins;
        final SharedPreferences s = store();
        if (s == null) return null;
        bins = new ArrayList<>();
        try {
            final String line = s.getString(camera, null);
            if (line != null) {
                final int bar = line.indexOf('|');
                if (bar > 0 && line.substring(0, bar).equals(String.valueOf(fingerprint))) bins.addAll(parse(line.substring(bar + 1)));
                else {
                    s.edit().remove(camera).apply();
                    com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "plain colour table of camera " + camera + ": another firmware's (or unreadable) bins dropped");
                }
            }
        } catch (RuntimeException bad) {
            bins.clear();
            s.edit().remove(camera).apply();
        }
        CACHE.put(camera, bins);
        return bins;
    }

    static ArrayList<Bin> parse(String s) {
        final ArrayList<Bin> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            final String[] f = part.split(",");
            if (f.length != 15) continue;
            try {
                final Bin b = new Bin(Integer.parseInt(f[0]));
                b.lr = Double.parseDouble(f[1]);
                b.lb = Double.parseDouble(f[2]);
                b.count = Integer.parseInt(f[3]);
                b.mired = Double.parseDouble(f[4]);
                b.labelled = Integer.parseInt(f[5]);
                boolean ok = Double.isFinite(b.lr) && Double.isFinite(b.lb) && b.count > 0 && (b.labelled == 0 || b.mired > 0);
                for (int i = 0; i < 9; i++) {
                    b.m[i] = Double.parseDouble(f[6 + i]);
                    ok &= Double.isFinite(b.m[i]);
                }
                if (ok && out.size() < MAX_BINS) out.add(b);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    static String format(List<Bin> bins) {
        final StringBuilder sb = new StringBuilder();
        for (Bin b : bins) {
            if (sb.length() > 0) sb.append(';');
            sb.append(String.format(Locale.ROOT, "%d,%.5f,%.5f,%d,%.3f,%d", b.index, b.lr, b.lb, b.count, b.mired, b.labelled));
            for (double v : b.m) sb.append(String.format(Locale.ROOT, ",%.5f", v));
        }
        return sb.toString();
    }

    private static void save(String camera, ArrayList<Bin> bins, long nowMs) {
        final SharedPreferences s = store();
        if (s == null || !Boolean.TRUE.equals(DIRTY.get(camera))) return;
        WRITTEN.put(camera, nowMs);
        DIRTY.put(camera, false);
        s.edit().putString(camera, fingerprint + "|" + format(bins)).apply();
    }

    @Nullable
    private static SharedPreferences store() {
        SharedPreferences s = store;
        if (s != null) return s;
        final Context context = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
        if (context == null) return null;
        try {
            s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (RuntimeException e) {
            return null;
        }
        store = s;
        return s;
    }

    /** Tests: a store and firmware in place of the app's, nothing cached (a new process). */
    static synchronized void useStoreForTest(@Nullable SharedPreferences prefs, String firmware) {
        CACHE.clear();
        SAMPLED.clear();
        WRITTEN.clear();
        DIRTY.clear();
        store = prefs;
        fingerprint = firmware;
    }

    /** Tests: nothing cached, the app's store and firmware. */
    static synchronized void resetForTest() {
        useStoreForTest(null, android.os.Build.FINGERPRINT);
    }
}
