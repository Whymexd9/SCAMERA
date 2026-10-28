package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P369 (owner, 2026-10-27: «Убедись что при включении симуляций плёнки наши алгоритмы не будут мешать симуляциям»): the gates of
 * our look under a film (any film, B10), each on the shot's snapshot - without a film every value is the old expression's, bit for
 * bit; at 100 % none of our boost, clarity, digital grain, «Текстура» or gain map reaches the film.
 */
public class FilmGatesTest {
    static FilmSim.Settings film(String stock, String format, float grain, float halation, float softness, float strength) {
        return new FilmSim.Settings(FilmSim.stock(stock), FilmSim.print("", FilmSim.stock(stock)), FilmSim.format(format), grain, halation,
                softness, 0, 0, 0, strength);
    }

    static FilmSim.Settings portra(float strength) {
        return film("kodak_portra_400", "135", 1, 1, 1, strength);
    }

    /** A Point with its fields set (the android.jar stub's constructor leaves them 0). */
    static Point pt(int x, int y) {
        final Point p = new Point();
        p.x = x;
        p.y = y;
        return p;
    }

    /** Parameters without its constructor (that reads the app's settings): the fields at their Java defaults, set below. */
    static Parameters parameters() {
        final Parameters p = org.mockito.Mockito.mock(Parameters.class);
        p.filmFrameScale = 1f;
        p.rawSize = null;
        p.labWholeSize = null;
        return p;
    }

    @Test
    public void noGainMapWithAFilm() {
        assertTrue(PostPipeline.gainMapWanted(true, null));
        assertFalse(PostPipeline.gainMapWanted(false, null));
        assertFalse("a film photo is saved in SDR", PostPipeline.gainMapWanted(true, portra(1)));
        assertFalse("at any strength", PostPipeline.gainMapWanted(true, portra(.3f)));
        assertFalse(PostPipeline.gainMapWanted(false, portra(1)));
    }

    @Test
    public void noTextureAfterTheFilmsPrint() {
        assertTrue(PostPipeline.wantsLocalContrast(true, null));
        assertFalse(PostPipeline.wantsLocalContrast(false, null));
        assertFalse(PostPipeline.wantsLocalContrast(true, portra(1)));
        assertFalse(PostPipeline.wantsLocalContrast(true, film("kodak_portra_400", "off", 0, 0, 0, .5f)));
    }

    @Test
    public void theFilmNodesComeFromTheSnapshot() {
        assertNull(FilmHalation.from(null));
        assertNull(FilmGrain.from(null));
        assertNull("no format: no halation", FilmHalation.from(film("kodak_portra_400", "off", 1, 1, 1, 1)));
        assertNull("no format: no grain", FilmGrain.from(film("kodak_portra_400", "off", 1, 1, 1, 1)));
        assertNotNull(FilmHalation.from(portra(1)));
        assertNotNull(FilmGrain.from(portra(1)));
        assertNull("neither halation nor scatter", FilmHalation.from(film("kodak_portra_400", "135", 1, 0, 0, 1)));
        assertNotNull("the scatter alone", FilmHalation.from(film("kodak_portra_400", "135", 1, 0, 1, 1)));
        assertNull("a reference stock without grain: its softness is the scatter", FilmGrain.from(film("kodak_portra_400", "135", 0, 1, 1, 1)));
        assertNotNull("a classic stock's softness alone", FilmGrain.from(film("trix400", "135", 0, 1, 1, 1)));
        assertTrue(FilmGrain.addsGrain(portra(1)));
        assertFalse(FilmGrain.addsGrain(null));
        assertFalse(FilmGrain.addsGrain(film("kodak_portra_400", "off", 1, 1, 1, 1)));
        assertFalse(FilmGrain.addsGrain(film("kodak_portra_400", "135", 0, 1, 1, 1)));
    }

