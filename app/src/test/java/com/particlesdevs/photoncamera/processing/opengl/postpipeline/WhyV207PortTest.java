package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P402 (owner, 2026-10-29: «Перенеси то чего нам не хватает», «dezipper нам также перенеси», the ported rows «не в дев настройках»):
 * the reference app v2.07's settings in ours - the classic fusion layout's exposures, the matrix saturation, the pyramid denoise's
 * noise factor and its matrix inverse, the wiring of the tone's AGX_INSET / ARK_BASE / hrEaseU and of the dezipper, and none of
 * them in the developer page. The pixels: tools/check_why_v207.py, check_ms_denoise.py, check_hybrid_dezipper.py.
 */
public class WhyV207PortTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void classicLayoutExposures() {
        WhyAe.Result r = new WhyAe.Result();
        r.effH = 1f;
        r.effS = 1f;
        float[] em = WhyFusion.exposures207(r);
        assertArrayEquals(new float[]{(float) Math.pow(2, -2.5), 1f, (float) Math.pow(2, -1.2), (float) Math.pow(2, 1.8)}, em, 1e-6f);
        r.effS = 3f;
        assertEquals("the bright layer at most 16", 16f, WhyFusion.exposures207(r)[3], 0f);
    }

    @Test public void matrixSaturationKeepsTheLuminance() {
        assertNull("1: no matrix", WhyTone.cctSat(1f, null));
        float[] white = {1.1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0.9f};
        assertSame(white, WhyTone.cctSat(1f, white));
        float[] luma = {0.2126f, 0.7152f, 0.0722f};
        for (float k : new float[]{0.5f, 1.25f, 2f}) {
            float[] m = WhyTone.cctSat(k, null);
            for (int j = 0; j < 3; j++) {
                float y = 0f;
                for (int i = 0; i < 3; i++) y += luma[i] * m[i * 3 + j];
                assertEquals("Y of each column kept at " + k, luma[j], y, 1e-6f);
            }
            for (int i = 0; i < 3; i++) {
                float sum = m[i * 3] + m[i * 3 + 1] + m[i * 3 + 2];
                assertEquals("grey stays grey", 1f, sum, 1e-6f);
            }
            assertEquals("its own channel x k", k + (1f - k) * luma[0], m[0], 1e-6f);
        }
    }

    @Test public void pyramidDenoiseNoiseFactorAndInverse() {
        assertEquals(1f, WhyMsDenoise.modelFactor(0f, 0f, 1f), 0f);
        assertEquals(1f, WhyMsDenoise.modelFactor(1e-3f, 1e-5f, 0f), 0f);
        assertEquals("a clean frame: 0.4", 0.4f, WhyMsDenoise.modelFactor(1e-5f, 1e-7f, 1f), 1e-6f);
        assertEquals("a noisy one: up to 4", 4f, WhyMsDenoise.modelFactor(1e-2f, 1e-4f, 1.5f), 1e-6f);
        float[] m = {1.62f, -0.48f, -0.14f, -0.21f, 1.42f, -0.21f, 0.03f, -0.52f, 1.49f}, inv = WhyMsDenoise.inverse3(m);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) s += m[i * 3 + k] * inv[k * 3 + j];
                assertEquals(i == j ? 1.0 : 0.0, s, 1e-5);
            }
        assertNull(WhyMsDenoise.inverse3(new float[9]));
    }

    @Test public void wiredInThePhotoAndTheViewfinder() throws IOException {
        final String src = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";
        final String shader = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue(shader.contains("#define ARK_BASE 0\n") && shader.contains("#define AGX_INSET 0\n"));
        final String why = read(src + "WhyCombine.java"), live = read(src + "LiveWhyTone.java");
        assertTrue(why.contains("if (WhyTone.arkLayout()) glProg.setDefine(\"ARK_BASE\", 1);"));
        assertTrue(why.contains("if (WhyTone.insetStrength() < 0.999f) glProg.setDefine(\"AGX_INSET\", 1);"));
        assertTrue(why.contains("glProg.setVar(\"hrEaseU\", 1f - WhyTone.shoulderStrength());"));
        assertTrue(live.contains("if (arkBase) defs.add(new String[]{\"ARK_BASE\", \"1\"});"));
        assertTrue(live.contains("if (inset < 0.999f) defs.add(new String[]{\"AGX_INSET\", \"1\"});"));
        assertTrue(live.contains("WhyFusion.liveExposures(r)"));
        assertTrue("the pyramid denoise only when on", read(src + "PostPipeline.java").contains("if (WhyMsDenoise.enabled()) add(new WhyMsDenoise());"));
        assertTrue("the matrix saturation on the viewfinder too",
                read("src/main/java/com/particlesdevs/photoncamera/ui/camera/views/viewfinder/LiveRawRenderer.java").contains("lookWhite=WhyTone.withCctSat("));
        final String dev = read("src/main/java/com/particlesdevs/photoncamera/settings/DevKeys.java");
        for (String k : new String[]{"hybrid_why_hr_knee", "hybrid_why_hr_room", "hybrid_why_hr_strength", "hybrid_why_fusion_layout",
                "hybrid_why_hl_blend", "hybrid_why_cct_sat", "hybrid_dezipper", "hybrid_why_ms_denoise"})
            assertTrue("not a developer row: " + k, !dev.contains("\"" + k + "\""));
        final String xml = read("src/main/res/xml/preferences.xml");
        for (String k : new String[]{"pref_scam_hybrid_why_hr_strength", "pref_scam_hybrid_why_hr_knee", "pref_scam_hybrid_why_hr_room",
                "pref_scam_hybrid_why_fusion_layout", "pref_scam_hybrid_why_hl_blend", "pref_scam_hybrid_why_cct_sat",
                "pref_scam_hybrid_why_agx_inset", "pref_scam_hybrid_dezipper", "pref_scam_hybrid_why_ms_denoise"})
            assertTrue("a settings row: " + k, xml.contains("android:key=\"" + k + "\""));
    }
}
