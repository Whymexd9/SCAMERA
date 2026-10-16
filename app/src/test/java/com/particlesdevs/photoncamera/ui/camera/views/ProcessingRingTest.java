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
}
