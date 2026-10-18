package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * P158: the processing ring round the gallery card: it creeps on by time (an ease-out over the last shot's time, never to the
 * end), follows the pipeline's milestones, closes and fades when the shot is done, and starts over for a shot queued behind.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ProcessingRingTest {
    private final Canvas canvas = new Canvas(Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888));

    private ProcessingRing ring() {
        ProcessingRing ring = new ProcessingRing(RuntimeEnvironment.getApplication());
        ring.layout(0, 0, 120, 120);
        return ring;
    }

    /** {@code frames} frames of 16 ms. */
    private void frames(ProcessingRing ring, int frames) {
        for (int i = 0; i < frames; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            ring.draw(canvas);
        }
    }

    @Test
    public void theTimeEstimateCreepsOnAndNeverReachesTheEnd() {
        assertEquals(0f, ProcessingRing.byTime(0, 3000), 1e-6f);
        float last = 0f;
        for (long t = 100; t <= 30000; t += 100) {
            float p = ProcessingRing.byTime(t, 3000);
            assertTrue("rises", t <= 9000 ? p > last : p >= last);   // later only float rounding is left
            assertTrue("stops short of the end", p <= ProcessingRing.TIME_CAP && p < 1f);
            last = p;
        }
        assertEquals("about 0.85 of the ring at the last shot's time", .85f, ProcessingRing.byTime(3000, 3000), .02f);
    }

    @Test
    public void milestonesPullTheRingAheadOfTheTimeAndTheEndClosesIt() {
        ProcessingRing ring = ring();
        ring.start();
        assertTrue(ring.isRunning());
        assertEquals(View.VISIBLE, ring.getVisibility());
        frames(ring, 5);
        float early = ring.shown();
        assertTrue("moving: " + early, early > 0f && early < .2f);
        ring.setStage(.85f);
        frames(ring, 30);
        assertTrue("at the milestone: " + ring.shown(), ring.shown() > .8f);
        assertTrue("short of the end before the shot is done", ring.shown() < 1f);
        ring.finish();
        frames(ring, 60);
        assertFalse("closed and faded", ring.isRunning());
        assertEquals(View.INVISIBLE, ring.getVisibility());
    }

    @Test
    public void aQueuedShotStartsTheRingOverAndAFinishWithoutAStartDoesNothing() {
        ProcessingRing ring = ring();
        ring.finish();
        assertFalse(ring.isRunning());
        ring.start();
        ring.start();               // a second shot queued behind the first
        ring.setStage(.55f);
        frames(ring, 20);
        ring.finish();              // the first is done
        boolean closed = false, restarted = false;
        for (int i = 0; i < 120 && !restarted; i++) {
            frames(ring, 1);
            if (ring.shown() >= .999f) closed = true;
            if (closed && ring.isRunning() && ring.shown() < .2f) restarted = true;
        }
        assertTrue("the first ring closed", closed);
        assertTrue("the ring starts over for the queued shot", restarted);
        ring.finish();
        frames(ring, 80);
        assertFalse(ring.isRunning());
    }

    @Test
    public void theMeasuredShareMovesOnForOneHeartbeatAtMost() {
        assertEquals(.3f, ProcessingRing.byMeasure(.3f, 1e-4f, 0), 1e-6f);
        assertEquals(.5f, ProcessingRing.byMeasure(.3f, 1e-4f, 2000), 1e-6f);
        assertEquals("one heartbeat ahead at most", .8f, ProcessingRing.byMeasure(.3f, 1e-4f, 60000), 1e-6f);
        assertEquals("never past the merge's end", 1f, ProcessingRing.byMeasure(.9f, 1e-3f, 5000), 0f);
        assertEquals("speed unknown: where it was measured", .4f, ProcessingRing.byMeasure(.4f, 0f, 4000), 0f);
    }

    /**
     * P220: a large shot creeps only to the merge's start until its merge reports, the worker's measured progress then carries
     * the ring to the merged milestone (moving on at the measured speed for one heartbeat, holding when the worker is slower
     * than that, never back), the rest creeps on after the milestone, the end closes it; a late heartbeat changes nothing.
     */
    @Test
    public void aLargeShotFollowsItsMeasuredMergeAndNeverGoesBack() {
        ProcessingRing ring = ring();
        ring.start();
        ring.setLarge();
        frames(ring, 190);                       // ~3 s of preparation
        assertTrue("short of the merge's start: " + ring.shown(), ring.shown() > 0f && ring.shown() <= ProcessingRing.MERGE_FROM);
        ring.setMeasured(0f);                    // the first heartbeat (5 s into the merge)
        frames(ring, 60);
        assertEquals(ProcessingRing.MERGE_FROM, ring.shown(), .01f);
        frames(ring, 252);                       // 5 s later the next one: a fifth of the merge done
        ring.setMeasured(.2f);
        frames(ring, 60);
        final float at = ProcessingRing.MERGE_FROM + (ProcessingRing.MERGED - ProcessingRing.MERGE_FROM) * .24f; // + 1 s at the speed
        assertEquals(at, ring.shown(), .01f);
        frames(ring, 625);                       // no heartbeat for 10 s: one heartbeat's lead, then it holds
        final float held = ring.shown();
        assertEquals(ProcessingRing.MERGE_FROM + (ProcessingRing.MERGED - ProcessingRing.MERGE_FROM) * .4f, held, .01f);
        ring.setMeasured(.3f);                   // slower than the lead: the ring holds, never back
        frames(ring, 120);
        assertTrue("never back: " + ring.shown() + " < " + held, ring.shown() >= held - 1e-6f);
        assertTrue(ring.shown() < held + .01f);
        ring.setMeasured(1f);
        frames(ring, 60);
        assertEquals(ProcessingRing.MERGED, ring.shown(), .01f);
        ring.setStage(ProcessingRing.MERGED);    // merged: the rest creeps on
        frames(ring, 120);
        final float post = ring.shown();
        assertTrue("on after the merge: " + post, post > ProcessingRing.MERGED + .02f && post < ProcessingRing.TIME_CAP);
        ring.setMeasured(.1f);                   // a heartbeat after the merge: nothing
        frames(ring, 5);
        assertTrue(ring.shown() >= post);
        ring.setStage(.85f);
        frames(ring, 60);
        assertTrue(ring.shown() > .8f && ring.shown() < 1f);
        ring.finish();
        frames(ring, 80);
        assertFalse(ring.isRunning());
    }

    @Test
    public void aMeasuredMergeStartsFromWhereTheRingStands() {
        ProcessingRing ring = ring();
        ring.start();                            // a shot not marked large whose merge turns out slow enough to report
        ring.setStage(.3f);
        frames(ring, 5);
        final float before = ring.shown();
        assertTrue(before > 0f && before <= .3f);
        ring.setMeasured(.5f);                   // the merge from the milestone (where the ring heads) to MERGED
        frames(ring, 5);
        assertTrue("from where it stood: " + ring.shown(), ring.shown() >= before);
        ring.setMeasured(.5f);
        frames(ring, 60);
        assertEquals(.3f + (ProcessingRing.MERGED - .3f) * .5f, ring.shown(), .02f);
        ring.finish();
        frames(ring, 80);
        assertFalse(ring.isRunning());
    }

    @Test
    public void theLargeHintOfAQueuedShotWaitsForItsRing() {
        ProcessingRing ring = ring();
        ring.start();
        ring.start();                            // the large shot queued behind
        frames(ring, 20);
        ring.finish();                           // the first is done; its ring closes
        ring.setLarge();                         // the queued shot's hint arrives meanwhile
        boolean restarted = false, closed = false;
        for (int i = 0; i < 150 && !restarted; i++) {
            frames(ring, 1);
            if (ring.shown() >= .999f) closed = true;
            if (closed && ring.isRunning() && ring.shown() < .05f) restarted = true;
        }
        assertTrue(restarted);
        frames(ring, 190);
        assertTrue("the queued large shot creeps to the merge's start only: " + ring.shown(), ring.shown() <= ProcessingRing.MERGE_FROM);
        ring.finish();
        frames(ring, 80);
        assertFalse(ring.isRunning());
    }
}
