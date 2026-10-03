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
 * header 128 B: magic 'NCH1', version 10, w, h, cfa, frameCount, white f32, black f32[4], flags u32
 *               (1 diagnostics, 2 merged DNG), baseIndex u32 (0), reserved
 * frame table:  frameCount x 32 B: role u32 (1 normal, 3 bracketed, 5 ultrashort), exposure f32 (ratio to base),
 *               iso u32, noiseSlope f32, noiseOffset f32, orderMs f32, flags u32, reserved u32
 * planes:       frameCount x w*h uint16 (sensor layout)
 * </pre>
 */
public final class LmcHybridBurst implements NiceTransport {
    static final int ROLE_NORMAL = 1, ROLE_BRACKETED = 3, ROLE_ULTRASHORT = 5;
    private static final long BASE_WINDOW_NS = 205_000_000L; // LMC: base candidates within 205 ms of the newest frame
    private static final int BASE_CANDIDATES = 4;
    private static final int WORKER_MAX_FRAMES = 48; // kHybridMaxFrames in vivo-nice-hybrid.h

    private final int width, height, cfa;
    private final float white;
    private final float[] black;
    private final boolean diagnostics;
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
        diagnostics = PreferenceKeys.isNiceDiagnosticsEnabled();
        if (p.quadCfa || cfa < 0 || cfa > 3 || PreferenceKeys.isRemosaicEnabled() || com.particlesdevs.photoncamera.util.Allocator.binning)
            throw new IOException("SCAM HDR: нужен обычный Bayer RAW, без Quad/Tetra, ремозаика и программного биннинга");
        if (black.length != 4) throw new IOException("SCAM HDR: нужны четыре уровня чёрного");
        if (width < 64 || height < 64 || (width & 1) != 0 || (height & 1) != 0 || (long) width * height > 16000000)
            throw new IOException("SCAM HDR: размер RAW до 16 МП");
        if (source.size() < 2 || source.size() > 64) throw new IOException("SCAM HDR: нужны 2–64 кадра");
        List<ImageFrame> normal = new ArrayList<>(), bracketed = new ArrayList<>(), shorts = new ArrayList<>();
        Set<Long> stamps = new HashSet<>();
        for (ImageFrame f : source) {
            String detail = "frame=" + f.number + " timestamp=" + f.timestamp + " ZSL=" + f.fromZsl;
            if (f.timestamp <= 0 || !stamps.add(f.timestamp)) throw new IOException("SCAM HDR: повторный или отсутствующий timestamp: " + detail);
            if (f.measuredIso <= 0 || f.measuredExposure <= 0) throw new IOException("SCAM HDR: нет измеренной экспозиции: " + detail);
            ImageFrame.CaptureRole role = f.getCaptureRole();
            if (role == null) throw new IOException("SCAM HDR: нет роли из совпавших метаданных RAW: " + detail);
            if (f.buffer == null || f.width != width || f.height != height || f.buffer.capacity() != (long) width * height * 2)
                throw new IOException("SCAM HDR: неполный RAW: " + detail);
            switch (role) {
                case NORMAL: normal.add(f); break;
                case LONG: bracketed.add(f); break;
                case SHORT: case EXTRA_SHORT: shorts.add(f); break;
                default: throw new IOException("SCAM HDR: неизвестная роль RAW: " + detail);
            }
        }
        if (normal.isEmpty()) throw new IOException("SCAM HDR: нет кадров обычной экспозиции");
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
        // one slot stays for the ultrashort frame and three for the bracketed ones.
        int normals = 1;
        for (ImageFrame f : normal) {
            if (f == base) continue;
            if (normals >= WORKER_MAX_FRAMES - 4) { Log.w("NICE_HDR", "hybrid: " + (normal.size() - normals) + " oldest N frames dropped, worker limit " + WORKER_MAX_FRAMES); break; }
            add(f, ROLE_NORMAL, ref, newest); normals++;
        }
        // Bracketed frames: only those really longer than the base.
        bracketed.sort(Comparator.comparingDouble(this::product));
        for (ImageFrame f : bracketed) {
            if (product(f) / ref < 1.5) { Log.w("NICE_HDR", "hybrid: bracketed frame " + f.number + " is not longer than the base, dropped"); continue; }
            add(f, ROLE_BRACKETED, ref, newest);
        }
        // Ultrashort: the short frame closest to base/8 (others are dropped: Bento merges one).
        if (!shorts.isEmpty()) {
            final double target = ref / PreferenceKeys.niceInternalValue("hybrid_bento_factor", 8f);
            shorts.sort(Comparator.comparingDouble(f -> Math.abs(Math.log(product(f) / target))));
            ImageFrame us = shorts.get(0);
            if (product(us) / ref < 0.75) add(us, ROLE_ULTRASHORT, ref, newest);
        }
        Log.i("NICE_HDR", "hybrid burst: frames=" + frames.size() + " base=" + base.number + " sharpness=" + base.sharpness
                + " normals=" + normal.size() + " bracketed=" + (frames.size() - normal.size() - (roles.contains(ROLE_ULTRASHORT) ? 1 : 0))
                + " ultrashort=" + roles.contains(ROLE_ULTRASHORT) + " noise=" + noiseSource + " base slope=" + noise.get(0)[0] + " offset=" + noise.get(0)[1]);
    }

    private void add(ImageFrame f, int role, double ref, long newest) throws IOException {
        double ratio = product(f) / ref;
        if (!(ratio > 1.0 / 512) || !(ratio < 512)) throw new IOException("SCAM HDR: экспозиция вне диапазона frame=" + f.number + " ratio=" + ratio);
        frames.add(f); roles.add(role); exposures.add((float) ratio); noise.add(noiseFor(f));
        orderMs.add((float) ((f.timestamp - newest) / 1e6));
    }

    private double product(ImageFrame f) { return (double) f.measuredExposure * f.measuredIso; }

    /** Per-frame noise model in normalised units (slope, offset): the frame's own Camera2 profile, or the selected settings profile. */
    private float[] noiseFor(ImageFrame frame) throws IOException {
        float slope = frame.noiseSlope, offset = frame.noiseOffset;
        String source = PreferenceKeys.getNiceNoiseSource();
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
        slope *= PreferenceKeys.niceInternalValue("noise_photon", 1f);
        offset *= PreferenceKeys.niceInternalValue("noise_readout", 1f);
        if (!Float.isFinite(slope) || slope <= 0 || !Float.isFinite(offset) || offset < 0)
            throw new IOException("SCAM HDR: некорректный профиль шума (" + noiseSource + ") для RAW frame=" + frame.number);
        return new float[]{slope, offset};
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int cfa() { return cfa; }
    @Override public boolean mergedDng() { return mergedDng; }
    @Override public boolean diagnostics() { return diagnostics; }

    private ByteBuffer header() {
        ByteBuffer h = ByteBuffer.allocate(128 + 32 * frames.size()).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x3143484e).putInt(10).putInt(width).putInt(height).putInt(cfa).putInt(frames.size()).putFloat(white);
        for (float b : black) h.putFloat(b);
        h.putInt((diagnostics ? 1 : 0) | (mergedDng ? 2 : 0)).putInt(0);
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
    /** Merges the frames in the worker; returns linear RGB float32 (w*h*3) in base-frame units, highlights above 1.0 from the ultrashort frame. */
    public static ByteBuffer process(Context context, List<ImageFrame> frames, Parameters p, boolean mergedDng) throws Exception {
        LmcHybridBurst burst = new LmcHybridBurst(frames, p);
        burst.mergedDng = mergedDng;
        if (burst.diagnostics) NiceDiagnostics.begin(context, p, burst.base, VivoNiceScene.fromReference(burst.base));
        return VivoNeuralClient.processNiceBurst(context, burst);
    }
}
