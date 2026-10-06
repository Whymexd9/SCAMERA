package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.Context;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;

/**
 * LMC hybrid burst transport (NCH v10): any number of frames, each with its role, exposure ratio to the
 * base frame, ISO and its own noise model; the base frame first. The worker merges them with the Sabre
 * kernel, the LMC rejection and frame weights, Bento (ultrashort) and Shasta (bracketed) rules.
 * <pre>
 * header 128 B: magic 'NCH1', version 11, w, h, cfa, frameCount, white f32, black f32[4], flags u32
 *               (1 diagnostics, 2 merged DNG, 4 clip flags), baseIndex u32 (0), grid u32 (1 = sensor grid, 2 = the Sabre 6.1 2x
 *               grid: RGB 2w x 2h, four sub-positions +-0.25 px per sensor pixel), colour block u32 (0 = unknown: the worker
 *               measures it, 1 plain Bayer, 2 Quad, 4 Tetra: a mosaic is merged from its own sites), reserved
 * frame table:  frameCount x 32 B: role u32 (1 normal, 3 bracketed, 5 ultrashort), exposure f32 (ratio to base),
 *               iso u32, noiseSlope f32, noiseOffset f32, orderMs f32, flags u32, reserved u32
 * planes:       frameCount x w*h uint16 (sensor layout)
 * </pre>
 * Result: RGB float32 on the output grid, then the optional trailers in this order: merged Bayer RAW (w*h uint16, flag 2),
 * effective-frame map (uint8 per output pixel, always from the hybrid), clip flags (uint8 per output pixel, flag 4).
 */
public final class LmcHybridBurst implements NiceTransport {
    static final int ROLE_NORMAL = 1, ROLE_BRACKETED = 3, ROLE_ULTRASHORT = 5;
    private static final long BASE_WINDOW_NS = 205_000_000L; // LMC: base candidates within 205 ms of the newest frame
    private static final int BASE_CANDIDATES = 4;
    private static final int WORKER_MAX_FRAMES = 48; // kHybridMaxFrames in vivo-nice-hybrid.h

    private final int width, height, cfa;
    /** RGB size the worker returns: the sensor grid or the Sabre 2x grid (pref_lmc_hybrid_output). */
    private final int outWidth, outHeight;
    /** Final JPEG size: the pipeline runs on the worker grid, the bitmap is resized at the very end (after sharpening). */
    private final int finalWidth, finalHeight;
    private final float white;
    private final float[] black;
    /**
     * P14: colour block of the stream measured on the first frame (MosaicBlockDetector): 1 plain Bayer, 2 Quad, 4 Tetra, 0 = no
     * clear model (the worker measures it). A mosaic is merged from its own sites on the sensor grid (header word 14).
     */
    private final int mosaicBlock;
    private final boolean diagnostics;
    /**
     * Ask the worker for the per-pixel clip flags (header flag 4): VivoNiceRgb's per-channel highlight recovery picks the
     * clip level per pixel from them (base white 1 or the ultrashort's k). Only while that recovery runs
     * (pref_lmc_hybrid_highlight_recovery &gt; 0); nice_dev.txt "hybrid_clip_flags 0" turns the trailer off.
     */
    private final boolean clipFlags;
    private boolean mergedDng;
    private final List<ImageFrame> frames = new ArrayList<>();
    private final List<Integer> roles = new ArrayList<>();
    private final List<Float> exposures = new ArrayList<>();
    private final List<float[]> noise = new ArrayList<>();
    private final List<Float> orderMs = new ArrayList<>();
    final ImageFrame base;
    private String noiseSource = "Camera2 SENSOR_NOISE_PROFILE";

