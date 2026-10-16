package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;

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
}
