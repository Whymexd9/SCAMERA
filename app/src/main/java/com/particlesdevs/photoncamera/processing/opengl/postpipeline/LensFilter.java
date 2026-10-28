package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.color.DiffusionGauss;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P228 / P287 / P289: the lens filter on the linear scene-referred RGB after the merge and its denoise, before the WHY tone
 * (where a bright source still has its real energy): the reference model's diffusion filters (Glimmerglass, Black Pro-Mist,
 * Pro-Mist, CineBloom; DiffusionModel), out = (1 - p) E + p (K * E), run as the reference app runs them - DiffusionGauss's
 * plan: every exponential of the PSF as three Gaussians, each a separable blur on the box-pyramid level where it is 3..6 pixels
 * wide (lensgauss/blur.glsl, the y pass adding it to the level's sum), the levels summed from the coarsest up
 * (lensgauss/up.glsl). The filter is linear: no knee, no level of the frame. No filter (or strength 0): the node only passes
 * its input on.
 * P380 (owner, 2026-10-28: the pair with Unspektrawesome's SPEKTRA, a Pro-Mist 2.0 in its film recipe): {@link #film} is the film's
 * own camera diffusion, a node "FilmDiffusion" right after the lens filter's and before FilmHalation (spektrafilm's order: the
 * camera's diffusion filter, then the film's halation): the same plan and passes, its share FilmSim.Settings.diffusionP and its
 * PSF sized on the film format's frame over FilmHalation.filmLongPx (the frame rule of the film's grain and halation). With a
 * lens filter on too, both run, one after the other.
 */
public final class LensFilter extends Node {
    private final String id;
    private final float strength;
    private final LensFilters.Tune tune;
    /** P380: the film snapshot whose diffusion this node draws (FilmDiffusion); null: the lens filter. */
    @Nullable private final com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film;

    public LensFilter(String id, float strength, LensFilters.Tune tune) {
        super("", "LensFilter");
        this.id = id;
        this.strength = strength;
        this.tune = tune;
        this.film = null;
    }

    private LensFilter(com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        super("", "FilmDiffusion");
        this.id = film.diffusion;
        this.strength = 1f;
        this.tune = LensFilters.Tune.defaults(film.diffusion);
        this.film = film;
    }

    /** P380: the film snapshot's diffusion node (PostPipeline.film); null without a film, a diffusion or a share above 0. */
    @Nullable
    public static LensFilter film(@Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        if (film == null || film.diffusion.isEmpty() || !(film.diffusionP() > 0)) return null;
        return new LensFilter(film);
    }

    /**
     * Owner, 2026-10-28 («"фильтры объектива": в видоискателе вижу эффект, на конечном фото нет»): a shot's filter as the
     * viewfinder showed it at the press (CaptureController takes it with the press, the shot's saver carries it to the
     * processing). The pipeline used to read the settings after the merge, seconds later: a filter changed in the tray meanwhile
     * (or a module's profile loaded) was the photo's - the owner's shot got none.
     */
    public static final class Choice {
        /** No filter (none chosen or strength 0). */
        public static final Choice NONE = new Choice("", 0f, null);
        public final String id;
        public final float strength;
        @Nullable public final LensFilters.Tune tune;

        Choice(String id, float strength, @Nullable LensFilters.Tune tune) {
            this.id = id;
            this.strength = strength;
            this.tune = tune;
        }

        /** The settings now, as the viewfinder reads them (LiveLensFilter.read). */
        public static Choice now() {
            final String id = PreferenceKeys.lensFilter();
            final float s = PreferenceKeys.lensFilterStrength();
            if (LensFilters.index(id) <= 0 || s <= 0f) return NONE;
            return new Choice(id, s, PreferenceKeys.lensFilterTune(id));
        }

        @Override public String toString() {
            return tune == null ? "none" : id + String.format(java.util.Locale.ROOT, " %.2f ", strength) + tune.serialize();
        }
    }

