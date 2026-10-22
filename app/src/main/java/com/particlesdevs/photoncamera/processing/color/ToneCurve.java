package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P144: the tone operators of «Кривая AgX и ACES» besides AgX - ACES, Uchimura (Gran Turismo) and an own curve - as WhyCam
 * 1.42 has them (libopenhdr_engine.so, tonemap_final: eval_acescg, uchimura_curve, u_tex_custom_curve; ToneCurveHelper).
 * <p>
 * The own curve: points in the unit square, x the normalised log exposure (0.5 + EV / 10: -5..+5 EV around the 0.18 middle
 * grey of the AgX inset), y the display value (raised to 2.4 into the AgX outset, as AgX's own tonescale); between the points
 * a monotone cubic (Fritsch-Carlson), so the curve never overshoots its points. Stored as "x:y,x:y,..." (WhyCam's format).
 * The Java evaluators here mirror why/combine.glsl (TONE_OP 1) for the tests and the curve preview.
 */
public final class ToneCurve {
    public static final int AGX = 0, ACES = 1, UCHIMURA = 2, CUSTOM = 3;
    /** Samples of the curve the shader gets (uniform vec4 curveU[64]). */
    public static final int SAMPLES = 256;
    /** WhyCam's default: a gentle S around the middle grey. */
    public static final String DEFAULT_POINTS = "0.0000:0.0000,0.2500:0.1600,0.5000:0.5000,0.7500:0.8400,1.0000:1.0000";
    /** Two points never share an x closer than this (a vertical step is no tone curve). */
    public static final float MIN_GAP = 0.01f;

    /** A point of the curve. */
    public static final class Point {
        public float x, y;

        public Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private final List<Point> points;

    private ToneCurve(List<Point> points) {
        this.points = points;
    }

    public static ToneCurve defaults() {
        return parse(DEFAULT_POINTS);
    }

    /** "x:y,x:y" (clamped to the unit square, sorted, the ends pinned to x 0 and 1); fewer than two points: the default. */
    public static ToneCurve parse(@Nullable String text) {
        List<Point> out = new ArrayList<>();
        if (text != null) {
            for (String pair : text.split(",")) {
                String[] xy = pair.trim().split(":");
                if (xy.length != 2) continue;
                try {
                    float x = Float.parseFloat(xy[0].trim()), y = Float.parseFloat(xy[1].trim());
                    if (Float.isNaN(x) || Float.isNaN(y)) continue;
                    out.add(new Point(clamp01(x), clamp01(y)));
                } catch (NumberFormatException ignored) {
                    // a broken pair is skipped
                }
            }
        }
        if (out.size() < 2) return parse(DEFAULT_POINTS); // the default itself has five points
        ToneCurve c = new ToneCurve(out);
        c.normalise();
        return c;
    }

    /** The straight line 0..1 (the curve changes nothing in display terms). */
    public static ToneCurve identity() {
        List<Point> p = new ArrayList<>();
        p.add(new Point(0f, 0f));
        p.add(new Point(1f, 1f));
        return new ToneCurve(p);
    }

    /** "x:y" anywhere in a text: two numbers around a colon. */
    private static final Pattern PAIR = Pattern.compile(
            "([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)\\s*:\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)");
    /** The first line of a saved curve file. */
    public static final String FILE_HEADER = "# SCAMERA tone curve: x = 0.5 + EV / 10 (-5..+5 EV around middle grey), y = display 0..1";

    /** The text of a saved curve file (Download/SCAMERA/ToneCurves): the header line, then the points as stored. */
    public String toFileText() {
        return FILE_HEADER + "\n" + serialize() + "\n";
    }

    /** P231: a number of a JSON text. */
    private static final String NUM = "([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)";
    /** P231: a point of a JSON points array: {"x": n, "y": n} (either order) or [x, y]. */
    private static final Pattern JSON_XY = Pattern.compile("\\{\\s*\"x\"\\s*:\\s*" + NUM + "\\s*,\\s*\"y\"\\s*:\\s*" + NUM + "[^}]*\\}"),
            JSON_YX = Pattern.compile("\\{\\s*\"y\"\\s*:\\s*" + NUM + "\\s*,\\s*\"x\"\\s*:\\s*" + NUM + "[^}]*\\}"),
            JSON_PAIR = Pattern.compile("\\[\\s*" + NUM + "\\s*,\\s*" + NUM + "\\s*\\]"),
            JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * P231: the points of WhyCam's curve file (ToneCurveFileManager, 1.5x: {"name": ..., "points": [{"x": .., "y": ..}, ...]},
     * or a bare array, points also as [x, y]) - the same points as its "x:y" preference string and as ours (x the normalised log
     * exposure, y the display value), clamped to 0..1 as WhyCam does; null when the text is no such JSON.
     */
    @Nullable
    static List<Point> jsonPoints(String text) {
        String t = text.trim();
        if (!t.startsWith("{") && !t.startsWith("[")) return null;
        int at = t.indexOf("\"points\"");
        String body = at >= 0 ? t.substring(at) : t;
        List<float[]> raw = new ArrayList<>();
        for (Pattern p : new Pattern[]{JSON_XY, JSON_YX, JSON_PAIR}) {
            Matcher m = p.matcher(body);
            while (m.find()) {
                try {
                    float a = Float.parseFloat(m.group(1)), b = Float.parseFloat(m.group(2));
                    if (p == JSON_YX) raw.add(new float[]{m.start(), b, a});
                    else raw.add(new float[]{m.start(), a, b});
                } catch (NumberFormatException ignored) {
                    // not a number after all
                }
            }
        }
        if (raw.isEmpty()) return null;
        raw.sort((p, q) -> Float.compare(p[0], q[0]));   // the file's order (the curve sorts by x later)
        List<Point> out = new ArrayList<>();
        for (float[] p : raw) if (Float.isFinite(p[1]) && Float.isFinite(p[2]) && p[1] >= 0 && p[2] >= 0) out.add(new Point(clamp01(p[1]), clamp01(p[2])));
        return out;
    }

    /** P231: the curve's own name in an WhyCam JSON file ("name"), null without one. */
    @Nullable
    public static String jsonName(@Nullable String text) {
        if (text == null || !text.trim().startsWith("{")) return null;
        Matcher m = JSON_NAME.matcher(text);
        if (!m.find()) return null;
        String name = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * A curve from a file's text, or null when it holds fewer than two points: P231 WhyCam's JSON curve file (jsonPoints), else
     * every "x:y" pair outside the lines starting with '#' (commas, spaces, new lines or semicolons between them - our files,
     * WhyCam's "x:y,..." string, a list typed by hand); values up to 255 (an 8-bit curve) are scaled to 0..1, larger ones refuse
     * the file.
     */
    @Nullable
    public static ToneCurve fromFileText(@Nullable String text) {
        if (text == null) return null;
        List<Point> json = jsonPoints(text);
        if (json != null) {
            if (json.size() < 2) return null;
            ToneCurve c = new ToneCurve(json);
            c.normalise();
            return c.size() >= 2 ? c : null;
        }
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\r?\\n")) if (!line.trim().startsWith("#")) body.append(line).append('\n');
        float[] linear = linearPairs(body.toString());
        if (linear != null) return fromLinearCurve(linear);
        List<float[]> raw = new ArrayList<>();
        float max = 0f;
        Matcher m = PAIR.matcher(body);
        while (m.find()) {
            try {
                float x = Float.parseFloat(m.group(1)), y = Float.parseFloat(m.group(2));
                if (!Float.isFinite(x) || !Float.isFinite(y)) continue;
                raw.add(new float[]{x, y});
                max = Math.max(max, Math.max(x, y));
            } catch (NumberFormatException ignored) {
                // not a number after all
            }
        }
        if (raw.size() < 2 || max > 255.5f) return null;
        float scale = max > 1.0001f ? 1f / 255f : 1f;
        List<Point> out = new ArrayList<>();
        for (float[] p : raw) out.add(new Point(clamp01(p[0] * scale), clamp01(p[1] * scale)));
        ToneCurve c = new ToneCurve(out);
        c.normalise();
        return c.size() >= 2 ? c : null;
    }

    /** A plain number of a list. */
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?");

    /**
     * A camera's own tone curve (owner, 2026-10-18, ToneCurve_imx06A.txt: vivo's DNG ProfileToneCurve) - a bare list of numbers,
     * x0, y0, x1, y1, ... (commas or spaces), without any "x:y": the pairs as {x, y, x, y, ...} when the list is such a curve
     * (an even count of at least 4, all in 0..1, x rising from 0 to 1), else null.
     */
    @Nullable
    static float[] linearPairs(String text) {
        if (text.indexOf(':') >= 0) return null;
        if (!text.replaceAll("[-+0-9.eE,;\\s]", "").isEmpty()) return null;   // words: some other file
        List<Float> v = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            try {
                v.add(Float.parseFloat(m.group()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (v.size() < 4 || v.size() % 2 != 0) return null;
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i++) {
            float f = v.get(i);
            if (!Float.isFinite(f) || f < -1e-4f || f > 1.0001f) return null;
            out[i] = clamp01(f);
            if (i % 2 == 0 && i > 0 && out[i] <= out[i - 2]) return null;
        }
        if (out[0] > 1e-3f || out[out.length - 2] < 0.999f) return null;
        return out;
    }

    /** Middle grey in linear light: our curve's x 0.5. */
    static final float MIDDLE_GREY = 0.18f;
    /** Our x of a linear value: 0.5 + EV / 10 around middle grey. */
    static float xOfLinear(float linear) {
        return 0.5f + (float) (Math.log(linear / MIDDLE_GREY) / Math.log(2.0)) / 10f;
    }

    /**
     * A DNG ProfileToneCurve-style curve (linear in, linear out, «in linear gamma» as the DNG specification puts it) in our terms:
     * x the exposure around middle grey (0.5 + EV / 10), y the display value (sRGB) - sampled every 0.03 of x up to the curve's
     * white (linear 1, +2.47 EV), then flat at white. The camera's curve keeps its look: middle grey of ToneCurve_imx06A lands at
     * y 0.57.
     */
    static ToneCurve fromLinearCurve(float[] pairs) {
        List<Point> out = new ArrayList<>();
        final float white = xOfLinear(1f);
        for (float x = 0f; x < white - 0.015f; x += 0.03f) out.add(new Point(x, display(linearAt(pairs, MIDDLE_GREY * (float) Math.pow(2.0, 10.0 * (x - 0.5))))));
        out.add(new Point(white, display(linearAt(pairs, 1f))));
        out.add(new Point(1f, display(linearAt(pairs, 1f))));
        ToneCurve c = new ToneCurve(out);
        c.normalise();
        return c;
    }

    /** The linear curve at {@code v} (straight lines between its points). */
    static float linearAt(float[] pairs, float v) {
        v = clamp01(v);
        for (int i = 2; i < pairs.length; i += 2) {
            if (v <= pairs[i]) {
                float x0 = pairs[i - 2], x1 = pairs[i];
                float t = x1 > x0 ? (v - x0) / (x1 - x0) : 0f;
                return pairs[i - 1] + t * (pairs[i + 1] - pairs[i - 1]);
            }
        }
        return pairs[pairs.length - 1];
    }

    /** Linear -> sRGB display value. */
    static float display(float linear) {
        linear = clamp01(linear);
        return linear <= 0.0031308f ? 12.92f * linear : 1.055f * (float) Math.pow(linear, 1 / 2.4) - 0.055f;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.4f:%.4f", points.get(i).x, points.get(i).y));
        }
        return sb.toString();
    }

    public List<Point> points() {
        return Collections.unmodifiableList(points);
    }

    public int size() {
        return points.size();
    }

    public Point get(int i) {
        return points.get(i);
    }

    /**
     * Adds a point at (x, y) and returns its index (-1 when an existing point is too close: the editor then moves that one).
     * The ends stay at x 0 and 1.
     */
    public int add(float x, float y) {
        x = clamp01(x);
        y = clamp01(y);
        for (Point p : points) if (Math.abs(p.x - x) < MIN_GAP) return -1;
        int i = 0;
        while (i < points.size() && points.get(i).x < x) i++;
        if (i == 0 || i == points.size()) return -1; // outside the pinned ends
        points.add(i, new Point(x, y));
        return i;
    }

    /** Moves a point: the ends keep their x, an inner point stays between its neighbours. */
    public void move(int i, float x, float y) {
        if (i < 0 || i >= points.size()) return;
        Point p = points.get(i);
        p.y = clamp01(y);
        if (i == 0) p.x = 0f;
        else if (i == points.size() - 1) p.x = 1f;
        else p.x = Math.max(points.get(i - 1).x + MIN_GAP, Math.min(points.get(i + 1).x - MIN_GAP, clamp01(x)));
    }

    /** Removes an inner point (the two ends always stay), true when it went. */
    public boolean remove(int i) {
        if (i <= 0 || i >= points.size() - 1) return false;
        points.remove(i);
        return true;
    }

    private void normalise() {
        Collections.sort(points, (a, b) -> Float.compare(a.x, b.x));
        points.get(0).x = 0f;
        points.get(points.size() - 1).x = 1f;
        // merge points closer than the gap (the later one wins), the ends kept
        for (int i = points.size() - 2; i >= 1; i--)
            if (points.get(i + 1).x - points.get(i).x < MIN_GAP || points.get(i).x - points.get(i - 1).x < MIN_GAP) points.remove(i);
    }

    /** The curve at x (0..1), the monotone cubic through the points. */
    public float eval(float x) {
        float[] m = tangents();
        x = clamp01(x);
        int k = 0;
        while (k < points.size() - 2 && x > points.get(k + 1).x) k++;
        Point a = points.get(k), b = points.get(k + 1);
        float h = Math.max(b.x - a.x, 1e-6f), t = (x - a.x) / h, t2 = t * t, t3 = t2 * t;
        float v = (2 * t3 - 3 * t2 + 1) * a.y + (t3 - 2 * t2 + t) * h * m[k] + (-2 * t3 + 3 * t2) * b.y + (t3 - t2) * h * m[k + 1];
        return clamp01(v);
    }

    /** {@code n} evenly spaced samples over 0..1 (the shader's table, the editor's line). */
    public float[] samples(int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = eval(n == 1 ? 0f : i / (float) (n - 1));
        return out;
    }

    /** Fritsch-Carlson tangents (WhyCam's generateLut): no overshoot between the points. */
    private float[] tangents() {
        int n = points.size();
        float[] d = new float[n - 1], m = new float[n];
        for (int i = 0; i < n - 1; i++)
            d[i] = (points.get(i + 1).y - points.get(i).y) / Math.max(1e-6f, points.get(i + 1).x - points.get(i).x);
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0f ? 0f : (d[i - 1] + d[i]) * 0.5f;
        for (int i = 0; i < n - 1; i++) {
            if (Math.abs(d[i]) < 1e-7f) {
                m[i] = 0f;
                m[i + 1] = 0f;
                continue;
            }
            float a = m[i] / d[i], b = m[i + 1] / d[i];
            if (a < 0f) m[i] = 0f;
            if (b < 0f) m[i + 1] = 0f;
            float s = a * a + b * b;
            if (s > 9f) {
                float t = 3f / (float) Math.sqrt(s);
                m[i] = t * a * d[i];
                m[i + 1] = t * b * d[i];
            }
        }
        return m;
    }

    // ───── the operators (mirrors of why/combine.glsl TONE_OP 1) ─────

    /** WhyCam eval_acescg: Narkowicz ACES with WhyCam's toe-driven b, e and its pre-gain, a, d. */
    public static float aces(float v, float toe, float preGain, float a, float d) {
        float x = Math.max(v, 0f) * preGain;
        float b = Math.max(toe * 0.75f, 0.005f);
        float e = Math.max(0.14f - (toe - 0.04f) * 0.8f, 0.03f);
        return x * (a * x + b) / (x * (2.43f * x + d) + e);
    }

    /** WhyCam uchimura_curve (Gran Turismo): toe power c to m, a linear section of slope a and length l, a shoulder to P. */
    public static float uchimura(float x, float P, float a, float m, float l, float c, float bOffset) {
        float l0 = (P - m) * l / a;
        float s0 = m + l0, s1 = m + a * l0;
        float c2 = a * P / (P - s1), cp = -c2 / P;
        float w0 = 1f - smoothstep(0f, m, x);
        float w2 = x >= m + l0 ? 1f : 0f;
        float w1 = 1f - w0 - w2;
        float t = m * (float) Math.pow(Math.max(x / m, 0f), c) + bOffset;
        float s = P - (P - s1) * (float) Math.exp(cp * (x - s0));
        float lin = m + a * (x - m);
        return t * w0 + lin * w1 + s * w2;
    }

    // ───── P177: the operators in the own curve's domain (x = 0.5 + EV / 10 around the 0.18 grey, y = display value) ─────

    /** The scene linear value of the own curve's x. */
    static float sceneOf(float x) {
        return 0.18f * (float) Math.pow(2.0, (x - 0.5f) * 10f);
    }

    /** AgX at x: its tonescale (why/combine.glsl agxTonescale) over its log range, a display value as the own curve's y. */
    public static float agxAt(float x, float slope, float sp, float tp, float minEv, float maxEv, float ev) {
        float range = Math.max(maxEv + Math.max(ev, 0f) - minEv, 1f);
        float px = Math.max(0.05f, Math.min(0.95f, -minEv / range));
        float lg = clamp01(((x - 0.5f) * 10f + ev - minEv) / range);
        return clamp01(agxTonescale(lg, slope, sp, tp, px));
    }

    /** why/combine.glsl agxTonescale (the kernel's AgX sigmoid of slope, shoulder and toe powers around the pivot px). */
    static float agxTonescale(float x, float slope, float sp, float tp, float px) {
        final float py = 0.5f;
        if (x >= px) {
            float sBase = slope * (1f - px) / (1f - py);
            float sDenom = (float) Math.pow(slope * (1f - px), -sp);
            float ss = (float) Math.pow(Math.max((Math.pow(sBase, sp) - 1f) * sDenom, 0.00001f), -1f / sp);
            float ms = slope * (x - px) / Math.max(ss, 0.00001f);
            float fs = ms / (float) Math.pow(1f + Math.pow(Math.max(ms, 0f), sp), 1f / sp);
            return ss * fs + py;
        }
        float tBase = slope * px / py;
        float tDenom = (float) Math.pow(slope * px, -tp);
        float ts = (float) Math.pow(Math.max((Math.pow(tBase, tp) - 1f) * tDenom, 0.00001f), -1f / tp);
        float mr = slope * (px - x) / Math.max(ts, 0.00001f);
        float ft = mr / (float) Math.pow(1f + Math.pow(Math.max(mr, 0f), tp), 1f / tp);
        return py - ts * ft;
    }

    /** ACES at x ({pre-gain, a, d, toe} as WhyTone.acesUniform): its linear display value raised to 1 / 2.4 as the own curve's y. */
    public static float acesAt(float x, float[] u) {
        return (float) Math.pow(clamp01(aces(sceneOf(x), u[3], u[0], u[1], u[2])), 1.0 / 2.4);
    }

    /** Uchimura at x ({pre, post, P, a} and {m, l, c, b} as WhyTone.uchimuraUniforms), as the own curve's y. */
    public static float uchimuraAt(float x, float[] a, float[] b) {
        float v = uchimura(sceneOf(x) * a[0], a[2], a[3], b[0], b[1], b[2], b[3]) * a[1];
        return (float) Math.pow(clamp01(v), 1.0 / 2.4);
    }

    /** An own curve following {@code f} through {@code n} evenly spaced points (never falling, the ends at x 0 and 1). */
    public static ToneCurve following(java.util.function.DoubleUnaryOperator f, int n) {
        StringBuilder sb = new StringBuilder();
        float last = 0f;
        for (int i = 0; i < n; i++) {
            float x = i / (float) (n - 1);
            float y = Math.max(last, clamp01((float) f.applyAsDouble(x)));
            last = y;
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.4f:%.4f", x, y));
        }
        return parse(sb.toString());
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
