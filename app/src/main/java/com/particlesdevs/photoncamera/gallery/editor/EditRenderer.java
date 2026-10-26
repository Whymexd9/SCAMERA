package com.particlesdevs.photoncamera.gallery.editor;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.GLES30;

import com.particlesdevs.photoncamera.gallery.dng.DngRaw;
import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * P351: the photo editor's GPU pipeline (OpenGL ES 3.0), the same for the live preview (on a downscaled source, GLSurfaceView
 * thread) and the export (full-resolution tiles, own EGL context): whatever context is current when a method is called.
 *
 * <p>Passes (shaders in assets/shaders/editor):
 * <ol>
 *   <li>demosaic (RAW only): 16-bit samples -> linear camera RGB (Malvar-He-Cutler);</li>
 *   <li>proxy, once per source: area-downscale to 512 px; per white balance / exposure: moments (log2 Y, its square, the dark
 *       channel), two Gaussian + guided-filter rounds (mid scale 1 % of the side, large scale 4 % on a 4x4-reduced level);</li>
 *   <li>nr (when on), develop (linear light + log2 Y), gauss2 x2 (sharpening + texture blurs, when on), main (tone, colour,
 *       local tools), mipmaps;</li>
 *   <li>finish: geometry, vignette, grain, to the screen or an export tile.</li>
 * </ol>
 * Stages are cached by their inputs: a crop drag runs only finish, a colour slider only main + finish.
 */
final class EditRenderer {
    private static final String TAG = "EditRenderer";
    static final int PROXY_SIDE = 512;
    static final float EPS_MID = 0.04f, EPS_LARGE = 0.06f;

    /** A texture with its framebuffer. */
    static final class Target {
        int tex, fbo, w, h, format, levels;

        void ensure(int w, int h, int format, boolean linear, int levels) {
            if (tex != 0 && this.w == w && this.h == h && this.format == format && this.levels == levels) return;
            release();
            this.w = w;
            this.h = h;
            this.format = format;
            this.levels = levels;
            tex = EditGl.texture(w, h, format, linear, levels);
            fbo = EditGl.fbo(tex);
        }

        void release() {
            EditGl.deleteFbo(fbo);
            EditGl.deleteTexture(tex);
            tex = fbo = 0;
        }
    }

    private int pDemosaic, pNr, pDevelop, pArea, pProxy, pDown4, pGauss4, pGauss2, pGuided, pMain, pFinish;
    private int vao, lutTex, pointsTex;
    private int momentFormat;
    private final Target raw = new Target(), proxySrc = new Target();
    private final Target pm = new Target(), pmTmp = new Target(), pmBlur = new Target(), ab = new Target(), abTmp = new Target(),
            abMid = new Target();
    private final Target lm = new Target(), lmTmp = new Target(), lmBlur = new Target(), lab = new Target(), labTmp = new Target(),
            abLarge = new Target();
    private final Target nr = new Target(), dev = new Target(), blurTmp = new Target(), blur = new Target(), main = new Target();
    final Target hist = new Target();
    private int largeW, largeH;
    // stage cache (the preview)
    private int cachedSrc;
    private String kNr, kDev, kBlur, kMain, kProxy;
    private float[] cachedLut, cachedPoints;

