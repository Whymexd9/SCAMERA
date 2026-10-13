package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Random;

/** P121: the neutral of a Quad / Tetra frame measured on the frame, put on the camera's curve from plain-Bayer neutrals. */
public class MosaicNeutralTest {
    /** A BGGR mosaic of colour blocks {@code block} of a grey-ish scene lit with neutral (r, 1, b), ±4 % site gains and noise. */
    private static ByteBuffer mosaic(int w, int h, int block, float r, float b, long seed) {
        final ByteBuffer buf = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random rnd = new Random(seed);
        final float black = 64, range = 1023 - 64;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final int px = (x / block) & 1, py = (y / block) & 1;
            // BGGR: blue at (0,0), red at (1,1)
            final float colour = px == 0 && py == 0 ? b : px == 1 && py == 1 ? r : 1f;
            final float scene = 0.15f + 0.3f * (float) Math.abs(Math.sin(x * 0.01) * Math.cos(y * 0.013));
            final float site = 1f + 0.04f * (float) Math.sin(x * 7 + y * 3);
            final float v = black + range * scene * colour * site + (float) rnd.nextGaussian() * 2f;
            buf.putShort((y * w + x) * 2, (short) Math.max(0, Math.min(1023, Math.round(v))));
        }
        return buf;
    }

    @Test public void quadAndTetraNeutralIsMeasured() {
        final float[] black = {64, 64, 64, 64};
        for (int block : new int[]{2, 4}) {
            final float[] m = MosaicNeutral.measure(mosaic(1024, 768, block, 0.47f, 0.69f, block), 1024, 768, 2048, 3, black, 1023, block);
            assertNotNull(m);
            assertEquals("R/G block " + block, Math.log(0.47), Math.log(m[0]), 0.03);
            assertEquals("B/G block " + block, Math.log(0.69), Math.log(m[1]), 0.03);
        }
    }

    @Test public void clippedOrTinyFramesGiveNothing() {
        final float[] black = {64, 64, 64, 64};
        final ByteBuffer white = ByteBuffer.allocate(256 * 256 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 256 * 256; i++) white.putShort(i * 2, (short) 1023);
        assertNull(MosaicNeutral.measure(white, 256, 256, 512, 3, black, 1023, 2));
        assertNull(MosaicNeutral.measure(white, 4, 4, 8, 3, black, 1023, 2));
    }

    @Test public void neutralGoesOnTheCameraCurve() {
        // the camera's plain-Bayer neutrals from tungsten to daylight
        final ArrayList<float[]> curve = new ArrayList<>();
        for (float[] p : new float[][]{{0.80f, 0.35f}, {0.60f, 0.50f}, {0.47f, 0.69f}, {0.40f, 0.85f}})
            curve.add(new float[]{(float) Math.log(p[0]), (float) Math.log(p[1])});
        // a measurement a little off the curve (a coloured scene) lands on its nearest point
        final float[] q = MosaicNeutral.project(curve, (float) Math.log(0.50), (float) Math.log(0.66));
        assertTrue(q[2] < 0.1f);
        assertEquals(0.47, Math.exp(q[0]), 0.04);
        assertEquals(0.69, Math.exp(q[1]), 0.05);
        // beyond the ends: the end point
        final float[] end = MosaicNeutral.project(curve, (float) Math.log(1.5), (float) Math.log(0.2));
        assertEquals(0.80, Math.exp(end[0]), 1e-3);
    }
}
