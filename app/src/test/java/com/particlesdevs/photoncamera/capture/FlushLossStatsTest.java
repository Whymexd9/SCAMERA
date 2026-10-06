package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** P27 (M6): the flush is skipped only for a camera that lost its first requests after a flush twice in a row. */
public class FlushLossStatsTest {
    @Before public void reset() { FlushLossStats.resetForTest(); }
    @After public void cleanup() { FlushLossStats.resetForTest(); }

    @Test public void x300UltraPatternTwiceSkipsTheFlushForThatCameraOnly() {
        // X300 Ultra log 265-325: 7 post-shutter requests, the four N (0-3) lost right after the flush, 4-6 arrived.
        FlushLossStats wide = FlushLossStats.of("3");
        wide.record(true, 7, Set.of(0, 1, 2, 3));
        assertFalse(wide.skipFlush());
        String line = wide.record(true, 7, Set.of(0, 1, 2, 3));
        assertTrue(wide.skipFlush());
        assertTrue(line, line.contains("skip the flush"));
        assertFalse(FlushLossStats.of("4").skipFlush());
    }

    @Test public void cleanFlushedShotClearsTheVotes() {
        FlushLossStats s = FlushLossStats.of("3");
        s.record(true, 7, Set.of(0, 1, 2, 3));
        s.record(true, 7, Set.of());
        s.record(true, 7, Set.of(0, 1));
        assertFalse(s.skipFlush());
    }

    @Test public void lossesThatAreNotLeadingOrWithoutFlushDoNotCount() {
        FlushLossStats s = FlushLossStats.of("5");
        for (int i = 0; i < 3; i++) s.record(true, 7, Set.of(2, 3));      // not the first requests
        for (int i = 0; i < 3; i++) s.record(false, 7, Set.of(0, 1, 2));  // not flushed
        for (int i = 0; i < 3; i++) s.record(true, 4, Set.of(0, 1, 2, 3)); // everything lost: nothing to compare with
        assertFalse(s.skipFlush());
    }
}
