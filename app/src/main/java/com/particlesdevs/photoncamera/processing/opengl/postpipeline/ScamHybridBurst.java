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
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SCAM Hybrid burst transport (NCH v10): any number of frames, each with its role, exposure ratio to the
 * base frame, ISO and its own noise model; the base frame first. The worker merges them with the Sabre
 * kernel, the SCAM rejection and frame weights, Bento (ultrashort) and Shasta (bracketed) rules.
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
public final class ScamHybridBurst implements ScamTransport {
    static final int ROLE_NORMAL = 1, ROLE_BRACKETED = 3, ROLE_ULTRASHORT = 5;
    private static final long BASE_WINDOW_NS = 205_000_000L; // SCAM: base candidates within 205 ms of the newest frame
    private static final int BASE_CANDIDATES = 4;
    // Frames one burst sends (the app's own cap: 41 N + 7 bracketed / ultrashort; the worker takes up to kHybridMaxFrames = 128,
    // scam-hybrid.h, and holds kHybridGpuFrames = 128 on the GPU).
    private static final int WORKER_MAX_FRAMES = 48;
    /** P27: N frames of the conservative retry after a failed merge. */
    private static final int CONSERVATIVE_NORMALS = 16;
    /** P27: worker tuning of the conservative retry (no tile-local alignment, Sabre 6.1 kernel, rim or chroma passes). */
    /**
     * P72: frames read in different layouts (Xiaomi 17 Ultra tele in in-sensor zoom: packed RAW10 and RAW14 frames alternate;
     * their field and colour block are not known to match) are not merged together: the layout most frames have stays (on a tie
     * the first frame's), the others are dropped and counted.
     */
    static void keepOneLayout(List<ImageFrame> frames) {
        java.util.Map<Integer, Integer> count = new java.util.LinkedHashMap<>();
        for (ImageFrame f : frames) count.merge(f.sourceFormat, 1, Integer::sum);
        if (count.size() <= 1) return;
        int keep = frames.get(0).sourceFormat;
        for (java.util.Map.Entry<Integer, Integer> e : count.entrySet()) if (e.getValue() > count.get(keep)) keep = e.getKey();
        final int kept = keep;
        frames.removeIf(f -> f.sourceFormat != kept);
        Log.w("SCAM_HDR", "hybrid: frames of " + count.size() + " RAW layouts " + count + " (format: frames): only 0x"
                + Integer.toHexString(kept) + " merged, " + frames.size() + " frames");
    }

    private static final String CONSERVATIVE_TUNING = "localAlign 0\nsabre61 0\nrimRatio 0\nchromaDiff 0\n";
    /**
     * P27 any resolution: largest input of the Sabre 2x grid (its 2w x 2h RGB float32 is 768 MB at 16 MP, today's largest 2x
     * output). Above it the hybrid merges on the sensor grid and the per-resolution guards below apply; at or below it nothing
     * changes.
     */
    static final long SABRE_2X_MAX_INPUT = 16_000_000L;
    /** P27 any resolution: worker memory that does not grow with N (output stage, RGB result transport), bytes per sensor pixel. */
    static final double FIXED_BYTES_PER_PIXEL = 40;
    /**
     * Worker memory of one more frame: the GPU strip windows of every storage slot (~300 RAW rows of the frame held at a time,
     * 3.8 KB per pixel column) and the F6 local-alignment field (CPU and GPU, ~0.15 B per pixel).
     */
    static final double STRIP_BYTES_PER_COLUMN = 3800, FIELD_BYTES_PER_PIXEL = 0.15;
    /** Share of the memory available before the merge (the burst is already in the shot arena then) the worker may take. */
    static final double MEMORY_SHARE = 0.6;
    /** N frames kept whatever the memory budget says: the base and one more (a failed merge still falls back, never fails). */
    static final int MIN_NORMALS = 2;

