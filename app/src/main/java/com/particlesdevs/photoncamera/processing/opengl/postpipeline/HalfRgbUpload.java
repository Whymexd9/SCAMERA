package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import android.opengl.GLES31;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * P145: the worker's half-float RGB into ScamRgb's RGBA16F input on the GPU - each band of rows copied unchanged into a
 * shader storage buffer and widened to RGBA by the compute shader scamhdr/halfrgba - instead of the CPU repack of every
 * pixel (ScamRgb.uploadHalfRgba: 3 -> 4 halves a pixel on up to 8 threads, then the driver's copy of 8 B a pixel).
 * <p>
 * The texture must hold the very halves the worker wrote, as the CPU repack does. GLES lets a driver flush 16-bit denormals
 * when it converts, so once per process all 65536 half patterns go through the pass and are read back; only a driver that
 * keeps every one (NaN: any NaN) gets this path, the others keep the CPU repack.
 */
final class HalfRgbUpload {
    private HalfRgbUpload() {}

    /** 1 = the GPU pass keeps every half on this driver, -1 = it does not, 0 = not checked yet. */
    private static volatile int state;
    /** Band of rows per buffer upload (about 16 MB of halves). */
    private static final int BAND_BYTES = 16 << 20;

    /** The input texture (alpha 1.0) or null when this driver does not keep the halves or a GL call failed. */
    @Nullable
    static GLTexture upload(GLProg prog, Point size, ByteBuffer rgb) {
        if (!exact(prog)) return null;
        GLTexture tex = null;
        try {
            tex = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            if (!run(prog, tex, size.x, size.y, rgb)) {
                tex.close();
                return null;
            }
            return tex;
        } catch (RuntimeException e) {
            Log.w("SCAM_PIPELINE", "half RGB GPU upload failed: " + e + ", CPU repack");
            if (tex != null) tex.close();
            return null;
        }
    }

    /** The bands of {@code rgb} (w * h * 3 halves) through scamhdr/halfrgba into {@code tex}; false on a GL error. */
    static boolean run(GLProg prog, GLTexture tex, int w, int h, ByteBuffer rgb) {
        while (GLES31.glGetError() != GLES31.GL_NO_ERROR) {
            // drain: only the calls below decide
        }
        final int rowBytes = w * 6;
        final int band = Math.max(1, Math.min(h, BAND_BYTES / rowBytes));
        final int capacity = (band * rowBytes + 3) & ~3;
        final int[] buffers = new int[2];
        GLES31.glGenBuffers(2, buffers, 0);
        try {
            for (int b : buffers) {
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, b);
                GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, capacity, null, GLES31.GL_STREAM_DRAW);
            }
            prog.setLayout(16, 16, 1);
            prog.useAssetProgram("scamhdr/halfrgba", true);
            GLES31.glBindImageTexture(0, tex.mTextureID, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
            final ByteBuffer src = rgb.duplicate().order(ByteOrder.nativeOrder());
            for (int y0 = 0, k = 0; y0 < h; y0 += band, k++) {
                final int rows = Math.min(band, h - y0);
                src.limit(src.capacity());
                src.position(y0 * rowBytes);
                src.limit(y0 * rowBytes + rows * rowBytes);
                // two buffers: the driver need not wait for the pass on the previous band before it takes the next one
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[k & 1]);
                GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, rows * rowBytes, src);
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[k & 1]);
                prog.setVar("bandU", w, y0);
                prog.setVar("rowsU", rows);
                GLES31.glDispatchCompute((w + 15) / 16, (rows + 15) / 16, 1);
            }
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_FRAMEBUFFER_BARRIER_BIT | GLES31.GL_PIXEL_BUFFER_BARRIER_BIT);
            GLES31.glBindImageTexture(0, 0, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
            prog.closed = true;
            int error = GLES31.glGetError();
            if (error != GLES31.GL_NO_ERROR) {
                Log.w("SCAM_PIPELINE", "half RGB GPU upload: GL error 0x" + Integer.toHexString(error) + ", CPU repack");
                return false;
            }
            return true;
        } finally {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            GLES31.glDeleteBuffers(2, buffers, 0);
        }
    }

    /** Whether the pass keeps every half on this driver (checked once per process, logged). */
    static boolean exact(GLProg prog) {
        final int s = state;
        if (s != 0) return s > 0;
        final long started = System.nanoTime();
        int mismatch;
        String failure = null;
        try {
            mismatch = probe(prog);
        } catch (RuntimeException | OutOfMemoryError e) {
            mismatch = 0x10000;
            failure = e.toString();
        }
        state = mismatch < 0 ? 1 : -1;
        Log.i("SCAM_PIPELINE", "half RGB GPU upload check: " + (mismatch < 0 ? "exact for all 65536 halves"
                : failure != null ? "failed (" + failure + "), CPU repack kept"
                : mismatch > 0xffff ? "GL error, CPU repack kept"
                : "half 0x" + Integer.toHexString(mismatch) + " differs, CPU repack kept")
                + " ms=" + (System.nanoTime() - started) / 1_000_000);
        return mismatch < 0;
    }

    /** All 65536 halves (as the RGB of 128 x 171 pixels) through the pass, read back; the first one lost, -1 for none. */
    private static int probe(GLProg prog) {
        final int w = 128, h = (65536 + 3 * w - 1) / (3 * w);
        final ByteBuffer halves = ByteBuffer.allocateDirect(w * h * 6).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 65536; i++) halves.putShort(i * 2, (short) i);
        GLTexture tex = new GLTexture(new Point(w, h), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        final int[] framebuffer = new int[1], oldRead = new int[1];
        try {
            if (!run(prog, tex, w, h, halves)) return 0x10000;
            GLES31.glGetIntegerv(GLES31.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
            GLES31.glGenFramebuffers(1, framebuffer, 0);
            GLES31.glBindFramebuffer(GLES31.GL_READ_FRAMEBUFFER, framebuffer[0]);
            GLES31.glFramebufferTexture2D(GLES31.GL_READ_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, tex.mTextureID, 0);
            final int halfType = WhyStats.halfReadType();
            final ByteBuffer back = ByteBuffer.allocateDirect(w * h * (halfType != 0 ? 8 : 16)).order(ByteOrder.nativeOrder());
            GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, halfType != 0 ? halfType : GLES31.GL_FLOAT, back);
            if (GLES31.glGetError() != GLES31.GL_NO_ERROR) return 0x10000;
            return firstLost(back, halfType != 0, w * h);
        } finally {
            GLES31.glBindFramebuffer(GLES31.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) GLES31.glDeleteFramebuffers(1, framebuffer, 0);
            tex.close();
        }
    }

    /**
     * The first half pattern h (0..65535, at RGB index h) the read-back does not give back - as halves the pattern itself, as
     * floats its exact widening; a NaN only has to stay a NaN - or -1 when none; alpha must be 1.0. Pixels past the patterns
     * hold zeros.
     */
    static int firstLost(ByteBuffer back, boolean halves, int pixels) {
        final float[] table = HalfFloat.TABLE;
        for (int p = 0; p < pixels; p++) {
            for (int c = 0; c < 4; c++) {
                final int h = c < 3 ? p * 3 + c : -1;
                final int want = c == 3 ? 0x3C00 : h < 65536 ? h : 0;
                final int at = p * 4 + c;
                final float got = halves ? table[back.getShort(at * 2) & 0xffff] : back.getFloat(at * 4);
                final boolean ok;
                if (Float.isNaN(table[want])) ok = Float.isNaN(got);
                else if (halves) ok = (back.getShort(at * 2) & 0xffff) == want;
                else ok = Float.floatToRawIntBits(got) == Float.floatToRawIntBits(table[want]);
                if (!ok) return c == 3 ? 0x3C00 : want;
            }
        }
        return -1;
    }
}
