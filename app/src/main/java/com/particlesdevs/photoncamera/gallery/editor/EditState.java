package com.particlesdevs.photoncamera.gallery.editor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Locale;

/**
 * P351 (owner, 2026-10-26: «нам нужен редактор изображения в галерее с базовыми функциями как в мобильном приложении
 * лайтрума»): every setting of one edit as a plain value - the tone / colour / effect / detail sliders by {@link Param} and the
 * geometry (quarter turns, horizontal flip, straighten angle, crop rect in normalised coordinates of the turned frame, crop
 * aspect). The renderer, the export, undo / redo and the saved instance state all read this one object; JSON keeps only the
 * settings off their default, so an unedited photo is {"v":1}.
 *
 * <p>Geometry model (CropMath): the oriented photo is turned by {@code quarterTurns} x 90 degrees clockwise, mirrored left-right
 * when {@code flipH}, rotated by {@code straighten} degrees (clockwise on screen) about its centre, then cut to the crop rect
 * {@code cropL..cropR x cropT..cropB}, which is axis-aligned in the turned frame ("base frame", 0..1 on each side).
 */
public final class EditState {
    /** The sliders: JSON key, range, default. Labels live in the UI (EditPanels), the math in EditMath / main.glsl. */
    public enum Param {
        EXPOSURE("exposure", -4f, 4f, 0f),
        CONTRAST("contrast", -100, 100, 0),
        HIGHLIGHTS("highlights", -100, 100, 0),
        SHADOWS("shadows", -100, 100, 0),
        WHITES("whites", -100, 100, 0),
        BLACKS("blacks", -100, 100, 0),
        TEMPERATURE("temp", -100, 100, 0),
        TINT("tint", -100, 100, 0),
        VIBRANCE("vibrance", -100, 100, 0),
        SATURATION("saturation", -100, 100, 0),
        TEXTURE("texture", -100, 100, 0),
        CLARITY("clarity", -100, 100, 0),
        DEHAZE("dehaze", -100, 100, 0),
        VIGNETTE("vignette", -100, 100, 0),
        VIGNETTE_MID("vignetteMid", 0, 100, 50),
        GRAIN("grain", 0, 100, 0),
        GRAIN_SIZE("grainSize", 0, 100, 25),
        SHARPEN("sharpen", 0, 150, 0),
        SHARPEN_RADIUS("sharpenRadius", 0.5f, 3f, 1f),
        LUMA_NR("lumaNr", 0, 100, 0),
        COLOR_NR("colorNr", 0, 100, 0),
        HUE_0("h0"), HUE_1("h1"), HUE_2("h2"), HUE_3("h3"), HUE_4("h4"), HUE_5("h5"), HUE_6("h6"), HUE_7("h7"),
        SAT_0("s0"), SAT_1("s1"), SAT_2("s2"), SAT_3("s3"), SAT_4("s4"), SAT_5("s5"), SAT_6("s6"), SAT_7("s7"),
        LUM_0("l0"), LUM_1("l1"), LUM_2("l2"), LUM_3("l3"), LUM_4("l4"), LUM_5("l5"), LUM_6("l6"), LUM_7("l7"),
        // Кривая: the parametric regions (LightCraft's parametric curve) and their split points
        CURVE_SHADOWS("cShadows"), CURVE_DARKS("cDarks"), CURVE_LIGHTS("cLights"), CURVE_HIGHLIGHTS("cHighlights"),
        CURVE_SPLIT_1("cSplit1", 5, 95, 25), CURVE_SPLIT_2("cSplit2", 5, 95, 50), CURVE_SPLIT_3("cSplit3", 5, 95, 75),
        // Цветокоррекция: shadows / midtones / highlights / global wheels (hue degrees, saturation, luminance), blending, balance
        GRADE_SH_HUE("gShH", 0, 360, 0), GRADE_SH_SAT("gShS", 0, 100, 0), GRADE_SH_LUM("gShL"),
        GRADE_MID_HUE("gMiH", 0, 360, 0), GRADE_MID_SAT("gMiS", 0, 100, 0), GRADE_MID_LUM("gMiL"),
        GRADE_HI_HUE("gHiH", 0, 360, 0), GRADE_HI_SAT("gHiS", 0, 100, 0), GRADE_HI_LUM("gHiL"),
        GRADE_ALL_HUE("gAlH", 0, 360, 0), GRADE_ALL_SAT("gAlS", 0, 100, 0), GRADE_ALL_LUM("gAlL"),
        GRADE_BLEND("gBlend", 0, 100, 50), GRADE_BALANCE("gBalance"),
        // Ч/Б: monochrome (0 / 1) and its 8-band mix
        MONO("mono", 0, 1, 0),
        BW_0("bw0"), BW_1("bw1"), BW_2("bw2"), BW_3("bw3"), BW_4("bw4"), BW_5("bw5"), BW_6("bw6"), BW_7("bw7");

        public final String key;
        public final float min, max, def;

