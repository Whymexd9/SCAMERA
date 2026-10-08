package com.particlesdevs.photoncamera.processing.color;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** P46: the Display P3 ICC profile (ICC.1:2022 v4.3 display profile) the P3 photos carry. */
public class IccProfilesTest {
    private static String fourcc(byte[] p, int at) {
        return new String(p, at, 4, StandardCharsets.US_ASCII);
    }

    /** Tag signature -> {offset, size}. */
    private static Map<String, int[]> tags(byte[] p) {
        final ByteBuffer b = ByteBuffer.wrap(p);
        final int n = b.getInt(128);
        final Map<String, int[]> out = new HashMap<>();
        for (int i = 0; i < n; i++) {
            final int at = 132 + 12 * i;
            out.put(fourcc(p, at), new int[]{b.getInt(at + 4), b.getInt(at + 8)});
        }
        return out;
    }

    private static double s15(byte[] p, int at) {
        return ByteBuffer.wrap(p).getInt(at) / 65536.0;
    }

    @Test
    public void header() {
        final byte[] p = IccProfiles.displayP3();
        final ByteBuffer b = ByteBuffer.wrap(p);
        assertEquals(p.length, b.getInt(0));
        assertEquals(0x04300000, b.getInt(8));
        assertEquals("mntr", fourcc(p, 12));
        assertEquals("RGB ", fourcc(p, 16));
        assertEquals("XYZ ", fourcc(p, 20));
        assertEquals("acsp", fourcc(p, 36));
        assertEquals(0, b.getInt(64)); // perceptual
        assertEquals(0.9642, s15(p, 68), 1e-4);
        assertEquals(1.0, s15(p, 72), 1e-4);
        assertEquals(0.8249, s15(p, 76), 1e-4);
        assertEquals(0, p.length % 4);
        assertTrue("small enough for one JPEG APP2 segment", p.length < IccEmbed.MAX_CHUNK);
    }

    @Test
    public void tagsAreInsideAndAligned() {
        final byte[] p = IccProfiles.displayP3();
        final Map<String, int[]> t = tags(p);
        for (String sig : new String[]{"desc", "cprt", "wtpt", "chad", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC"}) {
            final int[] e = t.get(sig);
            assertNotNull(sig, e);
            assertEquals(sig + " aligned", 0, e[0] % 4);
            assertTrue(sig + " inside", e[0] >= 132 + 12 * t.size() && e[0] + e[1] <= p.length);
        }
        assertEquals(10, t.size());
        assertArrayEquals("the TRCs share one curve", t.get("rTRC"), t.get("gTRC"));
        assertArrayEquals(t.get("rTRC"), t.get("bTRC"));
        final int[] desc = t.get("desc");
        assertEquals("mluc", fourcc(p, desc[0]));
        final ByteBuffer b = ByteBuffer.wrap(p);
        final int len = b.getInt(desc[0] + 20), off = b.getInt(desc[0] + 24);
        assertEquals("Display P3", new String(p, desc[0] + off, len, StandardCharsets.UTF_16BE));
    }

    @Test
    public void colorantsAreTheD50AdaptedP3Primaries() {
        final byte[] p = IccProfiles.displayP3();
        final Map<String, int[]> t = tags(p);
        // The values of Apple's / Android's Display P3 profiles (s15Fixed16).
        final double[][] expected = {{0.515121, 0.241196, -0.001053}, {0.291977, 0.692245, 0.041885}, {0.157104, 0.066574, 0.784073}};
        final String[] sigs = {"rXYZ", "gXYZ", "bXYZ"};
        double x = 0, y = 0, z = 0;
        for (int i = 0; i < 3; i++) {
            final int at = t.get(sigs[i])[0];
            assertEquals("XYZ ", fourcc(p, at));
            for (int c = 0; c < 3; c++) assertEquals(sigs[i] + " " + c, expected[i][c], s15(p, at + 8 + 4 * c), 6e-4);
            x += s15(p, at + 8);
            y += s15(p, at + 12);
            z += s15(p, at + 16);
        }
        // the colorants add up to the PCS white
        assertEquals(0.9642, x, 2e-4);
        assertEquals(1.0, y, 2e-4);
        assertEquals(0.8249, z, 2e-4);
        final int wt = t.get("wtpt")[0];
        assertEquals(0.9642, s15(p, wt + 8), 1e-4);
    }

    @Test
    public void chadIsBradfordD65ToD50() {
        final byte[] p = IccProfiles.displayP3();
        final int at = tags(p).get("chad")[0];
        assertEquals("sf32", fourcc(p, at));
        // ICC.1 Annex E example / Apple's profiles
        final double[] expected = {1.047882, 0.022918, -0.050217, 0.029586, 0.990478, -0.017075, -0.009247, 0.015075, 0.751678};
        for (int i = 0; i < 9; i++) assertEquals("chad " + i, expected[i], s15(p, at + 8 + 4 * i), 2e-4);
    }

    @Test
    public void trcIsTheSrgbParametricCurve() {
        final byte[] p = IccProfiles.displayP3();
        final int at = tags(p).get("rTRC")[0];
        assertEquals("para", fourcc(p, at));
        assertEquals(3, ByteBuffer.wrap(p).getShort(at + 8));
        final double[] expected = {2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045};
        for (int i = 0; i < 5; i++) assertEquals(expected[i], s15(p, at + 12 + 4 * i), 2e-5);
    }

    @Test
    public void profileIdIsTheMd5() throws Exception {
        final byte[] p = IccProfiles.displayP3();
        final byte[] copy = p.clone();
        for (int i = 44; i < 48; i++) copy[i] = 0;
        for (int i = 64; i < 68; i++) copy[i] = 0;
        for (int i = 84; i < 100; i++) copy[i] = 0;
        final byte[] md5 = MessageDigest.getInstance("MD5").digest(copy);
        for (int i = 0; i < 16; i++) assertEquals(md5[i], p[84 + i]);
        assertArrayEquals("deterministic", p, IccProfiles.displayP3());
        p[0] = 9;
        assertNotEquals("a copy each time", 9, IccProfiles.displayP3()[0]);
    }
}
