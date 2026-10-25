package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.RawCrop;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.PreferenceValue;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P277: a lab shot (ScamLab) merged and rendered again - side A with the settings the shot was taken with, side B with one of
 * them changed - by the shot's own worker and post pipeline: the worker gets the shot's burst (whole, or a fragment of it) and
 * the tuning of the settings in force, the post pipeline the shot's Parameters. While a side runs, the default preferences read
 * as the shot's snapshot plus the tried value (PreferenceValue.overlay), so every setting read through the settings manager -
 * the merge's tuning, the denoise, the tone, the colour, the sharpening - takes the side's value. A setting the burst already
 * fixed at the shot (frame counts, the noise model's multipliers, the output grid) changes nothing here: the result says so.
 * The scam_dev.txt lines read the same way (PreferenceKeys.labDevOverlay): the shot's lines (meta "dev"; a shot taken before
 * them: the file now) without the lines that set a compared setting over its row, with the side's own «Для разработчика» lines
 * ({@link #DEV} keys).
 * Side A of a shot and fragment is kept for the next setting (the same burst and settings give the same pixels).
 */
public final class ScamLabRun {
    private ScamLabRun() {}

    /** The longest side of a result kept for the screen. */
    static final int SCREEN_SIDE = 4096;
    /** A side's key "dev:&lt;line&gt;": a scam_dev.txt line of «Для разработчика» (its value: null no line, a Boolean, a number). */
    public static final String DEV = "dev:";
    private static final String HYBRID = "pref_scam_hybrid_";
    /** The «Движение» mode's own rows: pref_scam_hybrid_mm_&lt;key&gt; (MotionMode). */
    private static final String MOTION = "mm_";

    /** A lab shot: its folder and metadata. */
    public static final class Shot {
        public final File dir;
        public final JSONObject meta;
        public final int width, height, frames, grid, orient;
        /** The whole frame's statistics (null: none yet; a fragment run records them first). */
        @Nullable volatile LabFrame frame;
        /** The scam_dev.txt lines at the shot (null: a shot taken before they were kept). */
        @Nullable public final Map<String, Float> dev;
        /** The module profile the shot's settings are (per-module settings on at the shot; null: the common settings, or unknown). */
        @Nullable public final String module;
        /** The worker lines of the shot's own burst that replace a setting's value (a retry, a step left out): suffix -> line. */
        public final Map<String, String> burstForced;

        Shot(File dir, JSONObject meta, ScamLabBurst burst) {
            this.dir = dir;
            this.meta = meta;
            width = burst.width;
            height = burst.height;
            frames = burst.frames;
            grid = burst.grid;
            orient = meta.optInt("orient", 0);
            dev = devOf(meta.optJSONObject("dev"));
            final String m = meta.optString("module", "");
            module = m.isEmpty() ? null : m;
            burstForced = java.util.Collections.unmodifiableMap(burstLines(meta.optString("tuningBurst", "")));
            final LabFrame f = ScamLab.frame(dir);
            // a frame recorded before its input scale was: the shot's «Масштаб входа, EV»
            if (f != null && !(f.inScale > 0f)) f.inScale = (float) Math.pow(2.0, shotInputEv(this));
            frame = f;
        }

        public static Shot load(File dir) throws IOException {
            try {
                final JSONObject meta = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir, ScamLab.META).toPath()),
                        StandardCharsets.UTF_8));
                return new Shot(dir, meta, new ScamLabBurst(new File(dir, ScamLab.BURST), null, ""));
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
        }

        public long bytes() {
            return new File(dir, ScamLab.BURST).length();
        }

        /** The default fragment: the middle third of each side (a ninth of the frame). */
        public int[] middleFragment() {
            return ScamLabBurst.aligned(new int[]{width / 3, height / 3, width / 3, height / 3}, width, height);
        }

        /** The stored preferences when the shot was taken (read once). */
        @Nullable private Map<String, Object> prefs;

        /** The stored preferences when the shot was taken (a key unset there is absent). */
        public synchronized Map<String, Object> prefs() {
            if (prefs == null) {
                Map<String, Object> m;
                try {
                    // P319: a shot recorded before the Why rename: its keys under the new names
                    m = com.particlesdevs.photoncamera.settings.BrandMigration.whyMap(ScamLabParams.prefs(meta.getJSONObject("prefs")));
                } catch (Exception e) {
                    m = new HashMap<>();
                }
                prefs = java.util.Collections.unmodifiableMap(m);
            }
            return prefs;
        }

        /** The value of a preference when the shot was taken (null: unset). */
        @Nullable
        public Object pref(String key) {
            return prefs().get(key);
        }

        /** The shot was rendered with the OPPO tuned matrix (its Parameters' oppoTunedCcm: WhyTone.ccmSatComp acts on it). */
        public boolean oppoTunedCcm() {
            final JSONObject p = meta.optJSONObject("params");
            return p != null && p.optBoolean("oppoTunedCcm", false);
        }
    }

    @Nullable
    static Map<String, Float> devOf(@Nullable JSONObject o) {
        if (o == null) return null;
        final Map<String, Float> m = new HashMap<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            final String k = it.next();
            final double v = o.optDouble(k, Double.NaN);
            if (!Double.isNaN(v)) m.put(com.particlesdevs.photoncamera.settings.BrandMigration.why(k), (float) v); // P319: old names too
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * «Масштаб входа, EV» of the shot (its scam_dev line, else its setting, clamped as the setting is read): a float, as
     * WhyTone.value gives it, so side A's input scale is the frame's to the bit (its statistics are used as they are).
     */
    static float shotInputEv(Shot shot) {
        final Float dev = shot.dev == null ? null : shot.dev.get("hybrid_why_input_ev");
        if (dev != null) return dev;
        final Object v = shot.pref(HYBRID + "why_input_ev");
        return v == null ? 0f : (float) com.particlesdevs.photoncamera.settings.SettingsNumericRules.value(HYBRID + "why_input_ev", String.valueOf(v).trim(), 0);
    }

    /** The scam_dev.txt lines side A starts from: the shot's, or for a shot taken before they were kept the file's now. */
    public static Map<String, Float> devLines(Shot shot) {
        return shot.dev != null ? shot.dev : PreferenceKeys.scamDevLines();
    }

    /**
     * The scam_dev.txt lines that set the setting {@code key} over its row: "hybrid_&lt;k&gt;", the plain "&lt;k&gt;" a hybrid
     * shot reads too (PreferenceKeys.routeInternalValue), and for a «Движение» row pref_scam_hybrid_mm_&lt;k&gt; the line
     * "hybrid_&lt;k&gt;" (hybridValue reads it before the mode's value). None for other keys.
     */
    static List<String> lineNames(String key) {
        final List<String> out = new ArrayList<>();
        if (key == null || !key.startsWith(HYBRID)) return out;
        final String k = key.substring(HYBRID.length());
        out.add("hybrid_" + k);
        out.add(k);
        final String mm = MOTION;
        if (k.startsWith(mm)) out.add("hybrid_" + k.substring(mm.length()));
        return out;
    }

    /** The lines of {@code lines} that set one of {@code keys} over its row ("line value"). */
    public static List<String> overriding(java.util.Collection<String> keys, Map<String, Float> lines) {
        final java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (String k : keys) for (String n : lineNames(k)) {
            final Float v = lines.get(n);
            if (v != null) out.add(n + " " + com.particlesdevs.photoncamera.settings.ScamDevFile.number(v));
        }
        return new ArrayList<>(out);
    }

    /**
     * A side's scam_dev.txt lines: {@code base} without the lines of the {@code compared} settings (the side's value of each is
     * the one that applies), with the side's own {@link #DEV} lines (null: no line).
     */
    static Map<String, Float> sideDev(Map<String, Float> base, java.util.Collection<String> compared, Map<String, Object> side) {
        final java.util.TreeMap<String, Float> out = new java.util.TreeMap<>(base);
        for (String k : compared) for (String n : lineNames(k)) out.remove(n);
        for (Map.Entry<String, Object> e : side.entrySet()) {
            if (!e.getKey().startsWith(DEV)) continue;
            final String n = e.getKey().substring(DEV.length());
            final Float v = devNumber(e.getValue());
            if (v == null) out.remove(n); else out.put(n, v);
        }
        return out;
    }

    /** A {@link #DEV} value as its line's number (null: no line). */
    @Nullable
    public static Float devNumber(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v ? 1f : 0f;
        try {
            final float f = Float.parseFloat(String.valueOf(v).trim().replace(',', '.'));
            return Float.isFinite(f) ? f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The lines of a shot's own burst tuning (meta "tuningBurst": a conservative retry, the steps a crashed worker died in, a
     * strict 2x grid) that carry a setting's value, by the setting's pref_scam_hybrid_ suffix: the worker reads them after the
     * settings' lines and keeps the last value, so such a setting changes nothing on that burst.
     */
    static Map<String, String> burstLines(@Nullable String tuningBurst) {
        final Map<String, String> out = new java.util.LinkedHashMap<>();
        if (tuningBurst == null) return out;
        for (String line : tuningBurst.split("\\r?\\n")) {
            final String[] p = line.trim().split("\\s+");
            if (p.length != 2) continue;
            final String suffix = PreferenceKeys.hybridTuningSuffix(p[0]);
            if (suffix != null) out.put(suffix, p[0] + " " + p[1]);
        }
        return out;
    }

    /** The burst's own line that fixes the setting {@code key} on this shot ({@link Shot#burstForced}), null: none. */
    @Nullable
    public static String burstLine(Shot shot, String key) {
        return burstLine(shot.burstForced, key);
    }

    @Nullable
    static String burstLine(Map<String, String> forced, String key) {
        if (key == null) return null;
        final String k = key.startsWith(DEV + "hybrid_") ? key.substring((DEV + "hybrid_").length())
                : key.startsWith(HYBRID) ? key.substring(HYBRID.length()) : null;
        if (k == null) return null;
        String line = forced.get(k);
        final String mm = MOTION;
        if (line == null && k.startsWith(mm)) line = forced.get(k.substring(mm.length()));
        return line;
    }

    /** A tuning file as the worker takes it: each name's last value (sorted), for whether two merges differ. */
    static String effective(String tuning) {
        final java.util.TreeMap<String, String> m = new java.util.TreeMap<>();
        for (String line : tuning.split("\\r?\\n")) {
            final String[] p = line.trim().split("\\s+");
            if (p.length >= 2) m.put(p[0], p[1]);
        }
        return m.toString();
    }

    /**
     * One side's picture: the photo, the merge's frame map (photo-oriented, small; null without one), its stage maps (sensor
     * cells, {@link ScamMergeStages#orient} the photo's turn; null: a burst of one frame or the worker wrote none) and its time.
     */
    public static final class Side {
        public final Bitmap photo;
        @Nullable public final Bitmap frameMap;
        @Nullable public final ScamMergeStages stages;
        public final long mergeMs, postMs;
        final String tuning;

        Side(Bitmap photo, Bitmap frameMap, ScamMergeStages stages, long mergeMs, long postMs, String tuning) {
            this.photo = photo;
            this.frameMap = frameMap;
            this.stages = stages;
            this.mergeMs = mergeMs;
            this.postMs = postMs;
            this.tuning = tuning;
        }
    }

    public static final class Result {
        public Side a, b;
        /** The merge ran again for B (the setting is a merge setting). */
        public boolean mergedAgain;
        /** A and B are the same pixels (the setting did nothing on this shot). */
        public boolean same;
        /** The shot's scam_dev.txt lines of the compared settings the lab left out on both sides ("line value"). */
        public List<String> devMasked = new ArrayList<>();
        /** The shot kept no scam_dev.txt lines (taken before): both sides read the file as it is now. */
        public boolean devNow;
    }

    public interface Progress { void stage(String text); }

    /** The last sides rendered (shot | fragment | key = value): turning one control renders only its side again. */
    private static final android.util.LruCache<String, Side> sides = new android.util.LruCache<>(3);

    /** Forgets the rendered sides (another fragment, a deleted shot). */
    public static synchronized void forget() {
        sides.evictAll();
    }

    /**
     * Side A with the settings {@code a} and side B with the settings {@code b} (key -> value, null: the key unset, its default),
     * both on the shot's other settings; a side rendered before (same shot, fragment and values) is reused. {@code crop}
     * {x, y, w, h} of the sensor frame, null: the whole frame. Runs on the caller's thread (not the main one); throws with a
     * message for the user when a shot is being processed or the merge fails.
     */
    public static synchronized Result run(Context context, Shot shot, @Nullable int[] crop, java.util.SortedMap<String, Object> a,
                                          java.util.SortedMap<String, Object> b, Progress progress) throws Exception {
        if (!ScamLab.LOCK.tryLock())
            throw new IOException(Lang.t("Идёт обработка снимка. Повторите, когда она закончится.", "A shot is being processed. Try again when it is done."));
        PreferenceKeys.beginShotProfile(true);
        try {
            final String fragment = crop == null ? "whole" : crop[0] + "," + crop[1] + "," + crop[2] + "," + crop[3];
            final Result r = new Result();
            // the scam_dev.txt lines of the settings compared (on either side) give way to each side's value
            final java.util.TreeSet<String> compared = new java.util.TreeSet<>(a.keySet());
            compared.addAll(b.keySet());
            final Map<String, Float> lines = devLines(shot);
            r.devMasked = overriding(compared, lines);
            r.devNow = shot.dev == null && !lines.isEmpty();
            r.a = cachedSide(context, shot, crop, a, sideDev(lines, compared, a), fragment, progress, "A");
            r.b = cachedSide(context, shot, crop, b, sideDev(lines, compared, b), fragment, progress, "B");
            r.mergedAgain = !r.a.tuning.equals(r.b.tuning);
            r.same = PostAb.md5(r.a.photo).equals(PostAb.md5(r.b.photo));
            Log.i("SCAM_LAB", "lab " + shot.dir.getName() + " " + fragment + " A=" + a + " B=" + b + ": merges differ "
                    + r.mergedAgain + ", same " + r.same + ", A " + r.a.mergeMs + "+" + r.a.postMs + " ms, B " + r.b.mergeMs + "+" + r.b.postMs + " ms");
            return r;
        } finally {
            PreferenceValue.overlay(null, null);
            PreferenceKeys.labDevOverlay(null);
            PreferenceKeys.endShotProfile();
            ScamLab.LOCK.unlock();
        }
    }

    private static Side cachedSide(Context context, Shot shot, @Nullable int[] crop, java.util.SortedMap<String, Object> overrides,
                                   Map<String, Float> dev, String fragment, Progress progress, String name) throws Exception {
        final String id = shot.dir.getAbsolutePath() + "|" + fragment + "|" + overrides + "|" + dev;
        final Side known = sides.get(id);
        if (known != null && !known.photo.isRecycled()) return known;
        final Map<String, Object> values = basePrefs(shot);
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            if (e.getKey().startsWith(DEV)) continue; // a scam_dev.txt line: in dev
            if (e.getValue() == null) values.remove(e.getKey()); else values.put(e.getKey(), e.getValue());
        }
        if (crop != null && shot.frame == null) wholeFrame(context, shot, progress);
        progress.stage(Lang.t("Склейка " + name + "…", "Merge " + name + "…"));
        final Side side = side(context, shot, crop, values, dev, ScameraApp.getSettingsManagerStatic().getDefaultPreferences(), progress, name,
                crop == null ? null : shot.frame, null);
        sides.put(id, side);
        return side;
    }

    /**
     * A shot taken before the whole frame's statistics were kept (no ScamLab.FRAME): the whole frame merged and processed once
     * as shot, recording them; a fragment then uses them like a newer shot's.
     */
    private static void wholeFrame(Context context, Shot shot, Progress progress) throws Exception {
        progress.stage(Lang.t("Статистика всего кадра (один раз для снимка)…", "Whole-frame statistics (once per shot)…"));
        final LabFrame record = new LabFrame();
        final Side whole = side(context, shot, null, basePrefs(shot), devLines(shot), ScameraApp.getSettingsManagerStatic().getDefaultPreferences(),
                progress, "A", null, record);
        whole.photo.recycle();
        if (record.usable()) {
            ScamLab.writeFrame(shot.dir, record);
            shot.frame = record;
        }
    }

    /** The shot's preferences as the lab renders them: never the diagnostics, the kept bursts or the lab's own switch. */
    static Map<String, Object> basePrefs(Shot shot) throws org.json.JSONException {
        final Map<String, Object> m = ScamLabParams.prefs(shot.meta.getJSONObject("prefs"));
        m.put("pref_scam_hybrid_diagnostics", false);
        m.put("pref_keep_raw_burst", false);
        m.put(ScamLab.PREF, false);
        return m;
    }

    /** One side: the merge and the post pipeline under {@code values} and the scam_dev.txt lines {@code dev}. */
    private static Side side(Context context, Shot shot, @Nullable int[] crop, Map<String, Object> values, Map<String, Float> dev,
                             SharedPreferences prefs, Progress progress, String name, @Nullable LabFrame frozen, @Nullable LabFrame record) throws Exception {
        PreferenceValue.overlay(prefs, values);
        PreferenceKeys.labDevOverlay(dev);
        ByteBuffer rgb = null;
        Parameters p = null;
        try {
            final ScamLabBurst burst = new ScamLabBurst(new File(shot.dir, ScamLab.BURST), crop, shot.meta.optString("tuningBurst", ""));
            // what the worker merges with: the tuning file (the burst's own lines last, the last value of a name wins) and the CRE
            // source, a job marker beside it (ScamNeuralClient)
            final String tuning = effective(burst.settingsTuning() + burst.burstTuning()) + " creSource " + PreferenceKeys.getScamCreSource();
            ScamNeuralClient.carryReport = null;
            ScamNeuralClient.lastStages = null;
            final long t0 = System.nanoTime();
            rgb = ScamNeuralClient.processScamBurst(context, burst);
            final long t1 = System.nanoTime();
            final Bitmap map = frameMap(ScamHybridBurst.lastEffectiveFrames, burst.outputWidth(), burst.outputHeight(), shot.orient);
            final ScamMergeStages stages = ScamNeuralClient.lastStages;
            ScamNeuralClient.lastStages = null;
            if (stages != null) stages.orient = shot.orient;
            progress.stage(Lang.t("Обработка " + name + "…", "Processing " + name + "…"));
            p = parameters(shot, crop, burst);
            ScamHybridBurst.lastNormals = shot.meta.optInt("normals", 1);
            p.scamRgb = rgb;
            p.scamRgbOwned = true;
            p.scamHdrRawScale = 1f;
            final PostPipeline pipeline = new PostPipeline();
            // P277: a fragment's exposure, tone and highlights from the whole frame's statistics (LabFrame)
            pipeline.frozen = frozen;
            pipeline.record = record;
            Bitmap photo;
            try {
                photo = pipeline.Run(null, p);
            } finally {
                pipeline.close();
            }
            final long t2 = System.nanoTime();
            final Bitmap screen = ScamLab.scaled(photo, SCREEN_SIDE);
            if (screen != photo) photo.recycle();
            return new Side(screen, map, stages, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, tuning);
        } finally {
            PreferenceValue.overlay(null, null);
            PreferenceKeys.labDevOverlay(null);
            // ScamRgb frees the worker RGB after its upload and leaves a small copy (ScamProcessor's rule)
            if (rgb != null && (p == null || p.scamRgb == rgb)) Allocator.free(rgb);
            ScamRgb.dropClipFlags();
            ScamHybridBurst.lastEffectiveFrames = null;
            ScamHybridBurst.lastMergedDng = null;
            ScamNeuralClient.lastStages = null;
        }
    }

    /** The shot's Parameters for {@code burst}: the whole frame as shot, or the fragment's size and lens shading. */
    static Parameters parameters(Shot shot, @Nullable int[] crop, ScamLabBurst burst) throws org.json.JSONException {
        final Parameters p = ScamLabParams.fromJson(shot.meta.getJSONObject("params"));
        if (crop == null) return p;
        final int ow = burst.outputWidth(), oh = burst.outputHeight();
        final Point full = p.rawSize;
        if (p.hybridFinalSize != null && full != null && full.x > 0) {
            final double s = (double) p.hybridFinalSize.x / full.x;
            p.hybridFinalSize = new Point(Math.max(2, (int) Math.round(ow * s)) & ~1, Math.max(2, (int) Math.round(oh * s)) & ~1);
        }
        p.labWholeSize = full != null ? new Point(full) : null; // P306: a 200 MP shot's fragment takes its 200 MP denoise
        p.rawSize = new Point(ow, oh);
        p.sensorPix = new android.graphics.Rect(0, 0, ow, oh);
        if (p.gainMap != null && p.mapSize != null)
            p.gainMap = RawCrop.cropGainMap(p.gainMap, p.mapSize.x, p.mapSize.y, shot.width, shot.height,
                    new RawCrop.Area(crop[0], crop[1], crop[2], crop[3]));
        p.alignmentSize = new Point(ow / Math.max(1, p.tile) + 1, oh / Math.max(1, p.tile) + 1);
        p.tilesX = ow / 800 + 1;
        return p;
    }

    /**
     * The merge's effective-frame map (uint8 per output pixel, 64 = the median coverage) as a picture, turned as the photo:
     * dark blue few frames, through green, to yellow at twice the median and more. At most 640 px a side.
     */
    @Nullable
    static Bitmap frameMap(@Nullable ByteBuffer eff, int w, int h, int orient) {
        if (eff == null || (long) w * h > eff.capacity()) return null;
        final int step = Math.max(1, (Math.max(w, h) + 639) / 640);
        final int mw = w / step, mh = h / step;
        final boolean swap = ScamLabOrient.swaps(orient);
        final int pw = swap ? mh : mw, ph = swap ? mw : mh;
        final int[] px = new int[pw * ph];
        for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
            final float[] uv = ScamLabOrient.toSensor(orient, (x + .5f) / pw, (y + .5f) / ph);
            final int su = Math.min(w - 1, (int) (uv[0] * w)), sv = Math.min(h - 1, (int) (uv[1] * h));
            px[y * pw + x] = colour((eff.get(sv * w + su) & 255) / 128f);
        }
        return Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888);
    }

    private static final int[] RAMP = {0xFF1B0C41, 0xFF2C4A8C, 0xFF21908C, 0xFF5DC863, 0xFFFDE725};

    static int colour(float t) {
        t = Math.max(0, Math.min(1, t)) * (RAMP.length - 1);
        final int i = Math.min(RAMP.length - 2, (int) t);
        final float f = t - i;
        final int a = RAMP[i], b = RAMP[i + 1];
        int out = 0xFF000000;
        for (int s = 0; s <= 16; s += 8) out |= Math.round(((a >> s) & 255) * (1 - f) + ((b >> s) & 255) * f) << s;
        return out;
    }

    /** |A - B| x {@code gain} per channel (the two photos are the same size). */
    public static Bitmap difference(Bitmap a, Bitmap b, float gain) {
        final int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight());
        final int[] ra = new int[w], rb = new int[w];
        final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++) {
            a.getPixels(ra, 0, w, 0, y, w, 1);
            b.getPixels(rb, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int o = 0xFF000000;
                for (int s = 0; s <= 16; s += 8)
                    o |= Math.min(255, Math.round(Math.abs(((ra[x] >> s) & 255) - ((rb[x] >> s) & 255)) * gain)) << s;
                ra[x] = o;
            }
            out.setPixels(ra, 0, w, 0, y, w, 1);
        }
        return out;
    }
}
