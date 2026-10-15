package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.opengl.GLES31;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.opengl.GLBuffer;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P145 (owner: «Переноси всё что можно из этого списка на гпу»): the per-pixel part of the ARK auto exposure
 * ({@link ArkAe#stats}) by the compute shader ark/aestats - the maximum, then the two 4096-bin histograms and the sums -
 * instead of reading arkLow back (25 MB at 2048x1536) and two single-threaded CPU passes. What comes back is 33 KB.
 * <p>
 * Checked before it is used: no GL error, and both histograms hold exactly the total metering weight of the image (the
 * pixel count for the average metering), which a lost or doubled atomic would break. Otherwise null, and ArkStats reads
 * arkLow back and counts on the CPU as before.
 */
final class ArkAeGpu {
    private ArkAeGpu() {}

    static final int TILE = 64;

    @Nullable
    static ArkAe.Stats stats(GLProg prog, GLTexture low, int mode) {
        final int w = low.mSize.x, h = low.mSize.y;
        final int gx = (w + TILE - 1) / TILE, gy = (h + TILE - 1) / TILE;
        final GLFormat u32 = new GLFormat(GLFormat.DataType.UNSIGNED_32);
        while (GLES31.glGetError() != GLES31.GL_NO_ERROR) {
            // drain: only the passes below decide
        }
        try (GLBuffer max = new GLBuffer(1, u32); GLBuffer hy = new GLBuffer(ArkAe.BINS, u32);
             GLBuffer hm = new GLBuffer(ArkAe.BINS, u32); GLBuffer sums = new GLBuffer(2 * gx * gy, u32)) {
            prog.setDefine("PASS", 1);
            prog.setLayout(8, 8, 1);
            prog.useAssetProgram("ark/aestats", true);
            prog.setTexture("arkLow", low);
            prog.setVar("sizeU", w, h);
            prog.setVar("modeU", mode);
            prog.setBufferCompute("aeMax", max);
            prog.computeManual(gx, gy, 1);
            final int key = max.readBufferIntegers(false)[0];
            ArkAe.Stats probe = ArkAe.fromGpu(key, new int[ArkAe.BINS], new int[ArkAe.BINS], new int[0]);
            prog.setDefine("PASS", 2);
            prog.setLayout(8, 8, 1);
            prog.useAssetProgram("ark/aestats", true);
            prog.setTexture("arkLow", low);
            prog.setVar("sizeU", w, h);
            prog.setVar("modeU", mode);
            prog.setVar("invClipU", ArkAe.invClip(probe.gmax));
            prog.setBufferCompute("aeHistY", hy);
            prog.setBufferCompute("aeHistM", hm);
            prog.setBufferCompute("aeSums", sums);
            prog.computeManual(gx, gy, 1);
            int[] y = hy.readBufferIntegers(false), m = hm.readBufferIntegers(false), s = sums.readBufferIntegers(false);
            int error = GLES31.glGetError();
            if (error != GLES31.GL_NO_ERROR || y == null || m == null || s == null) {
                Log.w("SCAM_PIPELINE", "ARK AE GPU: GL error 0x" + Integer.toHexString(error) + ", CPU statistics");
                return null;
            }
            ArkAe.Stats st = ArkAe.fromGpu(key, y, m, s);
            double weight = totalWeight(w, h, mode), hmTotal = 0;
            for (double v : st.hm) hmTotal += v;
            // both histograms count every pixel once with its weight; the average metering's total is exact, the centre and
            // spot weights may differ on a ring pixel where the GPU rounds the distance differently (a fused multiply-add)
            boolean exact = st.tot == hmTotal && (mode < 1 ? st.tot == weight : Math.abs(st.tot - weight) <= weight * 1e-3);
            if (!exact) {
                Log.w("SCAM_PIPELINE", "ARK AE GPU: histogram weight " + (long) st.tot + " / " + (long) hmTotal + " instead of "
                        + (long) weight + ", CPU statistics");
                return null;
            }
            return st;
        } catch (RuntimeException e) {
            Log.w("SCAM_PIPELINE", "ARK AE GPU failed: " + e + ", CPU statistics");
            return null;
        }
    }

    /** The metering weight of a w x h image (ArkAe.stats: 1 each for the average, the centre and spot weights else). */
    static double totalWeight(int w, int h, int mode) {
        if (mode < 1) return (double) w * h;
        final float halfW = w * 0.5f, halfH = h * 0.5f;
        double tot = 0;
        for (int y = 0; y < h; y++) {
            float dy = (y - halfH) / halfH, dy2 = dy * dy;
            for (int x = 0; x < w; x++) {
                float dx = (x - halfW) / halfW, d2 = dx * dx + dy2;
                if (mode == 2) tot += d2 < 0.04f ? 10 : d2 < 0.16f ? 2 : 0;
                else if (mode == 1) tot += d2 < 0.25f ? 4 : d2 < 0.5625f ? 2 : 1;
                else tot += 1;
            }
        }
        return tot;
    }
}
