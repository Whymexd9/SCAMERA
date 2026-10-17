package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class LiveRawQuadBinTest {
    @Test public void tetraCellsAverageAllSixteenSitesWithoutReadingPaddingOrMovingTheInput() {
        int stride = 24, offset = 6;
        ByteBuffer data = ByteBuffer.allocateDirect(offset + stride * 8).order(ByteOrder.nativeOrder());
        data.position(offset);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
            data.putShort(offset + y * stride + x * 2, (short) (100 + (y / 4 * 2 + x / 4) * 100 + y % 4 * 4 + x % 4));
        ByteBuffer result = LiveRawFrame.binMosaic(data, 8, 8, stride, 0x20, 4).order(ByteOrder.nativeOrder());
        assertEquals(offset, data.position());
        assertEquals(8, result.remaining());
        for (int i = 0; i < 4; i++) assertEquals(108 + i * 100, result.getShort(i * 2) & 0xffff);
    }
    /** P179: a 200 MP Tetra viewfinder frame takes each block's central 2 x 2 (a quarter of the reads), one site per block. */
    @Test public void sparseTetraCellsAverageTheirCentralFourSites() {
        int stride = 24, offset = 6;
        ByteBuffer data = ByteBuffer.allocateDirect(offset + stride * 8).order(ByteOrder.nativeOrder());
        data.position(offset);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
            data.putShort(offset + y * stride + x * 2, (short) (100 + (y / 4 * 2 + x / 4) * 100 + y % 4 * 4 + x % 4 + (y % 4 == 0 ? 50 : 0)));
        ByteBuffer result = LiveRawFrame.binMosaic(data, 8, 8, stride, 0x20, 4, true).order(ByteOrder.nativeOrder());
        assertEquals(offset, data.position());
        // the centre (5, 6, 9, 10) of each block; its outer row (+50 here) is not read
        for (int i = 0; i < 4; i++) assertEquals(108 + i * 100, result.getShort(i * 2) & 0xffff);
    }

    /** P181 (owner: «Для 50мп сделай тоже упрощенный видоискатель»): a 50 MP Quad viewfinder frame takes one site per cell. */
    @Test public void sparseQuadCellsTakeOneSiteEach() {
        int stride = 20, offset = 4;
        ByteBuffer data = ByteBuffer.allocateDirect(offset + stride * 4).order(ByteOrder.nativeOrder());
        data.position(offset);
        int[][] cell = {{100, 200}, {300, 400}};
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
            data.putShort(offset + y * stride + x * 2, (short) (cell[y / 2][x / 2] + (x % 2) * 7 + (y % 2) * 13));
        ByteBuffer result = LiveRawFrame.binMosaic(data, 4, 4, stride, 0x20, 2, true).order(ByteOrder.nativeOrder());
        assertEquals(offset, data.position());
        assertEquals(8, result.remaining());
        for (int i = 0; i < 4; i++) assertEquals("the top left site of cell " + i, cell[i / 2][i % 2], result.getShort(i * 2) & 0xffff);
    }

    @Test public void onlyTheFullResolutionViewfindersAreSparse() {
        assertTrue("200 MP Tetra", LiveRawFrame.sparseBin(16320, 12288, 4));
        assertTrue("50 MP Quad (main, ultra-wide)", LiveRawFrame.sparseBin(8192, 6144, 2));
        assertTrue("50 MP Quad (telephoto)", LiveRawFrame.sparseBin(8160, 6144, 2));
        assertFalse("a 12 MP ISZ Quad", LiveRawFrame.sparseBin(4080, 3072, 2));
        assertFalse("a 12 MP ISZ Tetra", LiveRawFrame.sparseBin(4080, 3072, 4));
        assertFalse("a 4x4 test frame", LiveRawFrame.sparseBin(4, 4, 2));
    }

    @Test
    public void quadCellsBecomeOneBayerSiteEach() {
        // 4x4 plain 16-bit Quad-Bayer: four 2x2 cells of one colour each, values 100/200/300/400 with a small spread
        int w = 4, h = 4;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        int[][] cell = {{100, 200}, {300, 400}};
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            b.putShort((y * w + x) * 2, (short) (cell[y / 2][x / 2] + (x % 2) + 2 * (y % 2)));
        ByteBuffer out = LiveRawFrame.binQuad(b, w, h, w * 2, 0x20);
        java.nio.ShortBuffer s = out.order(ByteOrder.nativeOrder()).asShortBuffer();
        // mean of +0,+1,+2,+3 = +1.5, rounded half up -> +2
        assertEquals(102, s.get(0));
        assertEquals(202, s.get(1));
        assertEquals(302, s.get(2));
        assertEquals(402, s.get(3));
    }
    /** P188: the 50 MP viewfinder bins every frame when binning is fast; 200 MP keeps its 150 ms; a slow bin throttles itself. */
    @Test public void binIntervalFollowsTheCostBelow100MpAndStays150msAbove() {
        assertEquals(150_000_000L, LiveRawFrame.binIntervalNs(16320L * 12288, 5_000_000L));
        assertEquals(10_000_000L, LiveRawFrame.binIntervalNs(8192L * 6144, 5_000_000L));
        assertEquals("a fast bin: no wait at 30 fps", true, LiveRawFrame.binIntervalNs(8192L * 6144, 8_000_000L) < 33_000_000L);
        assertEquals(150_000_000L, LiveRawFrame.binIntervalNs(8192L * 6144, 120_000_000L));
        assertEquals(0L, LiveRawFrame.binIntervalNs(8192L * 6144, 0L));
    }
}