    /** The node of {@code choice}, null for none (the pipeline skips it). */
    @Nullable
    public static LensFilter of(Choice choice) {
        return choice.tune == null ? null : new LensFilter(choice.id, choice.strength, choice.tune);
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        final GLTexture in = previousNode.WorkingTexture;
        WorkingTexture = in;
        if (LensFilters.index(id) <= 0 || strength <= 0f) return;
        final long started = System.currentTimeMillis();
        final DiffusionGauss.Plan plan;
        if (film != null) {
            // P380: the film's diffusion on the film's frame (the photo's field: zoom / ISZ crop, a lab fragment's whole shot)
            final int filmLong = FilmHalation.filmLongPx(Math.max(in.mSize.x, in.mSize.y), basePipeline.mParameters);
            plan = DiffusionGauss.plan(id, film.diffusionP(), tune, in.mSize.x, in.mSize.y, filmLong, film.diffusionFrameUm());
        } else {
            // P277: a lab fragment takes the whole frame's size for the PSF, a lab shot records it
            final LabFrame frozen = pipeline.frozen;
            final boolean whole = frozen != null && frozen.width > 0 && frozen.height > 0;
            if (pipeline.record != null) {
                pipeline.record.width = in.mSize.x;
                pipeline.record.height = in.mSize.y;
            }
            final int frameW = whole ? frozen.width : in.mSize.x, frameH = whole ? frozen.height : in.mSize.y;
            plan = DiffusionGauss.plan(id, strength, tune, in.mSize.x, in.mSize.y, Math.max(frameW, frameH));
        }
        if (plan == null) return;
        final GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        final List<GLTexture> temp = new ArrayList<>();
        int passes = 0;
        try {
            final GLTexture[] level = new GLTexture[plan.top + 1];
            level[0] = in;
            for (int l = 1; l <= plan.top; l++) {
                level[l] = new GLTexture(new Point(DiffusionGauss.levelSize(in.mSize.x, l), DiffusionGauss.levelSize(in.mSize.y, l)), rgba,
                        null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.add(level[l]);
                glProg.useAssetProgram("lensgauss/down", false);
                glProg.setTexture("InputBuffer", level[l - 1]);
                glProg.drawBlocks(level[l]);
                passes++;
            }
            GLTexture coarse = null;
            for (int l = plan.top; l >= 0; l--) {
                final Point size = level[l].mSize;
                GLTexture sum = null;
                for (DiffusionGauss.Blur b : plan.blurs) {
                    if (b.level != l) continue;
                    final GLTexture h = new GLTexture(size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    final GLTexture next = new GLTexture(size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    temp.add(h);
                    temp.add(next);
                    glProg.useAssetProgram("lensgauss/blur", false);
                    glProg.setTexture("InputBuffer", level[l]);
                    glProg.setTexture("Acc", level[l]);
                    glProg.setVar("dir", 1f, 0f);
                    glProg.setVar("sigma", b.sigma);
                    glProg.setVar("weight", 1f, 1f, 1f);
                    glProg.setVar("accU", 0);
                    glProg.setVar("hasAccU", 0);
                    glProg.drawBlocks(h);
                    glProg.useAssetProgram("lensgauss/blur", false);
                    glProg.setTexture("InputBuffer", h);
                    glProg.setTexture("Acc", sum != null ? sum : level[l]);
                    glProg.setVar("dir", 0f, 1f);
                    glProg.setVar("sigma", b.sigma);
                    glProg.setVar("weight", b.weight[0], b.weight[1], b.weight[2]);
                    glProg.setVar("accU", 1);
                    glProg.setVar("hasAccU", sum != null ? 1 : 0);
                    glProg.drawBlocks(next);
                    passes += 2;
                    // P380 review: a texture goes as soon as no later pass reads it (GL keeps it for the passes queued on it) - the
                    // film's diffusion sized on a large format puts many Gaussians at full size: 15 - 23 live textures, 6 - 9 GB at 50 MP
                    release(temp, h);
                    if (sum != null) release(temp, sum);
                    sum = next;
                }
                final GLTexture target;
                if (l == 0) target = new GLTexture(in.mSize, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                else {
                    target = new GLTexture(size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    temp.add(target);
                }
                glProg.useAssetProgram("lensgauss/up", false);
                glProg.setTexture("Fine", sum != null ? sum : level[l]);
                glProg.setTexture("Coarse", coarse != null ? coarse : level[l]);
                glProg.setTexture("Image", level[l]);
                glProg.setVar("hasFineU", sum != null ? 1 : 0);
                glProg.setVar("hasCoarseU", coarse != null ? 1 : 0);
                glProg.setVar("finalU", l == 0 ? 1 : 0);
                glProg.setVar("pU", (float) plan.p);
                glProg.drawBlocks(target);
                passes++;
                // this level's sum, the coarser level's result and this level's image are read by no later pass
                if (sum != null) release(temp, sum);
                if (coarse != null) release(temp, coarse);
                if (l > 0) release(temp, level[l]);
                coarse = target;
            }
            glProg.closed = true;
            WorkingTexture = coarse;
            Log.i("SCAM_PIPELINE", (film != null ? "film diffusion " + film.format.id + String.format(java.util.Locale.ROOT, " grade %.2f ",
                    film.diffusionGrade) : "lens filter ") + id + String.format(java.util.Locale.ROOT, " p %.3f", plan.p) + " gaussians "
                    + plan.blurs.size() + " levels " + (plan.top + 1) + " passes " + passes + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            for (GLTexture t : temp) t.close();
        }
    }

    /** P380 review: a temporary texture closed at once and taken off the list the node closes at its end. */
    private static void release(List<GLTexture> temp, GLTexture t) {
        if (temp.remove(t)) t.close();
    }
}