        Param(String key, float min, float max, float def) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.def = def;
        }

        /** An HSL mixer slider: -100..100, 0. */
        Param(String key) {
            this(key, -100, 100, 0);
        }

        /** The slider step: 0.01 EV, 0.1 for the sharpening radius, whole units otherwise. */
        public float step() {
            return this == EXPOSURE ? 0.01f : this == SHARPEN_RADIUS ? 0.1f : 1f;
        }

        public boolean bipolar() {
            return min < 0;
        }

        /** {@code v} clamped to the range and rounded to the step. */
        public float snap(float v) {
            if (Float.isNaN(v)) return def;
            float s = step();
            float r = Math.round(Math.max(min, Math.min(max, v)) / s) * s;
            return Math.max(min, Math.min(max, r));
        }

        @Nullable
        static Param byKey(String key) {
            for (Param p : values()) if (p.key.equals(key)) return p;
            return null;
        }

        /** The HSL mixer slider of {@code band} 0..7 (red, orange, yellow, green, aqua, blue, purple, magenta). */
        public static Param hue(int band) { return values()[HUE_0.ordinal() + band]; }

        public static Param sat(int band) { return values()[SAT_0.ordinal() + band]; }

        public static Param lum(int band) { return values()[LUM_0.ordinal() + band]; }

        public static Param bw(int band) { return values()[BW_0.ordinal() + band]; }

