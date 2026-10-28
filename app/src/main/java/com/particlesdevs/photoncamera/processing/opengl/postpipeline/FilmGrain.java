package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P281: the film simulation's film sharpness and grain on the finished picture (after the tone, the LUT and the final resize,
 * before the rotation): sizes in micrometres of the film over the frame's pixel size (the format's long edge / the picture's
 * long side), so a small format makes them larger in the picture.
 * <ul>
 *   <li>sharpness: a Gaussian of the stock's MTF sigma (film/blur.glsl, two passes), skipped below a third of a pixel;</li>
 *   <li>grain: the stock's RMS granularity (48 um aperture) by Selwyn's law at the larger of the pixel and the grain's size,
 *       seen through the print's contrast for a negative (film/grain.glsl), its clumps the grain's size in pixels.</li>
 * </ul>
 * P286: a stock of the reference model (FilmSim.Stock.spectral) takes the reference's grain instead (film/grain_sf.glsl): the
 * particle model's density noise per layer, blurred 11.8 um (the reference app's), through the print and the scan (FilmSim.grainLut); its film
 * sharpness is the in-emulsion scatter of FilmHalation, so no blur here.
 */
public final class FilmGrain extends Node {
    private final FilmSim.Settings set;

    FilmGrain(FilmSim.Settings set) {
        super("", "FilmGrain");
        this.set = set;
    }

    /**
     * The node for the shot's film snapshot (P369: PostPipeline.film), null without a simulation or a format, or with neither grain
     * nor softness.
     */
    public static FilmGrain from(@androidx.annotation.Nullable FilmSim.Settings s) {
        if (s == null || s.format.longMm <= 0 || (s.grain <= 0f && (s.softness <= 0f || s.stock.spectral != null))) return null;
        return new FilmGrain(s);
    }

    /**
     * P369 (texture-double-grain): the node adds grain - a format and «Зерно» above 0 (ScamDenoise then puts back only the part of
     * its digital luma grain the film's strength leaves).
     */
    public static boolean addsGrain(@androidx.annotation.Nullable FilmSim.Settings s) {
        return s != null && s.format.longMm > 0 && s.grain > 0f;
    }

    /** The density sigma of one pixel of {@code pixelUm} for granularity {@code rms} and grains of {@code grainUm} (FilmSim.grainSigma). */
    static float sigma(float rms, float grainUm, float pixelUm) {
        return FilmSim.grainSigma(rms, grainUm, pixelUm);
    }

    @Override
    public void Compile() {}

    /** P286: the reference model's grain. */
    private void runReference(GLTexture in, float um, long started) {
        if (set.grain <= 0f) return;
        final com.particlesdevs.photoncamera.processing.color.FilmLut lut =
                com.particlesdevs.photoncamera.processing.color.FilmSim.grainLut(set, GRAIN_LUT);
        if (lut == null) return;
        final int tex = lut.upload();
        final GLTexture noise = new GLTexture(in.mSize, new com.particlesdevs.photoncamera.processing.opengl.GLFormat(
                com.particlesdevs.photoncamera.processing.opengl.GLFormat.DataType.FLOAT_16, 4));
        final GLTexture h = new GLTexture(in.mSize, noise.mFormat), v = new GLTexture(in.mSize, noise.mFormat);
        try {
            // the white noise, blurred by the reference app's 11.8 um of film (P289) x (format / 35 mm)^0.62 (P380: spektrafilm-ofx's
            // grainFinalBlurFormatScale), then seen through the print and the scan
            glProg.useAssetProgram("film/noise", false);
            glProg.setVar("seed", (float) (Math.abs(set.stock.id.hashCode()) % 9973));
            glProg.drawBlocks(noise);
            final float blur = FilmSim.grainBlurUm(set.format) / um;
            glProg.useAssetProgram("film/blur", false);
            glProg.setTexture("InputBuffer", noise);
            glProg.setVar("dir", 1f, 0f);
            glProg.setVar("sigma", blur);
            glProg.drawBlocks(h);
            glProg.useAssetProgram("film/blur", false);
            glProg.setTexture("InputBuffer", h);
            glProg.setVar("dir", 0f, 1f);
            glProg.setVar("sigma", blur);
            glProg.drawBlocks(v);
            final GLTexture out = new GLTexture(in.mSize, in.mFormat);
            glProg.useAssetProgram("film/grain_sf", false);
            glProg.setTexture("InputBuffer", in);
            glProg.setTexture("Noise", v);
            glProg.setTexture3D("GrainLut", tex);
            final float scale = set.grainApplied() / um;
            glProg.setVar("scale", scale);
            glProg.setVar("lutSize", (float) GRAIN_LUT);
            glProg.drawBlocks(out);
            glProg.closed = true;
            WorkingTexture = out;
            Log.i("SCAM_PIPELINE", "film grain (reference) " + set.stock.id + " " + set.format.id + String.format(java.util.Locale.ROOT,
                    " pixel %.2f um blur %.2f px scale %.4f ms=%d", um, blur, scale, System.currentTimeMillis() - started));
        } finally {
            android.opengl.GLES30.glDeleteTextures(1, new int[]{tex}, 0);
            noise.close();
            h.close();
            v.close();
        }
    }

