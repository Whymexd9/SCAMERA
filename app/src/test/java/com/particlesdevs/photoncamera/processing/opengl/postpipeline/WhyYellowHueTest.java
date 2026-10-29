package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P395 (owner, 2026-10-29: the yellow «Лента» facade orange-ochre beside ArkCam's yellow): why/combine yellowHueU - the saturated
 * orange-yellow scene hues turned up to 8 deg towards yellow - on by default on vivo (WhyTone.yellowHue 1, scam_dev hybrid_why_yellow_hue),
 * set by the photo (WhyCombine) and the RAW viewfinder (LiveWhyTone) alike. The pixels: tools/check_yellow_hue.py.
 */
public class WhyYellowHueTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void thePhotoAndTheViewfinderTurnTheYellows() throws IOException {
        final String src = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";
        assertTrue(read("src/main/assets/shaders/why/combine.glsl").contains("#if YELLOW_HUE == 1\nuniform float yellowHueU;"));
        final String why = read(src + "WhyCombine.java"), live = read(src + "LiveWhyTone.java");
        assertTrue(why.contains("if (WhyTone.yellowHue() > 0f) glProg.setDefine(\"YELLOW_HUE\", 1);"));
        assertTrue(why.contains("glProg.setVar(\"yellowHueU\", WhyTone.yellowHue());"));
        assertTrue(live.contains("if (yellowHue > 0f) defs.add(new String[]{\"YELLOW_HUE\", \"1\"});"));
        assertTrue(live.contains("set(p, \"yellowHueU\", yellowHue);"));
        // P407: on by default on vivo only (its matrix's fault; the OPPO ISP matrix renders the yellows at the reference's hue)
        assertTrue("on by default on vivo", read(src + "WhyTone.java").contains(
                "value(\"yellow_hue\", \"vivo\".equalsIgnoreCase(android.os.Build.MANUFACTURER) ? 1f : 0f)"));
        assertTrue("the dev line", read("src/main/java/com/particlesdevs/photoncamera/settings/DevKeys.java").contains("\"hybrid_why_yellow_hue\", \"1.0\""));
    }
}
