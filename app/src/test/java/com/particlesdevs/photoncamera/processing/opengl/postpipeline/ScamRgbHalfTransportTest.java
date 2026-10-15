package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScamRgbHalfTransportTest {
    @Test public void sdk50True2xUsesHalfDespiteFloat32ByteCountOverflowingInt() {
        long halfBytes = 16384L * 12288 * 6;
        assertTrue(halfBytes <= Integer.MAX_VALUE);
        assertTrue(16384L * 12288 * 12 > Integer.MAX_VALUE);
        assertTrue(ScamRgb.halfInput(true, true, 16384, 12288, halfBytes));
        assertFalse(ScamRgb.halfInput(true, true, 16384, 12288, halfBytes - 1));
    }

    @Test public void float32AndUntaggedBuffersKeepTheirOriginalInterpretation() {
        long pixels = 8192L * 6144;
        assertTrue(ScamRgb.halfInput(true, true, 8192, 6144, pixels * 6));
        assertFalse(ScamRgb.halfInput(true, true, 8192, 6144, pixels * 12));
        assertFalse(ScamRgb.halfInput(true, false, 8192, 6144, pixels * 6));
        assertFalse(ScamRgb.halfInput(false, true, 8192, 6144, pixels * 6));
    }

    @Test public void paddedPostAbBufferAndInvalidDimensionsRemainDistinguishable() {
        assertTrue(ScamRgb.halfInput(true, true, 64, 64, 64L * 64 * 6 + 64));
        assertFalse(ScamRgb.halfInput(true, true, 0, 64, 64));
        assertFalse(ScamRgb.halfInput(true, true, -64, -64, 64L * 64 * 6));
        assertFalse(ScamRgb.halfInput(true, true, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