    static java.util.List<String> names(java.util.List<com.particlesdevs.photoncamera.processing.opengl.nodes.Node> nodes) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (com.particlesdevs.photoncamera.processing.opengl.nodes.Node n : nodes) out.add(n.Name);
        return out;
    }

    static FilmSim.Settings diffused(String stock, String format, String diffusion, float grade, float halation, float softness, float strength) {
        return new FilmSim.Settings(FilmSim.stock(stock), FilmSim.print("", FilmSim.stock(stock)), FilmSim.format(format), 1, halation, softness,
                0, 0, 0, 0, 0, strength, diffusion, grade, 1f);
    }

    /**
     * P380: the light nodes before the tone - without a film the lens filter's alone, as before; the film's diffusion (spektrafilm's
     * order) after the lens filter and before the film's halation, with a lens filter on too; none without a diffusion or at grade 0.
     */
    @Test
    public void theFilmsDiffusionSitsBetweenTheLensFilterAndTheHalation() {
        final LensFilter.Choice bpm = new LensFilter.Choice(com.particlesdevs.photoncamera.processing.color.LensFilters.BLACK_PRO_MIST, .5f,
                com.particlesdevs.photoncamera.processing.color.LensFilters.Tune.defaults(com.particlesdevs.photoncamera.processing.color.LensFilters.BLACK_PRO_MIST));
        // no film: the node list of before
        assertEquals(java.util.Collections.emptyList(), names(PostPipeline.lightNodes(LensFilter.Choice.NONE, null)));
        assertEquals(java.util.Collections.singletonList("LensFilter"), names(PostPipeline.lightNodes(bpm, null)));
        // a film without a diffusion: as before (P369)
        assertEquals(java.util.Arrays.asList("LensFilter", "FilmHalation"), names(PostPipeline.lightNodes(bpm, portra(1))));
        assertEquals(java.util.Collections.singletonList("FilmHalation"), names(PostPipeline.lightNodes(LensFilter.Choice.NONE, portra(1))));
        // the film's diffusion
        final String pm = com.particlesdevs.photoncamera.processing.color.LensFilters.PEARLESCENT;
        assertEquals(java.util.Arrays.asList("LensFilter", "FilmDiffusion", "FilmHalation"),
                names(PostPipeline.lightNodes(bpm, diffused("kodak_portra_160", "imax70", pm, 2f, 1, 1, 1))));
        assertEquals(java.util.Arrays.asList("FilmDiffusion", "FilmHalation"),
                names(PostPipeline.lightNodes(LensFilter.Choice.NONE, diffused("kodak_portra_160", "imax70", pm, 2f, 1, 1, 1))));
        assertEquals("their recipe: halation and softness 0", java.util.Collections.singletonList("FilmDiffusion"),
                names(PostPipeline.lightNodes(LensFilter.Choice.NONE, diffused("kodak_portra_160", "imax70", pm, 2f, 0, 0, 1))));
        assertEquals("no format: the diffusion on the 36 mm frame, no halation", java.util.Collections.singletonList("FilmDiffusion"),
                names(PostPipeline.lightNodes(LensFilter.Choice.NONE, diffused("kodak_portra_160", "off", pm, .5f, 1, 1, 1))));
        assertEquals("grade 0: none", java.util.Collections.singletonList("FilmHalation"),
                names(PostPipeline.lightNodes(LensFilter.Choice.NONE, diffused("kodak_portra_160", "135", pm, 0f, 1, 1, 1))));
        assertEquals("a black-and-white stock too", java.util.Arrays.asList("FilmDiffusion", "FilmHalation"),
                names(PostPipeline.lightNodes(LensFilter.Choice.NONE, diffused("trix400", "135", pm, .5f, 1, 1, 1))));
        assertNull(LensFilter.film(null));
        assertNull(LensFilter.film(portra(1)));
        assertNotNull(LensFilter.film(diffused("kodak_portra_160", "135", pm, .5f, 1, 1, .3f)));
    }

    @Test
    public void theDetailBoostIsOursNotTheFilms() {
        for (float snr : new float[]{Float.NaN, 5f, 20f, 39f, 77f, 150f, 527f, 683f}) {
            for (float gain : new float[]{1f, .8f, 1.3f}) {
                assertEquals("no film: bit for bit", Float.floatToIntBits(gain * WhyCombine.detailBoost(snr)),
                        Float.floatToIntBits(WhyCombine.detailGain(gain, snr, null)));
            }
        }
        for (float snr : new float[]{39f, 150f, 683f}) {
            assertEquals("SNR " + snr + " at 100 %: the merge's own detail", 1f, WhyCombine.detailGain(1f, snr, portra(1)), 0f);
            assertEquals(1f + (WhyCombine.detailBoost(snr) - 1f) * .5f, WhyCombine.detailGain(1f, snr, portra(.5f)), 1e-6f);
        }
        assertEquals("any film (B10): no format either", 1f, WhyCombine.detailGain(1f, 683f, film("kodak_portra_400", "off", 0, 0, 0, 1)), 0f);
    }

    @Test
    public void theClarityAndTheSharpeningGrainAreOurs() {
        for (float v : new float[]{0f, .25f, .1789f, -.3f, 2f / 255f}) {
            assertEquals("no film: bit for bit", Float.floatToIntBits(v), Float.floatToIntBits(WhyLumaSharpen.filmShare(v, null)));
            assertEquals("100 %: none", 0f, WhyLumaSharpen.filmShare(v, portra(1)), 0f);
            assertEquals(v * .4f, WhyLumaSharpen.filmShare(v, portra(.6f)), 1e-7f);
        }
    }

    @Test
    public void theDigitalLumaGrainGivesWayToTheFilmsGrain() {
        final float g = ScamDenoise.LUMA_GRAIN;
        assertEquals(Float.floatToIntBits(g), Float.floatToIntBits(ScamDenoise.lumaGrainUnderFilm(g, null)));
        assertEquals("film, grain and format at 100 %", 0f, ScamDenoise.lumaGrainUnderFilm(g, portra(1)), 0f);
        assertEquals(g * .5f, ScamDenoise.lumaGrainUnderFilm(g, portra(.5f)), 1e-7f);
        assertEquals("no format: no film grain, the luma grain stays", Float.floatToIntBits(g),
                Float.floatToIntBits(ScamDenoise.lumaGrainUnderFilm(g, film("kodak_portra_400", "off", 1, 1, 1, 1))));
        assertEquals("«Зерно» 0: the luma grain stays", Float.floatToIntBits(g),
                Float.floatToIntBits(ScamDenoise.lumaGrainUnderFilm(g, film("kodak_portra_400", "135", 0, 1, 1, 1))));
    }

    @Test
    public void theFilmsFrameIsThePhotosField() {
        final Parameters p = parameters();
        p.rawSize = pt(4000, 3000);
        assertEquals("nothing cropped: the image itself", 4000, FilmHalation.filmLongPx(4000, p));
        assertEquals(4000, FilmHalation.filmLongPx(4000, null));
        // the digital zoom 2x: the photo is the centre half, the film's 36 mm cover 2000 px of the pipeline image
        p.filmFrameScale = FilmHalation.filmFrameScale(2f, false, p.rawSize);
        assertEquals(.5f, p.filmFrameScale, 0f);
        assertEquals(2000, FilmHalation.filmLongPx(4000, p));
        assertEquals("the grain's pixel size doubles (its size in the photo kept)", 18f,
                portra(1).pixelUm(FilmHalation.filmLongPx(4000, p)), 1e-4f);
        assertEquals("a crop under the zoom's threshold is none", 1f, FilmHalation.filmFrameScale(1.004f, false, p.rawSize), 0f);
        // ISZ 4:3 of a 16:9 frame: the 4:3 crop's long side is 3/4 of it
        final Point wide = pt(4096, 2304);
        p.rawSize = wide;
        p.filmFrameScale = FilmHalation.filmFrameScale(1f, true, wide);
        assertEquals(.75f, p.filmFrameScale, 1e-6f);
        assertEquals(3072, FilmHalation.filmLongPx(4096, p));
        assertEquals("a 4:3 frame keeps its long side", 1f, FilmHalation.filmFrameScale(1f, true, pt(4096, 3072)), 0f);
        assertEquals(.375f, FilmHalation.filmFrameScale(2f, true, wide), 1e-6f);
        // a ninth of a lab shot (a third of each side): the whole shot's micrometres
        final Parameters lab = parameters();
        lab.rawSize = pt(2000, 1500);
        lab.labWholeSize = pt(6000, 4500);
        assertEquals(6000, FilmHalation.filmLongPx(2000, lab));
        assertEquals(6f, portra(1).pixelUm(FilmHalation.filmLongPx(2000, lab)), 1e-4f);
        lab.labWholeSize = pt(2000, 1500);
        assertEquals("the whole shot in the lab: as itself", 2000, FilmHalation.filmLongPx(2000, lab));
    }

    /** P369 (B1, the review's fidelity-4): the film's exposure is the meter before the overflow's cut, within the noise clamp and x9. */
    @Test
    public void filmExposureKeepsTheNoiseClamp() {
        final WhyAe.Result r = new WhyAe.Result();
        r.ae = 2f;
        r.deficit = 1f;
        assertEquals("no cut: our ae", 2f, WhyAe.filmAe(r), 0f);
        r.deficit = 1.5f;
        assertEquals("no noise model: the cut undone", 3f, WhyAe.filmAe(r), 1e-6f);
        r.maxGainByNoise = 2.5f;
        assertEquals("the noise clamp bounds the cut undone", 2.5f, WhyAe.filmAe(r), 1e-6f);
        r.maxGainByNoise = 1.5f;
        assertEquals("never below our own ae", 2f, WhyAe.filmAe(r), 0f);
        r.maxGainByNoise = 0f;
        r.ae = 8f;
        assertEquals("the x9 cap", 9f, WhyAe.filmAe(r), 0f);
    }
}
