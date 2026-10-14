package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static android.opengl.GLES20.GL_MIRRORED_REPEAT;
import static android.opengl.GLES20.GL_NEAREST;

import android.content.Context;
import android.graphics.Point;
import android.os.SystemClock;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * «Нейроремозаик» of SHDR on a Quad / Tetra stream (the ISZ modules, SM8750): the equal-exposure N frames go through the NPU
 * Quad 2x2 / HP9 HexQuad model into one plain-bayer frame (SHDR's base), every other frame through the GPU remosaic. P129: the
 * SCAM HDR mosaic modes (scamera / detail / mfr and the Sabre-over-mosaic copies) went with SCAM HDR.
 */
public final class ScamMosaic {
    private static final String TAG = "SCAM_MOSAIC";
    private ScamMosaic() {}

    /**
     * P129: SHDR on a Quad / Tetra stream (an ISZ module without the sensor's remosaic) with «Нейроремозаик» on: the
     * equal-exposure N frames go through the NPU Quad 2x2 / HP9 HexQuad model into one plain-Bayer frame (marked
     * {@link ImageFrame#neuralReference}, SHDR's base), every other frame (the other N as donors, the short and long ones)
     * through the GPU remosaic, so SHDR merges plain Bayer (block 1). Null when it does not run (the setting is off, not the
     * SM8750, not a mosaic stream, a binned or 16:9 burst, too few equal N frames, the network failed): the frames are then
     * untouched and SHDR merges the mosaic natively, as without the setting.
     */
    public static List<ImageFrame> prepareForShdr(Context context, List<ImageFrame> images, Parameters p) {
        if (!PreferenceKeys.isShdrNeuralRemosaic() || p.rawBinning > 1 || images.isEmpty()) return null;
        int block = com.particlesdevs.photoncamera.processing.MosaicBlockStore.blockForShot(0);
        if (block == 0) {
            final ImageFrame f = images.get(0);
            final float black = (p.blackLevel[0] + p.blackLevel[1] + p.blackLevel[2] + p.blackLevel[3]) / 4f;
            final com.particlesdevs.photoncamera.processing.MosaicBlockDetector.Result r = f.buffer == null ? null
                    : com.particlesdevs.photoncamera.processing.MosaicBlockDetector.detect(f.buffer, f.width, f.height, f.width * 2,
                    black, p.whiteLevel, 8);
            block = r != null && r.confident ? r.block : 1;
            if (r != null) com.particlesdevs.photoncamera.processing.MosaicBlockStore.observeShot(r, "SHDR neural remosaic");
        }
        if (block != 2 && block != 4) return null;
        final int cfa = p.cfaPattern;
        final long start = SystemClock.elapsedRealtime();
        try {
            validate(images, p, block);
            final int emitted = RemosaicCore.emittedCfaPattern(cfa);
            Log.i(TAG, "SHDR neural remosaic: block=" + block + " cfa=" + cfa + "->" + emitted + " frames=" + images.size()
                    + " size=" + p.rawSize.x + "x" + p.rawSize.y);
            Merged m = mergeNormals(context, new ArrayList<>(images), p, block);
            remosaicGpu(m.frames, m.reference, p, cfa);
            m.reference.neuralReference = true;
            p.cfaPattern = (byte) emitted;
            p.remosaicDone = true;
            Log.i(TAG, "SHDR neural remosaic done: frames=" + m.frames.size() + " reference=frame " + m.reference.number
                    + " ms=" + (SystemClock.elapsedRealtime() - start));
            return m.frames;
        } catch (Exception e) {
            p.cfaPattern = (byte) cfa;
            Log.w(TAG, "SHDR neural remosaic not used, the mosaic merge as without it: " + e);
            return null;
        }
    }

    private static void validate(List<ImageFrame> images, Parameters p, int block) throws IOException {
        if (p.cfaPattern < 0 || p.cfaPattern > 3)
            throw new IOException(Lang.t("Нейроремозаик: для мозаики нужен порядок CFA 2×2 (RGGB/GRBG/GBRG/BGGR), сейчас ", "Neural remosaic: the mosaic needs a 2×2 CFA order (RGGB/GRBG/GBRG/BGGR), now ") + p.cfaPattern);
        if (Allocator.binning)
            throw new IOException(Lang.t("Нейроремозаик: для мозаики отключите программный биннинг", "Neural remosaic: turn off software binning for the mosaic"));
        int w = p.rawSize.x, h = p.rawSize.y;
        if (w % 8 != 0 || h % 8 != 0 || (long) w * h > 16000000)
            throw new IOException(Lang.t("Нейроремозаик: мозаика — размер кадра кратный 8, до 16 МП (сейчас ", "Neural remosaic: mosaic — frame size a multiple of 8, up to 16 MP (now ") + w + "x" + h + ")");
        if (block != 2 && block != 4) throw new IOException(Lang.t("Нейроремозаик: неизвестный размер блока мозаики ", "Neural remosaic: unknown mosaic block size ") + block);
        for (ImageFrame f : images)
            if (f.buffer == null || f.width != w || f.height != h || f.buffer.capacity() != (long) w * h * 2)
                throw new IOException(Lang.t("Нейроремозаик: неполный RAW кадра ", "Neural remosaic: incomplete RAW of frame ") + f.number);
    }

    /** Short / extra-short / long frames, by the capture role of their matched metadata (as the transport sorts them). */
    private static boolean isBracket(ImageFrame f) {
        ImageFrame.CaptureRole role = f.getCaptureRole();
        if (role != null) return role != ImageFrame.CaptureRole.NORMAL;
        return f.pair != null && (f.pair.isHighlightFrame || f.pair.isLongFrame);
    }

    private static final class Merged {
        final List<ImageFrame> frames;
        final ImageFrame reference;
        Merged(List<ImageFrame> frames, ImageFrame reference) { this.frames = frames; this.reference = reference; }
    }

    /**
     * N frames -> one plain-bayer frame (the NPU model) placed in the slot of the N reference; the other N frames stay as SHDR's
     * donors. Frames change only after success.
     */
    private static Merged mergeNormals(Context context, List<ImageFrame> work, Parameters p, int block) throws Exception {
        List<ImageFrame> normals = new ArrayList<>();
        for (ImageFrame f : work) if (!isBracket(f)) normals.add(f);
        normals.sort(Comparator.comparingLong(f -> f.timestamp));
        final int minimum = block == 2 ? 4 : 6;
        if (normals.size() < Math.max(4, minimum))
            throw new IOException(Lang.t("нужно не меньше ", "needs at least ") + Math.max(4, minimum) + Lang.t(" кадров N (увеличьте «Кадров в склейке»), есть ", " N frames (raise “Frames in the merge”), has ") + normals.size());
        // The models and the merge need equal exposures: the frames of the newest N's exposure and ISO.
        ImageFrame newest = normals.get(normals.size() - 1);
        List<ImageFrame> same = new ArrayList<>();
        for (ImageFrame f : normals)
            if (f.measuredExposure == newest.measuredExposure && f.measuredIso == newest.measuredIso) same.add(f);
        if (same.size() < minimum)
            throw new IOException(Lang.t("нужно не меньше ", "needs at least ") + minimum + Lang.t(" кадров N с одной выдержкой и ISO, есть ", " N frames with one shutter and ISO, has ") + same.size() + Lang.t(" из ", " of ") + normals.size());
        // The other N frames are donors of SHDR's merge itself: the model gets just the frames it needs.
        List<ImageFrame> use = new ArrayList<>(same.subList(Math.max(0, same.size() - minimum), same.size()));
        // The merged frame must lie in the geometry of the N reference (SHDR aligns the others to it): the model registers to
        // its first frame.
        ImageFrame reference = normals.get(normals.size() - 4);
        if (!use.contains(reference)) {
            reference = null;
            for (ImageFrame f : normals.subList(normals.size() - 4, normals.size())) if (use.contains(f)) { reference = f; break; }
            if (reference == null) throw new IOException(Lang.t("опорный кадр N не входит в серию одной экспозиции", "the N reference frame is not in the single-exposure series"));
        }
        use.remove(reference);
        use.add(0, reference);
        final long start = SystemClock.elapsedRealtime();
        ByteBuffer result = HexQuadBurst.processForScam(context, use, p, block == 2);
        if (result == null) throw new IOException(Lang.t("пустой результат", "empty result"));
        Log.i(TAG, "neural: " + use.size() + " N frames -> 1, ms=" + (SystemClock.elapsedRealtime() - start));
        List<ImageFrame> kept = new ArrayList<>(normals);
        ByteBuffer old = reference.buffer;
        reference.buffer = result;
        Allocator.free(old);
        List<ImageFrame> frames = new ArrayList<>();
        frames.add(reference);   // the DNG and the transport take the first normal frame as the reference
        for (ImageFrame f : work) {
            if (f == reference) continue;
            if (isBracket(f) || kept.contains(f)) frames.add(f);
            else f.close();
        }
        return new Merged(frames, reference);
    }

    /** Rearranges the frames into plain bayer in place (the merged reference, already bayer, is skipped). */
    private static void remosaicGpu(List<ImageFrame> frames, ImageFrame skip, Parameters p, int cfa) {
        Point size = new Point(p.rawSize.x, p.rawSize.y);
        float[] bl = p.blackLevel;
        float black = bl == null || bl.length < 4 ? 0f : (bl[0] + bl[1] + bl[2] + bl[3]) * 0.25f;
        float white = p.whiteLevel;
        GLFormat u16 = new GLFormat(GLFormat.DataType.UNSIGNED_16);
        List<ImageFrame> order = new ArrayList<>();
        for (ImageFrame f : frames) if (f != skip && !isBracket(f)) order.add(f);   // normal frames first: they set the channel gains
        for (ImageFrame f : frames) if (f != skip && isBracket(f)) order.add(f);
        long start = SystemClock.elapsedRealtime();
        try (GLCoreBlockProcessing gl = new GLCoreBlockProcessing(size, u16, GLDrawParams.Allocate.None)) {
            RemosaicCore core = new RemosaicCore(gl.mProgram);
            boolean first = true;
            for (ImageFrame f : order) {
                f.buffer.position(0);
                GLTexture raw = new GLTexture(size, u16, f.buffer, GL_NEAREST, GL_MIRRORED_REPEAT);
                GLTexture out = null;
                try {
                    out = core.run(raw, size, cfa, black, white, first);
                    first = false;
                    out.BufferLoad();
                    f.buffer.position(0);
                    out.textureBuffer(u16, f.buffer);
                    f.buffer.position(0);
                } finally {
                    raw.close();
                    if (out != null) out.close();
                }
            }
        }
        Log.i(TAG, "GPU remosaic: " + order.size() + " frames, ms=" + (SystemClock.elapsedRealtime() - start));
    }
}
