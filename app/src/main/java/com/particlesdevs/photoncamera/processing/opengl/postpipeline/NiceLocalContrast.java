package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLUtils;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * Mid-frequency local contrast ("texture") of the SCAM HDR render, on the display-encoded image after
 * the tone curves and before sharpening. A GCam/LMC render carries 20-45 % more contrast than ours in
 * the structures of 2-16 pixels (lettering, weave, wood grain, the body of an edge) while the finest
 * scale and the noise agree; fusion and USM cannot add that without raising the noise. The Laplacian
 * levels 1-3 of the luminance are scaled where their local amplitude is well above the band's own noise
 * floor (a low percentile of the amplitude), and less on strong edges.
 */
public final class NiceLocalContrast extends Node {
    public NiceLocalContrast() { super("", "NiceLocalContrast"); }
    @Override public void Compile() {}

    /** Low percentile of a level's local amplitude, from a coarse RGBA read-back. */
    private float noiseFloor(GLTexture energy) {
        GLTexture coarse = new GLTexture(new Point(96, 72), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        int[] oldRead = new int[1], framebuffer = new int[1];
        try {
            glProg.useAssetProgram("nicelc/coarse", false);
            glProg.setTexture("InputBuffer", energy);
            glProg.drawBlocks(coarse);
            android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, coarse.mTextureID, 0);
            java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(96 * 72 * 16).order(java.nio.ByteOrder.nativeOrder());
            android.opengl.GLES30.glReadPixels(0, 0, 96, 72, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_FLOAT, data);
            float[] values = new float[96 * 72];
            int n = 0;
            for (int i = 0; i < values.length; i++) {
                float v = data.getFloat(i * 16);
                if (v > 0f && !Float.isNaN(v)) values[n++] = v;
            }
            if (n == 0) return 0f;
            java.util.Arrays.sort(values, 0, n);
            return values[n / 4];
        } catch (RuntimeException e) {
            return 0f;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
            coarse.close();
        }
    }

    @Override public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        float amount = PreferenceKeys.getNiceTexture();
        if (amount <= 0f) { glProg.closed = true; return; }
        long started = System.currentTimeMillis();
        GLFormat mono = new GLFormat(GLFormat.DataType.FLOAT_16, 1);
        GLTexture luma = new GLTexture(WorkingTexture.mSize, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glProg.useAssetProgram("nicelc/luma", false);
        glProg.setTexture("InputBuffer", WorkingTexture);
        glProg.drawBlocks(luma);
        GLUtils.Pyramid pyramid = glUtils.createPyramid(5, 2, luma);
        GLTexture[] energy = new GLTexture[3];
        float[] floors = new float[3];
        for (int i = 0; i < 3; i++) {
            GLTexture lap = pyramid.laplace[i + 1];
            energy[i] = new GLTexture(lap.mSize, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("nicelc/energy", false);
            glProg.setTexture("InputBuffer", lap);
            glProg.drawBlocks(energy[i]);
            floors[i] = Math.max(noiseFloor(energy[i]), 1.0e-5f);
        }
        float g1 = PreferenceKeys.niceInternalValue("texture_g1", 1.3f);
        float g2 = PreferenceKeys.niceInternalValue("texture_g2", 1.6f);
        float g3 = PreferenceKeys.niceInternalValue("texture_g3", 1.7f);
        glProg.useAssetProgram("nicelc/apply", false);
        glProg.setTexture("InputBuffer", WorkingTexture);
        glProg.setTexture("Lap1", pyramid.laplace[1]);
        glProg.setTexture("Lap2", pyramid.laplace[2]);
        glProg.setTexture("Lap3", pyramid.laplace[3]);
        glProg.setTexture("En1", energy[0]);
        glProg.setTexture("En2", energy[1]);
        glProg.setTexture("En3", energy[2]);
        glProg.setVar("gain", (g1 - 1f) * amount, (g2 - 1f) * amount, (g3 - 1f) * amount);
        glProg.setVar("floorE", floors[0], floors[1], floors[2]);
        glProg.setVar("core", PreferenceKeys.niceInternalValue("texture_core0", 0.8f), PreferenceKeys.niceInternalValue("texture_core1", 1.8f));
        glProg.setVar("bmax", PreferenceKeys.niceInternalValue("texture_bmax", 0.12f));
        WorkingTexture = pipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
        for (GLTexture e : energy) e.close();
        pyramid.releasePyramid();
        Log.i("NICE_PIPELINE", "localContrast amount=" + amount + " gains=" + g1 + "," + g2 + "," + g3
                + " floors=" + floors[0] + "," + floors[1] + "," + floors[2] + " ms=" + (System.currentTimeMillis() - started));
    }
}