    private LmcHybridBurst(List<ImageFrame> source, Parameters p) throws IOException {
        width = p.rawSize.x; height = p.rawSize.y; cfa = p.cfaPattern; white = p.whiteLevel; black = p.blackLevel.clone();
        diagnostics = PreferenceKeys.hybridSwitch("diagnostics", false);
        clipFlags = PreferenceKeys.hybridSwitch("clip_flags", true) && PreferenceKeys.hybridValue("highlight_recovery", 100f) > 0f;
        if (p.quadCfa || cfa < 0 || cfa > 3 ||  com.particlesdevs.photoncamera.util.Allocator.binning)
            throw new IOException("Hybrid: нужен обычный Bayer RAW, без Quad/Tetra, ремозаика и программного биннинга");
        if (black.length != 4) throw new IOException("Hybrid: нужны четыре уровня чёрного");
        if (width < 64 || height < 64 || (width & 1) != 0 || (height & 1) != 0 || (long) width * height > 16000000)
            throw new IOException("Hybrid: размер RAW до 16 МП");
        if (source.size() < 2 || source.size() > 64) throw new IOException("Hybrid: нужны 2–64 кадра");
        android.graphics.Point fin = PreferenceKeys.hybridFinalSize(width, height);
        com.particlesdevs.photoncamera.processing.MosaicBlockDetector.Result mosaic = null;
        for (ImageFrame f : source) {
            if (f.buffer == null || f.width != width || f.height != height || f.buffer.capacity() != (long) width * height * 2) continue;
            mosaic = com.particlesdevs.photoncamera.processing.MosaicBlockDetector.detect(f.buffer, width, height, width * 2,
                    (black[0] + black[1] + black[2] + black[3]) / 4f, white, 8);
            break;
        }
        mosaicBlock = mosaic == null || !mosaic.confident ? 0 : mosaic.block;
        if (mosaic != null) Log.i("NICE_HDR", "hybrid stream colour block: " + mosaic);
        // A mosaic's own sites already fill the sensor grid of the stream (the worker merges them on the 2x grid of its plain-Bayer
        // sub-frames): its output is the sensor grid.
        final boolean twoX = !"sensor".equals(PreferenceKeys.hybridOutputMode()) && mosaicBlock <= 1;
        // No memory gate (user's call): the 2x pipeline holds the 2w x 2h float RGB plus the GL working set; availMem is logged.
        Log.i("NICE_HDR", "hybrid output mode=" + PreferenceKeys.hybridOutputMode() + " availMem=" + (availableMemory() >> 20) + " MB twoX=" + twoX);
        outWidth = twoX ? 2 * width : width; outHeight = twoX ? 2 * height : height;
        finalWidth = fin.x; finalHeight = fin.y;
        if (twoX) Log.i("NICE_HDR", "hybrid output: Sabre 2x grid " + outWidth + "x" + outHeight + ", final " + finalWidth + "x" + finalHeight
                + " (resize after the pipeline, " + PreferenceKeys.hybridDownsamplerName() + ")");
        List<ImageFrame> normal = new ArrayList<>(), bracketed = new ArrayList<>(), shorts = new ArrayList<>();
        Set<Long> stamps = new HashSet<>();
        for (ImageFrame f : source) {
            String detail = "frame=" + f.number + " timestamp=" + f.timestamp + " ZSL=" + f.fromZsl;
            if (f.timestamp <= 0 || !stamps.add(f.timestamp)) throw new IOException("Hybrid: повторный или отсутствующий timestamp: " + detail);
            if (f.measuredIso <= 0 || f.measuredExposure <= 0) throw new IOException("Hybrid: нет измеренной экспозиции: " + detail);
            ImageFrame.CaptureRole role = f.getCaptureRole();
            if (role == null) throw new IOException("Hybrid: нет роли из совпавших метаданных RAW: " + detail);
            if (f.buffer == null || f.width != width || f.height != height || f.buffer.capacity() != (long) width * height * 2)
                throw new IOException("Hybrid: неполный RAW: " + detail);
            // RawPayloadCheck: a frame whose RAW is not plain 16-bit never reaches the merge; the Bento / Shasta extras are
            // dropped like a lost buffer, an N frame stops the shot instead of saving garbage.
            if (f.rawPayloadError != null) {
                if (role == ImageFrame.CaptureRole.NORMAL)
                    throw new IOException("Hybrid: RAW-кадр не в 16-битном формате, снимок не сохранён: " + detail + " (" + f.rawPayloadError + ")");
                Log.w("NICE_HDR", "hybrid: " + role + " frame dropped, " + f.rawPayloadError + ": " + detail);
                continue;
            }
            switch (role) {
                case NORMAL: normal.add(f); break;
                case LONG: bracketed.add(f); break;
                case SHORT: case EXTRA_SHORT: shorts.add(f); break;
                default: throw new IOException("Hybrid: неизвестная роль RAW: " + detail);
            }
        }
        if (normal.isEmpty()) throw new IOException("Hybrid: нет кадров обычной экспозиции");
        normal.sort(Comparator.comparingLong(f -> -f.timestamp)); // newest first
        // Base frame: the sharpest of the newest candidates within the LMC time window.
        long newest = normal.get(0).timestamp;
        ImageFrame best = normal.get(0); double bestScore = -1;
        for (int i = 0; i < Math.min(BASE_CANDIDATES, normal.size()); i++) {
            ImageFrame f = normal.get(i);
            if (newest - f.timestamp > BASE_WINDOW_NS) break;
            if (Float.isNaN(f.sharpness)) f.computeSharpness();
            double s = Float.isNaN(f.sharpness) ? 0 : f.sharpness;
            if (s > bestScore) { bestScore = s; best = f; }
        }
        base = best;
        final double ref = product(base);
        add(base, ROLE_NORMAL, ref, newest);
        // The worker holds at most WORKER_MAX_FRAMES frames (uniform arrays); the oldest N frames go first,
        // two slots stay for the ultrashort frames and five for the bracketed ones.
        int normals = 1;
        for (ImageFrame f : normal) {
            if (f == base) continue;
            if (normals >= WORKER_MAX_FRAMES - 7) { Log.w("NICE_HDR", "hybrid: " + (normal.size() - normals) + " oldest N frames dropped, worker limit " + WORKER_MAX_FRAMES); break; }
            add(f, ROLE_NORMAL, ref, newest); normals++;
        }
        // Bracketed frames: only those really longer than the base.
        bracketed.sort(Comparator.comparingDouble(this::product));
        for (ImageFrame f : bracketed) {
            if (product(f) / ref < 1.5) { Log.w("NICE_HDR", "hybrid: bracketed frame " + f.number + " is not longer than the base, dropped"); continue; }
            add(f, ROLE_BRACKETED, ref, newest);
        }
        // Ultrashort: the short frame closest to base/8 and, with bento_frames 2, one more at the same exposure (within x1.3);
        // others are dropped.
        int ultrashort = 0;
        if (!shorts.isEmpty()) {
            final double target = ref / com.particlesdevs.photoncamera.capture.HybridPlan.ultrashortFactor();
            shorts.sort(Comparator.comparingDouble(f -> Math.abs(Math.log(product(f) / target))));
            final double first = product(shorts.get(0));
            final int want = com.particlesdevs.photoncamera.capture.HybridPlan.bentoFrames();
            for (ImageFrame us : shorts) {
                if (ultrashort >= want) break;
                if (product(us) / ref >= 0.75 || Math.abs(Math.log(product(us) / first)) > Math.log(1.3)) continue;
                add(us, ROLE_ULTRASHORT, ref, newest);
                ultrashort++;
            }
        }
        Log.i("NICE_HDR", "hybrid burst: frames=" + frames.size() + " base=" + base.number + " sharpness=" + base.sharpness
                + " normals=" + normal.size() + " bracketed=" + (frames.size() - normal.size() - ultrashort)
                + " ultrashort=" + ultrashort + " noise=" + noiseSource + " base slope=" + noise.get(0)[0] + " offset=" + noise.get(0)[1]);
    }

