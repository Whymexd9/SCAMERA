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
 * the ArkCam / LMC 9.6 photo tone reads - the box mean at half the sensor grid of the linear Rec.709 scene with the
 * full lens shading and no clip (Bento content above 1 stays) - reads it back once and runs the Smart-HDR statistics
 * and auto exposure of the mod on it (ArkAe). The ceiling clip comes from the data (max of arkLow, at most 4), the
 * night factor from the ISO of the reference frame (a ZSL frame at the preview exposure, as the mod's preview ISO)
 * against SENSOR_MAX_ANALOG_SENSITIVITY. The image passes through unchanged.
 */
public final class ArkStats extends Node {
    public ArkStats() { super("", "ArkStats"); }
    @Override public void Compile() {}

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
            // One read-back of arkLow (RGBA float, 16 B/pixel: 50 MB at 2048x1536) into native memory.
            int bytes = lowSize.x * lowSize.y * 16;
            pixels = Allocator.allocate(bytes);
            if (pixels == null) throw new IllegalStateException("arkLow read-back allocation of " + bytes + " B failed");
            pixels.order(ByteOrder.nativeOrder());
            st.low.BufferLoad();
            st.low.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_16, 4), pixels);
            pixels.rewind();
            NiceDiagnostics.buffer("02-ArkLow", pixels, lowSize.x, lowSize.y, 4, true);
            int iso = pipeline.mParameters.iso, maxIso = maxAnalogIso();
            ArkAe.Settings settings = ArkTone.settings();
            settings.sat *= ArkTone.ccmSatComp(pipeline.mParameters);
            ArkAe.Result r = ArkAe.smartHdr(pixels.asFloatBuffer(), 4, lowSize.x, lowSize.y, settings, iso, maxIso);
            st.ae = r;
            // Ultra HDR and anything else downstream that reads the display gain: the mod's exposure.
            pipeline.linearDisplayGain = r.ae;
            Log.i("NICE_PIPELINE", "ARK SMART_HDR_STAT " + r.describe() + " iso=" + iso + " maxIso=" + maxIso
                    + " inScale=" + st.inScale + " low=" + lowSize.x + "x" + lowSize.y + " factor=" + st.factor
                    + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            if (pixels != null) Allocator.free(pixels);
            if (fallback != null) fallback.close();
        }
    }
}
