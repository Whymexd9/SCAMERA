package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * P378 (owner, 2026-10-28: «исправить тонмапинг чтобы у нас небо было схожее с фото с Арк камеры. при этом чтобы в остальных
 * сценариях ничего не сломалось»): the sky mask of the WHY tone's sky term, built once per photo in WhyFusion before the fusion -
 * the cells of whyLow on the GPU (why/skycells.glsl, two 64 x 48 read-backs), the decision on the CPU (WhySkyMask: daylight,
 * bright over the frame, smooth, grey or blue, connected to the photo's top edge, the backlit gate), then the fine mask at whyLow
 * size, its longer side kept at ~2048 px (why/skymask.glsl: the cells' weights times the pixel's own gate against the local sky
 * level and its hue - branches, the edges of buildings darker than the sky and a face against it drop out). No sky (none found,
 * the switch off, a film, the post_ab old run, a lab fragment of a frame recorded without the sky's cells, an exposure too dark for
 * daylight): no mask, and neither why/lpyr nor why/combine gets the SKY define - the photo is the one before P378 bit for bit.
 * P378 review: a P277 lab fragment decides on the whole frame's cells (LabFrame, recorded by the whole frame's run) and takes them
 * at its place, as the AE takes the whole frame's statistics; the viewfinder (LiveWhyTone) has the sky term on its meter's cells.
 */
final class WhySky {
    private WhySky() {}

    private static final GLFormat CELLS = new GLFormat(GLFormat.DataType.FLOAT_32, 4);