        /** A wheel's hue / saturation / luminance: wheel 0 shadows, 1 midtones, 2 highlights, 3 global. */
        public static Param grade(int wheel, int part) { return values()[GRADE_SH_HUE.ordinal() + wheel * 3 + part]; }
    }

    /** Point curves (Кривая): master (all channels), red, green, blue; each x0, y0, x1, y1... in 0..1 by x, null = straight. */
    public static final int CURVE_MASTER = 0, CURVE_RED = 1, CURVE_GREEN = 2, CURVE_BLUE = 3;
    public final float[][] curves = new float[4][];

    /** Crop aspect: 0 free, -1 the photo's own (turned) aspect, > 0 width / height. */
    public static final float ASPECT_FREE = 0f, ASPECT_ORIGINAL = -1f;
    public static final float MAX_STRAIGHTEN = 45f;
    static final int JSON_VERSION = 1;

    private final float[] values = new float[Param.values().length];
    public int quarterTurns;
    public boolean flipH;
    /** Degrees, -45..45, clockwise on screen. */
    public float straighten;
    public float cropL = 0f, cropT = 0f, cropR = 1f, cropB = 1f;
    public float aspect = ASPECT_FREE;

    public EditState() {
        for (Param p : Param.values()) values[p.ordinal()] = p.def;
    }

    public float get(Param p) {
        return values[p.ordinal()];
    }

    public void set(Param p, float v) {
        values[p.ordinal()] = p.snap(v);
    }

    public EditState copy() {
        EditState s = new EditState();
        System.arraycopy(values, 0, s.values, 0, values.length);
        for (int i = 0; i < 4; i++) s.curves[i] = curves[i] != null ? curves[i].clone() : null;
        s.copyGeometry(this);
        return s;
    }

    /** Takes the geometry (turns, flip, straighten, crop, aspect) of {@code o}. */
    public void copyGeometry(EditState o) {
        quarterTurns = o.quarterTurns;
        flipH = o.flipH;
        straighten = o.straighten;
        cropL = o.cropL;
        cropT = o.cropT;
        cropR = o.cropR;
        cropB = o.cropB;
        aspect = o.aspect;
    }

    /** A state with the default tone and this one's geometry: the long-press «before» view keeps the frame. */
    public EditState withNeutralTone() {
        EditState s = new EditState();
        s.copyGeometry(this);
        return s;
    }

    public void resetTone() {
        for (Param p : Param.values()) values[p.ordinal()] = p.def;
        for (int i = 0; i < 4; i++) curves[i] = null;
    }

    /** Takes every setting but the geometry from {@code o} (paste settings, presets). */
    public void copyTone(EditState o) {
        System.arraycopy(o.values, 0, values, 0, values.length);
        for (int i = 0; i < 4; i++) curves[i] = o.curves[i] != null ? o.curves[i].clone() : null;
    }

    public boolean anyCurve() {
        for (float[] c : curves) if (c != null) return true;
        return false;
    }

    public boolean anyGrade() {
        for (int w = 0; w < 4; w++) if (get(Param.grade(w, 1)) != 0f || get(Param.grade(w, 2)) != 0f) return true;
        return false;
    }

    public boolean mono() {
        return get(Param.MONO) >= 0.5f;
    }

    public void resetGeometry() {
        quarterTurns = 0;
        flipH = false;
        straighten = 0f;
        cropL = cropT = 0f;
        cropR = cropB = 1f;
        aspect = ASPECT_FREE;
    }

    public boolean isDefaultTone() {
        for (Param p : Param.values()) if (values[p.ordinal()] != p.def) return false;
        return !anyCurve();
    }

    public boolean isDefaultGeometry() {
        return quarterTurns == 0 && !flipH && straighten == 0f && cropL == 0f && cropT == 0f && cropR == 1f && cropB == 1f;
    }

    public boolean isDefault() {
        return isDefaultTone() && isDefaultGeometry();
    }

    public boolean anyHsl() {
        for (int i = Param.HUE_0.ordinal(); i <= Param.LUM_7.ordinal(); i++) if (values[i] != 0f) return true;
        return false;
    }

    // ---------------------------------------------------------------- JSON

    /** {"v":1,"p":{key:value...},"g":{"q":turns,"f":flip,"a":angle,"c":[l,t,r,b],"r":aspect}}; defaults left out. */
    @NonNull
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", JSON_VERSION);
            JSONObject p = new JSONObject();
            for (Param q : Param.values()) {
                float v = values[q.ordinal()];
                if (v != q.def) p.put(q.key, (double) v);
            }
            if (p.length() > 0) o.put("p", p);
            if (anyCurve()) {
                JSONArray cs = new JSONArray();
                for (float[] c : curves) {
                    JSONArray a = new JSONArray();
                    if (c != null) for (float v : c) a.put((double) v);
                    cs.put(a);
                }
                o.put("k", cs);
            }
            if (!isDefaultGeometry() || aspect != ASPECT_FREE) {
                JSONObject g = new JSONObject();
                if (quarterTurns != 0) g.put("q", quarterTurns);
                if (flipH) g.put("f", true);
                if (straighten != 0f) g.put("a", (double) straighten);
                if (cropL != 0f || cropT != 0f || cropR != 1f || cropB != 1f) {
                    JSONArray c = new JSONArray();
                    c.put((double) cropL).put((double) cropT).put((double) cropR).put((double) cropB);
                    g.put("c", c);
                }
                if (aspect != ASPECT_FREE) g.put("r", (double) aspect);
                o.put("g", g);
            }
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The state of {@code json}; unknown keys are skipped, out-of-range values clamped; null for text that is not one. */
    @Nullable
    public static EditState fromJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("v", 0) != JSON_VERSION) return null;
            EditState s = new EditState();
            JSONObject p = o.optJSONObject("p");
            if (p != null) {
                JSONArray names = p.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    Param q = Param.byKey(names.getString(i));
                    if (q != null) s.set(q, (float) p.getDouble(q.key));
                }
            }
            JSONArray k = o.optJSONArray("k");
            for (int i = 0; k != null && i < Math.min(4, k.length()); i++) {
                JSONArray a = k.optJSONArray(i);
                if (a == null || a.length() < 4 || (a.length() & 1) != 0) continue;
                float[] c = new float[a.length()];
                for (int j = 0; j < c.length; j++) c[j] = clamp((float) a.getDouble(j), 0f, 1f);
                s.curves[i] = c;
            }
            JSONObject g = o.optJSONObject("g");
            if (g != null) {
                s.quarterTurns = ((g.optInt("q", 0) % 4) + 4) % 4;
                s.flipH = g.optBoolean("f", false);
                s.straighten = clamp((float) g.optDouble("a", 0), -MAX_STRAIGHTEN, MAX_STRAIGHTEN);
                JSONArray c = g.optJSONArray("c");
                if (c != null && c.length() == 4) {
                    float l = clamp((float) c.getDouble(0), 0f, 1f), t = clamp((float) c.getDouble(1), 0f, 1f);
                    float r = clamp((float) c.getDouble(2), 0f, 1f), b = clamp((float) c.getDouble(3), 0f, 1f);
                    if (r - l > 1e-4f && b - t > 1e-4f) {
                        s.cropL = l;
                        s.cropT = t;
                        s.cropR = r;
                        s.cropB = b;
                    }
                }
                float r = (float) g.optDouble("r", ASPECT_FREE);
                s.aspect = r > 0f || r == ASPECT_ORIGINAL ? r : ASPECT_FREE;
            }
            return s;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EditState)) return false;
        EditState o = (EditState) other;
        return Arrays.equals(values, o.values) && Arrays.deepEquals(curves, o.curves) && quarterTurns == o.quarterTurns && flipH == o.flipH && straighten == o.straighten
                && cropL == o.cropL && cropT == o.cropT && cropR == o.cropR && cropB == o.cropB && aspect == o.aspect;
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(values) * 31 + Arrays.deepHashCode(curves);
        h = 31 * h + quarterTurns;
        h = 31 * h + (flipH ? 1 : 0);
        h = 31 * h + Float.floatToIntBits(straighten);
        h = 31 * h + Float.floatToIntBits(cropL) + 7 * Float.floatToIntBits(cropT) + 11 * Float.floatToIntBits(cropR)
                + 13 * Float.floatToIntBits(cropB);
        return 31 * h + Float.floatToIntBits(aspect);
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "EditState%s", toJson());
    }
}
