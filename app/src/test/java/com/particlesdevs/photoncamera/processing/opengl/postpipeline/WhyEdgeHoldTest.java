package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P394 (owner, 2026-10-29: «нам нужно сделать грань максимально гладкой без зубчиков»): why/combine's edge hold - at a strong edge
 * the sharpened luma averaged along the edge and held within the full-size merge's 3 x 3 range. On by default in the photo
 * (WhyTone.edgeHold 1, scam_dev hybrid_why_edge_hold), only with the full-size merge and the sharpened luma, off (bit for bit) when
 * the uniform is unset (the viewfinder, the host checks).
 */
public class WhyEdgeHoldTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void thePhotoHoldsItsStrongEdges() throws IOException {
        final String glsl = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue(glsl.contains("#if EDGE_HOLD == 1\nuniform float edgeHoldU;"));
        assertTrue("off without the full-size merge or the uniform",
                glsl.contains("if (fullInputU != 1 || edgeHoldU <= 0.0) return yD;"));
        assertTrue("the sharpened luma only", glsl.contains("if (sharpU == 1 && bounded) yS = edgeHold(yS, xy, size, refDomain);"));
        final String src = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";
        final String why = read(src + "WhyCombine.java");
        assertTrue("its define only with the full-size merge (0: the shader before, token for token)",
                why.contains("if (WhyTone.edgeHold() > 0f && !st.inputReleased) glProg.setDefine(\"EDGE_HOLD\", 1);"));
        assertTrue(why.contains("glProg.setVar(\"edgeHoldU\", WhyTone.edgeHold());"));
        assertTrue("on by default", read(src + "WhyTone.java").contains("value(\"edge_hold\", 1f)"));
        assertTrue("the dev line", read("src/main/java/com/particlesdevs/photoncamera/settings/DevKeys.java").contains("\"hybrid_why_edge_hold\", \"1.0\""));
    }
}