    private final int width, height, cfa;
    /** RGB size the worker returns: the sensor grid or the Sabre 2x grid (pref_scam_hybrid_output). */
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
     * Ask the worker for the per-pixel clip flags (header flag 4): ScamRgb's per-channel highlight recovery picks the
     * clip level per pixel from them (base white 1 or the ultrashort's k). Only while that recovery runs
     * (pref_scam_hybrid_highlight_recovery &gt; 0); scam_dev.txt "hybrid_clip_flags 0" turns the trailer off.
     */
    private final boolean clipFlags;
    private final boolean conservative;
    private boolean mergedDng;
    private final List<ImageFrame> frames = new ArrayList<>();
    private final List<Integer> roles = new ArrayList<>();
    private final List<Float> exposures = new ArrayList<>();
    private final List<float[]> noise = new ArrayList<>();
    private final List<Float> orderMs = new ArrayList<>();
    final ImageFrame base;
    private String noiseSource = "Camera2 SENSOR_NOISE_PROFILE";

    /**
     * P27: one frame never costs the shot. A frame without a usable RAW, role or exposure, or whose payload is not plain 16-bit,
     * is dropped with a log line; with no N frame the plain frame closest to N becomes the base; a frame that missed its plan
     * by up to 0.5 EV darker (0.05 EV brighter) than the base is merged as an N frame. Throws only when no frame is usable.
     * {@code conservative}: the retry after a failed merge (sensor grid, at most 16 N frames, the round-5 extras off).
     */
    private ScamHybridBurst(List<ImageFrame> source, Parameters p, boolean conservative) throws IOException {
        width = p.rawSize.x; height = p.rawSize.y; white = p.whiteLevel; black = p.blackLevel.clone();
        // P27 (H14): a direct Quad Bayer stream is marked cfaPattern -2 (Parameters.quadCfa); its colour order is the
        // sensor's (baseCfaPattern) and the hybrid measures the colour block itself.
        cfa = p.quadCfa && p.cfaPattern < 0 ? p.baseCfaPattern : p.cfaPattern;
        this.conservative = conservative;
        diagnostics = PreferenceKeys.hybridSwitch("diagnostics", false);
        clipFlags = PreferenceKeys.hybridSwitch("clip_flags", true) && PreferenceKeys.hybridValue("highlight_recovery", 100f) > 0f;
        // P27 (H14): p.quadCfa does not stop the hybrid: it measures the colour block of the stream itself (header word 14).
        if (cfa < 0 || cfa > 3 || com.particlesdevs.photoncamera.util.Allocator.binning)
            throw new IOException(Lang.t("Hybrid: нужен RAW с порядком CFA 2×2, без программного биннинга", "Hybrid: needs a RAW with a 2×2 CFA order, without software binning"));
        if (black.length != 4) throw new IOException(Lang.t("Hybrid: нужны четыре уровня чёрного", "Hybrid: needs four black levels"));
        if (width < 64 || height < 64 || (width & 1) != 0 || (height & 1) != 0)
            throw new IOException(Lang.t("Hybrid: размер RAW ", "Hybrid: RAW size ") + width + "x" + height
                    + Lang.t(" (нужны чётные стороны от 64)", " (needs even sides of at least 64)"));
        // P27 any resolution: no megapixel cap. Only the hard limits stop the hybrid (sensor-grid RGB above one 2 GB buffer, a side
        // above the GPU's limit); HdrxProcessor bins such a stream with RawBin before, so this is a defensive check.
        final int gpuMaxSide = com.particlesdevs.photoncamera.processing.opengl.GLLimits.get().maxSide();
        final String beyond = com.particlesdevs.photoncamera.processing.RawBin.limitExceeded(width, height, gpuMaxSide);
        if (beyond != null) throw new IOException("Hybrid: RAW " + width + "x" + height
                + Lang.t(" за пределами склейки (", " is beyond the merge limits (") + beyond + ")");
        if (source.isEmpty() || source.size() > 64) throw new IOException(Lang.t("Hybrid: нужны 1–64 кадра", "Hybrid: needs 1–64 frames"));
        android.graphics.Point fin = PreferenceKeys.hybridFinalSize(width, height);
        List<ImageFrame> usable = new ArrayList<>();
        Set<Long> stamps = new HashSet<>();
        for (ImageFrame f : source) {
            String problem = f == null ? "missing frame"
                    : f.timestamp <= 0 || !stamps.add(f.timestamp) ? "repeated or missing timestamp"
                    : f.measuredIso <= 0 || f.measuredExposure <= 0 ? "no measured exposure"
                    : f.getCaptureRole() == null ? "no role from matched metadata"
                    : f.buffer == null || f.width != width || f.height != height || f.buffer.capacity() != (long) width * height * 2 ? "incomplete RAW"
                    // RawPayloadCheck: a frame whose RAW is not plain 16-bit never reaches the merge.
                    : f.rawPayloadError;
            if (problem != null) {
                Log.w("SCAM_HDR", "hybrid: frame dropped (" + problem + "): " + (f == null ? "null" : "frame=" + f.number
                        + " timestamp=" + f.timestamp + " ZSL=" + f.fromZsl + " role=" + f.getCaptureRole()));
                continue;
            }
            usable.add(f);
        }
        if (usable.isEmpty()) throw new IOException(Lang.t("Hybrid: ни одного пригодного RAW-кадра (", "Hybrid: no usable RAW frame (") + source.size() + Lang.t(" получено)", " received)"));
        keepOneLayout(usable);
        // P35: the stream's stored block (or a declared Quad / Tetra sensor mode) without the detector; a binned burst is another
        // grid and is always measured.
        final int knownBlock = p.rawBinning > 1 ? 0 : com.particlesdevs.photoncamera.processing.MosaicBlockStore.blockForShot(
                declaredSensorModeBlock());
        if (knownBlock > 0) {
            mosaicBlock = knownBlock;
            Log.i("SCAM_HDR", "hybrid stream colour block: " + knownBlock + " (stored for "
                    + com.particlesdevs.photoncamera.processing.MosaicBlockStore.shotKey() + ", detector skipped)");
        } else {
            com.particlesdevs.photoncamera.processing.ShotTimeline.mark("mosaic_start");
            com.particlesdevs.photoncamera.processing.MosaicBlockDetector.Result mosaic =
                    com.particlesdevs.photoncamera.processing.MosaicBlockDetector.detect(usable.get(0).buffer, width, height, width * 2,
                            (black[0] + black[1] + black[2] + black[3]) / 4f, white, 8);
            com.particlesdevs.photoncamera.processing.ShotTimeline.mark("mosaic_done");
            mosaicBlock = mosaic == null || !mosaic.confident ? 0 : mosaic.block;
            if (mosaic != null) Log.i("SCAM_HDR", "hybrid stream colour block: " + mosaic);
            if (p.rawBinning <= 1) com.particlesdevs.photoncamera.processing.MosaicBlockStore.observeShot(mosaic, "hybrid shot");
        }
        // P56: a Quad (2x ISZ) or Tetra (4x ISZ, Realme GT8 Pro 2026-10-09) mosaic gets the Sabre 2x grid too, merged natively by the
        // worker (kHybMergeMosaic / Fast at g = 2b: the sites at +-0.25 px).
        final boolean outputMode2x = !conservative && !"sensor".equals(PreferenceKeys.hybridOutputMode());
        final boolean wants2x = outputMode2x && mosaicBlock <= 4;
        if (outputMode2x && !wants2x) Log.i("SCAM_HDR", "hybrid output: sensor grid " + width + "x" + height + " (Sabre 2x grid off for a block "
                + mosaicBlock + " mosaic stream: the native merge has no 2x grid for it)");
        // P27 any resolution: the 2x grid only where it fits (input up to 16 MP, 2w x 2h within the GPU's limit, 2x RGB within one
        // Java buffer); otherwise the sensor grid. Up to 16 MP on a GPU of 16384 (Adreno 750) this is today's choice exactly.
        final boolean twoX = wants2x && sabre2xFits(width, height, gpuMaxSide);
        if (wants2x && !twoX) Log.i("SCAM_HDR", "hybrid output: sensor grid " + width + "x" + height + " (Sabre 2x grid "
                + 2L * width + "x" + 2L * height + " needs an input up to 16 MP within GPU side " + gpuMaxSide + ")");
        // No memory gate (user's call): the 2x pipeline holds the 2w x 2h float RGB plus the GL working set; availMem is logged.
        Log.i("SCAM_HDR", "hybrid output mode=" + PreferenceKeys.hybridOutputMode() + " availMem=" + (availableMemory() >> 20) + " MB twoX=" + twoX
                + (conservative ? " (conservative retry)" : ""));
        outWidth = twoX ? 2 * width : width; outHeight = twoX ? 2 * height : height;
        finalWidth = fin.x; finalHeight = fin.y;
        if (twoX) Log.i("SCAM_HDR", "hybrid output: Sabre 2x grid " + outWidth + "x" + outHeight + ", final " + finalWidth + "x" + finalHeight
                + " (resize after the pipeline, " + PreferenceKeys.hybridDownsamplerName() + ")");
        List<ImageFrame> normal = new ArrayList<>(), bracketed = new ArrayList<>(), shorts = new ArrayList<>();
        for (ImageFrame f : usable) {
            switch (f.getCaptureRole()) {
                case NORMAL: normal.add(f); break;
                case LONG: bracketed.add(f); break;
                default: shorts.add(f); break;
            }
        }
        if (normal.isEmpty()) {
            // P27: no N frame arrived (all lost, or a SCAM HDR burst without its N): the plain frame closest to N is the base,
            // the shortest bracketed one, else the longest short one.
            ImageFrame promoted;
            if (!bracketed.isEmpty()) {
                promoted = Collections.min(bracketed, Comparator.comparingDouble(this::product));
                bracketed.remove(promoted);
            } else {
                promoted = Collections.max(shorts, Comparator.comparingDouble(this::product));
                shorts.remove(promoted);
            }
            normal.add(promoted);
            Log.w("SCAM_HDR", "hybrid: no N frame arrived, frame " + promoted.number + " (" + promoted.getCaptureRole() + ") is the base");
        }
        normal.sort(Comparator.comparingLong(f -> -f.timestamp)); // newest first
        // P27 any resolution: above 16 MP the worker's working set grows with the frame, so N is capped by the memory available
        // now (DESIGN 5); the oldest N frames go first, as at the worker limit. At 16 MP or less nothing changes.
        final int reservedExtras = com.particlesdevs.photoncamera.capture.HybridPlan.bentoFrames() + 5;
        int normalLimit = conservative ? CONSERVATIVE_NORMALS : WORKER_MAX_FRAMES - reservedExtras;
        String limitReason = "worker limit";
        final long available = (long) width * height > SABRE_2X_MAX_INPUT ? memoryAvailable() : -1;
        // RAW CA "every frame" (rawCa 2) keeps a corrected copy of every plain-Bayer frame in the worker.
        final boolean caCopies = PreferenceKeys.hybridValue("rawca_mode", 0f) >= 2f;
        final int budget = memoryFrameBudget(width, height, mosaicBlock, available, caCopies);
        if (budget != Integer.MAX_VALUE) {
            final int byMemory = Math.max(MIN_NORMALS, budget - Math.min(reservedExtras, bracketed.size() + shorts.size()));
            Log.i("SCAM_HDR", "hybrid memory budget " + width + "x" + height + ": " + budget + " frames (available " + (available >> 20)
                    + " MB, fixed " + (long) (FIXED_BYTES_PER_PIXEL * width * height) / (1 << 20) + " MB, "
                    + (long) perFrameBytes(width, height, mosaicBlock, caCopies) / (1 << 20) + " MB per frame"
                    + (caCopies ? ", RAW CA copies" : "") + ") -> at most "
                    + Math.min(byMemory, normalLimit) + " N");
            if (byMemory < normalLimit) { normalLimit = byMemory; limitReason = "memory budget"; }
        }
        // Base frame: the sharpest of the newest candidates within the SCAM time window.
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
        // reserved slots stay for the ultrashort frames and the bracketed ones.
        int normals = 1;
        for (ImageFrame f : normal) {
            if (f == base) continue;
            if (normals >= normalLimit) { Log.w("SCAM_HDR", "hybrid: " + (normal.size() - normals) + " oldest N frames dropped, " + limitReason + " " + normalLimit); break; }
            if (add(f, ROLE_NORMAL, ref, newest)) normals++;
        }
        // Bracketed frames: those really longer than the base; one that came back at the N exposure is an N frame (P27).
        bracketed.sort(Comparator.comparingDouble(this::product));
        int bracketedCount = 0;
        for (ImageFrame f : bracketed) {
            double r = product(f) / ref;
            if (r < 1.2) {
                if (normalBand(r) && normals < normalLimit && add(f, ROLE_NORMAL, ref, newest)) {
                    normals++;
                    Log.w("SCAM_HDR", "hybrid: bracketed frame " + f.number + " at x" + (float) r + " of the base, merged as N");
                } else Log.w("SCAM_HDR", "hybrid: bracketed frame " + f.number + " at x" + (float) r + " is not longer than the base, dropped");
                continue;
            }
            if (add(f, ROLE_BRACKETED, ref, newest)) bracketedCount++;
        }
        // Ultrashort: the short frame closest to base/8 and, with bento_frames 2, one more at the same exposure (within x1.3);
        // a short frame that came back at the N exposure is an N frame (P27); others are dropped.
        int ultrashort = 0;
        for (Iterator<ImageFrame> it = shorts.iterator(); it.hasNext();) {
            ImageFrame f = it.next();
            double r = product(f) / ref;
            if (r < 0.75) continue;
            it.remove();
            if (normalBand(r) && normals < normalLimit && add(f, ROLE_NORMAL, ref, newest)) {
                normals++;
                Log.w("SCAM_HDR", "hybrid: short frame " + f.number + " at x" + (float) r + " of the base, merged as N");
            } else Log.w("SCAM_HDR", "hybrid: short frame " + f.number + " at x" + (float) r + " dropped");
        }
        if (!shorts.isEmpty()) {
            final double target = ref / com.particlesdevs.photoncamera.capture.HybridPlan.ultrashortFactor();
            shorts.sort(Comparator.comparingDouble(f -> Math.abs(Math.log(product(f) / target))));
            final double first = product(shorts.get(0));
            final int want = com.particlesdevs.photoncamera.capture.HybridPlan.bentoFrames();
            for (ImageFrame us : shorts) {
                if (ultrashort >= want) break;
                if (product(us) / ref >= 0.75 || Math.abs(Math.log(product(us) / first)) > Math.log(1.3)) continue;
                if (add(us, ROLE_ULTRASHORT, ref, newest)) ultrashort++;
            }
        }
        Log.i("SCAM_HDR", "hybrid burst: frames=" + frames.size() + " base=" + base.number + " sharpness=" + base.sharpness
                + " normals=" + normals + " bracketed=" + bracketedCount
                + " ultrashort=" + ultrashort + " noise=" + noiseSource + " base slope=" + noise.get(0)[0] + " offset=" + noise.get(0)[1]);
    }

    /** P27: a frame this much darker (or barely brighter) than the base is merged as an N frame (HybridPlan.classify's band). */
    private static boolean normalBand(double ratio) {
        return ratio >= Math.pow(2, -0.5) && ratio <= 1.035;
    }

    /** Adds a frame to the burst; false (frame dropped, logged) when its exposure is outside the worker's x1/512..x512. */
    private boolean add(ImageFrame f, int role, double ref, long newest) throws IOException {
        double ratio = product(f) / ref;
        if (!(ratio > 1.0 / 512) || !(ratio < 512)) {
            if (f == base) throw new IOException(Lang.t("Hybrid: экспозиция базового кадра вне диапазона frame=", "Hybrid: base frame exposure out of range frame=") + f.number);
            Log.w("SCAM_HDR", "hybrid: frame " + f.number + " dropped, exposure x" + ratio + " of the base is outside the merge range");
            return false;
        }
        frames.add(f); roles.add(role); exposures.add((float) ratio); noise.add(noiseFor(f));
        orderMs.add((float) ((f.timestamp - newest) / 1e6));
        return true;
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
        if (!validNoise(slope, offset)) {
            // P27: a missing or invalid SENSOR_NOISE_PROFILE never stops the shot: another frame's profile scaled to this
            // frame's ISO, else the built-in sensor model at its ISO, at worst clamped (logged as the noise source).
            String fallback = null;
            for (ImageFrame g : frames.isEmpty() ? Collections.singletonList(frame) : frames) {
                if (g == frame || !validNoise(g.noiseSlope, g.noiseOffset) || g.measuredIso <= 0) continue;
                float k = (float) frame.measuredIso / g.measuredIso;
                slope = g.noiseSlope * k; offset = g.noiseOffset * k * k;
                fallback = "profile of frame " + g.number + " scaled to ISO " + frame.measuredIso;
                break;
            }
            if (fallback == null) {
                android.util.Pair<Double, Double>[] model = com.particlesdevs.photoncamera.processing.render.NoiseModelProfile.LGV50_IMX363_0
                        .evaluate(frame.measuredIso, frame.measuredIso);
                double s = 0, o = 0; for (android.util.Pair<Double, Double> c : model) { s += c.first; o += c.second; }
                slope = (float) (s / model.length); offset = (float) (o / model.length);
                fallback = "built-in model at ISO " + frame.measuredIso;
            }
            if (!Float.isFinite(slope) || slope <= 0) slope = 1e-6f;
            if (!Float.isFinite(offset) || offset < 0) offset = 0f;
            noiseSource = "no valid noise profile for frame " + frame.number + ": " + fallback;
            Log.w("SCAM_HDR", "hybrid: " + noiseSource);
        }
        if (frame.binnedSamples > 1) {
            // P27: a binned frame (RawBin) averages binnedSamples sensor samples: variance / binnedSamples.
            slope /= frame.binnedSamples;
            offset /= frame.binnedSamples;
        }
        slope *= PreferenceKeys.hybridValue("noise_photon", 1f);
        offset *= PreferenceKeys.hybridValue("noise_readout", 1f);
        if (!Float.isFinite(slope) || slope <= 0) slope = 1e-6f;
        if (!Float.isFinite(offset) || offset < 0) offset = 0f;
        return new float[]{slope, offset};
    }

    private static boolean validNoise(float slope, float offset) {
        return Float.isFinite(slope) && slope > 0 && Float.isFinite(offset) && offset >= 0;
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
    /** P27 any resolution: ActivityManager.MemoryInfo.availMem (also under lowMemory), -1 when unknown. */
    private static long memoryAvailable() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.availMem;
        } catch (RuntimeException e) { return -1; }
    }

