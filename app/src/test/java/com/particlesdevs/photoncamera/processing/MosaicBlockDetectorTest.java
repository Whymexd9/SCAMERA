package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

public class MosaicBlockDetectorTest {
    private static final int W = 640, H = 480, BLACK = 64;

    /** A scene (gradient + texture) through a colour-block CFA of the given block, with shot-like noise and per-site gain. */
    private static ByteBuffer mosaic(int block, int stridePad, double siteSpread, boolean grey) {
        return mosaic(block, stridePad, siteSpread, grey, false);
    }

    /** bars: the left 30 % of the frame is a high-contrast bar chart of 2 and 4 px periods (a period dividing 8). */
    private static ByteBuffer mosaic(int block, int stridePad, double siteSpread, boolean grey, boolean bars) {
        Random r = new Random(block * 31L + stridePad);
        int stride = (W + stridePad) * 2;
        ByteBuffer b = ByteBuffer.allocateDirect(stride * H).order(ByteOrder.LITTLE_ENDIAN);
        double[] colour = grey ? new double[]{1, 1, 1} : new double[]{0.45, 1.0, 0.7}; // R, G, B response (no WB)
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int bx = (x / block) & 1, by = (y / block) & 1, p = (by << 1) | bx;
            int c = p == 0 ? 0 : p == 3 ? 2 : 1;
            double scene = 300 + 200 * x / W + 80 * Math.sin(x * 0.07) * Math.cos(y * 0.05);
            if (bars && x < W * 3 / 10) scene = (y < H / 2 ? (x / 1) % 2 : (x / 2) % 2) == 0 ? 60 : 800;
            double site = 1 + siteSpread * (((x % block) + 2 * (y % block)) % 3 - 1);
            double v = BLACK + scene * colour[c] * site + r.nextGaussian() * Math.sqrt(scene * colour[c] * 0.5 + 4);
            b.putShort(y * stride + x * 2, (short) Math.max(0, Math.min(1023, Math.round(v))));
        }
        return b;
    }

    @Test
    public void plainQuadAndTetraStreamsAreTold() {
        for (int block : new int[]{1, 2, 4}) {
            MosaicBlockDetector.Result r = MosaicBlockDetector.detect(mosaic(block, 0, 0.01, false), W, H, W * 2, BLACK, 8);
            assertTrue("block " + block + ": " + r, r.confident);
            assertEquals("block " + block + ": " + r, block, r.block);
        }
    }

    @Test
    public void periodicBarsOverAThirdOfTheFrameDoNotTurnTheAnswer() {
        for (int block : new int[]{1, 2, 4}) {
            MosaicBlockDetector.Result r = MosaicBlockDetector.detect(mosaic(block, 0, 0.01, false, true), W, H, W * 2, BLACK, 1023f, 8);
            assertTrue("block " + block + ": " + r, r.confident);
            assertEquals("block " + block + ": " + r, block, r.block);
        }
    }

    @Test
    public void rowPaddingAndSparseBandsDoNotChangeTheAnswer() {
        MosaicBlockDetector.Result r = MosaicBlockDetector.detect(mosaic(2, 32, 0.02, false), W, H, (W + 32) * 2, BLACK, 32);
        assertTrue(r.toString(), r.confident);
        assertEquals(2, r.block);
    }

    @Test
    public void aStreamWithoutColourContrastIsNotCalledAMosaic() {
        MosaicBlockDetector.Result r = MosaicBlockDetector.detect(mosaic(2, 0, 0, true), W, H, W * 2, BLACK, 8);
        assertFalse(r.toString(), r.confident);
        assertEquals(1, r.block);
    }

    @Test
    public void threeAgreeingMeasurementsFixTheBlockAndItIsRememberedPerStream() {
        MosaicStream.reset();
        MosaicStream.startSession("cam2|4096x3072|mode=29;");
        assertEquals(0, MosaicStream.block());
        MosaicBlockDetector.Result quad = MosaicBlockDetector.detect(mosaic(2, 0, 0.01, false), W, H, W * 2, BLACK, 8);
        assertEquals(0, MosaicStream.observe(quad));
        assertEquals(0, MosaicStream.observe(MosaicBlockDetector.detect(mosaic(2, 0, 0, true), W, H, W * 2, BLACK, 8))); // not confident: no vote
        assertEquals(0, MosaicStream.observe(quad));
        assertEquals(2, MosaicStream.observe(quad));
        assertFalse(MosaicStream.wantsFrame());
        MosaicStream.startSession("cam2|8192x6144|");
        assertEquals(0, MosaicStream.block());
        MosaicStream.startSession("cam2|4096x3072|mode=29;");
        assertEquals("remembered for the same module and requests", 2, MosaicStream.block());
        MosaicStream.reset();
    }
}
