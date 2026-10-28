package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.processing.color.LensFilters;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** P228: the live lens filter's uniforms follow the photo's model; main_fs.glsl carries the splice marker. */
public class LiveLensFilterTest {
    @Test public void theUniformsAreThePhotosNumbers() {
        float[] v = new float[LiveLensFilter.VALUES];
        LensFilters.Tune t = LensFilters.Tune.defaults(LensFilters.BLACK_PRO_MIST);
        LiveLensFilter.fill(v, LensFilters.BLACK_PRO_MIST, 0.5f, t);
        assertEquals(0.5f, v[0], 0f);
        assertEquals("the star's own knee (the diffusion has none)", LensFilters.shape(LensFilters.BLACK_PRO_MIST)[0], v[2], 1e-6f);
        LiveLensFilter.fill(v, LensFilters.STREAK, 1f, LensFilters.Tune.of(LensFilters.STREAK, 1f));
        assertEquals(LensFilters.streakLength(LensFilters.STREAK, LensFilters.Tune.of(LensFilters.STREAK, 1f)), v[6], 0f);
        LiveLensFilter.fill(v, LensFilters.KALEIDOSCOPE, 1f, LensFilters.Tune.of(LensFilters.KALEIDOSCOPE, 1f));
        assertEquals(12f, v[7], 0f);
        // owner, 2026-10-18: the diffusion is the photo's model, fitted to the viewport and the mip pyramid's widths
        float[][] w = LiveLensFilter.weights(LensFilters.BLACK_PRO_MIST, 0.5f, t, 1920, LiveLensFilter.levels(720, 960));
        assertEquals(1 + 10, w.length);
        float p = (float) com.particlesdevs.photoncamera.processing.color.DiffusionModel.scatter(LensFilters.BLACK_PRO_MIST, 0.5f);
        for (int c = 0; c < 3; c++) {
            float sum = 0;
            for (float[] k : w) sum += k[c];
            assertEquals("energy kept, channel " + c, 1f, sum, 1e-4f);
            assertTrue("the frame keeps at least 1 - p", w[0][c] >= 1f - p - 1e-6f);
        }
        float[][] wide = LiveLensFilter.weights(LensFilters.BLACK_PRO_MIST, 0.5f, LensFilters.Tune.parse(LensFilters.BLACK_PRO_MIST, "0,50,100,50,50"), 1920, 10);
        assertTrue("«Радиус» 100 %: the weight on the wider levels", mean(wide) > mean(w) + 0.3f);
        assertNull("no diffusion for the streak", LiveLensFilter.weights(LensFilters.STREAK, 0.5f, t, 1920, 10));
    }

    private static float mean(float[][] w) {
        float m = 0, s = 0;
        for (int k = 1; k < w.length; k++) { m += k * w[k][1]; s += w[k][1]; }
        return m / s;
    }

    /** Owner, 2026-10-18 (copies of the bright parts): the glow filters sample a mipmapped half-size pyramid, not rings of taps. */
    @Test public void theGlowComesFromThePyramid() throws Exception {
        for (String id : LensFilters.IDS) {
            int i = LensFilters.index(id);
            boolean perPixel = LensFilters.GND.equals(id) || LensFilters.LIGHT_POLLUTION.equals(id)
                    || LensFilters.KALEIDOSCOPE.equals(id) || LensFilters.INFRARED.equals(id);
            assertEquals(id, !perPixel, LiveLensFilter.needsPyramid(i));
        }
        assertFalse(LiveLensFilter.needsPyramid(0));
        assertEquals(540, LiveLensFilter.half(1080));
        assertEquals(1201, LiveLensFilter.half(2401));
        assertEquals(1, LiveLensFilter.half(0));
        assertEquals("540 x 1201 down to 1 x 1", 11, LiveLensFilter.levels(540, 1201));
        assertTrue("six pyramid levels on any phone screen", LiveLensFilter.levels(LiveLensFilter.half(720), LiveLensFilter.half(720)) >= 6);
        String live = new String(Files.readAllBytes(new File("src/main/assets/shaders/preview/lensfilter_live.glsl").toPath()), StandardCharsets.UTF_8);
        assertFalse("no ring of single taps", live.contains("ring1"));
        assertTrue("the levels B-spline sampled (no square highlights)", live.contains("textureLod(t, vec2(h0.x, h0.y), lod)") && live.contains("w3 / g1"));
        String prep = new String(Files.readAllBytes(new File("src/main/assets/shaders/preview/lensfilter_prep.glsl").toPath()), StandardCharsets.UTF_8);
        assertTrue(prep.contains("layout(location = 1) out vec4 LinOut"));
    }

    /**
     * Owner, 2026-10-28 («в видоискателе вижу эффект, на конечном фото нет»): the offered filters are the diffusion alone in the
     * viewfinder too - Glimmerglass (uLf 3) drew a star of diagonal streaks and Pro-Mist (uLf 4) a pastel the photo never has.
     */
    @Test public void theDiffusionFiltersShowNoStarOrPastelThePhotoLacks() throws Exception {
        assertEquals(3, LensFilters.index(LensFilters.GLIMMERGLASS));
        assertEquals(4, LensFilters.index(LensFilters.PEARLESCENT));
        String live = new String(Files.readAllBytes(new File("src/main/assets/shaders/preview/lensfilter_live.glsl").toPath()), StandardCharsets.UTF_8);
        assertFalse("no Glimmerglass star", live.contains("uLf == 3"));
        assertFalse("no Pro-Mist pastel", live.contains("uLf == 4"));
        for (String id : LensFilters.IDS) assertTrue(id, com.particlesdevs.photoncamera.processing.color.DiffusionModel.diffuses(id));
        assertTrue("the diffusion of every offered filter", live.contains("if (uLf <= 6) {"));
    }

    /** P300: the RAW viewfinder's filter pass reads the developed frame as a 2D texture with the same shaders. */
    @Test public void theRawViewfinderGetsTheSameShadersOnA2dTexture() throws Exception {
        for (String name : new String[]{"main_fs.glsl", "lensfilter_prep.glsl"}) {
            String src = new String(Files.readAllBytes(new File("src/main/assets/shaders/preview/" + name).toPath()), StandardCharsets.UTF_8);
            assertTrue(name, src.contains("samplerExternalOES") && src.contains("GL_OES_EGL_image_external_essl3"));
            String flat = LiveLensFilter.to2d(src);
            assertFalse(name, flat.contains("samplerExternalOES") || flat.contains("GL_OES_EGL_image_external"));
            assertTrue(name, flat.contains("uniform sampler2D sTexture;"));
        }
    }

    @Test public void theViewfinderShaderCarriesTheMarker() throws Exception {
        String main = new String(Files.readAllBytes(new File("src/main/assets/shaders/preview/main_fs.glsl").toPath()), StandardCharsets.UTF_8);
        assertTrue(main.contains(LiveLensFilter.MARKER));
        String spliced = LiveLensFilter.splice(main, "vec3 lensFilter(vec2 a, vec3 b, vec2 c, vec2 d, vec2 e) { return b; }");
        assertFalse(spliced.contains(LiveLensFilter.MARKER));
        assertTrue(spliced.contains("lensFilter(uv, color.rgb"));
    }
}