    /**
     * P27 any resolution: the Sabre 2x grid fits a w x h stream: input up to {@link #SABRE_2X_MAX_INPUT} (today's largest 2x
     * output), 2w and 2h within the GPU's {@code gpuMaxSide} (GLLimits.maxSide()), and the 2x RGB float32 (48 B per sensor pixel)
     * within one Java buffer.
     */
    static boolean sabre2xFits(int w, int h, int gpuMaxSide) {
        final long px = (long) w * h;
        return px <= SABRE_2X_MAX_INPUT && 2L * Math.max(w, h) <= gpuMaxSide && 48L * px <= Integer.MAX_VALUE;
    }

    /**
     * P35: block of the active module's declared ISZ sensor mode (5 Quad -> 2, 7 Tetra -> 4), else 0. Only a fallback: a shot
     * carries its own module's declared block from the shutter (MosaicBlockStore.setShotKey).
     */
    private static int declaredSensorModeBlock() {
        try {
            return com.particlesdevs.photoncamera.processing.MosaicBlockStore.declaredBlock(
                    com.particlesdevs.photoncamera.settings.ModuleRegistry.sensorMode(com.particlesdevs.photoncamera.settings.ModuleRegistry.active()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Worker memory of one more frame of a w x h stream (see {@link #STRIP_BYTES_PER_COLUMN}); a mosaic adds its worker copy. */
    static double perFrameBytes(int w, int h, int mosaicBlock) {
        return perFrameBytes(w, h, mosaicBlock, false);
    }

    /** {@code caCopies}: RAW CA corrects every frame (rawCa 2): a plain-Bayer frame adds its corrected copy (2 B per pixel). */
    static double perFrameBytes(int w, int h, int mosaicBlock, boolean caCopies) {
        final double px = (double) w * h;
        double bytes = STRIP_BYTES_PER_COLUMN * w + FIELD_BYTES_PER_PIXEL * px;
        // A Quad / Tetra stream: the worker keeps each picked frame and its binned copy (2 B + 2/b^2 B per pixel).
        if (mosaicBlock >= 2) bytes += 2.0 * px * (1 + 1.0 / ((double) mosaicBlock * mosaicBlock));
        // A mosaic is corrected in place; a plain-Bayer frame gets a corrected copy.
        else if (caCopies) bytes += 2.0 * px;
        return bytes;
    }

    /**
     * P27 any resolution (DESIGN 5): frames of all roles the worker can hold for a w x h stream: {@link #MEMORY_SHARE} of the
     * memory {@code availableBytes} available before the merge, minus the N-independent cost ({@link #FIXED_BYTES_PER_PIXEL}),
     * over {@link #perFrameBytes}. Integer.MAX_VALUE (no cap) at 16 MP or less or when the available memory is unknown.
     */
    static int memoryFrameBudget(int w, int h, int mosaicBlock, long availableBytes) {
        return memoryFrameBudget(w, h, mosaicBlock, availableBytes, false);
    }

    static int memoryFrameBudget(int w, int h, int mosaicBlock, long availableBytes, boolean caCopies) {
        final long px = (long) w * h;
        if (px <= SABRE_2X_MAX_INPUT || availableBytes <= 0) return Integer.MAX_VALUE;
        final double free = MEMORY_SHARE * availableBytes - FIXED_BYTES_PER_PIXEL * px;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE - 1, Math.floor(free / perFrameBytes(w, h, mosaicBlock, caCopies))));
    }

    /**
     * P27 any resolution: the client's wait for the worker. 900 s up to 16 MP (unchanged); above it the merge time grows with the
     * pixels (CRE, alignment, GPU strips, per-pixel passes), so the wait grows in proportion, at most an hour.
     */
    public static long workerTimeoutSeconds(int w, int h) {
        final long px = (long) w * h;
        return px <= SABRE_2X_MAX_INPUT ? 900 : Math.min(3600, (long) Math.ceil(900.0 * px / SABRE_2X_MAX_INPUT));
    }

    @Override public int cfa() { return cfa; }
    @Override public boolean mergedDng() { return mergedDng; }
    @Override public boolean diagnostics() { return diagnostics; }
    @Override public boolean clipFlags() { return clipFlags; }
    /**
     * P27: extra hybrid_tuning.txt lines for this burst (the conservative retry), appended after the user's tuning; then the steps
     * a worker of this process died in (see {@link #avoidedSteps}).
     */
    public String tuningOverride() { return (conservative ? CONSERVATIVE_TUNING : "") + avoidedTuning(); }
    /** The worker leaves the CRE motion out (job marker cre-off) after a worker of this process died inside it. */
    public boolean creOff() { return avoidedSteps.contains(STEP_CRE); }

    private ByteBuffer header() { return header(null); }
    /** pages: NCH v12, frame i at byte pages[i] * 4096 of the shared memfd (the shot's arena); null: v11, planes follow the table. */
    private ByteBuffer header(long[] pages) {
        ByteBuffer h = ByteBuffer.allocate(128 + 32 * frames.size()).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x3143484e).putInt(pages != null ? 12 : 11).putInt(width).putInt(height).putInt(cfa).putInt(frames.size()).putFloat(white);
        for (float b : black) h.putFloat(b);
        h.putInt((diagnostics ? 1 : 0) | (mergedDng ? 2 : 0) | (clipFlags ? 4 : 0)).putInt(0)
         .putInt(outWidth == width && outHeight == height ? 1 : 2).putInt(mosaicBlock).putInt(0).putInt(0);
        h.position(128);
        for (int i = 0; i < frames.size(); i++) {
            h.putInt(roles.get(i)).putFloat(exposures.get(i)).putInt(frames.get(i).measuredIso)
             .putFloat(noise.get(i)[0]).putFloat(noise.get(i)[1]).putFloat(orderMs.get(i)).putInt(0).putInt(pages != null ? (int) pages[i] : 0);
        }
        h.position(0);
        return h;
    }
    /**
     * P27 any resolution, above 16 MP only: a burst that is not all in one shot arena (the post-shutter path, frames binned by
     * RawBin) is moved into a new arena frame by frame, each original freed right after its copy, instead of a second copy of the
     * whole burst in the transport (2.7 GB more at 50 MP x 27): the peak is one frame more. Same bytes, order and position.
     */
    private void moveIntoArena() {
        final long frameBytes = (long) width * height * 2;
        int arena = -1;
        boolean shared = true;
        for (ImageFrame f : frames) {
            long[] where = f.buffer == null ? null : com.particlesdevs.photoncamera.util.Allocator.arenaOf(f.buffer);
            if (where == null || (arena >= 0 && where[0] != arena) || (where[1] & 4095) != 0) { shared = false; break; }
            arena = (int) where[0];
        }
        if (shared || frameBytes > Integer.MAX_VALUE) return;
        final com.particlesdevs.photoncamera.util.ShotArena late = com.particlesdevs.photoncamera.util.ShotArena.create(frames.size(), frameBytes);
        if (late == null) { Log.w("SCAM_HDR", "hybrid burst: no shot arena for " + frames.size() + " x " + (frameBytes >> 20) + " MB"); return; }
        int moved = 0;
        try {
            for (ImageFrame f : frames) {
                final ByteBuffer old = f.buffer;
                if (old == null || old.capacity() != frameBytes) break;
                final ByteBuffer view = late.copy(old, 0, (int) frameBytes);
                if (view == null) break;
                view.order(old.order());
                view.position(old.position());
                f.buffer = view;
                com.particlesdevs.photoncamera.util.Allocator.free(old);
                moved++;
            }
        } finally {
            late.release();
        }
        Log.i("SCAM_HDR", "hybrid burst moved into a shot arena: " + moved + "/" + frames.size() + " frames of " + (frameBytes >> 20) + " MB");
    }

    /** P30: every frame of the burst is a view of one shot arena: header into its first page, its memfd to the worker. */
    @Override public android.os.ParcelFileDescriptor sharedBurst() {
        if ((long) width * height > SABRE_2X_MAX_INPUT) moveIntoArena();
        int arena = -1;
        final long[] pages = new long[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            ByteBuffer b = frames.get(i).buffer;
            long[] where = b == null ? null : com.particlesdevs.photoncamera.util.Allocator.arenaOf(b);
            if (where == null || (arena >= 0 && where[0] != arena) || (where[1] & 4095) != 0 || b.capacity() != (long) width * height * 2) {
                Log.i("SCAM_HDR", "hybrid burst copied into the transport: frame " + frames.get(i).number + " "
                        + (where == null ? "not in an arena" : "arena " + where[0] + " offset " + where[1] + " capacity " + b.capacity()));
                return null;
            }
            arena = (int) where[0];
            pages[i] = where[1] >> 12;
        }
        if (arena < 0) return null;
        ByteBuffer heap = header(pages);
        if (heap.capacity() > com.particlesdevs.photoncamera.util.ShotArena.HEADER) return null;
        ByteBuffer direct = ByteBuffer.allocateDirect(heap.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        direct.put(heap); direct.flip();
        if (!com.particlesdevs.photoncamera.util.Allocator.arenaWrite(arena, 0, direct)) return null;
        final int fd = com.particlesdevs.photoncamera.util.Allocator.arenaFd(arena);
        return fd < 0 ? null : android.os.ParcelFileDescriptor.adoptFd(fd);
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
    /** P77: noise model factor the worker measured and merged with (HYBRID NOISE CHECK "-> model xK"), 1 = HAL model kept. */
    public static volatile float lastNoiseFactor = 1f;
    /** P80: the last hybrid RGB is IEEE half floats (6 B a pixel, the worker's rgb-half output), not float32. */
    public static volatile boolean lastRgbHalf;
    /** P80: whether the worker returns the RGB as half floats; scam_dev.txt "hybrid_rgb_half 0" keeps float32. */
    static boolean halfTransport() { return PreferenceKeys.scamDevSwitch("hybrid_rgb_half", true); }
    /** Size of the RGB returned by the last hybrid merge (sensor size unless the Sabre 2x output is on). */
    public static volatile android.graphics.Point lastOutputSize;
    /** Final JPEG size of the last hybrid shot (the bitmap is resized to it after the whole pipeline). */
    public static volatile android.graphics.Point lastFinalSize;
    /** P27: the worker died at GPU init in this process (Mali, X200 Pro): further shots go straight to the CPU fallback. */
    private static volatile String gpuUnusable;
    /**
     * Optional worker steps a worker of this process died in (its crash report names the stage, or the last "HYBRID GPU: compile"
     * line before the exit): every later merge of the process leaves them out. vivo X200 Pro (Mali-G925, owner's log 2026-10-07):
     * 14 of 14 merges died compiling the outlier-test program kHybFlags; without the outlier tests the merge may run.
     */
    static final java.util.Set<String> avoidedSteps = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final String STEP_OUTLIERS = "outlier tests", STEP_RIM = "rim pass", STEP_BENTO = "Bento colour pass",
            STEP_CHROMA = "chroma pass", STEP_CRE = "CRE motion";
    /** Crash site that leaves no GPU merge (a required program, the context): the session goes to the CPU fallback. */
    static final String STEP_GPU = "GPU";

    static String avoidedTuning() {
        StringBuilder t = new StringBuilder();
        if (avoidedSteps.contains(STEP_OUTLIERS)) t.append("hotSigma 0\nhotBaseSigma 0\n");
        if (avoidedSteps.contains(STEP_RIM)) t.append("rimRatio 0\n");
        if (avoidedSteps.contains(STEP_BENTO)) t.append("bentoChromaSigma 0\n");
        if (avoidedSteps.contains(STEP_CHROMA)) t.append("chromaDiff 0\n");
        return t.toString();
    }

    /**
     * Where a worker that died on a signal was (null: it did not die on a signal, or nothing names the step): the stage of its crash
     * report ("WORKER CRASH: ... stage=X last=Y"), else the last program the GPU thread announced before the exit when the GPU
     * context never reported itself ready (off Adreno every program is announced before it compiles).
     */
    static String crashStage(String report) {
        if (report == null || !report.contains("WORKER EXIT: signal")) return null;
        int at = report.lastIndexOf("WORKER CRASH: signal");
        if (at >= 0) {
            int end = report.indexOf('\n', at);
            String line = end < 0 ? report.substring(at) : report.substring(at, end);
            int s = line.indexOf(" stage="), l = line.indexOf(" last=", s + 1);
            if (s < 0) return null;
            String stage = line.substring(s + 7, l > s ? l : line.length()).trim();
            // a thread without a stage of its own (a driver's compiler thread): the GPU program being compiled, nothing else
            if (stage.equals("-") && l > s) {
                String last = line.substring(l + 6).trim();
                stage = last.startsWith("GPU compile ") ? last : "-";
            }
            return stage.isEmpty() || stage.equals("-") ? null : stage;
        }
        if (java.util.regex.Pattern.compile("HYBRID GPU: .* frames=\\d+ limits").matcher(report).find()) return null; // context was ready
        int compile = report.lastIndexOf("HYBRID GPU: compile ");
        if (compile < 0) return null;
        int end = report.indexOf('\n', compile);
        String name = (end < 0 ? report.substring(compile + 20) : report.substring(compile + 20, end)).trim();
        return name.isEmpty() ? null : "GPU compile " + name;
    }

    /** The step to leave out after a crash in {@code stage}: one of the STEP_ constants, or null (not a step the app can drop). */
    static String stepOf(String stage) {
        if (stage == null) return null;
        if (stage.startsWith("CRE")) return STEP_CRE;
        if (stage.startsWith("GPU compile ")) {
            String program = stage.substring(12);
            int colon = program.indexOf(':');
            if (colon >= 0) program = program.substring(0, colon);
            program = program.trim();
            switch (program) {
                case "mean": case "flags": return STEP_OUTLIERS;
                case "rim": return STEP_RIM;
                case "bento": return STEP_BENTO;
                case "chroma": return STEP_CHROMA;
                default: return STEP_GPU; // mark, guide, cells, reject, dilate, merge: every merge needs them
            }
        }
        if (stage.startsWith("GPU context") || stage.startsWith("GPU init") || stage.startsWith("EGL")) return STEP_GPU;
        return null;
    }

    /** Learns from a failed worker: true when this crash named a step the next attempt can leave out (or the GPU is unusable). */
    private static boolean learnFromCrash(String report) {
        final String stage = crashStage(report);
        final String step = stepOf(stage);
        if (step == null) return false;
        if (STEP_GPU.equals(step)) {
            gpuUnusable = "worker died in " + stage;
            Log.w("SCAM_HDR", "Hybrid: worker died in " + stage + "; GPU merge off for this session");
            return true;
        }
        final boolean added = avoidedSteps.add(step);
        Log.w("SCAM_HDR", "Hybrid: worker died in " + stage + "; " + step + " off for this session" + (added ? "" : " (already off)"));
        return added;
    }
    /** P27: how the last hybrid shot was merged: "gpu", "gpu-conservative" or "cpu-single". */
    public static volatile String lastMergeTier = "gpu";

    /**
     * Merges the frames in the worker; returns linear RGB float32 (w*h*3) in base-frame units, highlights above 1.0 from the
     * ultrashort frame. P27: a failed merge never costs the photo. Tier 2 retries once conservatively (sensor grid, 16 N
     * frames, round-5 passes off), unless the worker died at GPU init or timed out; tier 3 develops the base frame alone on the
     * CPU (bilinear demosaic in the worker's units).
     */
    public static ByteBuffer process(Context context, List<ImageFrame> frames, Parameters p, boolean mergedDng) throws Exception {
        ScamHybridBurst burst = new ScamHybridBurst(frames, p, false);
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("ctor_done");
        burst.mergedDng = mergedDng;
        lastOutputSize = new android.graphics.Point(burst.outWidth, burst.outHeight);
        lastFinalSize = new android.graphics.Point(burst.finalWidth, burst.finalHeight);
        lastMergeTier = "gpu";
        if (burst.diagnostics) {
            ScamDiagnostics.begin(context, p, burst.base, ScamScene.fromReference(burst.base));
            ScamDiagnostics.frames(burst.frames, burst.base);
        }
        if (gpuUnusable != null) {
            Log.w("SCAM_HDR", "Hybrid fallback=cpu-single: GPU merge unusable in this session (" + gpuUnusable + ")");
            return cpuSingle(burst);
        }
        try {
            return ScamNeuralClient.processScamBurst(context, burst);
        } catch (Exception first) {
            final String report = ScamNeuralClient.lastJobReport;
            final String why = String.valueOf(first.getMessage());
            final boolean timeout = first instanceof ScamNeuralClient.WorkerTimeoutException;
            // Only a stream of 16 MP or less marks the GPU unusable for the session: a larger one runs memory-heavy CPU stages
            // before the GPU context, where the low-memory killer can stop the worker; it takes the conservative retry instead.
            final boolean gpuInitDeath = (long) burst.width * burst.height <= SABRE_2X_MAX_INPUT
                    && report != null && report.contains("WORKER EXIT: signal") && !report.contains("HYBRID GPU:");
            Log.e("SCAM_HDR", "Hybrid merge failed (" + why + ")" + (gpuInitDeath ? ", worker died before the GPU context" : ""));
            if (gpuInitDeath) {
                gpuUnusable = "worker died at GPU init: " + lastLine(report, "WORKER EXIT:");
                return cpuSingle(burst);
            }
            learnFromCrash(report);
            if (gpuUnusable != null) return cpuSingle(burst);
            if (!timeout) {
                // The conservative retry, once more while each crash names a further optional step to leave out (at most 3 retries).
                String previous = report, previousWhy = why;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        ScamHybridBurst safe = new ScamHybridBurst(frames, p, true);
                        lastOutputSize = new android.graphics.Point(safe.outWidth, safe.outHeight);
                        lastFinalSize = new android.graphics.Point(safe.finalWidth, safe.finalHeight);
                        ScamNeuralClient.carryReport = "previous attempt: " + previousWhy + "\n" + (previous == null ? "" : tail(previous, 4000));
                        ByteBuffer out = ScamNeuralClient.processScamBurst(context, safe);
                        lastMergeTier = "gpu-conservative";
                        Log.w("SCAM_HDR", "Hybrid fallback=gpu-conservative after: " + why
                                + (avoidedSteps.isEmpty() ? "" : " (left out: " + avoidedSteps + ")"));
                        return out;
                    } catch (Exception second) {
                        Log.e("SCAM_HDR", "Hybrid conservative merge failed too: " + second.getMessage());
                        previous = ScamNeuralClient.lastJobReport;
                        previousWhy = String.valueOf(second.getMessage());
                        if (second instanceof ScamNeuralClient.WorkerTimeoutException || !learnFromCrash(previous) || gpuUnusable != null) break;
                    } finally {
                        ScamNeuralClient.carryReport = null;
                    }
                }
            }
            return cpuSingle(burst);
        }
    }

    private static String lastLine(String report, String prefix) {
        int at = report.lastIndexOf(prefix);
        if (at < 0) return "";
        int end = report.indexOf('\n', at);
        return end < 0 ? report.substring(at) : report.substring(at, end);
    }

    private static String tail(String text, int chars) {
        return text.length() <= chars ? text : text.substring(text.length() - chars);
    }

    /**
     * P27 last tier: the base frame alone, demosaicked on the CPU into the worker's output (linear camera RGB float32 on the
     * sensor grid, base units: (v - black) / (white - black), as sampleRaw in scam-hybrid.h). Noisier than a merge, but a
     * photo. Bilinear on plain Bayer; on a Quad / Tetra stream each colour is averaged over its blocks within one block.
     */
    private static ByteBuffer cpuSingle(ScamHybridBurst burst) throws IOException {
        final long start = android.os.SystemClock.elapsedRealtime();
        final int w = burst.width, h = burst.height;
        final long bytes = 12L * w * h;
        if (bytes > Integer.MAX_VALUE) throw new IOException(Lang.t("Hybrid: одиночный кадр ", "Hybrid: the single frame ") + w + "x" + h
                + Lang.t(" больше 2 ГБ", " is above 2 GB"));
        ByteBuffer out = com.particlesdevs.photoncamera.util.Allocator.allocate((int) bytes);
        if (out == null) throw new IOException(Lang.t("Hybrid: недостаточно памяти для одиночного кадра", "Hybrid: not enough memory for the single frame"));
        out.order(ByteOrder.nativeOrder());
        final int block = Math.max(1, burst.mosaicBlock);
        final int[] channel = cfaChannels(burst.cfa);
        final float[] blackLevel = burst.black.clone();
        final float[] inv = new float[4];
        for (int i = 0; i < 4; i++) inv[i] = 1f / Math.max(1f, burst.white - blackLevel[i]);
        final ShortBuffer raw = burst.base.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        final int radius = block;
        final int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        final IOException[] failure = {null};
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int y0 = (int) ((long) h * t / threads), y1 = (int) ((long) h * (t + 1) / threads);
            workers[t] = new Thread(() -> {
                try {
                    final short[][] rows = new short[2 * radius + 1][w];
                    final float[] row = new float[w * 3];
                    final FloatBuffer view = out.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
                    final ShortBuffer src = raw.duplicate();
                    final double[] sum = new double[3], weight = new double[3];
                    for (int y = y0; y < y1; y++) {
                        for (int dy = -radius; dy <= radius; dy++) {
                            src.position(reflect(y + dy, h) * w);
                            src.get(rows[dy + radius], 0, w);
                        }
                        for (int x = 0; x < w; x++) {
                            sum[0] = sum[1] = sum[2] = 0; weight[0] = weight[1] = weight[2] = 0;
                            for (int dy = -radius; dy <= radius; dy++) {
                                final int yy = reflect(y + dy, h);
                                final short[] r = rows[dy + radius];
                                final double wy = block == 1 && dy == 0 ? 2 : 1;
                                for (int dx = -radius; dx <= radius; dx++) {
                                    final int xx = reflect(x + dx, w);
                                    final int phase = (((yy / block) & 1) << 1) | ((xx / block) & 1);
                                    final float v = Math.max(-0.25f, Math.min(1f, ((r[xx] & 0xffff) - blackLevel[phase]) * inv[phase]));
                                    final double wt = wy * (block == 1 && dx == 0 ? 2 : 1);
                                    final int c = channel[phase];
                                    sum[c] += v * wt; weight[c] += wt;
                                }
                            }
                            final int own = channel[(((y / block) & 1) << 1) | ((x / block) & 1)];
                            final float ownValue = Math.max(-0.25f, Math.min(1f, ((rows[radius][x] & 0xffff)
                                    - blackLevel[(((y / block) & 1) << 1) | ((x / block) & 1)]) * inv[(((y / block) & 1) << 1) | ((x / block) & 1)]));
                            for (int c = 0; c < 3; c++) {
                                float v = block == 1 && c == own ? ownValue : weight[c] > 0 ? (float) (sum[c] / weight[c]) : 0f;
                                row[x * 3 + c] = Math.max(0f, v);
                            }
                        }
                        view.position(y * w * 3);
                        view.put(row);
                    }
                } catch (RuntimeException e) {
                    synchronized (failure) { failure[0] = new IOException(Lang.t("Hybrid: одиночный кадр не проявлен: ", "Hybrid: the single frame was not developed: ") + e, e); }
                }
            }, "hybrid-cpu-" + t);
            workers[t].start();
        }
        for (Thread worker : workers) try { worker.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        if (failure[0] != null) { com.particlesdevs.photoncamera.util.Allocator.free(out); throw failure[0]; }
        out.position(0);
        // Nothing of an earlier shot may apply to this one: sensor-grid output, no Bento, no clip flags, no frame map.
        lastOutputSize = new android.graphics.Point(w, h);
        lastBentoApplied = false; lastBentoFactor = 1f; lastBentoUsClipped = 0f; lastNoiseFactor = 1f; lastRgbHalf = false;
        ScamRgb.lastClipFlags = null;
        ScamBurst.lastEffectiveFrames = null;
        ScamBurst.lastMergedDng = null;
        lastMergeTier = "cpu-single";
        Log.w("SCAM_HDR", "Hybrid fallback=cpu-single: base frame " + burst.base.number + " " + w + "x" + h + " block " + block
                + " demosaicked on the CPU in " + (android.os.SystemClock.elapsedRealtime() - start) + " ms");
        return out;
    }

    private static int reflect(int i, int n) {
        if (i < 0) i = -i;
        if (i >= n) i = 2 * (n - 1) - i;
        return Math.max(0, Math.min(n - 1, i));
    }

    /** Colour (0 R, 1 G, 2 B) of each 2x2 phase ((y & 1) << 1 | (x & 1)) for CFA 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
    static int[] cfaChannels(int cfa) {
        switch (cfa) {
            case 1: return new int[]{1, 0, 2, 1};
            case 2: return new int[]{1, 2, 0, 1};
            case 3: return new int[]{2, 1, 1, 0};
            default: return new int[]{0, 1, 1, 2};
        }
    }
}
