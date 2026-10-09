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
import com.particlesdevs.photoncamera.remosaic.BurstPolicy;
import com.particlesdevs.photoncamera.remosaic.MobileRemosaicProcessor;
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
 * SCAM HDR on a Quad / Tetra stream (the ISZ modules). The SCAM transport, the merge and the network read plain
 * bayer, so the burst is rearranged first:
 * <ul>
 * <li>scamera / detail: every frame (N, L, S, ES) through the GPU remosaic, in place;</li>
 * <li>mfr: the N frames merged by Multi-frame Remosaic into one plain-bayer frame, which becomes the N reference;</li>
 * <li>neural: the N frames through the NPU Quad 2x2 / HP9 HexQuad model, the result likewise the N reference.</li>
 * </ul>
 * In the merged modes the three newest other N frames stay as ordinary (GPU-remosaiced) donors, older N frames are
 * dropped (the merged frame already holds them), and the short / long frames always take the GPU remosaic. A merged
 * mode that cannot run (too few frames, no model, memory) falls back to the GPU remosaic of every frame.
 */
public final class ScamMosaic {
    private static final String TAG = "SCAM_MOSAIC";
    private ScamMosaic() {}

    public static List<ImageFrame> prepare(Context context, List<ImageFrame> images, Parameters p) throws Exception {
        final boolean sabre = PreferenceKeys.isScamMosaicSabre();
        String base = PreferenceKeys.scamMosaicBase();
        // The quad / HexQuad networks are Hexagon v79 contexts (SM8750): elsewhere the QNN device cannot be created (status 14001
        // on the 8 Gen 3 of the OPPO and the vivo X100 Ultra, ~0.4 s lost per shot), so the GPU remosaic runs at once.
        if ("neural".equals(base) && !PreferenceKeys.isScamNetSoc()) {
            Log.i(TAG, "SCAM HDR mosaic: neural remosaic needs the SM8750 NPU, GPU remosaic (scamera) on this SoC");
            base = "scamera";
        }
        final String mode = base;
        final int block = PreferenceKeys.scamMosaicBlock();
        final long start = SystemClock.elapsedRealtime();
        validate(images, p, block);
        final int cfa = p.cfaPattern;
        final int emitted = RemosaicCore.emittedCfaPattern(cfa);
        Log.i(TAG, "SCAM HDR mosaic: mode=" + PreferenceKeys.scamMosaicMode() + " (network input: " + mode + ") block=" + block + " cfa=" + cfa + "->" + emitted
                + " frames=" + images.size() + " size=" + p.rawSize.x + "x" + p.rawSize.y);
        List<ImageFrame> work = new ArrayList<>(images);
        if (sabre) keepMosaicCopies(work);
        ImageFrame merged = null;
        if ("mfr".equals(mode) || "neural".equals(mode)) {
            try {
                Merged m = mergeNormals(context, work, p, mode, block, cfa, emitted, sabre);
                work = m.frames;
                merged = m.reference;
            } catch (Exception e) {
                Log.w(TAG, mode + " unavailable, GPU remosaic of every frame: " + e);
                work = new ArrayList<>(images);
                p.cfaPattern = (byte) cfa;
            }
        }
        remosaicGpu(work, merged, p, cfa);
        p.cfaPattern = (byte) emitted;
        p.quadCfa = false;
        p.remosaicDone = true;
        Log.i(TAG, "SCAM HDR mosaic done: frames=" + work.size() + (merged != null ? " merged reference (" + mode + ")" : "")
                + " ms=" + (SystemClock.elapsedRealtime() - start));
        return work;
    }

