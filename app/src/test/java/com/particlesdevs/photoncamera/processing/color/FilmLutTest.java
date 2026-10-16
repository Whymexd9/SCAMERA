package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** P134 «Плёночный LUT»: the .cube reader and the trilinear lookup the shader does. */
public class FilmLutTest {
    private static FilmLut read(String text) throws IOException {
        return FilmLut.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** A 2^3 cube: red fastest; a warm look (r +0.1, b -0.1 clamped). */
    private static String warm() {
        StringBuilder s = new StringBuilder("# made by hand\nTITLE \"Warm\"\nLUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n\n");
        for (int b = 0; b < 2; b++) for (int g = 0; g < 2; g++) for (int r = 0; r < 2; r++)
            s.append(Math.min(1, r + 0.1f)).append(' ').append(g).append(' ').append(Math.max(0, b - 0.1f)).append('\n');
        return s.toString();
    }

    @Test
    public void aCubeFileIsReadRedFastestWithItsTitleAndDomain() throws IOException {
        FilmLut lut = read(warm());
        assertNotNull(lut);
        assertEquals(2, lut.size);
        assertEquals("Warm", lut.title);
        assertEquals(24, lut.rgb.length);
        assertArrayEquals(new float[]{0.1f, 0f, 0f}, new float[]{lut.rgb[0], lut.rgb[1], lut.rgb[2]}, 1e-6f);
        assertArrayEquals("the second row is red 1", new float[]{1f, 0f, 0f}, new float[]{lut.rgb[3], lut.rgb[4], lut.rgb[5]}, 1e-6f);
        float[] mid = lut.sample(0.5f, 0.5f, 0.5f);
        assertArrayEquals(new float[]{0.55f, 0.5f, 0.45f}, mid, 1e-5f);
    }

    @Test
    public void theIdentityGivesTheInputBackAndADomainScalesTheInput() throws IOException {
        FilmLut id = FilmLut.identity(17);
        assertArrayEquals(new float[]{0.2f, 0.63f, 0.91f}, id.sample(0.2f, 0.63f, 0.91f), 1e-5f);
        assertArrayEquals("clamped to the cube", new float[]{1f, 0f, 0.5f}, id.sample(1.3f, -0.2f, 0.5f), 1e-5f);
        FilmLut range = read("LUT_3D_INPUT_RANGE 0 2\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n");
        assertNotNull(range);
        assertArrayEquals("input 0.5 is a quarter of the 0..2 domain", new float[]{0.25f, 0.25f, 0.25f}, range.sample(0.5f, 0.5f, 0.5f), 1e-5f);
    }

    @Test
    public void whatIsNoUsable3dLutIsRefused() throws IOException {
        assertNull("1D", read("LUT_1D_SIZE 4\n0 0 0\n0.3 0.3 0.3\n0.6 0.6 0.6\n1 1 1\n"));
        assertNull("too few rows", read("LUT_3D_SIZE 2\n0 0 0\n1 0 0\n"));
        assertNull("too many rows", read(warm() + "0 0 0\n"));
        assertNull("size 1", read("LUT_3D_SIZE 1\n0 0 0\n"));
        assertNull("size 66", read("LUT_3D_SIZE 66\n"));
        assertNull("not a number", read(warm().replace("0.1 0 0", "abc 0 0")));
        assertNull("NaN", read(warm().replace("0.1 0 0", "NaN 0 0")));
        assertNull("rows before the size", read("0 0 0\nLUT_3D_SIZE 2\n"));
        assertNull("empty domain", read(warm().replace("DOMAIN_MAX 1 1 1", "DOMAIN_MAX 0 1 1")));
        assertNull("nothing", read(""));
    }
}
