package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * P277 (owner, 2026-10-19: «Нам нужно чтобы при любом кропе экспозиция и тон считались по всему кадру»): the whole frame's
 * statistics of a lab shot - recorded by the shot's own post pipeline ({@link PostPipeline#record}, at the shot, or by a
 * whole-frame lab run for a shot taken before) and put in place of the fragment's own when the lab renders a fragment
 * ({@link PostPipeline#frozen}). Statistics, not decisions: the exposure, the tone and the highlights are still decided from them
 * with each side's settings (WhyAe.finish), so every AE / tone setting acts on a fragment as on the whole photo. «Масштаб входа,
 * EV» scales whyLow before them: a side with another value takes them scaled by its gain ({@link #scaled}). The colour of whyLow
 * (warmth, tint, the scene light kept, the calibration: WhyTone.colourMatrix) stays the shot's - the lab says so on a fragment.
 * <ul>
 *   <li>WhyStats: the WHY exposure statistics of whyLow for each metering mode and the input scale they were measured at, the
 *       gain clamp's noise scale, whyLow's width (WhyCombine's highlight mask is 1.2 % of it);</li>
 *   <li>ScamRgb: the per-channel clip levels (highlight recovery);</li>
 *   <li>ScamDenoise: the brightness of the finish input (p50 / p90: its SNR, the sharpening's back-off) and the lens-shading
 *       term of the whole map;</li>
 *   <li>LensFilter: the frame's level and size (the knee, the streak length, the diffusion's PSF);</li>
 *   <li>WhySky (P378 review): the sky cells of whyLow (WhySkyMask.Cells.pack, its grid): a fragment decides the sky on the whole
 *       frame's cells and takes them at its place.</li>
 * </ul>
 */
public final class LabFrame {
    public static final int MODES = 3;
    final WhyAe.Stats[] stats = new WhyAe.Stats[MODES];
    float noiseScale = Float.NaN;
    /** P391: what the merge left of the one-frame noise for the fusion (WhyTone.State.noiseMerged); NaN: not recorded. */
    float fusionNoise = Float.NaN;
    /** WhyStats' input scale (2^«Масштаб входа, EV») the statistics were measured at; NaN: not recorded (a frame before it). */
    float inScale = Float.NaN;
    int lowWidth;
    /** P333: the whole frame's whyLow height (0: not recorded), the depth of the fusion's pyramid with lowWidth. */
    int lowHeight;
    @Nullable float[] ccLo, ccHi, ccMax;
    boolean nearClip, hiUnflagged, ccBento;
    /** P362: the «Inpaint opposed» chrominance of the whole frame (a fragment uses it), null: none measured. */
    @Nullable float[] oppChroma;
    float p50 = Float.NaN, p90 = Float.NaN;
    /** P417: the dark quantile of the finish input's block means (ScamDenoise's dark colour tier), NaN: none. */
    float pDark = Float.NaN;
    @Nullable float[] lsc;
    boolean lscNone;
    float lensExpo = Float.NaN;
    int width, height;
    /** P378 review: the whole frame's sky cells (WhySkyMask.Cells.pack) and their grid {nx, ny, cell, step}; null: not recorded. */
    @Nullable float[] skyCells;
    @Nullable int[] skyGrid;

    /** P378 review: the whole frame's sky cells, scaled to {@code inScale} (null when none were recorded or they do not fit). */
    @Nullable WhySkyMask.Cells skyCells(float inScale) {
        if (skyCells == null || skyGrid == null || skyGrid.length != 4) return null;
        final WhySkyMask.Cells c = WhySkyMask.Cells.unpack(skyGrid[0], skyGrid[1], skyGrid[2], skyGrid[3], skyCells);
        if (c == null) return null;
        return Float.isFinite(this.inScale) && this.inScale > 0f && inScale > 0f ? c.scaled(inScale / this.inScale) : c;
    }

    @Nullable WhyAe.Stats stats(int mode) {
        return mode >= 0 && mode < MODES ? stats[mode] : null;
    }

    /** Whether it holds the exposure statistics (a run that recorded nothing is not used). */
    public boolean usable() {
        for (WhyAe.Stats s : stats) if (s != null) return true;
        return false;
    }

    public JSONObject toJson() throws JSONException {
        final JSONObject o = new JSONObject();
        o.put("version", 1);
        final JSONArray modes = new JSONArray();
        for (WhyAe.Stats s : stats) modes.put(s == null ? JSONObject.NULL : stats(s));
        o.put("stats", modes);
        put(o, "noiseScale", noiseScale);
        put(o, "fusionNoise", fusionNoise);
        put(o, "inScale", inScale);
        o.put("lowWidth", lowWidth);
        o.put("lowHeight", lowHeight);
        if (ccLo != null) o.put("ccLo", floats(ccLo)).put("ccHi", floats(ccHi)).put("ccMax", floats(ccMax))
                .put("nearClip", nearClip).put("hiUnflagged", hiUnflagged).put("ccBento", ccBento);
        if (oppChroma != null) o.put("oppChroma", floats(oppChroma));
        put(o, "p50", p50);
        put(o, "p90", p90);
        put(o, "pDark", pDark);
        if (lsc != null) o.put("lsc", floats(lsc));
        o.put("lscNone", lscNone);
        put(o, "lensExpo", lensExpo);
        o.put("width", width).put("height", height);
        if (skyCells != null && skyGrid != null) {
            final JSONArray g = new JSONArray();
            for (int v : skyGrid) g.put(v);
            o.put("skyGrid", g).put("skyCells", floats(skyCells));
        }
        return o;
    }

    public static LabFrame fromJson(JSONObject o) throws JSONException {
        final LabFrame f = new LabFrame();
        final JSONArray modes = o.getJSONArray("stats");
        for (int m = 0; m < Math.min(MODES, modes.length()); m++)
            if (!modes.isNull(m)) f.stats[m] = stats(modes.getJSONObject(m));
        f.noiseScale = get(o, "noiseScale");
        f.fusionNoise = get(o, "fusionNoise");
        f.inScale = get(o, "inScale");
        f.lowWidth = o.optInt("lowWidth", 0);
        f.lowHeight = o.optInt("lowHeight", 0);
        if (o.has("ccLo")) {
            f.ccLo = floats(o.getJSONArray("ccLo"));
            f.ccHi = floats(o.getJSONArray("ccHi"));
            f.ccMax = floats(o.getJSONArray("ccMax"));
            f.nearClip = o.optBoolean("nearClip");
            f.hiUnflagged = o.optBoolean("hiUnflagged");
            f.ccBento = o.optBoolean("ccBento");
        }
        if (o.has("oppChroma")) f.oppChroma = floats(o.getJSONArray("oppChroma"));
        f.p50 = get(o, "p50");
        f.p90 = get(o, "p90");
        f.pDark = get(o, "pDark");
        if (o.has("lsc")) f.lsc = floats(o.getJSONArray("lsc"));
        f.lscNone = o.optBoolean("lscNone");
        f.lensExpo = get(o, "lensExpo");
        f.width = o.optInt("width", 0);
        f.height = o.optInt("height", 0);
        if (o.has("skyCells") && o.has("skyGrid")) {
            final JSONArray g = o.getJSONArray("skyGrid");
            f.skyGrid = new int[g.length()];
            for (int i = 0; i < g.length(); i++) f.skyGrid[i] = g.getInt(i);
            f.skyCells = floats(o.getJSONArray("skyCells"));
        }
        return f;
    }

    private static JSONObject stats(WhyAe.Stats s) throws JSONException {
        return new JSONObject().put("gmax", (double) s.gmax).put("hy", doubles(s.hy)).put("hm", doubles(s.hm)).put("tail", doubles(s.tail))
                .put("tot", s.tot).put("sumY", s.sumY).put("sumLog", s.sumLog);
    }

    private static WhyAe.Stats stats(JSONObject o) throws JSONException {
        final WhyAe.Stats s = new WhyAe.Stats();
        s.gmax = (float) o.getDouble("gmax");
        fill(s.hy, o.getJSONArray("hy"));
        fill(s.hm, o.getJSONArray("hm"));
        fill(s.tail, o.getJSONArray("tail"));
        s.tot = o.getDouble("tot");
        s.sumY = o.getDouble("sumY");
        s.sumLog = o.getDouble("sumLog");
        return s;
    }

    /** A copy (WhyAe.finish only reads the statistics, but a frozen record serves many runs). */
    static WhyAe.Stats copy(WhyAe.Stats s) {
        final WhyAe.Stats c = new WhyAe.Stats();
        c.gmax = s.gmax;
        System.arraycopy(s.hy, 0, c.hy, 0, s.hy.length);
        System.arraycopy(s.hm, 0, c.hm, 0, s.hm.length);
        System.arraycopy(s.tail, 0, c.tail, 0, s.tail.length);
        c.tot = s.tot;
        c.sumY = s.sumY;
        c.sumLog = s.sumLog;
        return c;
    }

    /**
     * The statistics of whyLow with every sample x {@code k} (another «Масштаб входа, EV» on the same frame: whyLow is linear in
     * it): the maximum and the sums exactly, the luma / max-channel histograms rebinned at the new ceiling from each bin's middle,
     * the max channel above the ceiling from its tail bins (what the scaled ceiling leaves above it goes back to the tail). k 1:
     * a copy.
     */
    static WhyAe.Stats scaled(WhyAe.Stats s, float k) {
        if (!(k > 0f) || !Float.isFinite(k) || k == 1f) return copy(s);
        final WhyAe.Stats c = new WhyAe.Stats();
        c.gmax = s.gmax > 0f ? s.gmax * k : s.gmax;
        c.tot = s.tot;
        c.sumY = s.sumY * k;
        c.sumLog = s.sumLog + s.tot * Math.log(k);
        final double clipA = WhyAe.clip(s.gmax), clipB = WhyAe.clip(c.gmax);
        final int bins = WhyAe.BINS;
        double above = 0;
        for (double t : s.tail) above += t;
        for (int i = 0; i < bins; i++) {
            final double v = (i + .5) * clipA / bins * k;
            add(c.hy, c.tail, v / clipB, s.hy[i], false);
            // the top bin of the max channel holds the samples at and above the old ceiling: those above it come from the tail
            final double w = i == bins - 1 ? Math.max(0, s.hm[i] - above) : s.hm[i];
            add(c.hm, c.tail, v / clipB, w, true);
        }
        for (int t = 0; t < s.tail.length; t++) {
            if (s.tail[t] <= 0) continue;
            final double v = clipA * Math.pow(2.0, (t + .5) / WhyAe.TAIL_PER_OCTAVE) * k;
            add(c.hm, c.tail, v / clipB, s.tail[t], true);
        }
        return c;
    }

    /** {@code w} of a sample at {@code ratio} x the ceiling into a histogram (clamped as WhyAe.stats) and, above it, the tail. */
    private static void add(double[] hist, double[] tail, double ratio, double w, boolean withTail) {
        if (w <= 0) return;
        hist[(int) (Math.max(0.0, Math.min(1.0, ratio)) * 4095.99)] += w;
        if (withTail && ratio > 1.0) tail[WhyAe.tailBin((float) ratio)] += w;
    }

    private static JSONArray doubles(double[] v) throws JSONException {
        final JSONArray a = new JSONArray();
        for (double x : v) a.put(x == Math.rint(x) && Math.abs(x) < 1e15 ? (Object) (long) x : (Object) x);
        return a;
    }

    private static void fill(double[] v, JSONArray a) throws JSONException {
        for (int i = 0; i < Math.min(v.length, a.length()); i++) v[i] = a.getDouble(i);
    }

    private static JSONArray floats(float[] v) throws JSONException {
        final JSONArray a = new JSONArray();
        for (float x : v) a.put(Float.isFinite(x) ? (Object) (double) x : (Object) String.valueOf(x));
        return a;
    }

    private static float[] floats(JSONArray a) throws JSONException {
        final float[] v = new float[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = (float) Double.parseDouble(a.get(i).toString());
        return v;
    }

    private static void put(JSONObject o, String key, float v) throws JSONException {
        if (Float.isFinite(v)) o.put(key, (double) v);
    }

    private static float get(JSONObject o, String key) {
        return o.has(key) ? (float) o.optDouble(key, Double.NaN) : Float.NaN;
    }
}
