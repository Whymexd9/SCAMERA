package com.particlesdevs.photoncamera.processing;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P220: the merge worker's heartbeat as the user sees it: the share of the merge per pass (in the order the worker runs them,
 * the GPU strips most of it), never back within a job, handed to the ring of the running shot; the notification behind the
 * screen only after 10 s of merging and only with the permission already granted.
 */
public class MergeProgressTest {
    @After public void tearDown() {
        MergeProgress.listener = null;
        MergeProgress.end();
    }

    @Test public void passesRunInOrderAndTheStripsAreMostOfTheMerge() {
        float last = -1f;
        for (String pass : MergeProgress.PASSES) {
            final float start = MergeProgress.fraction(pass, 0f), end = MergeProgress.fraction(pass, 1f);
            assertTrue(pass, start >= last && end >= start && end <= 1f);
            last = start;
        }
        assertEquals(MergeProgress.PASSES.length, MergeProgress.STARTS.length);
        assertEquals(0.26f, MergeProgress.fraction("merge", 0f), 1e-6f);
        assertEquals(0.95f, MergeProgress.fraction("merge", 1f), 1e-6f);
        assertEquals(0.605f, MergeProgress.fraction("merge", .5f), 1e-6f);
        assertEquals("a pass ends where the next starts", MergeProgress.fraction("finish", 0f), MergeProgress.fraction("merge", 1f), 1e-6f);
        assertEquals(1f, MergeProgress.fraction("write", 1f), 1e-6f);
        assertEquals("share clamped", MergeProgress.fraction("align", 1f), MergeProgress.fraction("align", 7f), 1e-6f);
        assertEquals(-1f, MergeProgress.fraction("chroma", .5f), 0f);
    }

    @Test public void theShareNeverGoesBackAndReachesTheRing() {
        List<Float> ring = new ArrayList<>();
        MergeProgress.listener = ring::add;
        MergeProgress.update("merge", .5f);                // before a job: nothing
        assertTrue(ring.isEmpty());
        MergeProgress.begin(1000);
        assertTrue(MergeProgress.running());
        MergeProgress.update("align", .5f);
        MergeProgress.update("merge", .25f);
        MergeProgress.update("merge", .25f);               // the same again: no event
        MergeProgress.update("mosaic-split", 0f);          // the native merge failed over to the split: no step back
        MergeProgress.update("unknown-pass", 1f);
        MergeProgress.update("merge", .75f);
        assertEquals(3, ring.size());
        assertEquals(MergeProgress.fraction("align", .5f), ring.get(0), 1e-6f);
        assertEquals(MergeProgress.fraction("merge", .25f), ring.get(1), 1e-6f);
        assertEquals(MergeProgress.fraction("merge", .75f), ring.get(2), 1e-6f);
        assertEquals(ring.get(2), MergeProgress.done(), 1e-6f);
        assertEquals(4000, MergeProgress.runningMs(5000));
        // a retry starts over
        MergeProgress.begin(9000);
        assertEquals(0f, MergeProgress.done(), 0f);
        MergeProgress.update("align", 0f);
        assertEquals(4, ring.size());
        MergeProgress.listener = f -> { throw new IllegalStateException("a broken ring"); };
        MergeProgress.update("merge", .1f);                // never stops the merge
        MergeProgress.end();
        assertFalse(MergeProgress.running());
        assertEquals(0, MergeProgress.runningMs(20000));
    }

    @Test public void theNotificationOnlyBehindTheScreenAfterTenSecondsWithThePermission() {
        assertTrue(MergeProgress.showNotification(true, 10_000, false, true));
        assertTrue(MergeProgress.showNotification(true, 250_000, false, true));
        assertFalse("a short merge", MergeProgress.showNotification(true, 9_999, false, true));
        assertFalse("the app in front: the ring shows it", MergeProgress.showNotification(true, 60_000, true, true));
        assertFalse("never without the permission", MergeProgress.showNotification(true, 60_000, false, false));
        assertFalse("no merge", MergeProgress.showNotification(false, 60_000, false, true));
    }

    @Test public void theRingEventsCarryTheirValues() {
        assertTrue(ProcessingProgress.large().large);
        ProcessingProgress m = ProcessingProgress.merge(.4f);
        assertFalse(m.large);
        assertEquals(.4f, m.merge, 0f);
        assertEquals(1f, ProcessingProgress.merge(3f).merge, 0f);
        assertEquals(0f, ProcessingProgress.merge(-1f).merge, 0f);
    }
}
