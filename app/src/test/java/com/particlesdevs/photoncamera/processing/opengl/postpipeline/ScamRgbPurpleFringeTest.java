package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P399 (owner, 2026-10-29, the ship's bridge: «артефакты на краях окна и на приспособлении посреди окна»): scamhdr/scamrgb purpleU - the
 * lens's violet / magenta fringe on the dark side of an edge against a bright, not violet light goes neutral at its luminance - on by
 * default (scam_dev hybrid_highlight_purple), the per-channel route only. The pixels: tools/check_purple_fringe.py.
 */
public class ScamRgbPurpleFringeTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void theFringeAgainstABrightLightGoesNeutral() throws IOException {
        final String shader = read("src/main/assets/shaders/scamhdr/scamrgb.glsl");
        assertTrue(shader.contains("uniform float purpleU;"));
        assertTrue(shader.contains("if (purpleU > 0.0 && hlModeU == 1) o = purpleFringe(o);"));
        final String rgb = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamRgb.java");
        assertTrue("on by default", rgb.contains("glProg.setVar(\"purpleU\",Math.max(0f,Math.min(1f,PreferenceKeys.hybridValue(\"highlight_purple\",1f))));"));
        assertTrue(read("src/main/java/com/particlesdevs/photoncamera/settings/DevKeys.java").contains("\"hybrid_highlight_purple\", \"1.0\""));
    }
}