    void init(AssetManager am) throws IOException {
        pDemosaic = EditGl.program(am, "demosaic.glsl");
        pNr = EditGl.program(am, "nr.glsl");
        pDevelop = EditGl.program(am, "develop.glsl");
        pArea = EditGl.program(am, "area.glsl");
        pProxy = EditGl.program(am, "proxy.glsl");
        pDown4 = EditGl.program(am, "down4.glsl");
        pGauss4 = EditGl.program(am, "gauss4.glsl");
        pGauss2 = EditGl.program(am, "gauss2.glsl");
        pGuided = EditGl.program(am, "guided.glsl");
        pMain = EditGl.program(am, "main.glsl");
        pFinish = EditGl.program(am, "finish.glsl");
        int[] v = new int[1];
        GLES30.glGenVertexArrays(1, v, 0);
        vao = v[0];
        boolean f32 = EditGl.hasExtension("GL_EXT_color_buffer_float");
        if (!f32 && !EditGl.hasExtension("GL_EXT_color_buffer_half_float"))
            Log.w(TAG, "no float colour buffers: the editor's intermediate passes may fail");
        momentFormat = f32 ? GLES30.GL_RGBA32F : GLES30.GL_RGBA16F;
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        lutTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R32F, EditMath.LUT_SIZE, 1);
        EditGl.params(false, false);
        GLES30.glGenTextures(1, t, 0);
        pointsTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pointsTex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R32F, EditMath.LUT_SIZE, 4);
        EditGl.params(false, false);
        invalidate();
    }

    /** Forgets the cached stages (a new source, a lost context). */
    void invalidate() {
        cachedSrc = 0;
        kNr = kDev = kBlur = kMain = kProxy = null;
        cachedLut = null;
        cachedPoints = null;
    }

    // ---------------------------------------------------------------- sources

    /**
     * A RAW region (stored samples x, y, w, h of {@code img}) demosaiced into a linear camera-RGB texture this renderer owns
     * (valid until the next call). x, y even: the CFA phase of the image holds.
     */
    int rawSource(DngRaw.Image img, int x, int y, int w, int h) {
        boolean rgb = img.info.kind == DngRaw.KIND_RGB;
        int in = EditGl.uploadU16(img.data, img.width, rgb ? 3 : 1, x, y, w, h);
        raw.ensure(w, h, GLES30.GL_RGBA16F, true, 1);
        bind(raw.fbo, w, h);
        GLES30.glUseProgram(pDemosaic);
        texU(pDemosaic, "uRawTex", 0, in);
        GLES30.glUniform2i(loc(pDemosaic, "uSize"), w, h);
        int[] cfa = cfaOf(img.phase);
        GLES30.glUniform4i(loc(pDemosaic, "uCfa"), cfa[0], cfa[1], cfa[2], cfa[3]);
        GLES30.glUniform1f(loc(pDemosaic, "uScale"), img.scale);
        GLES30.glUniform1i(loc(pDemosaic, "uKind"), rgb ? 1 : 0);
        draw();
        EditGl.deleteTexture(in);
        return raw.tex;
    }

    /** The colours at (0,0) (1,0) (0,1) (1,1) of a DngRaw phase code. */
    static int[] cfaOf(int phase) {
        switch (phase) {
            case DngRaw.GRBG: return new int[]{1, 0, 2, 1};
            case DngRaw.GBRG: return new int[]{1, 2, 0, 1};
            case DngRaw.BGGR: return new int[]{2, 1, 1, 0};
            default: return new int[]{0, 1, 1, 2};
        }
    }

    /** A decoded photo as a texture (the caller deletes it). */
    static int bitmapSource(Bitmap b) {
        return EditGl.upload(b);
    }

    // ---------------------------------------------------------------- proxy

    /** The proxy's source: {@code tex} (w x h, the whole picture) area-downscaled to {@link #PROXY_SIDE}. */
    void setProxySource(int tex, int w, int h) {
        float s = Math.min(1f, PROXY_SIDE / (float) Math.max(w, h));
        int pw = Math.max(8, Math.round(w * s)), ph = Math.max(8, Math.round(h * s));
        proxySrc.ensure(pw, ph, GLES30.GL_RGBA16F, true, 1);
        bind(proxySrc.fbo, pw, ph);
        GLES30.glUseProgram(pArea);
        texU(pArea, "uIn", 0, tex);
        GLES30.glUniform2f(loc(pArea, "uInSize"), w, h);
        GLES30.glUniform2f(loc(pArea, "uOutSize"), pw, ph);
        GLES30.glUniform1i(loc(pArea, "uN"), Math.max(1, Math.min(8, (int) Math.ceil(w / (float) pw))));
        draw();
        largeW = (pw + 3) / 4;
        largeH = (ph + 3) / 4;
        kProxy = null;
    }

    /** The guided-filter bases for this white balance / exposure (cached by them). */
    void updateProxy(EditMath.Uniforms u) {
        String key = devKey(u);
        if (key.equals(kProxy)) return;
        kProxy = key;
        int pw = proxySrc.w, ph = proxySrc.h;
        pm.ensure(pw, ph, momentFormat, false, 1);
        bind(pm.fbo, pw, ph);
        GLES30.glUseProgram(pProxy);
        texU(pProxy, "uIn", 0, proxySrc.tex);
        GLES30.glUniform2f(loc(pProxy, "uTexel"), 1f / pw, 1f / ph);
        GLES30.glUniform3f(loc(pProxy, "uAir"), u.air[0], u.air[1], u.air[2]);
        developUniforms(pProxy, u);
        draw();
        float longSide = Math.max(pw, ph);
        guided(pm, pmTmp, pmBlur, ab, abTmp, abMid, 0.01f * longSide, EPS_MID);
        lm.ensure(largeW, largeH, momentFormat, false, 1);
        bind(lm.fbo, largeW, largeH);
        GLES30.glUseProgram(pDown4);
        texU(pDown4, "uIn", 0, pm.tex);
        GLES30.glUniform2i(loc(pDown4, "uInSize"), pw, ph);
        draw();
        guided(lm, lmTmp, lmBlur, lab, labTmp, abLarge, 0.04f * longSide / 4f, EPS_LARGE);
    }

    /** moments -> blur -> a, b -> blur: the coefficient texture {@code out} (RGBA16F, linear filter). */
    private void guided(Target mom, Target tmp, Target blurred, Target coef, Target coefTmp, Target out, float sigma, float eps) {
        int w = mom.w, h = mom.h;
        tmp.ensure(w, h, momentFormat, false, 1);
        blurred.ensure(w, h, momentFormat, false, 1);
        gauss4(mom, tmp, 1, 0, sigma);
        gauss4(tmp, blurred, 0, 1, sigma);
        coef.ensure(w, h, GLES30.GL_RGBA16F, false, 1);
        bind(coef.fbo, w, h);
        GLES30.glUseProgram(pGuided);
        texU(pGuided, "uIn", 0, blurred.tex);
        GLES30.glUniform1f(loc(pGuided, "uEps"), eps);
        draw();
        coefTmp.ensure(w, h, GLES30.GL_RGBA16F, false, 1);
        out.ensure(w, h, GLES30.GL_RGBA16F, true, 1);
        gauss4(coef, coefTmp, 1, 0, sigma);
        gauss4(coefTmp, out, 0, 1, sigma);
    }

    private void gauss4(Target in, Target out, float dx, float dy, float sigma) {
        bind(out.fbo, out.w, out.h);
        GLES30.glUseProgram(pGauss4);
        texU(pGauss4, "uIn", 0, in.tex);
        GLES30.glUniform2f(loc(pGauss4, "uTexel"), 1f / in.w, 1f / in.h);
        GLES30.glUniform2f(loc(pGauss4, "uDir"), dx, dy);
        float[] taps = EditMath.gaussTaps(sigma);
        GLES30.glUniform1f(loc(pGauss4, "uSigma"), Math.max(sigma, 0.05f));
        GLES30.glUniform1i(loc(pGauss4, "uTaps"), (int) taps[0]);
        GLES30.glUniform1f(loc(pGauss4, "uStride"), Math.max(1f, Math.round(taps[1])));
        draw();
    }

    float largeScaleX() {
        return proxySrc.w / (4f * largeW);
    }

    float largeScaleY() {
        return proxySrc.h / (4f * largeH);
    }

    // ---------------------------------------------------------------- the picture

    /**
     * nr -> develop -> blurs -> main for the source texture {@code src} (w x h), into {@link #mainTexture()} (mipmapped).
     * {@code longSide}: the whole picture's long side in this texture's pixels (the radii); {@code texToProxy}: this texture's
     * uv -> the proxy's. {@code cache}: skip the stages whose inputs did not change (the preview).
     */
    void renderSource(int src, int w, int h, float longSide, CropMath.Affine texToProxy, EditMath.Uniforms u, boolean cache) {
        if (!cache || src != cachedSrc || dev.w != w || dev.h != h || main.w != w || main.h != h) {
            kNr = kDev = kBlur = kMain = null;
            cachedSrc = src;
        }
        float ref = EditMath.refScale(longSide);
        boolean nrOn = u.nrLuma || u.nrChroma;
        String k1 = nrOn ? "nr" + u.sigL + "/" + u.sigC + "/" + u.stepLRef + "/" + u.stepCRef + u.nrLuma + u.nrChroma + u.linearIn : "off";
        boolean nrChanged = !k1.equals(kNr);
        int devIn = src;
        if (nrOn) {
            nr.ensure(w, h, GLES30.GL_RGBA16F, true, 1);
            if (nrChanged) {
                bind(nr.fbo, w, h);
                GLES30.glUseProgram(pNr);
                texU(pNr, "uSrc", 0, src);
                GLES30.glUniform2f(loc(pNr, "uTexel"), 1f / w, 1f / h);
                GLES30.glUniform1f(loc(pNr, "uStepL"), u.stepLRef * ref);
                GLES30.glUniform1f(loc(pNr, "uStepC"), u.stepCRef * ref);
                GLES30.glUniform1f(loc(pNr, "uSigL"), u.sigL);
                GLES30.glUniform1f(loc(pNr, "uSigC"), u.sigC);
                GLES30.glUniform1f(loc(pNr, "uLumaOn"), u.nrLuma ? 1f : 0f);
                GLES30.glUniform1f(loc(pNr, "uChromaOn"), u.nrChroma ? 1f : 0f);
                GLES30.glUniform1i(loc(pNr, "uLinearIn"), u.linearIn ? 1 : 0);
                draw();
            }
            devIn = nr.tex;
        }
        kNr = k1;
        String k2 = k1 + "|" + devKey(u);
        boolean devChanged = !k2.equals(kDev);
        dev.ensure(w, h, GLES30.GL_RGBA16F, true, 1);
        if (devChanged) {
            bind(dev.fbo, w, h);
            GLES30.glUseProgram(pDevelop);
            texU(pDevelop, "uIn", 0, devIn);
            GLES30.glUniform2f(loc(pDevelop, "uTexel"), 1f / w, 1f / h);
            developUniforms(pDevelop, u);
            draw();
        }
        kDev = k2;
        float sA = u.sigmaSharpRef * ref, sB = u.sigmaTexRef * ref;
        String k3 = u.blurOn ? k2 + "|" + sA + "/" + sB : "off";
        if (u.blurOn && !k3.equals(kBlur)) {
            blurTmp.ensure(w, h, GLES30.GL_RGBA16F, true, 1);
            blur.ensure(w, h, GLES30.GL_RGBA16F, true, 1);
            gauss2(dev, blurTmp, 1, 0, new float[]{0, 0, 0, 1}, new float[]{0, 0, 0, 1}, sA, sB);
            gauss2(blurTmp, blur, 0, 1, new float[]{1, 0, 0, 0}, new float[]{0, 1, 0, 0}, sA, sB);
        }
        kBlur = k3;
        String k4 = k3 + "|" + mainKey(u) + "|" + texToProxy.a + "," + texToProxy.c + "," + texToProxy.e + "," + texToProxy.f + "|" + kProxy;
        if (k4.equals(kMain)) return;
        kMain = k4;
        if (u.lut != cachedLut) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex);
            FloatBuffer fb = ByteBuffer.allocateDirect(4 * EditMath.LUT_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(u.lut).rewind();
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, EditMath.LUT_SIZE, 1, GLES30.GL_RED, GLES30.GL_FLOAT, fb);
            cachedLut = u.lut;
        }
        if (u.points != null && u.points != cachedPoints) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pointsTex);
            FloatBuffer fb = ByteBuffer.allocateDirect(4 * u.points.length).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(u.points).rewind();
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, EditMath.LUT_SIZE, 4, GLES30.GL_RED, GLES30.GL_FLOAT, fb);
            cachedPoints = u.points;
        }
        main.ensure(w, h, GLES30.GL_RGBA8, true, EditGl.levelsFor(w, h));
        bind(main.fbo, w, h);
        GLES30.glUseProgram(pMain);
        texU(pMain, "uDev", 0, dev.tex);
        texU(pMain, "uBlur", 1, u.blurOn ? blur.tex : dev.tex);
        texU(pMain, "uMid", 2, abMid.tex);
        texU(pMain, "uLarge", 3, abLarge.tex);
        texU(pMain, "uLut", 4, lutTex);
        texU(pMain, "uPoints", 5, pointsTex);
        GLES30.glUniform2f(loc(pMain, "uTexel"), 1f / w, 1f / h);
        GLES30.glUniformMatrix3fv(loc(pMain, "uTexToProxy"), 1, false, texToProxy.mat3(), 0);
        GLES30.glUniform2f(loc(pMain, "uLargeScale"), largeScaleX(), largeScaleY());
        GLES30.glUniform1i(loc(pMain, "uBlurOn"), u.blurOn ? 1 : 0);
        GLES30.glUniform1f(loc(pMain, "uSharpen"), u.sharpen);
        GLES30.glUniform1f(loc(pMain, "uTexture"), u.texture);
        GLES30.glUniform1f(loc(pMain, "uClarity"), u.clarity);
        GLES30.glUniform1f(loc(pMain, "uShadows"), u.shadows);
        GLES30.glUniform1f(loc(pMain, "uHighlights"), u.highlights);
        GLES30.glUniform1f(loc(pMain, "uDehaze"), u.dehaze);
        GLES30.glUniform3f(loc(pMain, "uAir"), u.air[0], u.air[1], u.air[2]);
        GLES30.glUniform1f(loc(pMain, "uSat"), u.sat);
        GLES30.glUniform1f(loc(pMain, "uVib"), u.vib);
        GLES30.glUniform1i(loc(pMain, "uScene"), u.scene ? 1 : 0);
        GLES30.glUniform1i(loc(pMain, "uColourOn"), u.colourOn ? 1 : 0);
        GLES30.glUniform1i(loc(pMain, "uMixerOn"), u.mixerOn ? 1 : 0);
        GLES30.glUniform1i(loc(pMain, "uMono"), u.mono ? 1 : 0);
        GLES30.glUniform1i(loc(pMain, "uGradeOn"), u.gradeOn ? 1 : 0);
        GLES30.glUniform1i(loc(pMain, "uPointsOn"), u.points != null ? 1 : 0);
        GLES30.glUniform3fv(loc(pMain, "uHsl"), 8, u.hsl, 0);
        GLES30.glUniform1fv(loc(pMain, "uBw"), 8, u.bw, 0);
        GLES30.glUniform1fv(loc(pMain, "uBandHues"), 8, EditMath.BAND_HUES, 0);
        GLES30.glUniform1f(loc(pMain, "uSkinHue"), u.skinHue);
        GLES30.glUniform3fv(loc(pMain, "uGrade"), 4, u.grade, 0);
        GLES30.glUniform1f(loc(pMain, "uGradeBlend"), u.gradeBlend);
        GLES30.glUniform1f(loc(pMain, "uGradeBalance"), u.gradeBalance);
        draw();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, main.tex);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
    }

    int mainTexture() {
        return main.tex;
    }

    private void gauss2(Target in, Target out, float dx, float dy, float[] selA, float[] selB, float sA, float sB) {
        bind(out.fbo, out.w, out.h);
        GLES30.glUseProgram(pGauss2);
        texU(pGauss2, "uIn", 0, in.tex);
        GLES30.glUniform2f(loc(pGauss2, "uTexel"), 1f / in.w, 1f / in.h);
        GLES30.glUniform2f(loc(pGauss2, "uDir"), dx, dy);
        GLES30.glUniform4fv(loc(pGauss2, "uSelA"), 1, selA, 0);
        GLES30.glUniform4fv(loc(pGauss2, "uSelB"), 1, selB, 0);
        GLES30.glUniform1f(loc(pGauss2, "uSigA"), Math.max(sA, 0.05f));
        GLES30.glUniform1f(loc(pGauss2, "uSigB"), Math.max(sB, 0.05f));
        float[] taps = EditMath.gaussTaps(Math.max(sA, sB));
        GLES30.glUniform1i(loc(pGauss2, "uTaps"), (int) taps[0]);
        GLES30.glUniform1f(loc(pGauss2, "uStride"), taps[1]);
        draw();
    }

    /** What finish needs besides the state. */
    static final class Finish {
        int fbo, w, h;              // target (0: the screen)
        float originX, originY;     // the tile's corner in output pixels
        boolean flipY, swap;
        int mode;                   // 0 normal, 1 the crop tab
        CropMath.Affine outToBase, baseToImg, imgToTex;
        float[] bg = {0f, 0f, 0f};
        /** Output pixels on the long side of the crop (grain size and its preview fade). */
        float outLongSide;
    }

    void finish(Finish f, EditState s, EditMath.Uniforms u) {
        bind(f.fbo, f.w, f.h);
        GLES30.glUseProgram(pFinish);
        texU(pFinish, "uImg", 0, main.tex);
        GLES30.glUniformMatrix3fv(loc(pFinish, "uOutToBase"), 1, false, f.outToBase.mat3(), 0);
        GLES30.glUniformMatrix3fv(loc(pFinish, "uBaseToImg"), 1, false, f.baseToImg.mat3(), 0);
        GLES30.glUniformMatrix3fv(loc(pFinish, "uImgToTex"), 1, false, f.imgToTex.mat3(), 0);
        GLES30.glUniform4f(loc(pFinish, "uCrop"), s.cropL, s.cropT, s.cropR, s.cropB);
        GLES30.glUniform2f(loc(pFinish, "uOutSize"), f.w, f.h);
        GLES30.glUniform2f(loc(pFinish, "uOrigin"), f.originX, f.originY);
        GLES30.glUniform1i(loc(pFinish, "uFlipY"), f.flipY ? 1 : 0);
        GLES30.glUniform1i(loc(pFinish, "uMode"), f.mode);
        GLES30.glUniform3f(loc(pFinish, "uBg"), f.bg[0], f.bg[1], f.bg[2]);
        GLES30.glUniform1f(loc(pFinish, "uVig"), u.vignette);
        GLES30.glUniform1f(loc(pFinish, "uVigMid"), u.vignetteMid);
        GLES30.glUniform1f(loc(pFinish, "uGrain"), u.grain);
        // the lattice: cells of grainCellRef px "at 4000" across the crop, whatever the output size
        float cw = s.cropR - s.cropL, ch = s.cropB - s.cropT;
        float cellsLong = EditMath.REF_SIDE / u.grainCellRef;
        float lx = cellsLong, ly = cellsLong;
        if (latticeAspect > 0) {
            if (latticeAspect >= 1) ly = cellsLong / latticeAspect;
            else lx = cellsLong * latticeAspect;
        }
        GLES30.glUniform2f(loc(pFinish, "uGrainLattice"), lx, ly);
        GLES30.glUniform1f(loc(pFinish, "uGrainPx"), f.outLongSide / cellsLong);
        GLES30.glUniform1i(loc(pFinish, "uSeed"), 1351);
        GLES30.glUniform1i(loc(pFinish, "uSwap"), f.swap ? 1 : 0);
        if (cw <= 0 || ch <= 0) return;
        draw();
    }

    /** The crop's pixel aspect (w / h) for the grain lattice; set by the caller before {@link #finish}. */
    float latticeAspect = 1f;

    // ---------------------------------------------------------------- helpers

    private void developUniforms(int p, EditMath.Uniforms u) {
        GLES30.glUniform1i(loc(p, "uLinearIn"), u.linearIn ? 1 : 0);
        GLES30.glUniform1i(loc(p, "uRaw"), u.raw ? 1 : 0);
        GLES30.glUniform3f(loc(p, "uWbMul"), u.wbMul[0], u.wbMul[1], u.wbMul[2]);
        GLES30.glUniformMatrix3fv(loc(p, "uToWork"), 1, true, u.toWork, 0);
        GLES30.glUniform3f(loc(p, "uGain"), u.gain[0], u.gain[1], u.gain[2]);
    }

    static String devKey(EditMath.Uniforms u) {
        StringBuilder b = new StringBuilder();
        b.append(u.linearIn).append(u.raw);
        for (float v : u.wbMul) b.append(',').append(v);
        for (float v : u.toWork) b.append(',').append(v);
        for (float v : u.gain) b.append(',').append(v);
        return b.toString();
    }

    static String mainKey(EditMath.Uniforms u) {
        StringBuilder b = new StringBuilder();
        b.append(u.sharpen).append(',').append(u.texture).append(',').append(u.clarity).append(',').append(u.shadows).append(',')
                .append(u.highlights).append(',').append(u.dehaze).append(',').append(u.sat).append(',').append(u.vib).append(',')
                .append(u.scene).append(u.colourOn).append(u.mixerOn).append(u.mono).append(u.gradeOn).append(',')
                .append(u.gradeBlend).append(',').append(u.gradeBalance).append(',').append(System.identityHashCode(u.lut)).append(',')
                .append(System.identityHashCode(u.points));
        for (float v : u.hsl) b.append(',').append(v);
        for (float v : u.bw) b.append(',').append(v);
        for (float v : u.grade) b.append(',').append(v);
        return b.toString();
    }

    private static void bind(int fbo, int w, int h) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glViewport(0, 0, w, h);
    }

    private void draw() {
        GLES30.glBindVertexArray(vao);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private static int loc(int p, String name) {
        return GLES30.glGetUniformLocation(p, name);
    }

    private static void texU(int p, String name, int unit, int tex) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(loc(p, name), unit);
    }

    void release() {
        for (Target t : new Target[]{raw, proxySrc, pm, pmTmp, pmBlur, ab, abTmp, abMid, lm, lmTmp, lmBlur, lab, labTmp, abLarge, nr,
                dev, blurTmp, blur, main, hist})
            t.release();
        for (int p : new int[]{pDemosaic, pNr, pDevelop, pArea, pProxy, pDown4, pGauss4, pGauss2, pGuided, pMain, pFinish})
            if (p != 0) GLES30.glDeleteProgram(p);
        if (vao != 0) GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
        EditGl.deleteTexture(lutTex);
        EditGl.deleteTexture(pointsTex);
        vao = lutTex = pointsTex = 0;
        invalidate();
    }
}
