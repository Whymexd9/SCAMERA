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
import java.util.HashMap;
import java.util.Map;

/**
 * P277: a lab shot (ScamLab) merged and rendered again - side A with the settings the shot was taken with, side B with one of
 * them changed - by the shot's own worker and post pipeline: the worker gets the shot's burst (whole, or a fragment of it) and
 * the tuning of the settings in force, the post pipeline the shot's Parameters. While a side runs, the default preferences read
 * as the shot's snapshot plus the tried value (PreferenceValue.overlay), so every setting read through the settings manager -
 * the merge's tuning, the denoise, the tone, the colour, the sharpening - takes the side's value. A setting the burst already
 * fixed at the shot (frame counts, the noise model's multipliers, the output grid) changes nothing here: the result says so.
 * Side A of a shot and fragment is kept for the next setting (the same burst and settings give the same pixels).
 */
public final class ScamLabRun {
    private ScamLabRun() {}

    /** The longest side of a result kept for the screen. */
    static final int SCREEN_SIDE = 4096;

    /** A lab shot: its folder and metadata. */
    public static final class Shot {
        public final File dir;
        public final JSONObject meta;
        public final int width, height, frames, grid, orient;
        /** The whole frame's statistics (null: none yet; a fragment run records them first). */
        @Nullable volatile LabFrame frame;

        Shot(File dir, JSONObject meta, ScamLabBurst burst) {
            this.dir = dir;
            this.meta = meta;
            width = burst.width;
            height = burst.height;
            frames = burst.frames;
            grid = burst.grid;
            orient = meta.optInt("orient", 0);
            frame = ScamLab.frame(dir);
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

        /** The value of a preference when the shot was taken (null: unset). */
        @Nullable
        public Object pref(String key) {
            try {
                return ScamLabParams.prefs(meta.getJSONObject("prefs")).get(key);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** One side's picture: the photo, the merge's frame map (photo-oriented, small; null without one) and its time. */
    public static final class Side {
        public final Bitmap photo;
        @Nullable public final Bitmap frameMap;
        public final long mergeMs, postMs;
        final String tuning;

        Side(Bitmap photo, Bitmap frameMap, long mergeMs, long postMs, String tuning) {
            this.photo = photo;
            this.frameMap = frameMap;
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
            r.a = cachedSide(context, shot, crop, a, fragment, progress, "A");
            r.b = cachedSide(context, shot, crop, b, fragment, progress, "B");
            r.mergedAgain = !r.a.tuning.equals(r.b.tuning);
            r.same = PostAb.md5(r.a.photo).equals(PostAb.md5(r.b.photo));
            Log.i("SCAM_LAB", "lab " + shot.dir.getName() + " " + fragment + " A=" + a + " B=" + b + ": merges differ "
                    + r.mergedAgain + ", same " + r.same + ", A " + r.a.mergeMs + "+" + r.a.postMs + " ms, B " + r.b.mergeMs + "+" + r.b.postMs + " ms");
            return r;
        } finally {
            PreferenceValue.overlay(null, null);
            PreferenceKeys.endShotProfile();
            ScamLab.LOCK.unlock();
        }
    }

    private static Side cachedSide(Context context, Shot shot, @Nullable int[] crop, java.util.SortedMap<String, Object> overrides,
                                   String fragment, Progress progress, String name) throws Exception {
        final String id = shot.dir.getAbsolutePath() + "|" + fragment + "|" + overrides;
        final Side known = sides.get(id);
        if (known != null && !known.photo.isRecycled()) return known;
        final Map<String, Object> values = basePrefs(shot);
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            if (e.getValue() == null) values.remove(e.getKey()); else values.put(e.getKey(), e.getValue());
        }
        if (crop != null && shot.frame == null) wholeFrame(context, shot, progress);
        progress.stage(Lang.t("Склейка " + name + "…", "Merge " + name + "…"));
        final Side side = side(context, shot, crop, values, ScameraApp.getSettingsManagerStatic().getDefaultPreferences(), progress, name,
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
        final Side whole = side(context, shot, null, basePrefs(shot), ScameraApp.getSettingsManagerStatic().getDefaultPreferences(), progress,
                "A", null, record);
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

    /** One side: the merge and the post pipeline under {@code values}. */
    private static Side side(Context context, Shot shot, @Nullable int[] crop, Map<String, Object> values, SharedPreferences prefs,
                             Progress progress, String name, @Nullable LabFrame frozen, @Nullable LabFrame record) throws Exception {
        PreferenceValue.overlay(prefs, values);
        ByteBuffer rgb = null;
        Parameters p = null;
        try {
            final ScamLabBurst burst = new ScamLabBurst(new File(shot.dir, ScamLab.BURST), crop, shot.meta.optString("tuningBurst", ""));
            final String tuning = burst.settingsTuning();
            ScamNeuralClient.carryReport = null;
            final long t0 = System.nanoTime();
            rgb = ScamNeuralClient.processScamBurst(context, burst);
            final long t1 = System.nanoTime();
            final Bitmap map = frameMap(ScamHybridBurst.lastEffectiveFrames, burst.outputWidth(), burst.outputHeight(), shot.orient);
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
            return new Side(screen, map, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, tuning);
        } finally {
            PreferenceValue.overlay(null, null);
            // ScamRgb frees the worker RGB after its upload and leaves a small copy (ScamProcessor's rule)
            if (rgb != null && (p == null || p.scamRgb == rgb)) Allocator.free(rgb);
            ScamRgb.dropClipFlags();
            ScamHybridBurst.lastEffectiveFrames = null;
            ScamHybridBurst.lastMergedDng = null;
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
