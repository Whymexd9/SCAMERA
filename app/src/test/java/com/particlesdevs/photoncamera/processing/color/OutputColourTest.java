package com.particlesdevs.photoncamera.processing.color;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** P46 colour matrices, luminance weights, H.273 codes and the GLSL constants of ark/combine.glsl. */
public class OutputColourTest {
    private static void near(double[] expected, double[] got, double tolerance) {
        assertEquals(expected.length, got.length);
        for (int i = 0; i < expected.length; i++) assertEquals("element " + i, expected[i], got[i], tolerance);
    }

    @Test
    public void srgbToDisplayP3MatchesThePublishedMatrix() {
        // e.g. Apple / W3C CSS Color 4: linear sRGB -> linear Display P3
        near(new double[]{0.8224621, 0.1775380, 0.0, 0.0331941, 0.9668058, 0.0, 0.0170827, 0.0723974, 0.9105199},
                OutputColour.srgbToP3(), 2e-6);
        near(new double[]{1.2249401, -0.2249404, 0.0, -0.0420569, 1.0420571, 0.0, -0.0196376, -0.0786361, 1.0982735},
                OutputColour.p3ToSrgb(), 2e-6);
    }

    @Test
    public void toBt2020MatchesItuRBt2087() {
        // ITU-R BT.2087 (BT.709 -> BT.2020 linear) and the P3 -> BT.2020 matrix of SMPTE / Apple
        near(new double[]{0.6274, 0.3293, 0.0433, 0.0691, 0.9195, 0.0114, 0.0164, 0.0880, 0.8956}, OutputColour.srgbToBt2020(), 1e-4);
        near(new double[]{0.7538, 0.1986, 0.0476, 0.0457, 0.9418, 0.0125, -0.0012, 0.0176, 0.9836}, OutputColour.p3ToBt2020(), 1e-4);
    }

    @Test
    public void whiteStaysWhiteAndTheMatricesInvert() {
        for (double[] m : new double[][]{OutputColour.srgbToP3(), OutputColour.p3ToSrgb(), OutputColour.srgbToBt2020(), OutputColour.p3ToBt2020()})
            near(new double[]{1, 1, 1}, OutputColour.multiply(m, new double[]{1, 1, 1}), 1e-12);
        near(new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, OutputColour.multiply(OutputColour.srgbToP3(), OutputColour.p3ToSrgb()), 1e-12);
        // sRGB inside P3: every sRGB primary has non-negative P3 coordinates; P3 green is outside sRGB
        for (double v : OutputColour.srgbToP3()) assertTrue(v >= -1e-12);
        assertTrue(OutputColour.multiply(OutputColour.p3ToSrgb(), new double[]{0, 1, 0})[0] < -0.2);
    }

    @Test
    public void luminanceWeights() {
        near(new double[]{0.2126, 0.7152, 0.0722}, OutputColour.luma(OutputColour.SRGB_XY), 1e-4);
        near(new double[]{0.2289746, 0.6917385, 0.0792869}, OutputColour.lumaP3(), 1e-6);
        near(new double[]{0.2627, 0.6780, 0.0593}, OutputColour.lumaBt2020(), 1e-4);
    }

    @Test
    public void shaderConstantsAreTheJavaMatrices() throws Exception {
        final String glsl = new String(Files.readAllBytes(new File("src/main/assets/shaders/ark/combine.glsl").toPath()), StandardCharsets.UTF_8);
        assertTrue(glsl.contains("#define P3_OUT 0"));
        checkMat3(glsl, "SRGB_TO_P3", OutputColour.srgbToP3());
        checkMat3(glsl, "P3_TO_SRGB", OutputColour.p3ToSrgb());
    }

    /** A GLSL mat3 constant (column-major) against a row-major Java matrix. */
    private static void checkMat3(String glsl, String name, double[] rowMajor) {
        final Matcher m = Pattern.compile("const mat3 " + name + " = mat3\\(([^;]*)\\);").matcher(glsl);
        assertTrue(name, m.find());
        final String[] parts = m.group(1).replaceAll("\\s+", "").split(",");
        assertEquals(9, parts.length);
        for (int c = 0; c < 3; c++)
            for (int r = 0; r < 3; r++) assertEquals(name + " r" + r + " c" + c, rowMajor[3 * r + c], Double.parseDouble(parts[3 * c + r]), 1e-9);
    }

    @Test
    public void spaceParsesTheStoredValue() {
        assertEquals(OutputColour.Space.SRGB, OutputColour.Space.parse(null));
        assertEquals(OutputColour.Space.SRGB, OutputColour.Space.parse("adobe"));
        assertEquals(OutputColour.Space.SRGB, OutputColour.Space.parse("srgb"));
        assertEquals(OutputColour.Space.DISPLAY_P3, OutputColour.Space.parse(" P3 "));
        assertEquals("srgb", OutputColour.Space.SRGB.value);
        assertEquals("p3", OutputColour.Space.DISPLAY_P3.value);
    }

    @Test
    public void signals() {
        final OutputColour.Signal srgb = OutputColour.Signal.SRGB;
        assertTrue(srgb.isDefault());
        assertFalse(srgb.hdr());
        assertNull(srgb.icc());
        assertEquals(srgb, OutputColour.Signal.of(OutputColour.Space.SRGB));
        final OutputColour.Signal p3 = OutputColour.Signal.of(OutputColour.Space.DISPLAY_P3);
        assertFalse(p3.isDefault());
        assertEquals(12, p3.primaries);
        assertEquals(13, p3.transfer);
        assertEquals(1, p3.matrix);
        assertTrue(p3.fullRange);
        assertArrayEquals(IccProfiles.displayP3(), p3.icc());
        final OutputColour.Signal hlg = OutputColour.Signal.HLG;
        assertTrue(hlg.hdr());
        assertFalse(hlg.isDefault());
        assertEquals(9, hlg.primaries);
        assertEquals(18, hlg.transfer);
        assertEquals(9, hlg.matrix);
        assertNull(hlg.icc());
        assertEquals("nclx 9/18/9 full", hlg.toString());
        assertEquals(1, OutputColour.primariesCode(OutputColour.Space.SRGB));
        assertEquals(12, OutputColour.primariesCode(OutputColour.Space.DISPLAY_P3));
    }

    @Test
    public void srgbTransferRoundTrip() {
        for (int i = 0; i <= 255; i++) {
            final double v = i / 255.0;
            assertEquals(v, OutputColour.linearToSrgb(OutputColour.srgbToLinear(v)), 1e-12);
        }
        assertEquals(0.214041, OutputColour.srgbToLinear(0.5), 1e-6);
        assertEquals(0.0, OutputColour.srgbToLinear(-0.1), 0);
    }
}
