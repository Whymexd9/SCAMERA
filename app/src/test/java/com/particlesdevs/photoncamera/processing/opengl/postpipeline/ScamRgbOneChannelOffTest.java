package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import org.junit.Test;

/**
 * P58 (OnePlus 15, 2026-10-08): R and G of a blown Bento sky clip at 0.915 k (a plateau below the worker's flag threshold,
 * no ultrashort clip flagged), B has no plateau. Before: B "off" filled with k, so B stayed below its level next to recovered
 * R / G (pink sky). Now: B takes its own sampled maximum (never below the measured clips, never above k) and the
 * unflagged-clip gate is set.
 */
public class ScamRgbOneChannelOffTest {
    private static final float K = 2.708499f;

    private static ByteBuffer scene(float rClip, float gClip, float bMax, boolean bPlateau) {
        final int n = 600_000;
        ByteBuffer bb = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder());
        FloatBuffer f = bb.asFloatBuffer();
        Random rnd = new Random(58);
        for (int i = 0; i < n; i++) {
            boolean sky = i % 5 == 0;
            float r, g, b;
            if (sky) {
                r = rClip * (1f - 0.002f * rnd.nextFloat());
                g = gClip * (1f - 0.002f * rnd.nextFloat());
                b = bPlateau ? bMax * (1f - 0.002f * rnd.nextFloat()) : (0.55f + 0.38f * rnd.nextFloat()) * bMax / 0.93f;
            } else {
                r = 0.2f * rnd.nextFloat(); g = 0.3f * rnd.nextFloat(); b = 0.2f * rnd.nextFloat();
            }
            f.put(r).put(g).put(b);
        }
        return bb;
    }

    @Test
    public void aChannelWithoutPlateauTakesItsOwnMaximumAndTheGateIsSet() {
        ScamRgb.ChannelClip cc = ScamRgb.channelClip(scene(0.915f * K, 0.917f * K, 0.93f * K, false), true, K, 0f, true, 1);
        assertEquals(0.915f * K, cc.hi[0], 0.01f * K);
        assertEquals(0.917f * K, cc.hi[1], 0.01f * K);
        assertTrue("B above the measured clips: " + cc.hi[2], cc.hi[2] >= Math.max(cc.hi[0], cc.hi[1]));
        assertTrue("B at most k: " + cc.hi[2], cc.hi[2] <= K);
        assertEquals(cc.max[2], cc.hi[2], 1e-4f);
        assertTrue(cc.hiUnflagged);
    }

    @Test
    public void flaggedUltrashortClipOrAPlateauAtKKeepsTheOldLevels() {
        // the worker flagged ultrashort clipping: no gate, a channel without a plateau still takes the nominal k
        ScamRgb.ChannelClip flagged = ScamRgb.channelClip(scene(0.99f * K, 0.99f * K, 0.5f * K, false), true, K, 0.1f, true, 1);
        assertFalse(flagged.hiUnflagged);
        assertEquals(K, flagged.hi[2], 1e-4f);
        // all three at about k: plateau levels as before, no gate (above 0.975 k)
        ScamRgb.ChannelClip high = ScamRgb.channelClip(scene(0.99f * K, 0.99f * K, 0.99f * K, true), true, K, 0f, true, 1);
        assertFalse(high.hiUnflagged);
        assertEquals(0.99f * K, high.hi[2], 0.01f * K);
    }
}