    private void add(ImageFrame f, int role, double ref, long newest) throws IOException {
        double ratio = product(f) / ref;
        if (!(ratio > 1.0 / 512) || !(ratio < 512)) throw new IOException("Hybrid: экспозиция вне диапазона frame=" + f.number + " ratio=" + ratio);
        frames.add(f); roles.add(role); exposures.add((float) ratio); noise.add(noiseFor(f));
        orderMs.add((float) ((f.timestamp - newest) / 1e6));
    }

    private double product(ImageFrame f) { return (double) f.measuredExposure * f.measuredIso; }

    /** Per-frame noise model in normalised units (slope, offset): the frame's own Camera2 profile, or the selected settings profile. */
    private float[] noiseFor(ImageFrame frame) throws IOException {
        float slope = frame.noiseSlope, offset = frame.noiseOffset;
        String source = PreferenceKeys.hybridString("noise_source", "auto");
        com.particlesdevs.photoncamera.processing.render.NoiseModelProfile profile = "settings".equals(source)
                ? com.particlesdevs.photoncamera.processing.render.NoiseModelProfile.byId(PreferenceKeys.getNoiseModelProfileId()) : null;
        if (profile != null) {
            Integer maxAnalog = com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics == null ? null
                    : com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics.get(
                            android.hardware.camera2.CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
            android.util.Pair<Double, Double>[] model = profile.evaluate(frame.measuredIso, maxAnalog == null ? frame.measuredIso : maxAnalog);
            double s = 0, o = 0; for (android.util.Pair<Double, Double> c : model) { s += c.first; o += c.second; }
            slope = (float) (s / model.length); offset = (float) (o / model.length);
            noiseSource = "settings profile " + profile.id;
        }
        slope *= PreferenceKeys.hybridValue("noise_photon", 1f);
        offset *= PreferenceKeys.hybridValue("noise_readout", 1f);
        if (!Float.isFinite(slope) || slope <= 0 || !Float.isFinite(offset) || offset < 0)
            throw new IOException("Hybrid: некорректный профиль шума (" + noiseSource + ") для RAW frame=" + frame.number);
        return new float[]{slope, offset};
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int outputWidth() { return outWidth; }
    @Override public int outputHeight() { return outHeight; }
    /** Scale of the returned RGB against the sensor grid (1 = sensor size). */
    public float outputScale() { return (float) outWidth / width; }
    private static long availableMemory() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            if (am == null) return 0;
            am.getMemoryInfo(info);
            return info.lowMemory ? 0 : info.availMem;
        } catch (RuntimeException e) { return 0; }
    }
    @Override public int cfa() { return cfa; }
    @Override public boolean mergedDng() { return mergedDng; }
    @Override public boolean diagnostics() { return diagnostics; }
    @Override public boolean clipFlags() { return clipFlags; }

