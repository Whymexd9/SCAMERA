package com.particlesdevs.photoncamera.processing.opengl.scripts;

import android.content.Context;
import android.graphics.Point;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.ml.FlowNetNcnnProcessor;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLOneScript;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLUtils;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_MIRRORED_REPEAT;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * Dense optical-flow alignment for the ESD4D merge, backed by the FlowNet-v2
 * ncnn model.
 *
 * The flow is computed per frame, on demand, at the exact moment the ESD4D
 * merge loop processes that alter frame ({@link #computeFlow(int)}). Only a
 * single flow texture is kept alive and re-uploaded every frame, so no flow
 * field is stored long-term.
 *
 * Pipeline per call:
 *  1. render a downscaled RGB frame from the raw bayer data through
 *     {@code shaders/optical/flowRGB.glsl} (full-frame stretch to 512x384,
 *     channel layout BGR[0,255] matching the desktop ncnn runner);
 *  2. run the FlowNet model (base, alter) via {@link FlowNetNcnnProcessor}
 *     yielding a dense flow field in model-pixel units;
 *  3. scale the flow into rawHalf-pixel units and upload it into the reused
 *     RGBA16F flow texture.
 *
 * The merge shader ({@code merge/mergeAlignFlow.glsl}) then bi-linearly
 * samples that texture per rawHalf pixel: alter(xy+flow(xy)).
 */
public class FlowNetAlignment implements AutoCloseable {
    public Parameters parameters;
    ArrayList<ImageFrame> images;
    GLProg glProg;
    GLUtils glUtils;
    GLOneScript origin;

    public static final int FLOW_W = 512;
    public static final int FLOW_H = 384;

    /** Reused dense-flow texture bound as "alignmentTexture" by the merge loop.
     * xy = forward base->alter displacement; zw = reverse alter->base displacement,
     * both expressed in rawHalf pixels. */
    public GLTexture flowTex;
    /** GCam/Cyclops forward-backward occlusion mask at the FlowNet grid (0=trusted, 1=reject). */
    public GLTexture occlusionTex;

    private final int minExpIdx;

    private FlowNetNcnnProcessor processor;
    private GLTexture inputBase;
    private GLTexture inputAlter;
    private GLTexture rgb0;
    private GLTexture rgb1;
    private float scaleX;
    private float scaleY;
    private boolean ready = false;
    private boolean initDone = false;
    private boolean zeroUploaded = false;

    public FlowNetAlignment(Point size, ArrayList<ImageFrame> images, GLProg glProg,
                            GLUtils glUtils, GLOneScript origin, int minExpIdx) {
        this.glProg = glProg;
        this.images = images;
        this.glUtils = glUtils;
        this.origin = origin;
        this.minExpIdx = minExpIdx;
    }

    private void log(String s) {
        Log.d("FlowNetAlignment", s);
    }

    /**
     * Allocates the GPU scratch and loads the ncnn model. Must run on the GL
     * thread once, before the first {@link #computeFlow(int)}. Returns false if
     * the model is unavailable (caller should fall back to pyramid alignment).
     */
    public boolean initFlow() {
        if (initDone) return ready;
        initDone = true;

        Context ctx = PhotonCamera.getAppContext();
        // Process-wide singleton: the model is loaded + shader-warmed on a
        // background thread at app start, so the first shot session does not
        // pay the 43MB model load or the pipeline JIT on the GL thread.
        processor = (ctx != null) ? FlowNetNcnnProcessor.start(ctx) : null;
        ready = processor != null && processor.waitReady(20000) && processor.isReady();
        log("flownet processor ready=" + ready);
        if (!ready) {
            processor = null;
            return false;
        }

        Point rawHalf = new Point(parameters.rawSize.x / 2, parameters.rawSize.y / 2);
        scaleX = (float) rawHalf.x / FLOW_W;
        scaleY = (float) rawHalf.y / FLOW_H;
        log("flow scale " + scaleX + " x " + scaleY);

        inputBase = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16, 1),
                images.get(0).buffer, GL_NEAREST, GL_CLAMP_TO_EDGE);
        inputAlter = new GLTexture(parameters.rawSize, new GLFormat(GLFormat.DataType.UNSIGNED_16, 1),
                null, GL_NEAREST, GL_MIRRORED_REPEAT);
        rgb0 = new GLTexture(new Point(FLOW_W, FLOW_H), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        rgb1 = new GLTexture(new Point(FLOW_W, FLOW_H), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        flowTex = new GLTexture(new Point(FLOW_W, FLOW_H), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        occlusionTex = new GLTexture(new Point(FLOW_W, FLOW_H), new GLFormat(GLFormat.DataType.FLOAT_16, 1),
                null, GL_LINEAR, GL_CLAMP_TO_EDGE);

        // No per-session warmup needed: FlowNetNcnnProcessor already built every
        // ncnn pipeline during its background init (zero-input forward).
        return true;
    }

    /**
     * Computes the dense flow (base frame -> alter frame {@code ind}) and
     * uploads it into the shared {@link #flowTex}. Returns {@link #flowTex}
     * ready to bind as the alignment texture; the vector at texel m is the
     * rawHalf-pixel displacement at rawHalf pixel m*scale.
     *
     * The base frame is re-rendered for every alter frame, overexposed by the
     * alter frame's {@code layerMpy} multiplier (always >= 1.0), so the flow
     * model sees brightness-matched inputs for bracketing.
     *
     * When the model is unavailable the texture stays all-zero (identity
     * alignment), keeping the merge pipeline functional.
     */
    public GLTexture computeFlow(int ind, boolean cyclopsOcclusion, float tolerance, float sigma) {
        long startAll = System.currentTimeMillis();
        if (!ready) {
            ensureZeroUpload();
            return flowTex;
        }

        float mult = images.get(ind).pair.layerMpy;
        inputAlter.loadData(images.get(ind).buffer);
        long t1 = System.currentTimeMillis();
        FloatBuffer baseRgba = renderFlowRGB(inputBase, rgb0, mult);
        FloatBuffer alterRgba = renderFlowRGB(inputAlter, rgb1, 1.0f);
        long t2 = System.currentTimeMillis();

        FlowNetNcnnProcessor.FlowResult res = processor.runInference(baseRgba, alterRgba, FLOW_W, FLOW_H);
        long t3 = System.currentTimeMillis();
        if (res == null) {
            log("flow inference failed for frame " + ind + "; identity alignment");
            ensureZeroUpload();
            return flowTex;
        }

        // Cyclops checks invertibility using a separately estimated reverse flow.
        // The two already brightness-matched tensors are simply swapped, matching
        // the recovered source_to_reference/reference_to_source contract.
        FlowNetNcnnProcessor.FlowResult reverseRes = null;
        if (cyclopsOcclusion) {
            baseRgba.rewind();
            alterRgba.rewind();
            reverseRes = processor.runInference(alterRgba, baseRgba, FLOW_W, FLOW_H);
            if (reverseRes == null) {
                log("reverse flow inference failed for frame " + ind + "; Cyclops mask disabled for this frame");
            }
        }
        long tReverse = System.currentTimeMillis();

        // Re-layout channel-last flows into one RGBA texture:
        // xy = forward, zw = reverse. The merge alignment continues to read .xy.
        int plane = FLOW_W * FLOW_H;
        float[] rgba = new float[plane * 4];
        FloatBuffer forward = res.asFloatBuffer();
        FloatBuffer reverse = reverseRes != null ? reverseRes.asFloatBuffer() : null;
        for (int i = 0; i < plane; i++) {
            int o = i * 4;
            rgba[o] = forward.get(2 * i) * scaleX;
            rgba[o + 1] = forward.get(2 * i + 1) * scaleY;
            if (reverse != null) {
                rgba[o + 2] = reverse.get(2 * i) * scaleX;
                rgba[o + 3] = reverse.get(2 * i + 1) * scaleY;
            }
        }
        flowTex.loadData(FloatBuffer.wrap(rgba));

        if (reverse != null) {
            // Build the complete recovered GetOcclusionMask chain on the small
            // 512x384 flow grid: invertibility residual -> good/bad threshold ->
            // three edge-replicated 3x3 box passes -> threshold -> separable
            // Gaussian -> [0,1] rejection mask.
            float[] mask = buildCyclopsOcclusion(forward, reverse, tolerance, sigma);
            occlusionTex.loadData(FloatBuffer.wrap(mask));
        } else {
            uploadZeroOcclusion();
        }

        long t4 = System.currentTimeMillis();
        log("flow frame " + ind + ": upload=" + (t1 - startAll) + "ms render=" + (t2 - t1)
                + "ms forward=" + (t3 - t2) + "ms reverse=" + (tReverse - t3)
                + "ms mask+pack=" + (t4 - tReverse) + "ms total=" + (t4 - startAll) + "ms");
        return flowTex;
    }


    /**
     * Recovered GCam/Cyclops GetOcclusionMask math.
     *
     * p -> q=p+forward(p) -> r=q+backward(trunc(q)); out-of-bounds is rejected.
     * R=min(1,|r-p|/normalizer), C=1-R, B=(C>0.5), then three 3x3 box
     * averages with edge replication, R2=1-(B>=1), and the original-style
     * separable Gaussian (N=trunc(6*sigma), asymmetric even-N sample centre).
     */
    private float[] buildCyclopsOcclusion(FloatBuffer forward, FloatBuffer reverse,
                                          float tolerance, float sigma) {
        final int n = FLOW_W * FLOW_H;
        float[] good = new float[n];
        float normalizer = Math.max(1e-6f,
                tolerance * (float) Math.sqrt(2.0)); // mask and flow grids are equal size

        for (int y = 0; y < FLOW_H; y++) {
            for (int x = 0; x < FLOW_W; x++) {
                int i = y * FLOW_W + x;
                float qx = x + forward.get(2 * i);
                float qy = y + forward.get(2 * i + 1);
                float rejection = 1.0f;
                if (qx >= 0.0f && qy >= 0.0f && qx < FLOW_W && qy < FLOW_H) {
                    // Java int conversion truncates toward zero, matching trunc(q)
                    // for the in-bounds non-negative coordinates used here.
                    int tx = (int) qx;
                    int ty = (int) qy;
                    int qi = ty * FLOW_W + tx;
                    float rx = qx + reverse.get(2 * qi);
                    float ry = qy + reverse.get(2 * qi + 1);
                    if (rx >= 0.0f && ry >= 0.0f && rx < FLOW_W && ry < FLOW_H) {
                        rejection = Math.min(1.0f,
                                (float) Math.hypot(rx - x, ry - y) / normalizer);
                    }
                }
                float confidence = 1.0f - rejection;
                good[i] = confidence > 0.5f ? 1.0f : 0.0f;
            }
        }

        float[] tmp = new float[n];
        for (int pass = 0; pass < 3; pass++) {
            box3Replicate(good, tmp);
            float[] swap = good; good = tmp; tmp = swap;
        }

        // Exact recovered threshold: only a fully-good box result remains zero
        // in the occlusion mask.
        float[] reject = new float[n];
        for (int i = 0; i < n; i++) reject[i] = good[i] >= 1.0f ? 0.0f : 1.0f;

        int taps = Math.max(2, (int) (6.0f * Math.max(sigma, 0.334f)));
        float[] kernel = new float[taps];
        int kernelCenter = (taps - 1) / 2;
        double sum = 0.0;
        double s2 = 2.0 * sigma * sigma;
        if (!(s2 > 0.0)) s2 = 2.0 * 0.334 * 0.334;
        for (int i = 0; i < taps; i++) {
            double d = i - kernelCenter;
            kernel[i] = (float) Math.exp(-(d * d) / s2);
            sum += kernel[i];
        }
        for (int i = 0; i < taps; i++) kernel[i] /= (float) sum;

        // Original filter uses floor(N/2) as the sampling shift; for even N
        // this is one pixel away from the kernel's floor((N-1)/2) centre.
        int sampleCenter = taps / 2;
        gaussian1D(reject, tmp, kernel, sampleCenter, true);
        gaussian1D(tmp, reject, kernel, sampleCenter, false);
        return reject;
    }

    private static void box3Replicate(float[] src, float[] dst) {
        for (int y = 0; y < FLOW_H; y++) {
            for (int x = 0; x < FLOW_W; x++) {
                float sum = 0.0f;
                for (int j = -1; j <= 1; j++) {
                    int yy = Math.max(0, Math.min(FLOW_H - 1, y + j));
                    for (int k = -1; k <= 1; k++) {
                        int xx = Math.max(0, Math.min(FLOW_W - 1, x + k));
                        sum += src[yy * FLOW_W + xx];
                    }
                }
                dst[y * FLOW_W + x] = sum / 9.0f;
            }
        }
    }

    private static void gaussian1D(float[] src, float[] dst, float[] kernel,
                                   int sampleCenter, boolean horizontal) {
        for (int y = 0; y < FLOW_H; y++) {
            for (int x = 0; x < FLOW_W; x++) {
                float sum = 0.0f;
                for (int k = 0; k < kernel.length; k++) {
                    int d = k - sampleCenter;
                    int xx = horizontal ? Math.max(0, Math.min(FLOW_W - 1, x + d)) : x;
                    int yy = horizontal ? y : Math.max(0, Math.min(FLOW_H - 1, y + d));
                    sum += src[yy * FLOW_W + xx] * kernel[k];
                }
                dst[y * FLOW_W + x] = Math.max(0.0f, Math.min(1.0f, sum));
            }
        }
    }

    private void uploadZeroOcclusion() {
        if (occlusionTex != null) {
            occlusionTex.loadData(FloatBuffer.wrap(new float[FLOW_W * FLOW_H]));
        }
    }

    private void ensureZeroUpload() {
        if (zeroUploaded) return;
        zeroUploaded = true;
        if (flowTex != null) {
            int plane = FLOW_W * FLOW_H;
            float[] rgba = new float[plane * 4];
            for (int i = 0; i < plane; i++) rgba[i * 4 + 3] = 1.0f;
            flowTex.loadData(FloatBuffer.wrap(rgba));
        }
    }

    /**
     * Renders an RGB frame usable by the FlowNet model. Returns a fresh direct
     * rgba float32 buffer (B,G,R in [0,255]) ready to feed the ncnn wrapper.
     * {@code exposure} is the brightness multiplier applied to the normalized
     * bayer data (base frames use the alter frame's layerMpy, alter frames 1.0).
     */
    private FloatBuffer renderFlowRGB(GLTexture rawTex, GLTexture rgbTex, float exposure) {
        glProg.setLayout(8, 8, 1);
        glProg.useAssetProgram("optical/flowRGB", true);
        glProg.setVar("whiteLevel", (float) parameters.whiteLevel);
        glProg.setVar("blackLevel", parameters.blackLevel);
        glProg.setVar("exposure", exposure);
        glProg.setVar("cfaPattern", parameters.cfaPattern);
        glProg.setVar("rawHalf", parameters.rawSize.x / 2, parameters.rawSize.y / 2);
        glProg.setVar("flowScale", scaleX, scaleY);
        glProg.setTexture("inTexture", rawTex);
        glProg.setTextureCompute("outTexture", rgbTex, true);
        glProg.computeAuto(rgbTex.mSize, 1);

        rgbTex.BufferLoad();
        ByteBuffer raw = rgbTex.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32, 4), true);
        raw.order(ByteOrder.nativeOrder());
        return raw.asFloatBuffer();
    }

    @Override
    public void close() {
        // processor is the process-wide singleton (kept warm across sessions);
        // do NOT close it here.
        processor = null;
        if (rgb1 != null) { rgb1.close(); rgb1 = null; }
        if (rgb0 != null) { rgb0.close(); rgb0 = null; }
        if (inputAlter != null) { inputAlter.close(); inputAlter = null; }
        if (inputBase != null) { inputBase.close(); inputBase = null; }
        if (occlusionTex != null) { occlusionTex.close(); occlusionTex = null; }
        if (flowTex != null) { flowTex.close(); flowTex = null; }
        GLTexture.notClosed();
    }
}
