package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P400 / P401 (owner, 2026-10-29, the ship's bridge: the crane and the tower behind the window washed out to the sky; «изучи что было на
 * версии 31091», «делай. При этом у нас остаётся пирамида Лапласа»): why/lpyr WINDOW - in a room with Bento headroom a daylit window
 * takes WhyTone.windowShare of build 31091's fusion (its weights and exposures, the same pyramid). The pixels: tools/check_why_window.py.
 */
public class WhyWindowTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static WhyAe.Result shot(float hr, float p50) {
        WhyAe.Result r = new WhyAe.Result();
        r.ae = 1.2798f;
        r.clip = 4f;
        r.hr = hr;
        r.p50 = p50;
        return r;
    }

    @Test public void onlyInARoomWithHeadroomAndAKnownExposure() {
        // the 14:22:07 shot: median EV 9.7 (p50 0.044 at the EV offset 14.18) - the full depth; the window from EV 10.5 to 12
        final float[] p = WhyFusion.windowParams(1f, shot(20.36f, 0.04395f), 14.18f);
        assertNotNull(p);
        assertEquals(1f, p[0], 1e-6f);
        final double shift = Math.log(1.2798) / Math.log(2.0) - 14.18;
        assertEquals(10.5 + shift, p[1], 1e-4);
        assertEquals(12.0 + shift, p[2], 1e-4);
        assertNull("no headroom", WhyFusion.windowParams(0.1f, shot(0f, 0.04395f), 14.18f));
        assertNull("a landscape: the median at EV 13", WhyFusion.windowParams(0.1f, shot(20.36f, (float) Math.pow(2.0, 13.0 - 14.18)), 14.18f));
        assertNull("exposure unknown", WhyFusion.windowParams(0.1f, shot(20.36f, 0.04395f), Float.NaN));
        assertNull("off", WhyFusion.windowParams(0f, shot(20.36f, 0.04395f), 14.18f));
        // half way between the room (EV 10) and the landscape (EV 11.5): half the depth
        assertEquals(0.5f, WhyFusion.windowParams(1f, shot(20.36f, (float) Math.pow(2.0, 10.75 - 14.18)), 14.18f)[0], 1e-4f);
    }

    @Test public void build31091sFusion() {
        // ArkAe.exposures / ArkFusion of build 31091: 2^(-1.5 effH), 1, 2^effS <= 8, 2^(2 effS) <= 16; centre 0.6, (0.8, 1, 0.7, 0.5)
        final WhyAe.Result r = shot(20.36f, 0.04395f);
        r.effH = 2.5916f;
        r.effS = 0.9994f;
        final float[] em = WhyFusion.exposures31(r);
        assertEquals(Math.pow(2.0, -1.5 * 2.5916), em[0], 1e-6);
        assertEquals(1f, em[1], 0f);
        assertEquals(Math.pow(2.0, 0.9994), em[2], 1e-5);
        assertEquals(Math.pow(2.0, 2 * 0.9994), em[3], 1e-5);
        r.effS = 3f;
        assertEquals(8f, WhyFusion.exposures31(r)[2], 0f);
        assertEquals(16f, WhyFusion.exposures31(r)[3], 0f);
        assertEquals(0.6f, WhyFusion.CENTER_31091, 0f);
        org.junit.Assert.assertArrayEquals(new float[]{0.8f, 1f, 0.7f, 0.5f}, WhyFusion.LAYERS_31091, 0f);
    }

    @Test public void theWindowLevelFollowsTheWidth() {
        assertEquals(5, WhyFusion.windowLevel(2048, 7));
        assertEquals(6, WhyFusion.windowLevel(4096, 8));
        assertEquals(3, WhyFusion.windowLevel(4096, 3));
        assertEquals(1, WhyFusion.windowLevel(16, 7));
    }

    @Test public void theShaderTermIsWiredAndOnByDefault() throws IOException {
        final String shader = read("src/main/assets/shaders/why/lpyr.glsl");
        assertTrue(shader.contains("#define WINDOW 0\n"));
        assertTrue(shader.contains("if (share > 0.0) fusedL = mix(fusedL, texelFetch(Fused31, xy, 0).r, share);"));
        final String fusion = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyFusion.java");
        assertTrue(fusion.contains("if (winLevel > 0) glProg.setDefine(\"WINDOW\", 1);"));
        assertTrue(fusion.contains("fused31 = pyramid(st, exposures31(r), LAYERS_31091.clone(), r.ae, CENTER_31091, new float[6], started,"));
        assertTrue(read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyTone.java").contains("value(\"window_31091\", 1f)"));
        assertTrue(read("src/main/java/com/particlesdevs/photoncamera/settings/DevKeys.java").contains("\"hybrid_why_window_31091\", \"1.0\""));
    }
}