    private ByteBuffer header() {
        ByteBuffer h = ByteBuffer.allocate(128 + 32 * frames.size()).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x3143484e).putInt(11).putInt(width).putInt(height).putInt(cfa).putInt(frames.size()).putFloat(white);
        for (float b : black) h.putFloat(b);
        h.putInt((diagnostics ? 1 : 0) | (mergedDng ? 2 : 0) | (clipFlags ? 4 : 0)).putInt(0)
         .putInt(outWidth == width && outHeight == height ? 1 : 2).putInt(mosaicBlock).putInt(0).putInt(0);
        h.position(128);
        for (int i = 0; i < frames.size(); i++) {
            h.putInt(roles.get(i)).putFloat(exposures.get(i)).putInt(frames.get(i).measuredIso)
             .putFloat(noise.get(i)[0]).putFloat(noise.get(i)[1]).putFloat(orderMs.get(i)).putInt(0).putInt(0);
        }
        h.position(0);
        return h;
    }
    @Override public void write(File file) throws IOException {
        try (FileChannel out = new FileOutputStream(file).getChannel()) { write(out); }
    }
    @Override public void write(FileChannel out) throws IOException {
        ByteBuffer header = header();
        long position = 0;
        while (header.hasRemaining()) position += out.write(header, position);
        final long[] offsets = new long[frames.size()];
        for (int i = 0; i < frames.size(); i++) { offsets[i] = position; position += frames.get(i).buffer.capacity(); }
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        final IOException[] failure = {null};
        Thread[] workers = new Thread[Math.min(4, frames.size())];
        for (int t = 0; t < workers.length; t++) {
            workers[t] = new Thread(() -> {
                try {
                    for (int i = next.getAndIncrement(); i < frames.size(); i = next.getAndIncrement()) {
                        ByteBuffer raw = frames.get(i).buffer.duplicate(); raw.clear(); long at = offsets[i];
                        while (raw.hasRemaining()) at += out.write(raw, at);
                    }
                } catch (IOException e) { synchronized (failure) { failure[0] = e; } }
            }, "hybrid-write-" + t);
            workers[t].start();
        }
        for (Thread w : workers) try { w.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        if (failure[0] != null) throw failure[0];
    }

    /** True when the worker applied Bento in the last shot: the highlights are real data from the ultrashort frame. */
    public static volatile boolean lastBentoApplied;
    /** Bento factor k of the last shot (the ultrashort content saturates at k in base-frame units) and the share of the mask where the ultrashort itself was clipped. */
    public static volatile float lastBentoFactor = 1f, lastBentoUsClipped = 0f;
    /** Size of the RGB returned by the last hybrid merge (sensor size unless the Sabre 2x output is on). */
    public static volatile android.graphics.Point lastOutputSize;
    /** Final JPEG size of the last hybrid shot (the bitmap is resized to it after the whole pipeline). */
    public static volatile android.graphics.Point lastFinalSize;
    /** Merges the frames in the worker; returns linear RGB float32 (w*h*3) in base-frame units, highlights above 1.0 from the ultrashort frame. */
    public static ByteBuffer process(Context context, List<ImageFrame> frames, Parameters p, boolean mergedDng) throws Exception {
        LmcHybridBurst burst = new LmcHybridBurst(frames, p);
        burst.mergedDng = mergedDng;
        lastOutputSize = new android.graphics.Point(burst.outWidth, burst.outHeight);
        lastFinalSize = new android.graphics.Point(burst.finalWidth, burst.finalHeight);
        if (burst.diagnostics) {
            NiceDiagnostics.begin(context, p, burst.base, VivoNiceScene.fromReference(burst.base));
            NiceDiagnostics.frames(burst.frames, burst.base);
        }
        return VivoNeuralClient.processNiceBurst(context, burst);
    }
}
