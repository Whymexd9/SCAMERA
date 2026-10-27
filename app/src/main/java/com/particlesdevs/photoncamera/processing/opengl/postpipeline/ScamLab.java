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
    /**
     * P359: an imported burst's folder holds no copy of it: SOURCE names the file (a kept RAW series in Download/SCAMERA); NOW
     * marks a shot the lab renders with the settings of now instead of the shot's (its whole-frame statistics: FRAME_NOW).
     */
    public static final String SOURCE = "source.txt", NOW = "settings-now", FRAME_NOW = "frame-now.json";
    /** A lab run and a shot's processing never overlap: one GL texture table, the worker's statics, one report. */
    public static final ReentrantLock LOCK = new ReentrantLock();

    private static volatile File shotDir;
    /** P359: the RAW series «Сохранять RAW-серию» keeps of this shot (not a lab shot): its metadata go beside it. */
    private static volatile File keptBurst;
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
        keptBurst = null;
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
        return (shotDir != null || keptBurst != null) && shotMeta;
    }

    /**
     * P359 (owner, 2026-10-27: «Дай возможность в лаборатории использовать старые бурсты с новой обработкой/пайплайном»):
     * ScamNeuralClient, a shot whose RAW series «Сохранять RAW-серию» keeps in Download/SCAMERA: the lab's metadata, photo and
     * whole-frame statistics go beside it (scam_burst-&lt;time&gt;.json / .jpg / .frame.json), so the lab can open it later.
     */
    static void keeping(File burst) {
        if (shotDir == null) keptBurst = burst;
    }

    /** A kept series' file beside it: its name without ".bin" / ".nch" + {@code suffix}. */
    public static File sidecar(File burst, String suffix) {
        final String n = burst.getName();
        final int dot = n.lastIndexOf('.');
        return new File(burst.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + suffix);
    }

    /** Where this shot's metadata, photo and statistics go: the lab folder's files, or a kept series' sidecars; null: neither. */
    @Nullable
    private static File target(String labName, String sideSuffix) {
        final File dir = shotDir, kept = keptBurst;
        if (dir != null) return new File(dir, labName);
        return kept == null ? null : sidecar(kept, sideSuffix);
    }

    /** The whole frame's statistics of a lab folder ({@link #FRAME}), null when the shot has none (taken before them). */
    @Nullable
    public static LabFrame frame(File dir) {
        return frameFile(new File(dir, FRAME));
    }

    /** The whole frame's statistics in {@code f}, null without them. */
    @Nullable
    static LabFrame frameFile(File f) {
        if (!f.isFile()) return null;
        try {
            return LabFrame.fromJson(new JSONObject(new String(LabFiles.read(f), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w("SCAM_LAB", "lab frame statistics not read: " + e);
            return null;
        }
    }

    static void writeFrame(@Nullable File file, LabFrame frame) {
        if (file == null || frame == null || !frame.usable()) return;
        try {
            write(file, frame.toJson().toString().getBytes(StandardCharsets.UTF_8));
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
        final File metaFile = target(META, ".json");
        if (metaFile == null || p == null) return;
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
            write(metaFile, meta.toString().getBytes(StandardCharsets.UTF_8));
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
        final File metaFile = target(META, ".json"), previewFile = target(PREVIEW, ".jpg"), frameFile = target(FRAME, ".frame.json");
        if (metaFile == null || img == null || !shotMeta) return;
        writeFrame(frameFile, record);
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
            final JSONObject meta = new JSONObject(new String(java.nio.file.Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8));
            meta.put("orient", code).put("photoWidth", img.getWidth()).put("photoHeight", img.getHeight());
            write(metaFile, meta.toString().getBytes(StandardCharsets.UTF_8));
            Log.i("SCAM_LAB", "photo over the sensor frame: code " + code);
        } catch (Exception | OutOfMemoryError e) {
            Log.w("SCAM_LAB", "lab orientation not found: " + e);
        }
        try {
            final Bitmap small = scaled(img, 1600);
            try (FileOutputStream o = new FileOutputStream(previewFile)) {
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
        final File dir = shotDir, kept = keptBurst;
        shotDir = null;
        keptBurst = null;
        if (dir == null) {
            // P359: a kept series the worker did not write takes its sidecars with it
            if (kept != null && !kept.isFile()) for (String suffix : new String[]{".json", ".jpg", ".frame.json"}) sidecar(kept, suffix).delete();
            return;
        }
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
        for (File d : dirs) if (burstOf(d).isFile() && new File(d, META).isFile()) out.add(d);
        return out;
    }

    /** P359: a lab folder's burst: its own, or the kept series an imported folder names. */
    public static File burstOf(File dir) {
        final File own = new File(dir, BURST);
        if (own.isFile()) return own;
        final File source = new File(dir, SOURCE);
        if (source.isFile()) try {
            final String path = new String(LabFiles.read(source), StandardCharsets.UTF_8).trim();
            if (!path.isEmpty()) return new File(path);
        } catch (IOException e) {
            Log.w("SCAM_LAB", "lab source not read: " + e);
        }
        return own;
    }

    /** P359: the lab renders this shot with the settings of now (true) or as shot (false). */
    public static boolean settingsNow(File dir) {
        return new File(dir, NOW).isFile();
    }

    public static void settingsNow(File dir, boolean now) {
        final File f = new File(dir, NOW);
        try {
            if (now) {
                if (!f.isFile()) write(f, new byte[0]);
            } else f.delete();
        } catch (IOException e) {
            Log.w("SCAM_LAB", "lab settings mode not stored: " + e);
        }
    }

    /** A lab folder and the files the lab made in it (nothing else, nothing outside Lab: an imported series stays). */
    public static boolean remove(File dir) {
        if (dir == null || !root().equals(dir.getParentFile())) return false;
        // P363: an earlier installation's files through the folder grant
        for (String name : new String[]{BURST, META, PREVIEW, FRAME, SOURCE, NOW, FRAME_NOW, "sandbox.json"}) LabFiles.delete(new File(dir, name));
        // P355: the JPEGs of B the lab saved there
        final File[] saved = dir.listFiles(f -> f.getName().startsWith("B_") && f.getName().endsWith(".jpg"));
        if (saved != null) for (File f : saved) LabFiles.delete(f);
        return dir.delete() || LabFiles.delete(dir);
    }

    // ───── P359: kept RAW series (Download/SCAMERA) opened in the lab ─────

    /** An NCH v11 / v12 burst file's header: {width, height, cfa, frames, grid, block}, null when it is none. */
    @Nullable
    static int[] header(File f) {
        if (f == null || !f.isFile() || f.length() < 128) return null;
        try (java.nio.channels.FileChannel r = LabFiles.channel(f)) {
            final byte[] b = new byte[128];
            LabFiles.readFully(r, 0, b);
            final java.nio.ByteBuffer h = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            final int version = h.getInt(4);
            if (h.getInt(0) != ScamLabBurst.MAGIC || (version != 11 && version != 12)) return null;
            final int w = h.getInt(8), hh = h.getInt(12), frames = h.getInt(20);
            if (w <= 0 || hh <= 0 || frames < 1 || frames > 64) return null;
            return new int[]{w, hh, h.getInt(16), frames, Math.max(1, h.getInt(52)), h.getInt(56)};
        } catch (IOException e) {
            return null;
        }
    }

    /** The kept RAW series in Download/SCAMERA not opened in the lab yet, newest first. */
    public static List<File> keptSeries() {
        final File[] files = com.particlesdevs.photoncamera.util.ScameraFiles.dir().listFiles(f -> f.isFile()
                && (f.getName().endsWith(".bin") || f.getName().endsWith(".nch")) && f.length() > 128);
        final List<File> out = new ArrayList<>();
        if (files == null) return out;
        final java.util.Set<String> opened = new java.util.HashSet<>();
        final File[] dirs = root().listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) if (new File(d, SOURCE).isFile()) opened.add(burstOf(d).getAbsolutePath());
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) if (!opened.contains(f.getAbsolutePath()) && header(f) != null) out.add(f);
        return out;
    }

    /** A kept series' metadata beside it (a series kept since P359), null for an older one. */
    @Nullable
    public static File keptMeta(File burst) {
        final File m = sidecar(burst, ".json");
        return m.isFile() ? m : null;
    }

    /**
     * Opens a kept series in the lab: a folder Lab/&lt;its time&gt;_imp naming it (no copy). Its metadata: its own sidecar, or
     * (a series kept before P359) the newest lab shot or sidecar of the same frame size, grid and CFA as a template - its
     * parameters with this burst's own white point (the grey world of its base frame), rendered with the settings of now.
     * Throws with a message for the user when there is no template.
     */
    public static File importSeries(File burst) throws IOException {
        final int[] h = header(burst);
        if (h == null) throw new IOException(com.particlesdevs.photoncamera.util.Lang.t("Не серия SHDR (NCH v11 / v12)", "Not an SHDR burst (NCH v11 / v12)"));
        JSONObject meta;
        boolean templated = false;
        try {
            final File own = keptMeta(burst);
            if (own != null) meta = new JSONObject(new String(LabFiles.read(own), StandardCharsets.UTF_8));
            else {
                meta = template(h, burst);
                if (meta == null) throw new IOException(com.particlesdevs.photoncamera.util.Lang.t(
                        "Серия сохранена без параметров снимка, и нет снимка-образца того же размера (" + h[0] + "×" + h[1]
                                + "): сделайте один снимок для лаборатории этим модулем и откройте серию снова.",
                        "The series was kept without its shot's parameters and there is no template shot of its size (" + h[0] + "×" + h[1]
                                + "): take one lab shot with this module and open the series again."));
                templated = true;
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        final File dir = newFolder(root(), new Date(burst.lastModified()));
        final File named = new File(dir.getParentFile(), dir.getName() + "_imp");
        if (!named.mkdirs()) throw new IOException("lab folder not made: " + named);
        try {
            if (templated) meta.put("imported", true);
            meta.put("source", burst.getName());
            write(new File(named, META), meta.toString().getBytes(StandardCharsets.UTF_8));
            write(new File(named, SOURCE), burst.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            for (String[] pair : new String[][]{{".jpg", PREVIEW}, {".frame.json", FRAME}}) {
                final File side = sidecar(burst, pair[0]);
                if (side.isFile()) write(new File(named, pair[1]), LabFiles.read(side));
            }
            // an older series has only the template's settings: the lab renders it with the settings of now
            if (templated) settingsNow(named, true);
        } catch (org.json.JSONException | IOException e) {
            remove(named);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        Log.i("SCAM_LAB", "series " + burst + " opened in the lab as " + named + (templated ? " (template parameters)" : ""));
        return named;
    }

    /** The metadata of the newest lab shot (or kept sidecar) of the same burst geometry, with {@code burst}'s white point. */
    @Nullable
    private static JSONObject template(int[] h, File burst) throws org.json.JSONException, IOException {
        final List<File[]> metas = new ArrayList<>(); // {metadata, its burst}
        for (File d : shots()) metas.add(new File[]{new File(d, META), burstOf(d)});
        final File[] side = com.particlesdevs.photoncamera.util.ScameraFiles.dir().listFiles(f -> f.getName().startsWith("scam_burst-")
                && f.getName().endsWith(".json") && !f.getName().endsWith(".frame.json"));
        if (side != null) {
            Arrays.sort(side, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File m : side) metas.add(new File[]{m, sidecar(m, ".bin")});
        }
        for (File[] m : metas) {
            final int[] other = header(m[1]);
            if (other == null || other[0] != h[0] || other[1] != h[1] || other[2] != h[2] || other[4] != h[4] || other[5] != h[5]) continue;
            final JSONObject meta;
            try {
                meta = new JSONObject(new String(LabFiles.read(m[0]), StandardCharsets.UTF_8));
            } catch (IOException unreadable) {
                continue; // an earlier installation's shot without the folder grant: the next template
            }
            final JSONObject params = meta.optJSONObject("params");
            if (params == null) continue;
            final float[] white = greyWorld(burst, h);
            if (white != null) params.put("whitePoint", new org.json.JSONArray().put(white[0]).put(white[1]).put(white[2]));
            // the base frame's own ISO and noise (its table entry) for the denoise, not the template's
            final float[] noise = baseNoise(burst);
            if (noise != null) {
                final org.json.JSONArray pairs = new org.json.JSONArray();
                for (int c = 0; c < 3; c++) pairs.put(new org.json.JSONArray().put((double) noise[1]).put((double) noise[2]));
                params.put("iso", Math.round(noise[0]));
                params.put("noiseModeler", new JSONObject().put("base", pairs).put("compute", pairs).put("analog", Math.round(noise[0]))
                        .put("iso", Math.round(noise[0])));
            }
            meta.put("template", m[0].getName().equals(META) ? m[0].getParentFile().getName() : m[0].getName());
            meta.remove("tuningBurst"); // the template's retry lines are not this burst's
            meta.remove("orient"); // the template's way up (ScamLabOrient) is not known to be this burst's
            return meta;
        }
        return null;
    }

    /** The base frame's {measured ISO, noise slope, noise offset} from the burst's frame table, null when unreadable. */
    @Nullable
    static float[] baseNoise(File burst) {
        try (java.nio.channels.FileChannel r = LabFiles.channel(burst)) {
            final byte[] e = new byte[32];
            LabFiles.readFully(r, 128, e);
            final java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(e).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            final int iso = b.getInt(8);
            final float slope = b.getFloat(12), offset = b.getFloat(16);
            if (iso <= 0 || !(slope > 0f) || !(offset >= 0f) || !Float.isFinite(slope) || !Float.isFinite(offset)) return null;
            return new float[]{iso, slope, offset};
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The white point (camera R / G, 1, B / G of a grey) of a burst's base frame by the grey world over its mid-tones (2 % - 90 %
     * of white over black, clipped cells out), null when it cannot be read.
     */
    @Nullable
    static float[] greyWorld(File burst, int[] h) {
        try (java.nio.channels.FileChannel r = LabFiles.channel(burst)) {
            final byte[] head = new byte[128 + 32 * h[3]];
            LabFiles.readFully(r, 0, head);
            final java.nio.ByteBuffer hb = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            final int version = hb.getInt(4), w = h[0], hh = h[1];
            final float white = hb.getFloat(24);
            final float[] black = {hb.getFloat(28), hb.getFloat(32), hb.getFloat(36), hb.getFloat(40)};
            final java.nio.ByteBuffer table = java.nio.ByteBuffer.wrap(head, 128, 32 * h[3]).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
            final long plane = ScamLabBurst.planeOffset(version, table, h[3], 0, w, hh);
            return greyWorld(r.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, plane, (long) w * hh * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN), w, hh, h[2], Math.max(1, h[5]), black, white);
        } catch (IOException | RuntimeException e) {
            Log.w("SCAM_LAB", "grey world of " + burst + ": " + e);
            return null;
        }
    }

    /**
     * The grey world of a RAW16 plane ({@code cfa} 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR of the colour cells, {@code block} sites a cell
     * side: 2 Quad, 4 Tetra), every 4th 2 x 2 cell each way.
     */
    @Nullable
    static float[] greyWorld(java.nio.ByteBuffer plane, int w, int h, int cfa, int block, float[] black, float white) {
        final int[][] colour = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        final int[] c = colour[Math.max(0, Math.min(3, cfa))];
        final double[] sum = new double[3];
        final long[] n = new long[3];
        final int cell = 2 * block, step = cell * 4;
        final float[] v = new float[4];
        for (int y = 0; y + cell <= h; y += step) {
            for (int x = 0; x + cell <= w; x += step) {
                boolean usable = true;
                for (int q = 0; q < 4 && usable; q++) {
                    final int px = x + (q & 1) * block, py = y + (q >> 1) * block;
                    final float s = ((plane.getShort((py * w + px) * 2) & 0xFFFF) - black[q]) / (white - black[q]);
                    if (!(s > 0.02f && s < 0.9f)) usable = false;
                    v[q] = s;
                }
                if (!usable) continue;
                for (int q = 0; q < 4; q++) {
                    sum[c[q]] += v[q];
                    n[c[q]]++;
                }
            }
        }
        if (n[0] < 64 || n[1] < 64 || n[2] < 64) return null;
        final double r = sum[0] / n[0], g = sum[1] / n[1], b = sum[2] / n[2];
        if (!(r > 0 && g > 0 && b > 0)) return null;
        return new float[]{(float) (r / g), 1f, (float) (b / g)};
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