    /** The mask into {@code st.sky} / {@code st.skyMask} (both null without a sky); {@code src}: whyLow after bracket_dn. */
    static void build(GLProg glProg, PostPipeline pipeline, WhyTone.State st, GLTexture src) {
        st.sky = null;
        st.skyMask = null;
        final long started = System.currentTimeMillis();
        final String skip = skip(pipeline, src.mSize);
        if (skip != null) {
            if (!skip.isEmpty()) Log.i("SCAM_PIPELINE", "WHY sky: none (" + skip + ")");
            return;
        }
        final Parameters p = pipeline.mParameters;
        final WhyAe.Result r = st.ae;
        final float ev = WhySkyMask.evOffset(p.iso, p.exposureTime, p.aperture, st.inScale);
        final Point low = src.mSize;
        // P378 review: a lab fragment decides on the whole frame's cells at its place
        final LabFrame frozen = fragment(pipeline, low) ? pipeline.frozen : null;
        // the brightest whyLow value short of daylight: no cell can pass (a night, a room), the passes are not drawn
        if (frozen == null && (!Float.isFinite(ev) || ev + (float) (Math.log(Math.max(r.gmax, 1e-6f)) / Math.log(2.0)) < WhySkyMask.EV_LO)) {
            Log.i("SCAM_PIPELINE", "WHY sky: none (" + (Float.isFinite(ev) ? "the brightest EV "
                    + String.format(java.util.Locale.ROOT, "%.1f", ev + Math.log(Math.max(r.gmax, 1e-6f)) / Math.log(2.0))
                    + " under daylight" : "exposure unknown") + ")");
            return;
        }
        final float ceiling = Math.max(r.clip, r.hr);
        final GLTexture[] stats = new GLTexture[2];
        GLTexture cells = null, mask = null;
        try {
            final WhySkyMask.Cells c;
            if (frozen != null) {
                c = frozen.skyCells(st.inScale);
                if (c == null) {
                    Log.i("SCAM_PIPELINE", "WHY sky: none (a lab fragment: the whole frame's cells do not fit their grid)");
                    return;
                }
            } else {
                final int cell = WhySkyMask.cellPx(low.x, low.y);
                final Point grid = new Point((low.x + cell - 1) / cell, (low.y + cell - 1) / cell);
                final float[][] read = new float[2][];
                stats[0] = new GLTexture(grid, CELLS, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                stats[1] = new GLTexture(grid, CELLS, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                for (int mode = 0; mode < 2; mode++) {
                    glProg.setDefine("MODE", mode);
                    glProg.useAssetProgram("why/skycells", false);
                    glProg.setTexture("InputBuffer", src);
                    glProg.setVar("cellU", cell);
                    glProg.setVar("stepU", WhySkyMask.step(cell));
                    glProg.setVar("clipU", WhySkyMask.CLIP_LEVEL * ceiling);
                    glProg.drawBlocks(stats[mode]);
                    glProg.closed = true;
                    final ByteBuffer b = ByteBuffer.allocateDirect(grid.x * grid.y * 16).order(ByteOrder.nativeOrder());
                    stats[mode].textureBuffer(CELLS, b);
                    b.rewind();
                    read[mode] = new float[grid.x * grid.y * 4];
                    b.asFloatBuffer().get(read[mode]);
                }
                c = WhySkyMask.Cells.fromGpu(low.x, low.y, read[0], read[1]);
                // P378 review (P277): the whole frame's cells for its lab fragments
                if (pipeline.record != null) {
                    pipeline.record.skyCells = c.pack();
                    pipeline.record.skyGrid = new int[]{c.nx, c.ny, c.cell, c.step};
                }
            }
            final WhySkyMask.Scene scene = new WhySkyMask.Scene(ev, WhySkyMask.topEdge(p.cameraRotation, p.mirror), ceiling);
            final WhySkyMask.Decision d = WhySkyMask.decide(c, scene);
            if (d.mask == null) {
                Log.i("SCAM_PIPELINE", "WHY sky: " + d.log + (frozen != null ? " (lab fragment: the whole frame's cells)" : "")
                        + " ms=" + (System.currentTimeMillis() - started));
                return;
            }
            // the cells' weights bilinear (a soft edge between cells), the fine mask made before its samplers are bound: RG16F at
            // whyLow size up to 12 MP, its longer side kept at ~2048 px above (50 MP: 12 MB instead of 100 MB of RGBA16F)
            final Point cellGrid = new Point(c.nx, c.ny);
            cells = new GLTexture(cellGrid, new GLFormat(GLFormat.DataType.FLOAT_16, 4), BufferUtils.getFrom(d.mask.cells), GL_LINEAR, GL_CLAMP_TO_EDGE);
            final int box = maskBox(low.x, low.y);
            mask = new GLTexture(WhyTone.reduced(low, box), new GLFormat(GLFormat.DataType.FLOAT_16, 2), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            final float[] origin = cellOrigin(p, frozen);
            glProg.useAssetProgram("why/skymask", false);
            glProg.setTexture("InputBuffer", src);
            glProg.setTexture("SkyCells", cells);
            glProg.setVar("cellScaleU", 1f / (c.cell * c.nx), 1f / (c.cell * c.ny));
            glProg.setVar("cellOriginU", origin[0], origin[1]);
            glProg.setVar("boxU", box);
            glProg.drawBlocks(mask);
            glProg.closed = true;
            st.sky = mask;
            st.skyMask = d.mask;
            Log.i("SCAM_PIPELINE", "WHY sky: " + d.log + " cells=" + c.nx + "x" + c.ny + "@" + c.cell
                    + (frozen != null ? " (lab fragment: the whole frame's cells at " + origin[0] + "," + origin[1] + ")" : "")
                    + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            for (GLTexture t : stats) if (t != null) t.close();
            if (cells != null) cells.close();
            // P378 review: a failure after the fine mask was made (its program, its draw) leaks it no more
            if (mask != null && st.sky != mask) mask.close();
        }
    }

    /** whyLow px along a side of one mask pixel: 1 up to a longer side of 2048 px (12 MP), then the box that keeps it at ~2048. */
    static int maskBox(int w, int h) {
        return Math.max(1, (Math.max(w, h) + 2047) / 2048);
    }

    /** Whether this run is a P277 lab fragment (the whole frame's whyLow recorded, and another size). */
    static boolean fragment(PostPipeline pipeline, Point low) {
        final LabFrame frozen = pipeline.frozen;
        return frozen != null && frozen.lowWidth > 0 && (frozen.lowWidth != low.x || frozen.lowHeight != low.y);
    }

    /**
     * P378 review (P277): the fragment's origin in the whole frame's whyLow px (Parameters.labOrigin of LabFrame's whyLow size): the
     * fine mask reads the whole frame's cells there; 0, 0 for the photo itself.
     */
    static float[] cellOrigin(Parameters p, LabFrame frozen) {
        if (frozen == null || p.labOrigin == null) return new float[]{0f, 0f};
        return new float[]{p.labOrigin.x * frozen.lowWidth, p.labOrigin.y * frozen.lowHeight};
    }

    /**
     * Why this shot gets no sky term before any pass: "" for the switch off (nothing logged), a reason, or null to build the mask.
     * P369's film (the film is fed the scene light, our tone is not its look), the post_ab old run (the photo before), a lab
     * fragment of a frame recorded without the sky's cells (before P378's review, or a fragment without its place: its top edge
     * is not the photo's); P378 review: one with them takes the whole frame's decision at its place.
     */
    static String skip(PostPipeline pipeline, Point low) {
        return skip(WhyTone.sky(), pipeline.film != null, com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy(),
                fragment(pipeline, low), pipeline.frozen != null && pipeline.frozen.skyCells != null
                        && pipeline.mParameters != null && pipeline.mParameters.labOrigin != null);
    }

    /** {@link #skip(PostPipeline, Point)} on its inputs (WhySkyTest). */
    static String skip(float strength, boolean film, boolean legacy, boolean fragment, boolean frozenCells) {
        if (!(strength > 0f)) return "";
        if (film) return "a film simulation";
        if (legacy) return "post_ab old run";
        if (fragment && !frozenCells) return "a lab fragment of a frame recorded without the sky's cells";
        return null;
    }
}