    /** The grain LUT's size a side. */
    static final int GRAIN_LUT = 17;

    @Override
    public void Run() {
        final GLTexture in = previousNode.WorkingTexture;
        WorkingTexture = in;
        final long started = System.currentTimeMillis();
        final float um = set.pixelUm(FilmHalation.filmLongPx(Math.max(in.mSize.x, in.mSize.y), basePipeline.mParameters));
        if (um <= 0f) return;
        GLTexture cur = in;
        if (set.stock.spectral != null) {
            runReference(in, um, started);
            return;
        }
        final float blur = FilmSim.classicBlurPx(set, um); // x the simulation's strength, as the grain
        if (blur >= FilmSim.CLASSIC_BLUR_MIN_PX) {
            final GLTexture h = new GLTexture(in.mSize, in.mFormat);
            glProg.useAssetProgram("film/blur", false);
            glProg.setTexture("InputBuffer", cur);
            glProg.setVar("dir", 1f, 0f);
            glProg.setVar("sigma", blur);
            glProg.drawBlocks(h);
            final GLTexture v = new GLTexture(in.mSize, in.mFormat);
            glProg.useAssetProgram("film/blur", false);
            glProg.setTexture("InputBuffer", h);
            glProg.setVar("dir", 0f, 1f);
            glProg.setVar("sigma", blur);
            glProg.drawBlocks(v);
            glProg.closed = true;
            h.close();
            cur = v;
        }
        float s = 0f;
        if (set.grain > 0f) {
            final FilmSim.Stock st = set.stock;
            // P369: FilmSim's (the settings' preview, FilmEffects, takes the same)
            s = FilmSim.classicGrainSigma(set, um);
            final float cell = FilmSim.classicGrainCell(set, um);
            final GLTexture out = new GLTexture(in.mSize, in.mFormat);
            glProg.useAssetProgram("film/grain", false);
            glProg.setTexture("InputBuffer", cur);
            glProg.setVar("sigma", s, s, s * 1.25f);
            glProg.setVar("cell", cell, cell, cell * 1.4f);
            glProg.setVar("sharedPart", st.mono() ? 1f : .55f);
            glProg.setVar("slide", st.reversal() ? 1 : 0);
            glProg.setVar("mono", st.mono() ? 1 : 0);
            glProg.setVar("seed", (float) (Math.abs(st.id.hashCode()) % 9973));
            glProg.drawBlocks(out);
            glProg.closed = true;
            if (cur != in) cur.close();
            cur = out;
        }
        WorkingTexture = cur;
        Log.i("SCAM_PIPELINE", "film grain " + set.stock.id + " " + set.format.id + String.format(java.util.Locale.ROOT,
                " pixel %.2f um blur %.2f px grain sigma %.4f cell %.2f px ms=%d", um, blur, s,
                FilmSim.classicGrainCell(set, um), System.currentTimeMillis() - started));
    }
}
