package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * ARK tone, step 1 (tone_port.md 2.1 / 7.2): builds "arkLow", the equivalent of Google's ds_linear_rgb (G_CLEAN) that
 * the ArkCam / SCAM 9.6 photo tone reads - the box mean at half the sensor grid of the linear Rec.709 scene with the
 * full lens shading and no clip (Bento content above 1 stays) - reads it back once and runs the Smart-HDR statistics
 * and auto exposure of the mod on it (ArkAe). The ceiling clip comes from the data (max of arkLow, at most 4), the
 * night factor from the ISO of the reference frame (a ZSL frame at the preview exposure, as the mod's preview ISO)
 * against SENSOR_MAX_ANALOG_SENSITIVITY. The image passes through unchanged.
 */
public final class ArkStats extends Node {
    public ArkStats() { super("", "ArkStats"); }
    @Override public void Compile() {}

    /**
     * W1.6: the ARK sharpening of this pipeline (null without it). In the G_CLEAN domain it needs nothing of the AE, so its
     * GPU passes are issued right after the arkLow read-back and run while the CPU computes the Smart-HDR statistics; the
     * passes and their inputs are the same, only issued earlier (the old model of post_ab keeps the node order).
     */
    ArkLumaSharpen earlySharpen;

    /** GL_HALF_FLOAT_OES (EXT_color_buffer_half_float): the same IEEE halves as GL_HALF_FLOAT. */
    private static final int GL_HALF_FLOAT_OES = 0x8D61;

    /**
     * The half-float read type of the bound read framebuffer when the implementation reads an RGBA16F target natively as
     * RGBA halves (GL_IMPLEMENTATION_COLOR_READ_FORMAT / _TYPE), else 0.
     */
    private static int halfReadType() {
        int[] v = new int[2];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_IMPLEMENTATION_COLOR_READ_FORMAT, v, 0);
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_IMPLEMENTATION_COLOR_READ_TYPE, v, 1);
        android.opengl.GLES30.glGetError();
        return v[0] == android.opengl.GLES30.GL_RGBA && (v[1] == android.opengl.GLES30.GL_HALF_FLOAT || v[1] == GL_HALF_FLOAT_OES) ? v[1] : 0;
    }

    /** W1.6: 1 = the half read-back gives the old model's floats on this driver, -1 = it does not, 0 = not checked yet. */
    private static volatile int halfReadbackState;

    /**
     * W1.6: the half read-back is used only where it gives the floats of the old GL_FLOAT read-back for every half the
     * target can hold. ES lets a driver flush 16-bit denormals when it converts them, so the exact widening of
     * {@link HalfFloat#TABLE} is not by itself the driver's conversion; a 128x128 RGBA16F texture with every half pattern is
     * read back both ways once per process and compared ({@link #firstHalfMismatch}). Any GL error counts as a mismatch.
     */
    private static boolean halfReadbackExact(int halfType) {
        final int state = halfReadbackState;
        if (state != 0) return state > 0;
        final long started = System.nanoTime();
        int mismatch;
        String failure = null;
        try {
            mismatch = probeHalfReadback(halfType);
        } catch (RuntimeException | OutOfMemoryError e) {
            mismatch = 0x10000;
            failure = e.toString();
        }
        halfReadbackState = mismatch < 0 ? 1 : -1;
        Log.i("SCAM_PIPELINE", "ARK half read-back check: " + (mismatch < 0 ? "exact for all 65536 halves"
                : failure != null ? "failed (" + failure + "), float read-back kept"
                : mismatch > 0xffff ? "GL error, float read-back kept"
                : "half 0x" + Integer.toHexString(mismatch) + " differs, float read-back kept")
                + " ms=" + (System.nanoTime() - started) / 1_000_000);
        return mismatch < 0;
    }

    /** Uploads every half pattern, reads it back as halves and as floats; the first mismatch, -1 for none, 0x10000 on a GL error. */
    private static int probeHalfReadback(int halfType) {
        final int w = 128, h = 128;                                      // 16384 RGBA texels: each of the 65536 halves once
        final ByteBuffer upload = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 65536; i++) upload.putShort(i * 2, (short) i);
        final ByteBuffer halves = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder());
        final ByteBuffer floats = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder());
        final int[] texture = new int[1], framebuffer = new int[1], oldRead = new int[1], oldTexture = new int[1];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_TEXTURE_BINDING_2D, oldTexture, 0);
        for (int i = 0; i < 8 && android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR; i++) {
            // drain: only the calls below decide
        }
        try {
            android.opengl.GLES30.glGenTextures(1, texture, 0);
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, texture[0]);
            android.opengl.GLES30.glTexStorage2D(android.opengl.GLES30.GL_TEXTURE_2D, 1, android.opengl.GLES30.GL_RGBA16F, w, h);
            android.opengl.GLES30.glTexSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, android.opengl.GLES30.GL_RGBA,
                    android.opengl.GLES30.GL_HALF_FLOAT, upload);
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, texture[0], 0);
            android.opengl.GLES30.glReadPixels(0, 0, w, h, android.opengl.GLES30.GL_RGBA, halfType, halves);
            android.opengl.GLES30.glReadPixels(0, 0, w, h, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_FLOAT, floats);
            if (android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR) return 0x10000;
            return firstHalfMismatch(halves.asShortBuffer(), floats.asFloatBuffer());
        } finally {
            // The pipeline's bindings come back (read framebuffer, the active unit's texture) before the probe objects go.
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, oldTexture[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
            if (texture[0] != 0) android.opengl.GLES30.glDeleteTextures(1, texture, 0);
        }
    }

    /**
     * The first half pattern h (0..65535, uploaded at index h) whose read-backs do not give the old model's float, -1 when
     * none: the half read-back must return h itself and the float read-back the exact widening of h, bit for bit; a NaN
     * only has to stay a NaN both ways (Smart-HDR treats every NaN alike).
     */
    static int firstHalfMismatch(java.nio.ShortBuffer halves, java.nio.FloatBuffer floats) {
        final float[] table = HalfFloat.TABLE;
        for (int h = 0; h < 65536; h++) {
            final int back = halves.get(h) & 0xffff;
            final float f = floats.get(h);
            if (Float.isNaN(table[h])) {
                if (!Float.isNaN(table[back]) || !Float.isNaN(f)) return h;
            } else if (back != h || Float.floatToRawIntBits(f) != Float.floatToRawIntBits(table[h])) {
                return h;
            }
        }
        return -1;
    }

    /** SENSOR_MAX_ANALOG_SENSITIVITY of the camera; the mod uses 10000 without it. */
    static int maxAnalogIso() {
        try {
            CameraCharacteristics c = CaptureController.mCameraCharacteristics;
            Integer v = c == null ? null : c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
            return v != null && v > 0 ? v : 10000;
        } catch (RuntimeException e) {
            return 10000;
        }
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        glProg.closed = true;
        long started = System.currentTimeMillis();
        // A state left by a failed earlier shot is dropped, not closed: GLTexture.closeAll() already deleted its
        // textures and their ids may belong to live textures now.
        ArkTone.State st = new ArkTone.State();
        pipeline.ark = st;
        st.factor = ArkTone.lowFactor(pipeline.mParameters);
        st.inScale = (float) Math.pow(2.0, ArkTone.value("input_ev", 0f));
        Point lowSize = ArkTone.reduced(WorkingTexture.mSize, st.factor);
        GLTexture gainMap = pipeline.GainMap;
        GLTexture fallback = null;
        if (gainMap == null) {
            fallback = new GLTexture(new Point(1, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                    BufferUtils.getFrom(new float[]{1f, 1f, 1f, 1f}), GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        ByteBuffer pixels = null;
        try {
            st.low = new GLTexture(lowSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("ark/low", false);
            glProg.setTexture("InputBuffer", WorkingTexture);
            ArkTone.setColour(glProg, pipeline, gainMap, st.inScale);
            glProg.setVar("factorU", st.factor);
            glProg.drawBlocks(st.low);
            glProg.closed = true;
            st.low.BufferLoad();
            final boolean legacy = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy();
            // W1.6: arkLow read back as the IEEE halves it holds (8 B/pixel: 25 MB at 2048x1536) and widened exactly on the
            // CPU, instead of the driver's conversion into a 50 MB float buffer. The diagnostics dump and the old model read
            // floats as before, and so does a driver whose native read type is not RGBA / HALF_FLOAT or whose float
            // conversion is not the exact widening (halfReadbackExact).
            final int halfType = legacy || ScamDiagnostics.active() ? 0 : halfReadType();
            boolean half = halfType != 0 && halfReadbackExact(halfType);
            if (half) {
                int bytes = lowSize.x * lowSize.y * 8;
                pixels = Allocator.allocate(bytes);
                if (pixels != null) {
                    pixels.order(ByteOrder.nativeOrder());
                    android.opengl.GLES30.glReadPixels(0, 0, lowSize.x, lowSize.y, android.opengl.GLES30.GL_RGBA, halfType, pixels);
                    if (android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR) {
                        Allocator.free(pixels);
                        pixels = null;
                    }
                }
                half = pixels != null;
            }
            if (!half) {
                // One read-back of arkLow (RGBA float, 16 B/pixel: 50 MB at 2048x1536) into native memory.
                int bytes = lowSize.x * lowSize.y * 16;
                pixels = Allocator.allocate(bytes);
                if (pixels == null) throw new IllegalStateException("arkLow read-back allocation of " + bytes + " B failed");
                pixels.order(ByteOrder.nativeOrder());
                st.low.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_16, 4), pixels);
            }
            pixels.rewind();
            if (!half) ScamDiagnostics.buffer("02-ArkLow", pixels, lowSize.x, lowSize.y, 4, true);
            final boolean early = !legacy && earlySharpen != null && earlySharpen.runEarly(pipeline, WorkingTexture);
            int iso = pipeline.mParameters.iso, maxIso = maxAnalogIso();
            ArkAe.Settings settings = ArkTone.settings();
            settings.sat *= ArkTone.ccmSatComp(pipeline.mParameters);
            ArkAe.Result r = half ? ArkAe.smartHdrHalf(pixels.asShortBuffer(), 4, lowSize.x, lowSize.y, settings, iso, maxIso)
                    : ArkAe.smartHdr(pixels.asFloatBuffer(), 4, lowSize.x, lowSize.y, settings, iso, maxIso);
            st.ae = r;
            // Ultra HDR and anything else downstream that reads the display gain: the mod's exposure.
            pipeline.linearDisplayGain = r.ae;
            Log.i("SCAM_PIPELINE", "ARK SMART_HDR_STAT " + r.describe() + " iso=" + iso + " maxIso=" + maxIso
                    + " inScale=" + st.inScale + " low=" + lowSize.x + "x" + lowSize.y + " factor=" + st.factor
                    + " ms=" + (System.currentTimeMillis() - started) + " readback=" + (half ? "half" : "float")
                    + (early ? " sharpen=early" : ""));
        } finally {
            if (pixels != null) Allocator.free(pixels);
            if (fallback != null) fallback.close();
        }
    }
}
