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
    /** Selwyn: sigma(a) = sigma(48 um aperture) x sqrt(area(48 um) / a^2) = RMS / 1000 x 42.5 / a. */
    static final float SELWYN = 42.5f;
    /** Our calibration of a granularity to the density noise a scan shows. */
    static final float GRAIN_SCALE = .35f;

    FilmGrain(FilmSim.Settings set) {
        super("", "FilmGrain");
        this.set = set;
    }

    /** The node for the settings now, null without a simulation or a format, or with neither grain nor softness. */
    public static FilmGrain fromSettings() {
        final FilmSim.Settings s = FilmSim.current();
        if (s == null || s.format.longMm <= 0 || (s.grain <= 0f && (s.softness <= 0f || s.stock.spectral != null))) return null;
        return new FilmGrain(s);
    }

    /** The density sigma of one pixel of {@code pixelUm} for granularity {@code rms} and grains of {@code grainUm}. */
    static float sigma(float rms, float grainUm, float pixelUm) {
        return rms / 1000f * SELWYN / Math.max(1f, Math.max(pixelUm, grainUm));
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
            // the white noise, blurred by the reference app's 11.8 um of film (P289), then seen through the print and the scan
            glProg.useAssetProgram("film/noise", false);
            glProg.setVar("seed", (float) (Math.abs(set.stock.id.hashCode()) % 9973));
            glProg.drawBlocks(noise);
            final float blur = com.particlesdevs.photoncamera.processing.color.FilmSpectral.GRAIN_BLUR_UM / um;
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
        final float um = set.pixelUm(Math.max(in.mSize.x, in.mSize.y));
        if (um <= 0f) return;
        GLTexture cur = in;
        if (set.stock.spectral != null) {
            runReference(in, um, started);
            return;
        }
        final float blur = set.stock.mtfUm / um * set.softnessApplied(); // x the simulation's strength, as the grain
        if (blur >= .33f) {
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
            // a print sees the negative's grain through its own contrast; a projected film shows its own
            final float through = st.reversal() ? 1f : set.print.film ? 3.0f : 2.7f;
            s = sigma(st.rms, st.grainUm, um) * through * GRAIN_SCALE * set.grainApplied();
            final float cell = Math.max(1f, st.grainUm / um);
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
                Math.max(1f, set.stock.grainUm / um), System.currentTimeMillis() - started));
    }
}
