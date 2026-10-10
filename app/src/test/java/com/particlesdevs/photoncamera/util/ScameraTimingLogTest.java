package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P80: the timing file keeps the lines that carry times. */
public class ScameraTimingLogTest {
    @Test
    public void timingLinesAreKept() {
        assertTrue(ScameraTimingLog.isTiming("HYBRID GPU ms: flags=296 mark=1265"));
        assertTrue(ScameraTimingLog.isTiming("Node:ScamHdrDenoise elapsed:2544 ms"));
        assertTrue(ScameraTimingLog.isTiming("SCAM WORKER TIMELINE ms: cre=8"));
        assertTrue(ScameraTimingLog.isTiming("JPEG encode ms=654"));
        assertTrue(ScameraTimingLog.isTiming("SHOT TIMELINE ms: shutter=0 submit=182"));
    }

    @Test
    public void otherLinesAreNot() {
        assertFalse(ScameraTimingLog.isTiming("HYBRID BENTO: not applied (not enough clipping)"));
        assertFalse(ScameraTimingLog.isTiming(null));
    }
}
