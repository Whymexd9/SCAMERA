package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P281: the film simulation's halation, on the linear scene light before the tone (where a light source still has its real
 * energy): light that passed the emulsion and came back from the film base exposes the red layer (a little of the green) in a
 * wide halo. The halo is the image's own Gaussian pyramid summed around the film's halation size (the stock's um over the
 * frame's pixel size: a small format makes it wider in the picture), three bounces each wider and weaker; energy kept:
 * out = (E + a (K * E)) / (1 + a) per channel, a = the stock's antihalation leak times the setting.
 */
public final class FilmHalation extends Node {
    private final FilmSim.Settings set;

    FilmHalation(FilmSim.Settings set) {
        super("", "FilmHalation");
        this.set = set;
    }

    /** The node for the settings now, null without a simulation, a format or halation. */
    public static FilmHalation fromSettings() {
        final FilmSim.Settings s = FilmSim.current();
        if (s == null || s.format.longMm <= 0 || s.halation <= 0f) return null;
        final float[] h = s.stock.halation;
        if (h[0] + h[1] + h[2] <= 0f) return null;
        return new FilmHalation(s);
    }

    /** The pyramid level whose blur is about {@code sigmaPx} (a gaussdown level k blurs about 0.8 * 2^k). */
    static int level(float sigmaPx, int levels) {
        final int k = Math.round((float) (Math.log(Math.max(1f, sigmaPx / .8f)) / Math.log(2)));
        return Math.max(1, Math.min(levels - 2, k));
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        final GLTexture in = previousNode.WorkingTexture;
        WorkingTexture = in;
        final long started = System.currentTimeMillis();
        final float um = set.pixelUm(Math.max(in.mSize.x, in.mSize.y));
        if (um <= 0f) return;
        final float sigma = set.stock.halationUm / um;
        final GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        final List<GLTexture> temp = new ArrayList<>();
        try {
            final List<GLTexture> v = new ArrayList<>();
            v.add(in);
            while (v.size() < 12 && Math.max(v.get(v.size() - 1).mSize.x, v.get(v.size() - 1).mSize.y) > 4) {
                final GLTexture last = v.get(v.size() - 1);
                final GLTexture next = glUtils.gaussianDownsample(last, new Point(Math.max(1, last.mSize.x / 2), Math.max(1, last.mSize.y / 2)));
                temp.add(next);
                v.add(next);
            }
            final int k = level(sigma, v.size());
            // three bounces, each wider (one level up) and half as strong
            final float[] bounce = {.57f, .29f, .14f};
            GLTexture acc = null;
            for (int b = Math.min(2, v.size() - 1 - k); b >= 0; b--) {
                final GLTexture lv = v.get(k + b);
                final GLTexture out = new GLTexture(lv.mSize, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.add(out);
                glProg.useAssetProgram("lensfilter/upsum", false);
                glProg.setTexture("Level", lv);
                glProg.setTexture("Small", acc != null ? acc : lv);
                glProg.setVar("weight", bounce[b], bounce[b], bounce[b]);
                glProg.setVar("hasSmall", acc != null ? 1 : 0);
                glProg.drawBlocks(out);
                acc = out;
            }
            final float[] a = new float[3];
            for (int c = 0; c < 3; c++) a[c] = set.stock.halation[c] * set.halation;
            glProg.useAssetProgram("film/halation", false);
            glProg.setTexture("InputBuffer", in);
            glProg.setTexture("Halo", acc);
            glProg.setVar("amount", a[0], a[1], a[2]);
            WorkingTexture = basePipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
            Log.i("SCAM_PIPELINE", "film halation " + set.stock.id + " " + set.format.id + String.format(java.util.Locale.ROOT,
                    " pixel %.2f um sigma %.1f px level %d amount %.3f/%.3f/%.3f ms=%d", um, sigma, k, a[0], a[1], a[2],
                    System.currentTimeMillis() - started));
        } finally {
            for (GLTexture t : temp) t.close();
        }
    }
}