    private static void validate(List<ImageFrame> images, Parameters p, int block) throws IOException {
        if (p.cfaPattern < 0 || p.cfaPattern > 3)
            throw new IOException(Lang.t("SCAM HDR: для мозаики нужен порядок CFA 2×2 (RGGB/GRBG/GBRG/BGGR), сейчас ", "SCAM HDR: the mosaic needs a 2×2 CFA order (RGGB/GRBG/GBRG/BGGR), now ") + p.cfaPattern);
        if (Allocator.binning)
            throw new IOException(Lang.t("SCAM HDR: для мозаики отключите программный биннинг", "SCAM HDR: turn off software binning for the mosaic"));
        int w = p.rawSize.x, h = p.rawSize.y;
        if (w % 8 != 0 || h % 8 != 0 || (long) w * h > 16000000)
            throw new IOException(Lang.t("SCAM HDR: мозаика — размер кадра кратный 8, до 16 МП (сейчас ", "SCAM HDR: mosaic — frame size a multiple of 8, up to 16 MP (now ") + w + "x" + h + ")");
        if (block != 2 && block != 4) throw new IOException(Lang.t("SCAM HDR: неизвестный размер блока мозаики ", "SCAM HDR: unknown mosaic block size ") + block);
        for (ImageFrame f : images)
            if (f.buffer == null || f.width != w || f.height != h || f.buffer.capacity() != (long) w * h * 2)
                throw new IOException(Lang.t("SCAM HDR: неполный RAW кадра ", "SCAM HDR: incomplete RAW of frame ") + f.number);
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
     * Sabre over the mosaic sites: every N frame keeps a copy of its own mosaic samples (the merge's donor sites), taken
     * before the buffers are rearranged. At most 16 N frames (the four slots and 12 extras), the oldest are dropped.
     */
    private static void keepMosaicCopies(List<ImageFrame> work) {
        List<ImageFrame> normals = new ArrayList<>();
        for (ImageFrame f : work) if (!isBracket(f)) normals.add(f);
        normals.sort(Comparator.comparingLong(f -> f.timestamp));
        while (normals.size() > 16) {
            ImageFrame old = normals.remove(0);
            work.remove(old);
            old.close();
        }
        for (ImageFrame f : normals) {
            ByteBuffer copy = Allocator.allocateAndCopy(f.buffer.capacity(), f.buffer, 0);
            if (copy == null) {
                Log.w(TAG, "no memory for the mosaic copy of frame " + f.number + ": Sabre over mosaic sites off");
                for (ImageFrame g : normals) if (g.mosaic != null) { Allocator.free(g.mosaic); g.mosaic = null; }
                return;
            }
            copy.position(0);
            f.mosaic = copy;
        }
        Log.i(TAG, "mosaic copies of " + normals.size() + " N frames kept for the Sabre merge");
    }

    /** N frames -> one plain-bayer frame placed in the slot of the N reference. Frames change only after success. */
    private static Merged mergeNormals(Context context, List<ImageFrame> work, Parameters p, String mode, int block,
                                       int cfa, int emitted, boolean keepExtras) throws Exception {
        List<ImageFrame> normals = new ArrayList<>();
        for (ImageFrame f : work) if (!isBracket(f)) normals.add(f);
        normals.sort(Comparator.comparingLong(f -> f.timestamp));
        final boolean neural = "neural".equals(mode);
        final int minimum = neural ? (block == 2 ? 4 : 6) : 3;
        if (normals.size() < Math.max(4, minimum))
            throw new IOException(Lang.t("нужно не меньше ", "needs at least ") + Math.max(4, minimum) + Lang.t(" кадров N (увеличьте «SCAM HDR — N-кадров из ZSL»), есть ", " N frames (raise “SCAM HDR — N frames from ZSL”), has ") + normals.size());
        // The models and the merge need equal exposures: the frames of the newest N's exposure and ISO.
        ImageFrame newest = normals.get(normals.size() - 1);
        List<ImageFrame> same = new ArrayList<>();
        for (ImageFrame f : normals)
            if (f.measuredExposure == newest.measuredExposure && f.measuredIso == newest.measuredIso) same.add(f);
        if (same.size() < minimum)
            throw new IOException(Lang.t("нужно не меньше ", "needs at least ") + minimum + Lang.t(" кадров N с одной выдержкой и ISO, есть ", " N frames with one shutter and ISO, has ") + same.size() + Lang.t(" из ", " of ") + normals.size());
        // With the Sabre merge the other N frames are donors of the merge itself: the model gets just the frames it needs.
        int cap = neural ? (keepExtras ? minimum : 50) : BurstPolicy.frameCount(same.size(), p.rawSize.x, p.rawSize.y);
        List<ImageFrame> use = new ArrayList<>(same.subList(Math.max(0, same.size() - cap), same.size()));
        // The merged frame must lie in the geometry of the N reference (the transport aligns the others to it, and with
        // the Sabre merge its mosaic sites are residuals against the network output): the neural model registers to its
        // first frame, Multi-frame Remosaic to the middle one.
        ImageFrame reference = normals.get(normals.size() - 4);
        if (!use.contains(reference)) {
            reference = null;
            for (ImageFrame f : normals.subList(normals.size() - 4, normals.size())) if (use.contains(f)) { reference = f; break; }
            if (reference == null) throw new IOException(Lang.t("опорный кадр N не входит в серию одной экспозиции", "the N reference frame is not in the single-exposure series"));
        }
        use.remove(reference);
        use.add(neural ? 0 : use.size() / 2, reference);
        ByteBuffer result;
        final long start = SystemClock.elapsedRealtime();
        if (neural) {
            result = HexQuadBurst.processForScam(context, use, p, block == 2);
        } else {
            String name = new String[]{"RGGB", "GRBG", "GBRG", "BGGR"}[emitted];
            result = MobileRemosaicProcessor.mergeForScam(use, p, block, name);
        }
        if (result == null) throw new IOException(Lang.t("пустой результат", "empty result"));
        Log.i(TAG, mode + ": " + use.size() + " N frames -> 1, ms=" + (SystemClock.elapsedRealtime() - start));
        // The four newest N are the transport's slots; its reference is the oldest of them.
        List<ImageFrame> kept = keepExtras ? new ArrayList<>(normals) : new ArrayList<>(normals.subList(normals.size() - 4, normals.size()));
        if (!kept.contains(reference)) kept.add(reference);
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
