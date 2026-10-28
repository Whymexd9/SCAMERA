package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P378 review: the sky term's wiring - when WhySky builds no mask (WhySky.skip), the mask's size (maskBox), and that every SKY
 * define (the photo's combine and fusion, the viewfinder's combine) comes only with its mask or cells; the fine mask is closed on
 * a failure after it was made.
 */
public class WhySkyTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static final String SRC = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";

    @Test
    public void whatGetsNoSkyTerm() {
        assertEquals("the switch off: nothing logged", "", WhySky.skip(0f, false, false, false, false));
        assertEquals("", WhySky.skip(Float.NaN, false, false, false, false));
        assertEquals("a film simulation", WhySky.skip(1f, true, false, false, false));
        assertEquals("post_ab old run", WhySky.skip(1f, false, true, false, false));
        assertTrue(WhySky.skip(1f, false, false, true, false).startsWith("a lab fragment"));
        assertNull("a lab fragment with the whole frame's cells: built", WhySky.skip(1f, false, false, true, true));
        assertNull("the photo: built", WhySky.skip(1f, false, false, false, false));
        assertNull(WhySky.skip(0.5f, false, false, false, true));
    }

    @Test
    public void theMaskKeepsItsLongerSideAt2048() {
        assertEquals(1, WhySky.maskBox(2048, 1536));
        assertEquals(1, WhySky.maskBox(1000, 750));
        assertEquals(2, WhySky.maskBox(4096, 3072));
        assertEquals(4, WhySky.maskBox(8160, 6120));
        assertEquals(2, WhySky.maskBox(2049, 1537));
    }

    @Test
    public void everySkyDefineComesWithItsMask() throws IOException {
        final String combine = read(SRC + "WhyCombine.java");
        assertTrue("the photo's combine: SKY with the mask only", combine.contains("if (st.sky != null) glProg.setDefine(\"SKY\", 1);"));
        assertEquals("one SKY define in WhyCombine", 1, count(combine, "setDefine(\"SKY\""));
        assertTrue("its uniforms with the mask only", combine.contains("if (st.sky != null) {\n                // P378: L2 / L3 keep the backlit sky's level"));
        final String fusion = read(SRC + "WhyFusion.java");
        assertTrue("the fusion's SKY with the guided share and the mask", fusion.contains("final boolean sky = guidedF != null && st.sky != null;")
                && fusion.contains("if (sky) glProg.setDefine(\"SKY\", 1);"));
        assertEquals(1, count(fusion, "setDefine(\"SKY\""));
        final String live = read(SRC + "LiveWhyTone.java");
        assertTrue("the viewfinder's SKY with its cells, vf_why 1", live.contains("if (withSky && point) defs.add(new String[]{\"SKY\", \"1\"});"));
        assertTrue("its draws take it with a texture shown", live.contains("skyOnDraw = skyWanted && sky.shown() && skyTex != 0;")
                && live.contains("skyOnDraw = uploadSky(view);"));
        assertTrue("never with a film", live.contains("skyWanted = WhyTone.sky() > 0f && filmNow == null && mode() == POINT;"));
    }

    @Test
    public void theFineMaskIsClosedOnAFailure() throws IOException {
        final String sky = read(SRC + "WhySky.java");
        final String build = WhyCombineUniformParityTest.method(sky, "static void build(");
        assertTrue("closed in finally unless handed over", build.contains("if (mask != null && st.sky != mask) mask.close();"));
        assertTrue("handed over after its draw", build.indexOf("st.sky = mask;") > build.indexOf("glProg.drawBlocks(mask);"));
        final String fusion = read(SRC + "WhyFusion.java");
        assertTrue("a failure: no sky term, not a failed shot", fusion.contains("Log.w(\"SCAM_PIPELINE\", \"WHY sky: failed (\" + e + \"), no sky term\");"));
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }
}
