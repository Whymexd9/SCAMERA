package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * P277 (owner, 2026-10-19: «гайд как небольшое приложение ... чтобы в приложение можно было подгрузить например свою Рав серию и
 * посмотреть на ней как и что работает»; «отдельный тогл специально для этого раздела»; «Сохранять будем в
 * /storage/emulated/0/Download/SCAMERA/Lab и разделять по дате и времени по папкам»): «Сохранять снимки для лаборатории» keeps
 * each SHDR shot as a folder Download/SCAMERA/Lab/yyyy-MM-dd_HH-mm-ss with
 * <ul>
 *   <li>{@link #BURST}: the merge worker's input as it got it (the NCH transport; the merged-DNG and diagnostics flags cleared
 *       afterwards, so a replay returns only the RGB, its frame map and clip flags);</li>
 *   <li>{@link #META}: the post pipeline's Parameters right before the post pipeline ({@link ScamLabParams}), the worker tuning
 *       of the shot (the settings' part and the burst's own lines apart), every default preference, the scam_dev.txt lines in
 *       force ("dev"), the module profile of the preferences ("module", per-module settings only), the device and sizes;</li>
 *   <li>{@link #PREVIEW}: the photo of the shot, small, for the list.</li>
 * </ul>
 * The lab (ScamLabRun, «Гайд и лаборатория») merges and renders that burst again with one setting changed.
 */
public final class ScamLab {
    private ScamLab() {}

    public static final String PREF = "pref_scam_lab_keep";
    public static final String DIR = "Lab", BURST = "burst.nch", META = "meta.json", PREVIEW = "preview.jpg", FRAME = "frame.json";
    /** A lab run and a shot's processing never overlap: one GL texture table, the worker's statics, one report. */
    public static final ReentrantLock LOCK = new ReentrantLock();

    private static volatile File shotDir;
    private static volatile String shotTuning = "", shotOverride = "";
    private static volatile boolean shotMeta;
    /** The worker RGB's luma, small (the shot's sensor side), for the photo's orientation (ScamLabOrient). */
    private static volatile float[] shotThumb;
    private static volatile int thumbW, thumbH;
    static final int THUMB = 64;

    public static boolean wanted() {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getBoolean(PREF, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Download/SCAMERA/Lab. */
    public static File root() {
        return new File(com.particlesdevs.photoncamera.util.ScameraFiles.dir(), DIR);
    }

    static String folderName(Date when) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(when);
    }

    /** A new folder under {@code root} named after {@code when} ("_2", "_3" ... when two shots share a second). */
    static File newFolder(File root, Date when) {
        final String base = folderName(when);
        File dir = new File(root, base);
        for (int i = 2; dir.exists() && i < 100; i++) dir = new File(root, base + "_" + i);
        return dir;
    }

    /** ScamProcessor, before the merge of a shot: its lab folder when the switch is on (hybrid shots only). */
    public static void beginShot(boolean hybrid) {
        shotDir = null;
        shotMeta = false;
        shotTuning = shotOverride = "";
        shotThumb = null;
        if (!hybrid || !wanted()) return;
        final File dir = newFolder(root(), new Date());
        if (dir.mkdirs()) shotDir = dir;
        else Log.w("SCAM_LAB", "lab folder not made: " + dir);
    }

    /** ScamProcessor: this shot is a lab shot with its metadata written (its pipeline records the whole frame's statistics). */
    public static boolean recording() {
        return shotDir != null && shotMeta;
    }

    /** The whole frame's statistics of a lab folder ({@link #FRAME}), null when the shot has none (taken before them). */
    @Nullable
    public static LabFrame frame(File dir) {
        final File f = new File(dir, FRAME);
        if (!f.isFile()) return null;
        try {
            return LabFrame.fromJson(new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w("SCAM_LAB", "lab frame statistics not read: " + e);
            return null;
        }
    }

    static void writeFrame(File dir, LabFrame frame) {
        if (dir == null || frame == null || !frame.usable()) return;
        try {
            write(new File(dir, FRAME), frame.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception | OutOfMemoryError e) {
            Log.w("SCAM_LAB", "lab frame statistics not written: " + e);
        }
    }

    /** ScamNeuralClient: where the worker keeps this shot's burst (null: not a lab shot). */
    @Nullable
    static File burstTarget() {
        final File dir = shotDir;
        return dir == null ? null : new File(dir, BURST);
    }

    /** ScamNeuralClient: the tuning the worker got (a retry's replaces the first attempt's, with its burst). */
    static void recordTuning(String settings, String burst) {
        if (shotDir == null) return;
        shotTuning = settings == null ? "" : settings;
        shotOverride = burst == null ? "" : burst;
    }

    /** ScamProcessor, right before the post pipeline: the shot's metadata. */
    public static void snapshot(Parameters p, @Nullable String camera) {
        final File dir = shotDir;
        if (dir == null || p == null) return;
        try {
            final JSONObject meta = new JSONObject();
            meta.put("version", 1);
            meta.put("time", System.currentTimeMillis());
            meta.put("build", com.particlesdevs.photoncamera.BuildConfig.VERSION_CODE);
            meta.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            if (camera != null) meta.put("camera", camera);
            final android.graphics.Point out = ScamHybridBurst.lastOutputSize, fin = ScamHybridBurst.lastFinalSize;
            if (out != null) meta.put("outWidth", out.x).put("outHeight", out.y);
            if (fin != null) meta.put("finalWidth", fin.x).put("finalHeight", fin.y);
            meta.put("normals", ScamHybridBurst.lastNormals); // WhyStats reads it beside the frame map
            meta.put("tuning", shotTuning);
            meta.put("tuningBurst", shotOverride);
            meta.put("params", ScamLabParams.toJson(p));
            meta.put("prefs", ScamLabParams.prefs(com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic()
                    .getDefaultPreferences().getAll()));
            // the scam_dev.txt lines in force (side A of a lab run reads these, not the file of the day) and, with per-module
            // settings, the module profile the preferences above are (the lab's «Сохранить B» writes into it)
            final JSONObject dev = new JSONObject();
            for (java.util.Map.Entry<String, Float> e : com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevLines().entrySet())
                if (e.getValue() != null && Float.isFinite(e.getValue())) dev.put(e.getKey(), (double) e.getValue());
            meta.put("dev", dev);
            final String module = shotModule();
            if (module != null) meta.put("module", module);
            write(new File(dir, META), meta.toString().getBytes(StandardCharsets.UTF_8));
            shotMeta = true;
            if (p.scamRgb != null && p.rawSize != null) {
                final int w = p.rawSize.x, h = p.rawSize.y;
                thumbW = THUMB;
                thumbH = Math.max(1, Math.round(THUMB * (float) h / w));
                shotThumb = rgbLuma(p.scamRgb, w, h, ScamHybridBurst.lastRgbHalf, thumbW, thumbH);
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w("SCAM_LAB", "lab metadata not written: " + e);
        }
    }

    /** The module profile the main settings hold while per-module settings are on (null: off, the common settings). */
    @Nullable
    private static String shotModule() {
        try {
            if (!com.particlesdevs.photoncamera.settings.PreferenceKeys.isPerLensSettingsOn()) return null;
            final String id = com.particlesdevs.photoncamera.settings.PreferenceKeys.profiles().editing();
            return id == null || id.isEmpty() ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * ScamProcessor, right after the post pipeline (before the digital zoom and ISZ crops): the photo, at most 1600 px a side,
     * for the list and the lab's fragment, and how it lies over the sensor frame (meta "orient", ScamLabOrient).
     */
    public static void preview(Bitmap img, @Nullable LabFrame record) {
        final File dir = shotDir;
        if (dir == null || img == null || !shotMeta) return;
        writeFrame(dir, record);
        final float[] thumb = shotThumb;
        shotThumb = null;
        if (thumb != null) try {
            final int pw = THUMB, ph = Math.max(1, Math.round(THUMB * (float) img.getHeight() / img.getWidth()));
            final Bitmap small = Bitmap.createScaledBitmap(img, pw, ph, true);
            final int[] px = new int[pw * ph];
            small.getPixels(px, 0, pw, 0, 0, pw, ph);
            if (small != img) small.recycle();
            final float[] luma = new float[pw * ph];
            for (int i = 0; i < px.length; i++) luma[i] = (float) Math.log(1 + ((px[i] >> 16 & 255) * 2 + (px[i] >> 8 & 255) * 5 + (px[i] & 255)));
            final int code = ScamLabOrient.find(thumb, thumbW, thumbH, luma, pw, ph);
            final File metaFile = new File(dir, META);
            final JSONObject meta = new JSONObject(new String(java.nio.file.Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8));
            meta.put("orient", code).put("photoWidth", img.getWidth()).put("photoHeight", img.getHeight());
            write(metaFile, meta.toString().getBytes(StandardCharsets.UTF_8));
            Log.i("SCAM_LAB", "photo over the sensor frame: code " + code);
        } catch (Exception | OutOfMemoryError e) {
            Log.w("SCAM_LAB", "lab orientation not found: " + e);
        }
        try {
            final Bitmap small = scaled(img, 1600);
            try (FileOutputStream o = new FileOutputStream(new File(dir, PREVIEW))) {
                small.compress(Bitmap.CompressFormat.JPEG, 90, o);
            } finally {
                if (small != img) small.recycle();
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w("SCAM_LAB", "lab preview not written: " + e);
        }
    }

    /**
     * ScamProcessor, at the end of a shot: a complete folder (burst and metadata) keeps the burst's header without the merged
     * DNG and the diagnostics; an incomplete one (the merge failed, the switch was turned on mid-shot) is removed.
     */
    public static void endShot() {
        final File dir = shotDir;
        shotDir = null;
        if (dir == null) return;
        final File burst = new File(dir, BURST);
        if (shotMeta && burst.isFile() && clearReplayFlags(burst)) {
            Log.i("SCAM_LAB", "lab shot kept: " + dir + " (" + (burst.length() >> 20) + " MB)");
            return;
        }
        Log.w("SCAM_LAB", "lab shot incomplete, removed: " + dir);
        remove(dir);
    }

    /** Header word 11 without flags 1 (diagnostics) and 2 (merged DNG): a replay gets the RGB and its trailers only. */
    static boolean clearReplayFlags(File burst) {
        try (RandomAccessFile f = new RandomAccessFile(burst, "rw")) {
            if (f.length() < 128) return false;
            f.seek(0);
            if (Integer.reverseBytes(f.readInt()) != ScamLabBurst.MAGIC) return false;
            f.seek(44);
            final int flags = Integer.reverseBytes(f.readInt());
            f.seek(44);
            f.writeInt(Integer.reverseBytes(flags & ~3));
            return true;
        } catch (IOException e) {
            Log.w("SCAM_LAB", "lab burst header not updated: " + e);
            return false;
        }
    }

    /** The lab folders that hold a shot (burst + metadata), newest first. */
    public static List<File> shots() {
        final File[] dirs = root().listFiles(File::isDirectory);
        final List<File> out = new ArrayList<>();
        if (dirs == null) return out;
        Arrays.sort(dirs, (a, b) -> b.getName().compareTo(a.getName()));
        for (File d : dirs) if (new File(d, BURST).isFile() && new File(d, META).isFile()) out.add(d);
        return out;
    }

    /** A lab folder and the files the lab made in it (nothing else, nothing outside Lab). */
    public static boolean remove(File dir) {
        if (dir == null || !root().equals(dir.getParentFile())) return false;
        for (String name : new String[]{BURST, META, PREVIEW, FRAME}) new File(dir, name).delete();
        return dir.delete();
    }

    /** log(1 + luma) of an RGB (float32 or half, 3 a pixel, w x h) at tw x th points, each the mean of a 3 x 3 sample. */
    static float[] rgbLuma(java.nio.ByteBuffer rgb, int w, int h, boolean half, int tw, int th) {
        final float[] out = new float[tw * th];
        final int bytes = half ? 2 : 4;
        if ((long) w * h * 3 * bytes > rgb.capacity()) return null;
        final java.nio.ByteBuffer b = rgb.duplicate().order(java.nio.ByteOrder.nativeOrder());
        for (int ty = 0; ty < th; ty++) for (int tx = 0; tx < tw; tx++) {
            double sum = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                final int x = Math.max(0, Math.min(w - 1, (int) ((tx + .5f + dx / 3f) * w / tw)));
                final int y = Math.max(0, Math.min(h - 1, (int) ((ty + .5f + dy / 3f) * h / th)));
                final int at = (y * w + x) * 3 * bytes;
                float r, g, bl;
                if (half) {
                    r = android.util.Half.toFloat(b.getShort(at));
                    g = android.util.Half.toFloat(b.getShort(at + 2));
                    bl = android.util.Half.toFloat(b.getShort(at + 4));
                } else {
                    r = b.getFloat(at); g = b.getFloat(at + 4); bl = b.getFloat(at + 8);
                }
                final float l = .25f * r + .625f * g + .125f * bl;
                sum += Float.isFinite(l) ? Math.max(0, l) : 0;
            }
            out[ty * tw + tx] = (float) Math.log(1 + 255 * sum / 9);
        }
        return out;
    }

    static Bitmap scaled(Bitmap img, int side) {
        final int w = img.getWidth(), h = img.getHeight(), big = Math.max(w, h);
        if (big <= side) return img;
        final float s = (float) side / big;
        return Bitmap.createScaledBitmap(img, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    static void write(File file, byte[] bytes) throws IOException {
        final File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(bytes); }
        if (!tmp.renameTo(file)) { tmp.delete(); throw new IOException("rename " + tmp); }
    }
}
