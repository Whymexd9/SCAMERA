package com.particlesdevs.photoncamera.processing.color;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * P121 white balance of an ISZ stream without remosaic (owner, 2026-10-13: «при использовании ISZ без ремозаика баланс белого не
 * работает нормально на всех смартфонах ... сделать его аналогичным тому как он работает без ISZ»).
 * <p>
 * The merge keeps the colour of a Quad / Tetra mosaic exactly, but the neutral point the camera reports for such a stream is not
 * the scene's: the ISP takes the mosaic for plain Bayer, where every 2x2 phase holds about the same mix of R, G and B, and its
 * white balance then sees a grey scene. Here the neutral of a mosaic shot is measured on the frame itself (each colour block
 * averaged, shades of grey p = 4 over the cells neither clipped nor dark) and then put on the camera's own illuminant curve: the
 * neutral points the same physical camera reported in plain-Bayer sessions, learned from the viewfinder (as without ISZ).
 * Without a learned curve the measured neutral is used as it is, within a sane range.
 * <p>
 * P212f (W5 = M13 phase 1, scam_dev.txt "mosaic_awb_locus", default on): the curve was a polyline through the learned points with
 * its ends clamped, and learning dropped a point within 0.03 of another and evicted the oldest above 48, so 905 distinct
 * measurements of the OPPO log gave 221 outputs, 0.330 / 0.705 375 times (132 of 137 daylight estimates pinned at that end point),
 * near-identical inputs jumped by 0.02 (log18 L992 / L1001) and a warm light was clamped (X200U 30840: 1.073 / 0.280 -> 0.785 /
 * 0.329, magenta). Now every point carries a count (a sample within the spacing moves it by the count-weighted mean; above
 * {@link #MAX_POINTS} the two closest points merge, so a rare tungsten point survives any number of daylight samples), and a
 * smooth locus is fitted through them (ln b as a Huber-weighted quadratic of ln r with ≥ 4 points over ≥ 0.4 of colour
 * temperature, else a line; the weights ln(1 + count)); a measurement goes to its nearest point on it, and its tint (off the
 * locus) is that of the learned points around it ({@link #TINT_KERNEL}: the ISP's own tint for a light it learned - the curve is a
 * cloud, 0.40 / 0.54 and 0.40 / 0.62 are both ISP neutrals - which the polyline kept by snapping to the nearest point), plus its
 * own within {@link #TINT} times its confidence (0 for the P121 statistic). Beyond the coolest learned light the locus is
 * extrapolated up to {@link #EXTRAPOLATE}; beyond the warmest by {@link #WARM_KEEP} of the excess, at most {@link #WARM_MAX}
 * (M13 corr. 4: under a sodium lamp the ISP kept 0.771 / 0.288 where the statistic read 0.876 / 0.274 - log-2026-10-17 L30883 -
 * the clamp at the curve's 0.755 / 0.298 was close, a full extrapolation would neutralise the lamp). With fewer than 2 points the
 * camera's DNG matrices give the locus ({@link #prior}: the neutrals of its two reference illuminants, on vivo the shape only -
 * anchored at a learned point or the PD2454 daylight neutrals). P212z (review): one learned point spans the prior's colour
 * temperature range on either side of it (it was a locus of zero width, so every light warmer than the point was held within
 * 0.2 ln of it - a tungsten 0.62 / 0.37 against one daylight point 0.359 / 0.671 became 0.410 / 0.577, where P121 used the
 * measurement as it is), and a locus without two learned points has no warmest learned light: no WARM_KEEP there. Phase 1
 * removes the pinning (the replay of log-2026-10-17's 1,519 camera-2 lines: 314 at 0.330 / 0.705 -> 0), the jumps
 * (near-identical inputs jumping > 0.02 ln: 14 -> 0) and the clamp; it does not remove the statistic's steady offset against the
 * ISP (0.330 against the plain 0.359 is mostly along the locus) - that is the teacher's (MosaicTeacher, log only).
 * <p>
 * P212f (W4 = M10): {@link #measure2}, the robust statistic with a confidence (per-site clipping, noise weights from the camera's
 * noise profile instead of the dark cut, the lens shading gains, the output crop, grey-cell selection, a split sigma), over up to
 * 3 N frames ({@link #combine}); the photo logs it beside the P121 one and takes it with scam_dev.txt "mosaic_awb_v2 1".
 * <p>
 * P366 (owner, 2026-10-27, X200 Ultra camera 4 ISZ at noon by the sea: «вот такие фото получаю с исз, исправь» - green / yellow-green
 * photos): camera 4's 12 learned points were two clusters (daylight-ish 0.44-0.51 / 0.57-0.65 and an indoor lamp 0.53 / 0.38), so the
 * line through them fell at -3.08 against its DNG prior's -1.06, and the cool daylight statistic 0.357 / 0.708 was put on its cool
 * extension at 0.429 / 0.752 (R/G and B/G 10-15 % above the ISP's ~0.39 / 0.645: green). Now (scam_dev.txt "mosaic_awb_slope_guard",
 * default on) a fitted locus whose slope at the learned points' centre is more than {@link #SLOPE_BAND} x as steep as the prior's
 * is not taken while the points cover less than {@link #PRIOR_SPAN_TRUST} of the prior's span along its direction (camera
 * 4: 0.54 of 1.37 ln): the prior's slope through their weighted centre instead (0.357 / 0.708 -> 0.356 / 0.706, its colour
 * temperature kept). And ("mosaic_awb_cool_keep", default on) a measurement beyond the extended cool end is moved along the locus by
 * at most what it is moved towards it ({@link Locus#project}: the clamp at the end raised the R/G of a light cooler than any learned
 * one), and never more than WARM_MAX beyond that end (as the warm end: a blue-dominated frame's statistic is no light to follow
 * further). The along-locus offset of the statistic against the ISP stays: that is the teacher's ({@link #taught}, MosaicTeacher).
 * Both are for a photo's own measurement: a neutral checked against the camera's curve (the HAL's on the vivo native and OPPO
 * sensor-mode routes, P315's off-locus check) is checked against the curve its points fit, as before ({@link #onLearnedCurve}).
 */
public final class MosaicNeutral {
    private MosaicNeutral() {}

    static final int MAX_POINTS = 48;
    static final float POINT_SPACING = 0.03f; // ln units between learned points
    /** P212f: a point's count is capped here (its mean keeps following the light it stands for, by 1/64 a sample at least). */
    static final int MAX_COUNT = 64;
    /** P212f: the locus is extended this far (ln, along the curve) beyond its ends; a perpendicular tint kept up to TINT x confidence. */
    static final double EXTRAPOLATE = 0.4, TINT = 0.04;
    /** P212f: beyond the warmest learned light, this share of the excess along the locus, at most WARM_MAX (the ISP keeps warmth). */
    static final double WARM_KEEP = 0.5, WARM_MAX = 0.2;
    /** P212f: the kernel (ln) of the learned points' local tint. */
    static final double TINT_KERNEL = POINT_SPACING;
    /** P212f: a quadratic needs this many points over this span of colour temperature (ln b - ln r); the Huber scale (ln). */
    static final int QUAD_POINTS = 4;
    static final double QUAD_SPAN = 0.4, HUBER = 0.02;
    /** P212f: the slope d ln b / d ln r of a locus without a shape of its own (OPPO camera 2's DNG locus, D65 -> StdA: -1.14). */
    static final double DEFAULT_SLOPE = -1.14;
    /** P212f: the DNG prior's two neutrals must be this far apart (ln) to describe a locus. */
    static final double PRIOR_MIN_SPAN = 0.2;
    /**
     * P366: a fitted slope (at the learned points' centre) at most SLOPE_BAND x as steep as the prior's stands (X200U camera 4's -3.08
     * against -1.06 is 2.9 x: two clusters apart in tint more than in colour temperature). A shallower one stands as before: learned
     * daylight loci are shallower than the D65-StdA chord (OPPO camera 2's -0.62 against its prior's -1.14, 0.55 x; the X8U's -0.66),
     * and a placeholder ColorMatrix (sRGB, as the X8U's forward matrices are, P315) would put a prior near -1.58 - a band of 0.5..2
     * would replace the X8U's learned slope, and its sensor-mode route holds the HAL's neutral to that curve.
     */
    static final double SLOPE_BAND = 2.0;
    /**
     * P366: the learned points' extent along the prior's direction, as a share of the prior's own span (its two reference
     * illuminants, D65 to StdA), from which their slope stands against the prior whatever it is (they then describe the camera's
     * locus over a good part of its daylight-to-tungsten range; camera 4's 0.54 of 1.37 ln does not).
     */
    static final double PRIOR_SPAN_TRUST = 0.5;
    private static final String PREFS = "mosaic_awb_curve";
    /** P212f: the counted points (ln r, ln b, count, last seen in hours); the P121 list is still written for an older build. */
    private static final String LOCUS_PREFS = "mosaic_awb_locus";
    static final long SAVE_EVERY_MS = 30_000L;
    private static Context context;
    private static final Map<String, ArrayList<float[]>> CURVES = new HashMap<>();
    /** P212f: the fitted locus per camera and the curve version (learn count) it was fitted at, the last save. */
    private static final Map<String, Object[]> FITS = new HashMap<>();
    private static final Map<String, Integer> VERSIONS = new HashMap<>();
    private static final Map<String, Long> SAVED = new HashMap<>();
    static LongSupplier clock = System::currentTimeMillis;

    public static synchronized void init(Context c) {
        if (context == null && c != null) context = c.getApplicationContext();
    }

    /** P212f: scam_dev.txt "mosaic_awb_locus" (default on; off: the P121 polyline, skip within the spacing, FIFO). */
    static boolean locusOn() {
        return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("mosaic_awb_locus", true);
    }

    /** P366: scam_dev.txt "mosaic_awb_slope_guard" (default on; off: any falling fitted slope stands, as P212f). */
    static boolean slopeGuardOn() {
        return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("mosaic_awb_slope_guard", true);
    }

    /** P366: scam_dev.txt "mosaic_awb_cool_keep" (default on; off: beyond the extended cool end the end itself, as P212f). */
    static boolean coolKeepOn() {
        return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("mosaic_awb_cool_keep", true);
    }

    /** A plain-Bayer viewfinder neutral (r, 1, b) of camera {@code id}: a point of its illuminant curve. */
    public static synchronized void learn(String id, float r, float b) {
        if (id == null || id.isEmpty() || !(r > 0.05f && r < 20f && b > 0.05f && b < 20f)) return;
        final ArrayList<float[]> pts = curve(id);
        final float lr = (float) Math.log(r), lb = (float) Math.log(b);
        final long now = clock.getAsLong();
        final float hours = (float) (now / 3_600_000.0);
        final boolean locus = locusOn();
        int nearest = -1;
        double nd = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            final float[] p = pts.get(i);
            final double d = Math.hypot(p[0] - lr, p[1] - lb);
            if (d < nd) { nd = d; nearest = i; }
        }
        final boolean structural;
        if (nearest >= 0 && nd < POINT_SPACING) {
            if (!locus) return; // P121: a point within the spacing is skipped
            // P212f: merged into the nearest point by the count-weighted mean
            final float[] p = pts.get(nearest);
            final float c = Math.min(p[2], MAX_COUNT - 1);
            p[0] = (c * p[0] + lr) / (c + 1);
            p[1] = (c * p[1] + lb) / (c + 1);
            p[2] = Math.min(MAX_COUNT, p[2] + 1);
            p[3] = hours;
            structural = false;
        } else {
            pts.add(new float[]{lr, lb, 1f, hours});
            if (pts.size() > MAX_POINTS) {
                if (locus) mergeClosest(pts);
                else pts.remove(0);
            }
            structural = true;
        }
        VERSIONS.merge(id, 1, Integer::sum);
        final Long last = SAVED.get(id);
        if (structural || last == null || now - last >= SAVE_EVERY_MS) {
            SAVED.put(id, now);
            save(id, pts);
        }
    }

    /** P212f: the two closest points become one (their count-weighted mean, the counts added, the later sighting). */
    static void mergeClosest(ArrayList<float[]> pts) {
        int a = -1, b = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++)
            for (int j = i + 1; j < pts.size(); j++) {
                final double d = Math.hypot(pts.get(i)[0] - pts.get(j)[0], pts.get(i)[1] - pts.get(j)[1]);
                if (d < best) { best = d; a = i; b = j; }
            }
        if (a < 0) return;
        final float[] p = pts.get(a), q = pts.get(b);
        final float cp = Math.max(1f, p[2]), cq = Math.max(1f, q[2]);
        p[0] = (cp * p[0] + cq * q[0]) / (cp + cq);
        p[1] = (cp * p[1] + cq * q[1]) / (cp + cq);
        p[2] = Math.min(MAX_COUNT, cp + cq);
        p[3] = Math.max(p[3], q[3]);
        pts.remove(b);
    }

    /** Points learned for camera {@code id} {ln r, ln b, count, last seen (hours)}, oldest first. */
    static synchronized ArrayList<float[]> curve(String id) {
        ArrayList<float[]> pts = CURVES.get(id);
        if (pts != null) return pts;
        pts = new ArrayList<>();
        if (context != null) {
            // P212f: the counted points; a camera without them yet loads its P121 points with count 1
            final String counted = context.getSharedPreferences(LOCUS_PREFS, Context.MODE_PRIVATE).getString(id, "");
            final String s = counted.isEmpty() ? context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, "") : counted;
            pts.addAll(parse(s));
        }
        CURVES.put(id, pts);
        return pts;
    }

    /** P212f: "ln r,ln b[,count,hours];..." (the P121 format has the first two only: count 1). */
    static ArrayList<float[]> parse(String s) {
        final ArrayList<float[]> pts = new ArrayList<>();
        if (s == null) return pts;
        for (String p : s.split(";")) {
            final String[] v = p.split(",");
            if (v.length != 2 && v.length != 4) continue;
            try {
                final float lr = Float.parseFloat(v[0]), lb = Float.parseFloat(v[1]);
                final float count = v.length == 4 ? Math.max(1f, Math.min(MAX_COUNT, Float.parseFloat(v[2]))) : 1f;
                final float hours = v.length == 4 ? Float.parseFloat(v[3]) : 0f;
                if (Float.isFinite(lr) && Float.isFinite(lb)) pts.add(new float[]{lr, lb, count, hours});
            } catch (NumberFormatException ignored) {}
        }
        return pts;
    }

    private static void save(String id, ArrayList<float[]> pts) {
        if (context == null) return;
        final StringBuilder b = new StringBuilder(), counted = new StringBuilder();
        for (float[] p : pts) {
            b.append(b.length() == 0 ? "" : ";").append(String.format(Locale.ROOT, "%.4f,%.4f", p[0], p[1]));
            counted.append(counted.length() == 0 ? "" : ";").append(String.format(Locale.ROOT, "%.4f,%.4f,%d,%.2f", p[0], p[1], (int) p[2], p[3]));
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, b.toString()).apply();
        context.getSharedPreferences(LOCUS_PREFS, Context.MODE_PRIVATE).edit().putString(id, counted.toString()).apply();
    }

    /**
     * The result: the neutral (r, 1, b), how it was found (log line), (P212e) the cells it was measured on, and (P212f) its distance
     * from the locus (ln; NaN: no locus) and whether the locus was extrapolated to reach it. P366 (review): {@code learnedDistance},
     * the measurement's distance from the curve its learned points fit ({@link #onLearnedCurve}) - {@code distance} unless the slope
     * guard set that fit aside; what P315's off-locus check compares (MosaicNeutralResolver.offLocus).
     */
    public static final class Result {
        public final float[] neutral;
        public final String how;
        public final int cells;
        public final float distance;
        public final boolean extrapolated;
        public final float learnedDistance;
        Result(float[] neutral, String how, int cells) { this(neutral, how, cells, Float.NaN, false); }
        Result(float[] neutral, String how, int cells, float distance, boolean extrapolated) {
            this(neutral, how, cells, distance, extrapolated, distance);
        }
        Result(float[] neutral, String how, int cells, float distance, boolean extrapolated, float learnedDistance) {
            this.neutral = neutral;
            this.how = how;
            this.cells = cells;
            this.distance = distance;
            this.extrapolated = extrapolated;
            this.learnedDistance = learnedDistance;
        }
    }

    /**
     * P212e (W3): the middle of camera {@code id}'s learned curve {r, 1, b} (its points ordered by colour temperature, ln b - ln r):
     * the OPPO native white balance's last prior; null with fewer than 2 points (or before {@link #init}: the curve is not read
     * then, so an empty one is never cached for the process).
     */
    public static synchronized float[] middle(String id) {
        if (id == null || id.isEmpty() || context == null && !CURVES.containsKey(id)) return null;
        final ArrayList<float[]> s = new ArrayList<>(curve(id));
        if (s.size() < 2) return null;
        s.sort((a, c) -> Float.compare(a[1] - a[0], c[1] - c[0]));
        final float[] m = s.get(s.size() / 2);
        return new float[]{(float) Math.exp(m[0]), 1f, (float) Math.exp(m[1])};
    }

    /**
     * The neutral of a mosaic frame: 16-bit samples (little endian, {@code stride} bytes a row), colour blocks of {@code block}
     * sites in the 2x2 order {@code cfa} (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR), black per phase, white; null when too few cells count.
     */
    public static Result estimate(String id, ByteBuffer raw, int width, int height, int stride, int cfa, float[] black, float white,
                                  int block) {
        return estimate(id, raw, width, height, stride, cfa, black, white, block, null);
    }

    /** P212f: {@link #estimate} with the camera's DNG locus ({@link #prior}; null: none) for a camera with fewer than 2 points. */
    public static Result estimate(String id, ByteBuffer raw, int width, int height, int stride, int cfa, float[] black, float white,
                                  int block, @Nullable Prior prior) {
        final float[] m = measure(raw, width, height, stride, cfa, black, white, block);
        if (m == null) return null;
        return resolve(id, m[0], m[1], (int) m[2], 0f, prior, "measured");
    }

    /** P212f (W4): the neutral of a {@link #measure2} statistic on the camera's locus, its tint kept by its confidence. */
    public static Result resolve(String id, Stat stat, @Nullable Prior prior) {
        return resolve(id, stat.r, stat.b, stat.cells, stat.confidence(), prior, "statistic");
    }

    /**
     * A measured neutral {@code r} / {@code b} of {@code cells} cells put on camera {@code id}'s curve: P212f the fitted locus
     * (its tint kept within TINT x {@code confidence}); with scam_dev.txt "mosaic_awb_locus 0" the P121 polyline. Without any
     * curve the measured neutral as it is, within a sane range.
     */
    static Result resolve(String id, float r, float b, int cells, float confidence, @Nullable Prior prior, String what) {
        return resolve(id, r, b, cells, confidence, prior, what, false);
    }

    /**
     * P366 (review): neutral {@code r} / {@code b} on camera {@code id}'s curve as its learned points fit it - P212f's fit and the clamp
     * at its cool end, without the slope guard and the cool keep, which are for a photo's own measurement: an illuminant the camera
     * learned stays on that curve (X200U camera 4's lamp, its ISP's 0.504 / 0.377: 0.06 ln from its points' line, 0.18 from the
     * guarded one, which runs between the daylight and the lamp clusters). MosaicNeutralResolver.curveDistance (the vivo native and
     * OPPO sensor-mode routes keep a converged HAL neutral within CURVE_DISTANCE of the curve) and P315's raw-grey point (offLocus).
     */
    static Result onLearnedCurve(String id, float r, float b, @Nullable Prior prior, String what) {
        return resolve(id, r, b, 0, 0f, prior, what, true);
    }

    private static Result resolve(String id, float r, float b, int cells, float confidence, @Nullable Prior prior, String what,
                                  boolean learned) {
        final float lr = (float) Math.log(r), lb = (float) Math.log(b);
        final ArrayList<float[]> pts;
        synchronized (MosaicNeutral.class) { pts = copy(curve(id)); }
        if (!locusOn()) {
            if (pts.size() < 2) return measuredAsIs(id, r, b, cells, what);
            final float[] q = project(pts, lr, lb);
            return new Result(new float[]{(float) Math.exp(q[0]), 1f, (float) Math.exp(q[1])},
                    String.format(Locale.ROOT, "%s %.3f / %.3f, on the curve of camera %s (%d points, %.3f away) %.3f / %.3f",
                            what, r, b, id, pts.size(), q[2], Math.exp(q[0]), Math.exp(q[1])), cells, q[2], false);
        }
        final Locus fitted = locus(id, pts, prior);
        if (fitted == null) return measuredAsIs(id, r, b, cells, what);
        // P366 (review): a check against the learned curve takes the fit the slope guard set aside, and P212f's clamp at its cool end
        final Locus locus = learned && fitted.learned != null ? fitted.learned : fitted;
        final double tint = TINT * Math.max(0f, Math.min(1f, confidence));
        final Projection q = locus.project(lr, lb, tint, !learned && locus.coolKeep);
        final double learnedDistance = learned || fitted.learned == null ? q.distance : fitted.learned.project(lr, lb, 0, false).distance;
        return new Result(new float[]{(float) Math.exp(q.lr), 1f, (float) Math.exp(q.lb)},
                String.format(Locale.ROOT, "%s %.3f / %.3f, on the curve of camera %s (%s, %.3f away%s%s%s) %.3f / %.3f", what, r, b, id,
                        locus.describe(), q.distance, q.extrapolated ? String.format(Locale.ROOT, ", extrapolated %.3f", q.beyond) : "",
                        q.coolKept ? ", beyond the cool end: its colour temperature kept" : "",
                        tint > 0 ? String.format(Locale.ROOT, ", tint %+.3f of %.3f", q.tint, tint) : "", Math.exp(q.lr), Math.exp(q.lb)),
                cells, (float) q.distance, q.extrapolated, (float) learnedDistance);
    }

    /**
     * P366: the neutral the ISP teacher sets for a mosaic photo (MosaicTeacher.Use, the photo's robust statistic {@code stat}):
     * ln n = c ln T + (1 - c) ln L - T the teacher's neutral for the statistic, L the same statistic on the camera's locus (its
     * tint kept by its confidence: the teacher's grid is of this statistic, so both read the same frame the same way), c the
     * teacher's weight ({@link MosaicTeacher.Use#weight}).
     */
    public static Result taught(String id, Stat stat, @Nullable Prior prior, MosaicTeacher.Use use) {
        final Result locus = resolve(id, stat, prior);
        final double c = Math.max(0, Math.min(1, use.weight));
        final double lr = c * Math.log(use.prediction.r) + (1 - c) * Math.log(locus.neutral[0]);
        final double lb = c * Math.log(use.prediction.b) + (1 - c) * Math.log(locus.neutral[2]);
        final float r = (float) Math.exp(lr), b = (float) Math.exp(lb);
        return new Result(new float[]{r, 1f, b}, String.format(Locale.ROOT,
                "the ISP teacher (%s) %.3f / %.3f x %.2f + the locus %.3f / %.3f x %.2f = %.3f / %.3f [the locus: %s]", use.describe(),
                use.prediction.r, use.prediction.b, c, locus.neutral[0], locus.neutral[2], 1 - c, r, b, locus.how),
                stat.cells, locus.distance, locus.extrapolated, locus.learnedDistance);
    }

    private static Result measuredAsIs(String id, float r, float b, int cells, String what) {
        final float cr = clamp(r), cb = clamp(b);
        return new Result(new float[]{cr, 1f, cb}, String.format(Locale.ROOT, "%s %.3f / %.3f (no curve of camera %s yet)", what, cr, cb, id),
                cells);
    }

    private static ArrayList<float[]> copy(ArrayList<float[]> pts) {
        final ArrayList<float[]> out = new ArrayList<>(pts.size());
        for (float[] p : pts) out.add(p.clone());
        return out;
    }

    private static float clamp(float v) { return Math.max(0.15f, Math.min(6f, v)); }

    /**
     * The nearest point of the curve: the learned points ordered by colour temperature (ln b - ln r), joined into a polyline;
     * {lr, lb, distance}. P212f: the P121 projection ("mosaic_awb_locus 0").
     */
    static float[] project(ArrayList<float[]> pts, float lr, float lb) {
        final ArrayList<float[]> s = new ArrayList<>(pts);
        s.sort((a, c) -> Float.compare(a[1] - a[0], c[1] - c[0]));
        float bestD = Float.MAX_VALUE, br = s.get(0)[0], bb = s.get(0)[1];
        for (int i = 0; i + 1 < s.size(); i++) {
            final float[] a = s.get(i), c = s.get(i + 1);
            final float dx = c[0] - a[0], dy = c[1] - a[1], len2 = dx * dx + dy * dy;
            float t = len2 > 0 ? ((lr - a[0]) * dx + (lb - a[1]) * dy) / len2 : 0f;
            t = Math.max(0f, Math.min(1f, t));
            final float px = a[0] + t * dx, py = a[1] + t * dy;
            final float d = (float) Math.hypot(lr - px, lb - py);
            if (d < bestD) { bestD = d; br = px; bb = py; }
        }
        return new float[]{br, bb, bestD};
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // P212f (W5 = M13 phase 1): the locus

    /**
     * P212f: the camera's DNG locus - the neutrals {ln r, ln b} of its two reference illuminants through CalibrationTransform x
     * ColorMatrix (ManualWhiteBalance's math), warmer first. {@code shapeOnly}: its slope only (vivo: generic DNG matrices,
     * SceneIlluminant) - it then needs a learned point, unless it was anchored at the PD2454 daylight neutral.
     */
    public static final class Prior {
        final double r1, b1, r2, b2;
        final boolean shapeOnly;
        Prior(double r1, double b1, double r2, double b2, boolean shapeOnly) {
            this.r1 = r1;
            this.b1 = b1;
            this.r2 = r2;
            this.b2 = b2;
            this.shapeOnly = shapeOnly;
        }

        double slope() {
            final double du = r2 - r1;
            return Math.abs(du) > 1e-6 ? (b2 - b1) / du : DEFAULT_SLOPE;
        }

        /** P366: the distance (ln) between its two reference illuminants' neutrals. */
        double span() {
            return Math.hypot(r1 - r2, b1 - b2);
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%.3f / %.3f - %.3f / %.3f%s", Math.exp(r1), Math.exp(b1), Math.exp(r2), Math.exp(b2),
                    shapeOnly ? " (shape)" : "");
        }
    }

    private static final Map<String, Object[]> PRIORS = new HashMap<>();

    /** P212f: the DNG locus of camera {@code id} (cached per characteristics; null: none). */
    @Nullable
    public static Prior prior(@Nullable android.hardware.camera2.CameraCharacteristics c, @Nullable String id) {
        if (c == null || id == null) return null;
        synchronized (PRIORS) {
            final Object[] cached = PRIORS.get(id);
            if (cached != null && cached[0] == c) return (Prior) cached[1];
        }
        Prior p = null;
        try {
            p = prior(matrix(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
                    matrix(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
                    illuminant(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)),
                    matrix(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
                    matrix(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
                    illuminant(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)));
            if (p != null && "vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER))
                p = anchored(p, SceneIlluminant.daylightNeutral(id));
        } catch (RuntimeException ignored) {
            p = null;
        }
        synchronized (PRIORS) { PRIORS.put(id, new Object[]{c, p}); }
        return p;
    }

    /**
     * P212f: a vivo prior is the DNG shape only (its matrices do not describe the sensor, SceneIlluminant): anchored at the
     * camera's daylight neutral where one is known (PD2454 cameras 3-5: the daylight end moved onto it), else shape only.
     */
    static Prior anchored(Prior p, @Nullable float[] daylight) {
        if (daylight == null || daylight.length < 3 || !(daylight[0] > 0f && daylight[1] > 0f && daylight[2] > 0f))
            return new Prior(p.r1, p.b1, p.r2, p.b2, true);
        final double dr = Math.log(daylight[0] / daylight[1]) - p.r2, db = Math.log(daylight[2] / daylight[1]) - p.b2;
        return new Prior(p.r1 + dr, p.b1 + db, p.r2 + dr, p.b2 + db, false);
    }

    private static float[] matrix(@Nullable android.hardware.camera2.params.ColorSpaceTransform t) {
        if (t == null) return null;
        final float[] m = new float[9];
        for (int r = 0; r < 3; r++) for (int col = 0; col < 3; col++) m[r * 3 + col] = t.getElement(col, r).floatValue();
        return m;
    }

    private static int illuminant(@Nullable Object v) {
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }

    /**
     * P212f: the DNG locus from the row-major ColorMatrix / CalibrationTransform (null: identity) of the two reference illuminants
     * (EXIF LightSource codes, SENSOR_REFERENCE_ILLUMINANT*); null when a matrix or an illuminant is missing, a neutral is not
     * positive, or the two are less than PRIOR_MIN_SPAN apart.
     */
    @Nullable
    static Prior prior(@Nullable float[] cm1, @Nullable float[] ct1, int ill1, @Nullable float[] cm2, @Nullable float[] ct2, int ill2) {
        final double[] w1 = illuminantXyz(ill1), w2 = illuminantXyz(ill2);
        if (cm1 == null || cm2 == null || w1 == null || w2 == null) return null;
        final double[] n1 = mul(ct1, mul(cm1, w1)), n2 = mul(ct2, mul(cm2, w2));
        for (int i = 0; i < 3; i++) if (!(n1[i] > 0) || !(n2[i] > 0) || !Double.isFinite(n1[i]) || !Double.isFinite(n2[i])) return null;
        double r1 = Math.log(n1[0] / n1[1]), b1 = Math.log(n1[2] / n1[1]), r2 = Math.log(n2[0] / n2[1]), b2 = Math.log(n2[2] / n2[1]);
        if (Math.hypot(r1 - r2, b1 - b2) < PRIOR_MIN_SPAN) return null;
        if (r1 < r2) { // warmer (larger r / g) first
            final double tr = r1, tb = b1;
            r1 = r2; b1 = b2; r2 = tr; b2 = tb;
        }
        return new Prior(r1, b1, r2, b2, false);
    }

    private static double[] mul(@Nullable float[] m, double[] v) {
        if (m == null || m.length < 9) return v.clone();
        final double[] out = new double[3];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) out[r] += m[r * 3 + c] * v[c];
        return out;
    }

    /** XYZ (Y = 1) of an EXIF LightSource: the CIE standard illuminants by their chromaticity, the others by Kelvin (Kang). */
    @Nullable
    static double[] illuminantXyz(int code) {
        double x, y;
        switch (code) {
            case 17: x = 0.44757; y = 0.40745; break;          // Standard A
            case 18: x = 0.34842; y = 0.35161; break;          // Standard B
            case 19: x = 0.31006; y = 0.31616; break;          // Standard C
            case 1: case 4: case 9: case 20: x = 0.33242; y = 0.34743; break; // D55 (daylight, flash, fine weather)
            case 10: case 21: x = 0.31271; y = 0.32902; break; // D65 (cloudy)
            case 11: case 22: x = 0.29902; y = 0.31485; break; // D75 (shade)
            case 23: x = 0.34567; y = 0.35850; break;          // D50
            case 2: case 14: return com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.xyz(4150);
            case 3: return com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.xyz(2856);
            case 12: return com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.xyz(6430);
            case 13: return com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.xyz(5000);
            case 15: return com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.xyz(3450);
            case 24: return com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance.xyz(3200);
            default: return null;
        }
        return new double[]{x / y, 1, (1 - x - y) / y};
    }

    /** P212f: a measurement's place on the locus. */
    static final class Projection {
        /** The neutral (ln r, ln b), its distance from the locus, the tint kept, how far beyond the ends (ln, 0: inside). */
        final double lr, lb, distance, tint, beyond;
        final boolean extrapolated;
        /** P366: beyond the extended cool end, moved along the locus by no more than towards it (its colour temperature kept). */
        final boolean coolKept;
        Projection(double lr, double lb, double distance, double tint, double beyond, boolean extrapolated) {
            this(lr, lb, distance, tint, beyond, extrapolated, false);
        }
        Projection(double lr, double lb, double distance, double tint, double beyond, boolean extrapolated, boolean coolKept) {
            this.lr = lr;
            this.lb = lb;
            this.distance = distance;
            this.tint = tint;
            this.beyond = beyond;
            this.extrapolated = extrapolated;
            this.coolKept = coolKept;
        }
    }

    /**
     * P212f: a smooth illuminant locus ln b = a0 + a1 (u - u0) + a2 (u - u0)^2 over u = ln r: {@code [lo, hi]} is what its points
     * (or prior) cover, extended to {@code [elo, ehi]} by EXTRAPOLATE along the curve. P212z: {@code warmKeep} - its warm end is
     * the warmest learned light (2 points or more), so beyond it only WARM_KEEP of the excess is followed.
     */
    static final class Locus {
        final double u0, a0, a1, a2, lo, hi, elo, ehi;
        final String kind;
        final int points;
        final boolean warmKeep;
        /** P366: beyond the extended cool end the measurement's colour temperature is kept ({@link #project}); false: P212f's clamp. */
        boolean coolKeep = true;
        /**
         * P366 (review): the fit of the learned points the slope guard set aside (P212f's quadratic or line), null when the guard let
         * it stand: the curve a neutral is checked against ({@link #onLearnedCurve}).
         */
        @Nullable Locus learned;
        /** The learned points (ln r, ln b), their kernel weights ln(1 + count) and tints off the curve (along its normal); null: none. */
        @Nullable double[] pu, pb, pw, pt;

        Locus(double u0, double a0, double a1, double a2, double lo, double hi, String kind, int points) {
            this(u0, a0, a1, a2, lo, hi, kind, points, points >= 2);
        }

        Locus(double u0, double a0, double a1, double a2, double lo, double hi, String kind, int points, boolean warmKeep) {
            this.u0 = u0;
            this.a0 = a0;
            this.a1 = a1;
            this.a2 = a2;
            this.lo = lo;
            this.hi = hi;
            this.kind = kind;
            this.points = points;
            this.warmKeep = warmKeep;
            this.elo = lo - EXTRAPOLATE / Math.sqrt(1 + df(lo) * df(lo));
            this.ehi = hi + EXTRAPOLATE / Math.sqrt(1 + df(hi) * df(hi));
        }

        double f(double u) {
            final double d = u - u0;
            return a0 + a1 * d + a2 * d * d;
        }

        double df(double u) {
            return a1 + 2 * a2 * (u - u0);
        }

        private double d2(double u, double lr, double lb) {
            final double e = f(u) - lb;
            return (u - lr) * (u - lr) + e * e;
        }

        /** P212f: the learned points the local tint comes from ({ln r, ln b, count, ...}); their tints off this curve. */
        Locus withPoints(List<float[]> pts) {
            final int n = pts.size();
            pu = new double[n];
            pb = new double[n];
            pw = new double[n];
            pt = new double[n];
            for (int i = 0; i < n; i++) {
                final float[] p = pts.get(i);
                pu[i] = p[0];
                pb[i] = p[1];
                pw[i] = Math.log(1 + (p.length > 2 ? Math.max(1f, p[2]) : 1f));
                final double u = footU(p[0], p[1]), s = df(u), norm = Math.sqrt(1 + s * s);
                pt[i] = (p[0] - u) * (-s / norm) + (p[1] - f(u)) / norm;
            }
            return this;
        }

        /**
         * The learned points' tint at (lr, lb): their tints weighted by ln(1 + count) and a Gaussian of TINT_KERNEL in their
         * distance, shrunk towards the curve where fewer than one point's weight is near (0 without points).
         */
        double localTint(double lr, double lb) {
            if (pu == null) return 0;
            final double h2 = 2 * TINT_KERNEL * TINT_KERNEL;
            double sw = 0, st = 0;
            for (int i = 0; i < pu.length; i++) {
                final double d2 = (pu[i] - lr) * (pu[i] - lr) + (pb[i] - lb) * (pb[i] - lb);
                if (d2 > 4.5 * h2) continue;
                final double w = pw[i] * Math.exp(-d2 / h2);
                sw += w;
                st += w * pt[i];
            }
            return st / Math.max(sw, Math.log(2));
        }

        /**
         * The neutral for a measurement: its nearest point on the curve within [elo, ehi] - beyond the warmest learned light
         * moved by WARM_KEEP of the excess (at most WARM_MAX; P212z: only when {@link #warmKeep}, else the whole excess up to
         * EXTRAPOLATE as beyond the cool end) - offset along the normal there by the learned points' local tint, the measurement's
         * own tint kept within {@code tint} of it. P366 ({@link #coolKeep}): a measurement whose nearest point lies beyond the
         * extended cool end (held at elo) is moved along the curve there by no more than it is moved towards it - the end pulled a
         * light cooler than any the camera learned back towards the learned ones, raising its R/G; nothing is known of the locus
         * there but its direction - and (review) left at most WARM_MAX beyond the end, as the warm end holds its excess: a
         * measurement lying along the curve 1 ln beyond the end was followed the whole way (0.157 / 1.991 against a cool end at
         * 0.306 / 0.943), and a sky- or sea-filled frame's statistic, biased cool, would have warmed the photo by that much.
         */
        Projection project(double lr, double lb, double tint) {
            return project(lr, lb, tint, coolKeep);
        }

        /** {@link #project} with the cool keep on or off (P366 review: off for a check against the learned curve). */
        Projection project(double lr, double lb, double tint, boolean keepCool) {
            double u = footU(lr, lb);
            final boolean atCoolEnd = keepCool && u - elo < 1e-6 && elo < lo;
            final double f0 = f(u), s0 = df(u), n0 = Math.sqrt(1 + s0 * s0);
            final double along = (lr - u) * (-s0 / n0) + (lb - f0) / n0; // the measurement's own tint
            final double distance = Math.hypot(lr - u, lb - f0);
            double beyond = 0;
            if (u > hi) {
                final double excess = arc(hi, u), kept = warmKeep ? Math.min(WARM_MAX, WARM_KEEP * excess) : excess;
                if (excess > 0) u = hi + (u - hi) * kept / excess;
                beyond = kept;
            } else if (u < lo) {
                beyond = arc(u, lo);
            }
            final double fu = f(u), s = df(u), norm = Math.sqrt(1 + s * s);
            final double nx = -s / norm, ny = 1 / norm; // the normal towards larger ln b
            final double local = localTint(lr, lb);
            final double t = local + Math.max(-tint, Math.min(tint, along - local));
            double qx = u + t * nx, qy = fu + t * ny;
            boolean coolKept = false;
            if (atCoolEnd) {
                // the move from the measurement: along the curve (its tangent towards warmer light, larger ln r) and towards it;
                // q - m = alongMove t + towardMove n, so keeping |towardMove| of the along move leaves the neutral alongMove -
                // |towardMove| beyond the end along the tangent - at most WARM_MAX (review)
                final double tx = 1 / norm, ty = s / norm, dx = qx - lr, dy = qy - lb;
                final double alongMove = dx * tx + dy * ty, towardMove = dx * nx + dy * ny;
                if (alongMove > Math.abs(towardMove)) {
                    final double past = Math.min(WARM_MAX, alongMove - Math.abs(towardMove));
                    qx -= past * tx;
                    qy -= past * ty;
                    coolKept = true;
                }
            }
            return new Projection(qx, qy, distance, t, beyond, beyond > 1e-4, coolKept);
        }

        /** u of the nearest point of the curve within [elo, ehi] to (lr, lb). */
        double footU(double lr, double lb) {
            final int k = 96;
            double bu = elo, bd = Double.MAX_VALUE;
            for (int i = 0; i <= k; i++) {
                final double u = elo + (ehi - elo) * i / k, d = d2(u, lr, lb);
                if (d < bd) { bd = d; bu = u; }
            }
            // golden-section refinement inside the neighbouring samples (the distance is smooth along a gentle curve)
            final double step = (ehi - elo) / k;
            double a = Math.max(elo, bu - step), b = Math.min(ehi, bu + step);
            final double g = 0.6180339887498949;
            double c = b - g * (b - a), d = a + g * (b - a);
            for (int i = 0; i < 40; i++) {
                if (d2(c, lr, lb) < d2(d, lr, lb)) b = d;
                else a = c;
                c = b - g * (b - a);
                d = a + g * (b - a);
            }
            final double u = 0.5 * (a + b);
            return d2(u, lr, lb) > bd ? bu : u;
        }

        /** The curve's length between u1 < u2 (8 steps). */
        private double arc(double u1, double u2) {
            double len = 0;
            for (int i = 0; i < 8; i++) {
                final double a = u1 + (u2 - u1) * i / 8, b = u1 + (u2 - u1) * (i + 1) / 8;
                len += Math.hypot(b - a, f(b) - f(a));
            }
            return len;
        }

        String describe() {
            return String.format(Locale.ROOT, "%s, %d points", kind, points);
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%s: ln b = %.4f %+.4f (ln r %+.4f) %+.4f (ln r %+.4f)^2 over ln r %.3f..%.3f (extended %.3f..%.3f)",
                    describe(), a0, a1, -u0, a2, -u0, lo, hi, elo, ehi);
        }
    }

    /** P212f: camera {@code id}'s fitted locus (cached until a point is learned), null without one. */
    private static Locus locus(String id, ArrayList<float[]> pts, @Nullable Prior prior) {
        synchronized (MosaicNeutral.class) {
            final Object[] f = FITS.get(id);
            final Integer version = VERSIONS.getOrDefault(id, 0);
            // P366: the scam_dev switches of the fit are part of the key (an A/B within a process)
            final int switches = (slopeGuardOn() ? 1 : 0) | (coolKeepOn() ? 2 : 0);
            if (f != null && version.equals(f[0]) && f[1] == prior && ((Integer) f[3]) == pts.size() && ((Integer) f[4]) == switches)
                return (Locus) f[2];
            final Locus l = fit(pts, prior);
            FITS.put(id, new Object[]{version, prior, l, pts.size(), switches});
            return l;
        }
    }

    /** P212f: the curve of camera {@code id} as the log shows it (points and fit), for an offline replay. */
    public static String describe(String id, @Nullable Prior prior) {
        final ArrayList<float[]> pts;
        synchronized (MosaicNeutral.class) { pts = copy(curve(id)); }
        final StringBuilder b = new StringBuilder();
        final Locus l = fit(pts, prior);
        b.append(l == null ? "no locus" : l.toString()).append("; prior ").append(prior == null ? "none" : prior.toString()).append("; points");
        for (float[] p : pts) b.append(String.format(Locale.ROOT, " %.4f,%.4f,%d", p[0], p[1], (int) p[2]));
        return b.toString();
    }

    /**
     * P212f: the locus through points {ln r, ln b, count, ...}: a Huber quadratic (≥ QUAD_POINTS over ≥ QUAD_SPAN of ln b - ln r),
     * else a Huber line (≥ 2 points over ≥ 0.05 of ln r, a falling slope), else the prior's slope (or DEFAULT_SLOPE) through the
     * points' weighted mean; one point: the prior's shape through it, P212z over the prior's span of ln r on either side of the
     * point (a vivo prior is the shape only, so where the point lies on it is unknown: it may be the daylight end or the tungsten
     * one); none: the prior itself (unless shape only). Null: nothing. P366: with a prior, a quadratic or line whose slope at the
     * points' centre is more than SLOPE_BAND x as steep as the prior's is not taken while the points cover less than
     * PRIOR_SPAN_TRUST of the prior's span along its direction (the prior's slope through their weighted centre then).
     */
    @Nullable
    static Locus fit(List<float[]> pts, @Nullable Prior prior) {
        final Locus l = fitLocus(pts, prior);
        if (l != null) l.coolKeep = coolKeepOn();
        return l;
    }

    @Nullable
    private static Locus fitLocus(List<float[]> pts, @Nullable Prior prior) {
        final int n = pts.size();
        if (n == 0) {
            if (prior == null || prior.shapeOnly) return null;
            final double s = prior.slope();
            return new Locus(prior.r2, prior.b2, s, 0, Math.min(prior.r1, prior.r2), Math.max(prior.r1, prior.r2), "DNG prior", 0, false);
        }
        // P366: the points' own slope stands against the prior only within the band, unless they cover enough of its range
        final boolean guard = prior != null && n >= 2 && slopeGuardOn() && spanAlong(pts, prior) < PRIOR_SPAN_TRUST * prior.span();
        double rejected = Double.NaN;
        Locus setAside = null; // P366 (review): the fit P212f took, when the guard does not (Locus.learned)
        double wsum = 0, mu = 0, mb = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, clo = Double.MAX_VALUE, chi = -Double.MAX_VALUE;
        final double[] u = new double[n], y = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            final float[] p = pts.get(i);
            u[i] = p[0];
            y[i] = p[1];
            w[i] = Math.log(1 + (p.length > 2 ? Math.max(1f, p[2]) : 1f));
            wsum += w[i];
            mu += w[i] * u[i];
            mb += w[i] * y[i];
            lo = Math.min(lo, u[i]);
            hi = Math.max(hi, u[i]);
            clo = Math.min(clo, y[i] - u[i]);
            chi = Math.max(chi, y[i] - u[i]);
        }
        mu /= wsum;
        mb /= wsum;
        if (n >= QUAD_POINTS && chi - clo >= QUAD_SPAN) {
            final double[] a = huber(u, y, w, mu, 2);
            if (a != null) {
                final Locus q = new Locus(mu, a[0], a[1], a[2], lo, hi, "quadratic", n);
                if (falling(q)) {
                    if (!guard || inBand(q.df(mu), prior)) return q.withPoints(pts);
                    rejected = q.df(mu);
                    setAside = q.withPoints(pts);
                }
            }
        }
        if (n >= 2 && hi - lo >= 0.05) {
            final double[] a = huber(u, y, w, mu, 1);
            if (a != null) {
                final Locus line = new Locus(mu, a[0], a[1], 0, lo, hi, "line", n);
                if (falling(line)) {
                    if (!guard || inBand(a[1], prior)) {
                        line.learned = setAside;
                        return line.withPoints(pts);
                    }
                    rejected = a[1];
                    if (setAside == null) setAside = line.withPoints(pts);
                }
            }
        }
        if (n == 1 && prior == null) return null;
        final double s = prior != null ? prior.slope() : DEFAULT_SLOPE;
        if (n == 1) {
            // P212z (review): not a locus of zero width at the point (every warmer light was held within WARM_MAX of it)
            final double span = Math.abs(prior.r1 - prior.r2);
            return new Locus(mu, mb, s, 0, lo - span, hi + span, "DNG shape through 1 point", 1, false).withPoints(pts);
        }
        final String kind = !Double.isNaN(rejected)
                ? String.format(Locale.ROOT, "DNG slope through the points (their %.2f is %.1f x the prior's)", rejected, rejected / s)
                : prior != null ? "DNG shape through the points" : "default slope through the points";
        final Locus held = new Locus(mu, mb, s, 0, lo, hi, kind, n).withPoints(pts);
        held.learned = setAside;
        return held;
    }

    /** P366: the extent (ln) of points {ln r, ln b, ...} along the prior's direction (1, slope). */
    static double spanAlong(List<float[]> pts, Prior prior) {
        final double s = prior.slope(), norm = Math.sqrt(1 + s * s);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (float[] p : pts) {
            final double a = (p[0] + s * p[1]) / norm;
            lo = Math.min(lo, a);
            hi = Math.max(hi, a);
        }
        return pts.isEmpty() ? 0 : hi - lo;
    }

    /** P366: a (falling) slope at most SLOPE_BAND x as steep as the prior's. */
    static boolean inBand(double slope, Prior prior) {
        return slope / prior.slope() <= SLOPE_BAND;
    }

    /** P212f: a locus whose slope falls (warmer: r up, b down) within [-4, -0.2] over its whole extended domain. */
    private static boolean falling(Locus l) {
        for (double u : new double[]{l.elo, l.lo, l.hi, l.ehi}) {
            final double s = l.df(u);
            if (!(s <= -0.2 && s >= -4)) return false;
        }
        return true;
    }

    /** Huber IRLS of y = a0 + a1 (u - u0) [+ a2 (u - u0)^2] with base weights w; null when singular. */
    @Nullable
    private static double[] huber(double[] u, double[] y, double[] w, double u0, int degree) {
        final int m = degree + 1, n = u.length;
        final double[] h = new double[n];
        Arrays.fill(h, 1);
        double[] a = null;
        for (int iter = 0; iter < 8; iter++) {
            final double[][] A = new double[m][m + 1];
            for (int i = 0; i < n; i++) {
                final double d = u[i] - u0, wi = w[i] * h[i];
                final double[] x = degree == 2 ? new double[]{1, d, d * d} : new double[]{1, d};
                for (int r = 0; r < m; r++) {
                    for (int c = 0; c < m; c++) A[r][c] += wi * x[r] * x[c];
                    A[r][m] += wi * x[r] * y[i];
                }
            }
            final double[] s = solve(A, m);
            if (s == null) return a;
            a = s;
            for (int i = 0; i < n; i++) {
                final double d = u[i] - u0;
                final double res = Math.abs(y[i] - (a[0] + a[1] * d + (degree == 2 ? a[2] * d * d : 0)));
                h[i] = res <= HUBER ? 1 : HUBER / res;
            }
        }
        return a;
    }

    @Nullable
    private static double[] solve(double[][] A, int m) {
        for (int c = 0; c < m; c++) {
            int p = c;
            for (int r = c + 1; r < m; r++) if (Math.abs(A[r][c]) > Math.abs(A[p][c])) p = r;
            if (Math.abs(A[p][c]) < 1e-12) return null;
            final double[] t = A[c]; A[c] = A[p]; A[p] = t;
            for (int r = 0; r < m; r++) {
                if (r == c) continue;
                final double k = A[r][c] / A[c][c];
                for (int k2 = c; k2 <= m; k2++) A[r][k2] -= k * A[c][k2];
            }
        }
        final double[] x = new double[m];
        for (int i = 0; i < m; i++) x[i] = A[i][m] / A[i][i];
        return x;
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // P121: the statistic

    /** Shades of grey (p = 4) over the colour cells of the frame: {R / G, B / G, cells} (P212e: the cells too), null with fewer than 64. */
    static float[] measure(ByteBuffer raw, int width, int height, int stride, int cfa, float[] black, float white, int block) {
        if (raw == null || block < 1 || width < 4 * block || height < 4 * block || cfa < 0 || cfa > 3) return null;
        final ShortBuffer px = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        final int rowShorts = stride / 2, cell = 2 * block;
        final int cellsX = width / cell, cellsY = height / cell;
        final int step = Math.max(1, Math.max(cellsX, cellsY) / 256);
        // canonical position of red / blue in the 2x2 colour order
        final int redPhase = cfa == 0 ? 0 : cfa == 1 ? 1 : cfa == 2 ? 2 : 3, bluePhase = 3 - redPhase;
        final float range = Math.max(1f, white - (black[0] + black[1] + black[2] + black[3]) / 4f);
        double sr = 0, sg = 0, sb = 0;
        int n = 0;
        final float[] c = new float[4];
        for (int cy = 0; cy < cellsY; cy += step) {
            for (int cx = 0; cx < cellsX; cx += step) {
                boolean bad = false;
                for (int ph = 0; ph < 4; ph++) {
                    final int x0 = cx * cell + (ph & 1) * block, y0 = cy * cell + (ph >> 1) * block;
                    double sum = 0;
                    for (int y = 0; y < block; y++) {
                        final int row = (y0 + y) * rowShorts;
                        for (int x = 0; x < block; x++) {
                            final int i = row + x0 + x;
                            if (i >= px.limit()) { bad = true; break; }
                            sum += px.get(i) & 0xFFFF;
                        }
                    }
                    final float v = (float) (sum / (block * block) - black[ph]) / range;
                    if (v > 0.9f) bad = true;
                    c[ph] = v;
                }
                if (bad) continue;
                final float r = c[redPhase], b = c[bluePhase], g = 0.5f * (c[(redPhase ^ 1)] + c[(redPhase ^ 2)]);
                if (g < 0.02f || r <= 0f || b <= 0f) continue;
                sr += Math.pow(r, 4); sg += Math.pow(g, 4); sb += Math.pow(b, 4);
                n++;
            }
        }
        if (n < 64 || sg <= 0) return null;
        final double g = Math.pow(sg / n, 0.25);
        return new float[]{(float) (Math.pow(sr / n, 0.25) / g), (float) (Math.pow(sb / n, 0.25) / g), n};
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // P212f (W4 = M10): the robust statistic

    /** P212f: what {@link #measure2} reads and corrects. */
    public static final class Setup {
        /** The output crop {left, top, right, bottom} (fractions of the frame): only cells inside count. */
        public float left, top, right = 1f, bottom = 1f;
        /** Lens shading gains in LensShadingMap.copyGainFactors order (R, G even, G odd, B a point, {@code gainCols} a row); null: none. */
        @Nullable public float[] gains;
        public int gainCols, gainRows;
        /** The noise of a colour-block mean at level x (of white - black): S x + O; NaN: the generic model at {@link #iso}. */
        public double noiseS = Double.NaN, noiseO = Double.NaN;
        public int iso = 100;

        /** The crop of the photo's frame: the digital zoom's residual {@code zoom} (centred), then (ISZ, native FULLRES) 4:3. */
        public Setup crop(int width, int height, float zoom, boolean aspect4x3) {
            final double f = zoom > 1.005f ? zoom : 1.0;
            double w = width / f, h = height / f;
            if (aspect4x3 && w > 0 && h > 0) {
                // IszAspectCrop.bounds in fractions: the largest 4:3 (or 3:4) inside, centred
                final double unit = Math.min(Math.max(w, h) / 4, Math.min(w, h) / 3);
                final double cw = w >= h ? 4 * unit : 3 * unit, ch = w >= h ? 3 * unit : 4 * unit;
                w = cw;
                h = ch;
            }
            left = (float) (0.5 - w / width / 2);
            right = (float) (0.5 + w / width / 2);
            top = (float) (0.5 - h / height / 2);
            bottom = (float) (0.5 + h / height / 2);
            return this;
        }

        /** The noise profile of a result {@code profile} (Camera2 SENSOR_NOISE_PROFILE, per CFA channel): its green pair. */
        public Setup noise(@Nullable android.util.Pair<Double, Double>[] profile, int iso) {
            this.iso = iso > 0 ? iso : 100;
            if (profile == null || profile.length == 0 || profile[0] == null) return this;
            final int g0 = profile.length >= 4 ? 1 : 0, g1 = profile.length >= 4 ? 2 : 0;
            if (profile[g0] == null || profile[g1] == null || profile[g0].first == null || profile[g1].first == null) return this;
            final double s = 0.5 * (profile[g0].first + profile[g1].first), o = 0.5 * (profile[g0].second + profile[g1].second);
            if (s > 0 && o >= 0 && Double.isFinite(s) && Double.isFinite(o)) {
                noiseS = s;
                noiseO = o;
            }
            return this;
        }

        double s() {
            return noiseS > 0 ? noiseS : 4e-5 * iso / 100.0;
        }

        double o() {
            return noiseO >= 0 ? noiseO : 6e-7 * (iso / 100.0) * (iso / 100.0);
        }
    }

    /** P212f: the robust statistic of one or more frames. */
    public static final class Stat {
        /** R / G and B / G; the split sigma of their logs (ln units). */
        public final float r, b, sigmaR, sigmaB;
        /** The effective number of cells ((Σw)^2 / Σw^2), the clipped fraction of the cells read, their median G level. */
        public final float nEff, clipped, median;
        /** The cells that counted, the frames, whether the shading gains were applied. */
        public final int cells, frames;
        public final boolean shaded;

        Stat(float r, float b, float sigmaR, float sigmaB, float nEff, float clipped, float median, int cells, int frames, boolean shaded) {
            this.r = r;
            this.b = b;
            this.sigmaR = sigmaR;
            this.sigmaB = sigmaB;
            this.nEff = nEff;
            this.clipped = clipped;
            this.median = median;
            this.cells = cells;
            this.frames = frames;
            this.shaded = shaded;
        }

        public float sigma() {
            return (float) Math.hypot(sigmaR, sigmaB);
        }

        /** 1 at a sigma of 0.01 ln and below, 0 at 0.05 and above; lower with fewer than 2000 effective cells. */
        public float confidence() {
            final float c = Math.max(0f, Math.min(1f, (0.05f - sigma()) / 0.04f));
            return c * Math.min(1f, nEff / 2000f);
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%.3f / %.3f (sd %.4f / %.4f, conf %.2f, %d cells, nEff %.0f, clipped %.1f %%, median %.4f, %d frame%s%s)",
                    r, b, sigmaR, sigmaB, confidence(), cells, nEff, 100 * clipped, median, frames, frames == 1 ? "" : "s",
                    shaded ? ", shading" : "");
        }
    }

    /** Sampled cells across the longer side of the crop (as measure). */
    static final int GRID = 256;
    /** Cells of a level tile (TILE x TILE sampled cells): a cell's noise weight comes from its tile without the cell itself. */
    static final int TILE = 4;
    /** Grey cells: within SELECT ln of the estimate (widened by twice the cell's chroma noise); unclassifiable above NOISY. */
    static final double SELECT = 0.15, NOISY = 0.3;
    /** The brightest fraction of the cells left out (lamps, speculars); the clip level of a site (of white - black). */
    static final double TRIM = 0.002, CLIP = 0.98;
    /** Below this sum of weights there is no statistic. */
    static final double MIN_WEIGHT = 200;

    private static final ThreadLocal<float[][]> SCRATCH = new ThreadLocal<>();

    /**
     * P212f (W4 = M10): the neutral of a frame (16-bit little endian, {@code stride} bytes a row; colour blocks of {@code block}
     * sites in the 2x2 order {@code cfa}; black per phase, white), robust and with a confidence:
     * <ul>
     * <li>a cell (its 4 colour blocks) with any site at CLIP of white - black is left out (G sites clip first inside a Quad block:
     * the P121 block-mean test missed them, an overexposed start read 0.351 / 0.678 against 0.332 / 0.670 settled);</li>
     * <li>no dark cut: each cell weighs w = l^2 / (l^2 + S l + O), the camera's noise profile at the level l of its tile of cells
     * without the cell itself (a weight independent of the cell's own noise does not bias the ratio), negative R / B kept (the
     * dark cut left 2,928 of ~49k cells voting on the night burst 185248);</li>
     * <li>the lens shading gains at the cell's centre undo vignetting and colour shading; only cells inside the output crop;</li>
     * <li>the estimate: the weighted median chroma of the cells (robust to a dominant colour up to half the frame - a shades of
     * grey start would sit in a bright blue sky), then two passes of grey-cell selection (cells within SELECT ln of the estimate,
     * widened by their chroma noise; cells too noisy to tell stay) giving the weighted grey world over them (p = 1: no noise
     * bias), the brightest TRIM left out;</li>
     * <li>its sigma from the spread of 4 interleaved quarters of the cells (a half split with 3 degrees of freedom).</li>
     * </ul>
     * Null when the weights sum to less than MIN_WEIGHT.
     */
    @Nullable
    public static Stat measure2(ByteBuffer raw, int width, int height, int stride, int cfa, float[] black, float white, int block,
                                @Nullable Setup setup) {
        if (raw == null || block < 1 || width < 4 * block || height < 4 * block || cfa < 0 || cfa > 3 || black == null
                || black.length < 4 || !(white > 0)) return null;
        final Setup s = setup != null ? setup : new Setup();
        final ShortBuffer px = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        final int rowShorts = stride / 2, cell = 2 * block, cellsX = width / cell, cellsY = height / cell;
        float cl = Math.max(0f, s.left), ct = Math.max(0f, s.top), cr = Math.min(1f, s.right), cb = Math.min(1f, s.bottom);
        if (!(cr - cl >= 0.02f) || !(cb - ct >= 0.02f)) { cl = 0f; ct = 0f; cr = 1f; cb = 1f; }
        final int cx0 = Math.min(cellsX - 1, (int) Math.floor(cl * cellsX)), cx1 = Math.max(cx0 + 1, Math.min(cellsX, (int) Math.ceil(cr * cellsX)));
        final int cy0 = Math.min(cellsY - 1, (int) Math.floor(ct * cellsY)), cy1 = Math.max(cy0 + 1, Math.min(cellsY, (int) Math.ceil(cb * cellsY)));
        final int step = Math.max(1, Math.max(cx1 - cx0, cy1 - cy0) / GRID);
        final int gx = (cx1 - cx0 + step - 1) / step, gy = (cy1 - cy0 + step - 1) / step, n = gx * gy;
        if (n <= 0) return null;
        float[][] buf = SCRATCH.get();
        if (buf == null || buf[0].length < n) {
            buf = new float[5][n];
            SCRATCH.set(buf);
        }
        final float[] R = buf[0], G = buf[1], B = buf[2], L = buf[3], W = buf[4];
        final int red = cfa, blue = 3 - cfa;
        final float range = Math.max(1f, white - (black[0] + black[1] + black[2] + black[3]) / 4f);
        final float[] clipAt = new float[4];
        for (int ph = 0; ph < 4; ph++) clipAt[ph] = black[ph] + (float) CLIP * (white - black[ph]);
        final float[] gains = s.gains;
        final int gc = s.gainCols, gr = s.gainRows;
        final boolean shaded = gains != null && gc > 1 && gr > 1 && gains.length >= 4 * gc * gr;
        final float[] g3 = new float[3], c = new float[4];
        int read = 0, clipped = 0;
        for (int iy = 0; iy < gy; iy++) {
            final int cy = cy0 + iy * step;
            for (int ix = 0; ix < gx; ix++) {
                final int cx = cx0 + ix * step, k = iy * gx + ix;
                L[k] = Float.NaN; // NaN: no cell
                W[k] = -2f; // -2: no cell, -1: clipped, else its weight
                boolean clip = false, out = false;
                for (int ph = 0; ph < 4 && !out; ph++) {
                    final int x0 = cx * cell + (ph & 1) * block, y0 = cy * cell + (ph >> 1) * block;
                    int sum = 0, max = 0;
                    for (int y = 0; y < block; y++) {
                        final int base = (y0 + y) * rowShorts + x0;
                        if (base + block > px.limit()) { out = true; break; }
                        for (int x = 0; x < block; x++) {
                            final int v = px.get(base + x) & 0xFFFF;
                            sum += v;
                            if (v > max) max = v;
                        }
                    }
                    c[ph] = ((float) sum / (block * block) - black[ph]) / range;
                    if (max >= clipAt[ph]) clip = true;
                }
                if (out) continue;
                read++;
                final float g = 0.5f * (c[red ^ 1] + c[red ^ 2]);
                L[k] = Math.max(0f, g); // the raw level (noise)
                if (clip) { clipped++; W[k] = -1f; continue; } // -1: clipped
                float sr = 1f, sg = 1f, sb = 1f;
                if (shaded) {
                    final float u = (cx * cell + block) / (float) width, v = (cy * cell + block) / (float) height;
                    if (gainAt(gains, gc, gr, u, v, g3)) { sr = g3[0]; sg = g3[1]; sb = g3[2]; }
                }
                R[k] = c[red] * sr;
                G[k] = g * sg;
                B[k] = c[blue] * sb;
                W[k] = 0f;
            }
        }
        if (read == 0) return null;
        // noise weights from the tile level without the cell itself
        final double S = s.s(), O = s.o();
        final int tx = (gx + TILE - 1) / TILE, ty = (gy + TILE - 1) / TILE;
        final double[] tileSum = new double[tx * ty];
        final int[] tileN = new int[tx * ty];
        for (int iy = 0; iy < gy; iy++)
            for (int ix = 0; ix < gx; ix++) {
                final float l = L[iy * gx + ix];
                if (Float.isNaN(l)) continue;
                final int t = (iy / TILE) * tx + ix / TILE;
                tileSum[t] += l;
                tileN[t]++;
            }
        final int[] levelHist = new int[256];
        double lum = 0;
        int usable = 0;
        for (int iy = 0; iy < gy; iy++)
            for (int ix = 0; ix < gx; ix++) {
                final int k = iy * gx + ix;
                final float l = L[k];
                if (Float.isNaN(l)) continue;
                levelHist[levelBin(l)]++;
                if (W[k] < 0f) continue;
                final int t = (iy / TILE) * tx + ix / TILE;
                final double level = tileN[t] > 1 ? Math.max(0, (tileSum[t] - l) / (tileN[t] - 1)) : l;
                final double var = S * level + O;
                W[k] = (float) (level * level / (level * level + var));
                // the chroma noise of the cell (ln, at the level and today's guess of the chroma: R and B at about half of G)
                L[k] = (float) (level > 0 ? Math.sqrt((S * 0.5 * level + O) / (0.25 * level * level) * 2 + var / (level * level)) : 10);
                usable++;
            }
        if (usable == 0) return null;
        // the brightest TRIM of the usable cells (corrected luminance) are left out
        final float trimAbove = brightest(R, G, B, W, n, usable);
        // the start: the weighted median chroma of the cells with R, G, B > 0
        double[] est = medianChroma(R, G, B, W, n, trimAbove);
        if (est == null) {
            final double[] gw = greyWorld(R, G, B, W, L, n, trimAbove, null, 0, gx);
            if (gw == null) return null;
            est = new double[]{Math.log(gw[0]), Math.log(gw[1])};
        }
        double[] sums = null;
        for (int pass = 0; pass < 2; pass++) {
            final double[] next = greyWorld(R, G, B, W, L, n, trimAbove, est, SELECT, gx);
            if (next == null || !(next[0] > 0) || !(next[1] > 0)) break;
            sums = next;
            est = new double[]{Math.log(next[0]), Math.log(next[1])};
        }
        if (sums == null) {
            sums = greyWorld(R, G, B, W, L, n, trimAbove, null, 0, gx);
            if (sums == null || !(sums[0] > 0) || !(sums[1] > 0)) return null;
        }
        if (sums[2] < MIN_WEIGHT) return null;
        // the split sigma: the spread of the 4 quarters' estimates, the standard error of their mean
        double sigR = 1, sigB = 1;
        boolean quarters = true;
        final double[] qr = new double[4], qb = new double[4];
        for (int q = 0; q < 4; q++) {
            if (!(sums[5 + 2 * q] > 0) || !(sums[6 + 2 * q] > 0) || !(sums[13 + q] > 0)) { quarters = false; break; }
            qr[q] = Math.log(sums[5 + 2 * q] / sums[13 + q]);
            qb[q] = Math.log(sums[6 + 2 * q] / sums[13 + q]);
        }
        if (quarters) {
            sigR = Math.sqrt(variance(qr) / 4);
            sigB = Math.sqrt(variance(qb) / 4);
        }
        return new Stat((float) sums[0], (float) sums[1], (float) sigR, (float) sigB, (float) (sums[2] * sums[2] / Math.max(1e-12, sums[3])),
                (float) clipped / read, levelOf(levelHist, read), (int) sums[4], 1, shaded);
    }

    private static double variance(double[] v) {
        double m = 0;
        for (double x : v) m += x;
        m /= v.length;
        double s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return s / (v.length - 1);
    }

    /** The corrected luminance above which the brightest TRIM of the usable cells lie (+Inf: none left out). */
    private static float brightest(float[] R, float[] G, float[] B, float[] W, int n, int usable) {
        final int drop = (int) Math.floor(usable * TRIM);
        if (drop < 1) return Float.POSITIVE_INFINITY;
        final float[] lum = new float[usable];
        int m = 0;
        for (int k = 0; k < n && m < usable; k++) if (W[k] >= 0f) lum[m++] = R[k] + 2 * G[k] + B[k];
        if (m <= drop) return Float.POSITIVE_INFINITY;
        Arrays.sort(lum, 0, m);
        return lum[m - drop - 1];
    }

    /** Chroma histogram bins (ln) for the start's median: CHROMA_BIN wide from CHROMA_LO. */
    static final double CHROMA_BIN = 0.005, CHROMA_LO = -4;
    static final int CHROMA_BINS = 1400;

    /** The weighted (noise weights) median of ln R / G and ln B / G over the unclipped cells with R, G, B > 0; null with < 50. */
    @Nullable
    private static double[] medianChroma(float[] R, float[] G, float[] B, float[] W, int n, float trimAbove) {
        final double[] hr = new double[CHROMA_BINS], hb = new double[CHROMA_BINS];
        double total = 0;
        int m = 0;
        for (int k = 0; k < n; k++) {
            if (!(W[k] > 0f && R[k] > 0f && G[k] > 0f && B[k] > 0f && R[k] + 2 * G[k] + B[k] <= trimAbove)) continue;
            hr[chromaBin(Math.log(R[k] / G[k]))] += W[k];
            hb[chromaBin(Math.log(B[k] / G[k]))] += W[k];
            total += W[k];
            m++;
        }
        if (m < 50 || !(total > 0)) return null;
        return new double[]{histMedian(hr, total), histMedian(hb, total)};
    }

    private static int chromaBin(double v) {
        return (int) Math.max(0, Math.min(CHROMA_BINS - 1, Math.floor((v - CHROMA_LO) / CHROMA_BIN)));
    }

    private static double histMedian(double[] h, double total) {
        double sum = 0;
        for (int i = 0; i < h.length; i++) {
            sum += h[i];
            if (sum >= total / 2) return CHROMA_LO + (i + 0.5) * CHROMA_BIN;
        }
        return CHROMA_LO + (h.length - 0.5) * CHROMA_BIN;
    }

    /**
     * The weighted grey world (Σ w R / Σ w G, Σ w B / Σ w G) over the unclipped cells at most {@code trimAbove} bright and (with
     * {@code est} {ln r, ln b}) within {@code select} ln of it, widened by twice their chroma noise (cells noisier than NOISY
     * stay). {r, b, Σw, Σw², cells, then Σ w R / Σ w B of the 4 quarters (5..12) and their Σ w G (13..16)}; null without weight.
     */
    @Nullable
    private static double[] greyWorld(float[] R, float[] G, float[] B, float[] W, float[] noise, int n, float trimAbove,
                                      @Nullable double[] est, double select, int gx) {
        final double[] out = new double[17];
        double sr = 0, sg = 0, sb = 0, sw = 0, sw2 = 0;
        int cells = 0;
        final double er = est == null ? 0 : Math.exp(est[0]), eb = est == null ? 0 : Math.exp(est[1]);
        for (int k = 0; k < n; k++) {
            final float w = W[k];
            if (!(w > 0f) || R[k] + 2 * G[k] + B[k] > trimAbove) continue;
            if (est != null && noise[k] <= NOISY) {
                if (!(R[k] > 0f && G[k] > 0f && B[k] > 0f)) continue;
                final double d = Math.hypot(Math.log(R[k] / G[k]) - est[0], Math.log(B[k] / G[k]) - est[1]);
                if (d > select + 2 * noise[k]) continue;
            }
            sr += w * R[k];
            sg += w * G[k];
            sb += w * B[k];
            sw += w;
            sw2 += (double) w * w;
            cells++;
            final int q = ((k % gx) & 1) + 2 * ((k / gx) & 1);
            out[5 + 2 * q] += w * R[k];
            out[6 + 2 * q] += w * B[k];
            out[13 + q] += w * G[k];
        }
        if (!(sg > 0) || sw <= 0) return null;
        out[0] = sr / sg;
        out[1] = sb / sg;
        out[2] = sw;
        out[3] = sw2;
        out[4] = cells;
        return out;
    }

    private static int levelBin(float l) {
        if (!(l > 1f / 65536f)) return 0;
        return Math.min(255, (int) ((Math.log(l) / Math.log(2) + 16.0) * 16.0));
    }

    private static float levelOf(int[] hist, int total) {
        int sum = 0;
        for (int i = 0; i < hist.length; i++) {
            sum += hist[i];
            if (sum * 2 >= total) return (float) Math.pow(2, -16.0 + (i + 0.5) / 16.0);
        }
        return 1f;
    }

    /** The shading gains {R, G (mean of the two), B} at (u, v) of the frame, bilinear; false when the map gives no positive gain. */
    static boolean gainAt(float[] g, int cols, int rows, float u, float v, float[] out) {
        final float fx = Math.min(cols - 1.0001f, Math.max(0f, u * (cols - 1))), fy = Math.min(rows - 1.0001f, Math.max(0f, v * (rows - 1)));
        final int ix = (int) fx, iy = (int) fy;
        final float ax = fx - ix, ay = fy - iy;
        final int i00 = 4 * (iy * cols + ix), i10 = i00 + 4, i01 = i00 + 4 * cols, i11 = i01 + 4;
        out[0] = bilinear(g, i00, i10, i01, i11, 0, ax, ay);
        out[1] = 0.5f * (bilinear(g, i00, i10, i01, i11, 1, ax, ay) + bilinear(g, i00, i10, i01, i11, 2, ax, ay));
        out[2] = bilinear(g, i00, i10, i01, i11, 3, ax, ay);
        return out[0] > 0f && out[1] > 0f && out[2] > 0f;
    }

    private static float bilinear(float[] g, int i00, int i10, int i01, int i11, int ch, float ax, float ay) {
        final float top = g[i00 + ch] + (g[i10 + ch] - g[i00 + ch]) * ax, bottom = g[i01 + ch] + (g[i11 + ch] - g[i01 + ch]) * ax;
        return top + (bottom - top) * ay;
    }

    /**
     * P212f: the statistic of several frames of one exposure (up to 3 N): the median of their logs (two: the mean), its sigma the
     * larger of their split sigma and their spread, over the square root of the frames; null without any.
     */
    @Nullable
    public static Stat combine(List<Stat> frames) {
        final ArrayList<Stat> s = new ArrayList<>();
        for (Stat f : frames) if (f != null && f.r > 0 && f.b > 0) s.add(f);
        if (s.isEmpty()) return null;
        if (s.size() == 1) return s.get(0);
        final int n = s.size();
        final double[] lr = new double[n], lb = new double[n];
        double vr = 0, vb = 0, nEff = 0, clip = 0, med = 0;
        int cells = 0;
        boolean shaded = true;
        for (int i = 0; i < n; i++) {
            final Stat f = s.get(i);
            lr[i] = Math.log(f.r);
            lb[i] = Math.log(f.b);
            vr += f.sigmaR * f.sigmaR;
            vb += f.sigmaB * f.sigmaB;
            nEff += f.nEff;
            clip += f.clipped;
            med += f.median;
            cells += f.cells;
            shaded &= f.shaded;
        }
        final double mr = median(lr), mb = median(lb);
        final double sr = Math.sqrt(Math.max(vr / n, variance(lr)) / n), sb = Math.sqrt(Math.max(vb / n, variance(lb)) / n);
        return new Stat((float) Math.exp(mr), (float) Math.exp(mb), (float) sr, (float) sb, (float) nEff, (float) (clip / n),
                (float) (med / n), cells, n, shaded);
    }

    private static double median(double[] v) {
        final double[] s = v.clone();
        Arrays.sort(s);
        final int n = s.length;
        return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2]);
    }

    /** Tests: a new process (nothing cached, no store until init). */
    static synchronized void resetForTest() {
        context = null;
        forgetForTest();
        synchronized (PRIORS) { PRIORS.clear(); }
        clock = System::currentTimeMillis;
    }

    /** Tests: the store of {@code c} (init keeps the first context of the process), nothing cached. */
    static synchronized void useContextForTest(Context c) {
        context = c;
        forgetForTest();
    }

    /** Tests: a new process reading the same store (the caches forgotten, the context kept). */
    static synchronized void forgetForTest() {
        CURVES.clear();
        FITS.clear();
        VERSIONS.clear();
        SAVED.clear();
    }
}
