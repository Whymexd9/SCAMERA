package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import com.particlesdevs.photoncamera.processing.LiveRawFrame;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P314: the RAW viewfinder's geometry - the visible crop (moved out of LiveRawRenderer.draw for the CPU AE, unchanged), the size
 * the ARK tone is drawn at (the crop's colour grid, never above the viewport) and how a frame of cell means is sampled.
 */
public class LiveRawGeometryTest {
    private static LiveRawFrame.Frame frame(int w, int h, int block, float[] crop) {
        LiveRawFrame.Frame f = new LiveRawFrame.Frame();
        f.width = w;
        f.height = h;
        f.cfaBlock = block;
        f.crop = crop;
        return f;
    }

    /** The crop of LiveRawRenderer.draw before P314, as it stood inline (the reference). */
    private static int[] before(LiveRawFrame.Frame f, int[] viewport, float[] rotation) {
        int period = f.cfaBlock * 2;
        int left = (int) (f.crop[0] * f.width) / period * period, top = (int) (f.crop[1] * f.height) / period * period;
        int cw = Math.min(f.width - left, (int) (f.crop[2] * f.width)) / period * period;
        int ch = Math.min(f.height - top, (int) (f.crop[3] * f.height)) / period * period;
        if (viewport[2] > 0 && viewport[3] > 0) {
            boolean quarterTurn = Math.abs(rotation[1]) > Math.abs(rotation[0]);
            float aspect = quarterTurn ? viewport[2] / (float) viewport[3] : viewport[3] / (float) viewport[2];
            if (cw / (float) Math.max(ch, 1) > aspect) {
                int fitted = Math.max(period, (int) (ch * aspect) / period * period);
                left += (cw - fitted) / 2 / period * period;
                cw = fitted;
            } else {
                int fitted = Math.max(period, (int) (cw / aspect) / period * period);
                top += (ch - fitted) / 2 / period * period;
                ch = fitted;
            }
        }
        return new int[]{left, top, Math.max(period, cw), Math.max(period, ch)};
    }

    @Test
    public void theVisibleCropIsTheOneTheDrawUsedBefore() {
        Random random = new Random(314);
        int[][] sizes = {{4096, 3072}, {2048, 1536}, {4080, 3072}, {1020, 768}, {8160, 6144}, {16, 8}, {4000, 3000}};
        int[][] viewports = {{0, 0, 1440, 1920}, {0, 0, 1920, 1440}, {0, 0, 1080, 2400}, {0, 0, 810, 1080}, {0, 0, 1, 1}, {0, 0, 0, 0}};
        for (int n = 0; n < 3000; n++) {
            int[] s = sizes[random.nextInt(sizes.length)];
            int block = new int[]{1, 2, 4}[random.nextInt(3)];
            float cw = 0.2f + 0.8f * random.nextFloat(), ch = 0.2f + 0.8f * random.nextFloat();
            float[] crop = {(1 - cw) * random.nextFloat(), (1 - ch) * random.nextFloat(), cw, ch};
            if (n % 7 == 0) crop = new float[]{0, 0, 1, 1};
            LiveRawFrame.Frame f = frame(s[0], s[1], block, crop);
            int[] vp = viewports[random.nextInt(viewports.length)];
            // the texture rotation (Matrix.setRotateM about z): cos, sin in elements 0, 1
            double angle = Math.toRadians(90 * random.nextInt(4));
            float[] rotation = new float[16];
            rotation[0] = rotation[5] = (float) Math.cos(angle);
            rotation[1] = (float) Math.sin(angle);
            rotation[4] = -rotation[1];
            boolean quarterTurn = Math.abs(rotation[1]) > Math.abs(rotation[0]);
            assertArrayEquals("case " + n, before(f, vp, rotation), LiveRawRenderer.visibleCrop(f, vp[2], vp[3], quarterTurn));
        }
    }

    @Test
    public void theToneIsDrawnAtTheCropsColourGrid() {
        // vivo X200 Ultra 2x ISZ: the Quad 4096 x 3072 binned to its cell means 2048 x 1536 (1024 x 768 colours), portrait
        // 1440 x 1920 (the frame's x along the screen's y): 9/16 of the viewport, not 1440 x 1920
        int[] crop = LiveRawRenderer.visibleCrop(frame(2048, 1536, 1, new float[]{0, 0, 1, 1}), 1440, 1920, false);
        assertArrayEquals(new int[]{810, 1080}, LiveRawRenderer.toneSize(1440, 1920, crop[2], crop[3], 1, false));
        // the same Quad unbinned («Full»: block 2) - the same colours
        crop = LiveRawRenderer.visibleCrop(frame(4096, 3072, 2, new float[]{0, 0, 1, 1}), 1440, 1920, false);
        assertArrayEquals(new int[]{810, 1080}, LiveRawRenderer.toneSize(1440, 1920, crop[2], crop[3], 2, false));
        // 4x Tetra 4080 x 3072 binned to 1020 x 768 (510 x 384 colours)
        crop = LiveRawRenderer.visibleCrop(frame(1020, 768, 1, new float[]{0, 0, 1, 1}), 1440, 1920, false);
        assertArrayEquals(new int[]{450, 600}, LiveRawRenderer.toneSize(1440, 1920, crop[2], crop[3], 1, false));
        // 1x Bayer 4096 x 3072 (2048 x 1536 colours) and the 50 / 200 MP FULLRES bins (4096 x 3072 / 4080 x 3072): the viewport
        crop = LiveRawRenderer.visibleCrop(frame(4096, 3072, 1, new float[]{0, 0, 1, 1}), 1440, 1920, false);
        assertArrayEquals(new int[]{1440, 1920}, LiveRawRenderer.toneSize(1440, 1920, crop[2], crop[3], 1, false));
        assertArrayEquals(new int[]{1440, 1920}, LiveRawRenderer.toneSize(1440, 1920, 4080, 3060, 1, false));
        // a landscape screen with a quarter turn: the frame's x along the screen's x
        crop = LiveRawRenderer.visibleCrop(frame(2048, 1536, 1, new float[]{0, 0, 1, 1}), 1920, 1440, true);
        assertArrayEquals(new int[]{1080, 810}, LiveRawRenderer.toneSize(1920, 1440, crop[2], crop[3], 1, true));
        // an empty viewport stays drawable
        assertArrayEquals(new int[]{1, 1}, LiveRawRenderer.toneSize(0, 0, 100, 100, 1, false));
    }

    /** Every tone size holds the crop's colours on both axes, keeps the viewport's aspect, and a zoom passes few sizes. */
    @Test
    public void theToneSizeNeverDropsColoursAndChangesInSteps() {
        Set<String> sizes = new HashSet<>();
        for (int zoom = 0; zoom <= 400; zoom++) {
            float share = 1f / (1f + zoom / 100f);   // 1x .. 5x within the 2x ISZ module
            float[] c = {(1 - share) / 2, (1 - share) / 2, share, share};
            for (boolean quarterTurn : new boolean[]{false, true}) {
                int vpW = quarterTurn ? 1920 : 1440, vpH = quarterTurn ? 1440 : 1920;
                for (int block : new int[]{1, 2, 4}) {
                    int[] crop = LiveRawRenderer.visibleCrop(frame(2048 * block, 1536 * block, block, c), vpW, vpH, quarterTurn);
                    int[] t = LiveRawRenderer.toneSize(vpW, vpH, crop[2], crop[3], block, quarterTurn);
                    int gx = (quarterTurn ? crop[2] : crop[3]) / (2 * block), gy = (quarterTurn ? crop[3] : crop[2]) / (2 * block);
                    assertTrue("zoom " + zoom + ": " + t[0] + "x" + t[1] + " holds " + gx + "x" + gy, t[0] >= Math.min(gx, vpW) && t[1] >= Math.min(gy, vpH));
                    assertTrue(t[0] <= vpW && t[1] <= vpH);
                    assertEquals((double) vpW / vpH, (double) t[0] / t[1], 2.0 / t[1]);
                    if (block == 1 && !quarterTurn) sizes.add(t[0] + "x" + t[1]);
                }
            }
        }
        assertTrue("a 1x - 5x zoom makes the targets anew at most at each step: " + sizes, sizes.size() <= LiveRawRenderer.TONE_STEPS / 2 + 1);
    }

    @Test
    public void aFrameOfCellMeansIsSampledAsItsMosaic() {
        LiveRawFrame.Frame f = frame(2048, 1536, 1, new float[]{0, 0, 1, 1});
        assertEquals("a plain Bayer frame: the range-filtered Bayer", 1, LiveRawRenderer.cfaMode(f));
        f.cellMean = true;
        assertEquals("cell means: one colour per 2 x 2 of them, as the mosaic", 0, LiveRawRenderer.cfaMode(f));
        assertEquals(0, LiveRawRenderer.cfaMode(frame(4096, 3072, 2, new float[]{0, 0, 1, 1})));
        assertEquals(0, LiveRawRenderer.cfaMode(frame(4080, 3072, 4, new float[]{0, 0, 1, 1})));
    }
}
