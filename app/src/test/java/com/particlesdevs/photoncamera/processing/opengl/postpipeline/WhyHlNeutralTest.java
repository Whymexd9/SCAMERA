package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P398 (owner, 2026-10-29, the ship's bridge: «света почему-то получаются кремовые»): why/combine hlNeutralU - a pale colour at the
 * top of the tone goes towards white at its brightest channel - on by default (WhyTone.hlNeutral 1, scam_dev hybrid_why_hl_neutral),
 * set by the photo (WhyCombine) and the RAW viewfinder (LiveWhyTone) alike. The pixels: tools/check_hl_neutral.py.
 */
public class WhyHlNeutralTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void thePhotoAndTheViewfinderTakePaleLightToWhite() throws IOException {
        final String src = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";
        final String shader = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue(shader.contains("#define HL_NEUTRAL 0\n"));
        assertTrue(shader.contains("#if HL_NEUTRAL == 1\nuniform float hlNeutralU;"));
        final String why = read(src + "WhyCombine.java"), live = read(src + "LiveWhyTone.java");
        assertTrue(why.contains("if (WhyTone.hlNeutral() > 0f) glProg.setDefine(\"HL_NEUTRAL\", 1);"));
        assertTrue(why.contains("glProg.setVar(\"hlNeutralU\", WhyTone.hlNeutral());"));
        assertTrue(live.contains("if (hlNeutral > 0f) defs.add(new String[]{\"HL_NEUTRAL\", \"1\"});"));
        assertTrue(live.contains("set(p, \"hlNeutralU\", hlNeutral);"));
        assertTrue("on by default", read(src + "WhyTone.java").contains("value(\"hl_neutral\", 1f)"));
        assertTrue("the dev line", read("src/main/java/com/particlesdevs/photoncamera/settings/DevKeys.java").contains("\"hybrid_why_hl_neutral\", \"1.0\""));
    }
}
