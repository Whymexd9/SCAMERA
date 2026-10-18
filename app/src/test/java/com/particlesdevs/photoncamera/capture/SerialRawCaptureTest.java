package com.particlesdevs.photoncamera.capture;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SerialRawCaptureTest {
    @Test public void rawOnlyStillRequiresDistinctStartedRawResultAndSequenceWithoutInventingJpegEvents() {
        Events events = new Events();
        SerialRawCapture series = new SerialRawCapture(4, false, events);
        series.start();
        for (int i = 0; i < 4; i++) {
            series.started(i, 100 + i);
            series.raw(100 + i);
            series.result(i, 100 + i);
            assertEquals(i + 1, events.submitted.size());
            series.sequenceCompleted(i);
        }
        assertEquals(4, events.submitted.size());
        assertEquals(1, events.completed);
        assertEquals(0, events.failed);
    }
    /** P179: up to three stills queued at once; each still matched on its own, the series completes in order. */
    @Test public void keepsUpToThreeStillsInFlightAndCompletesInOrder() {
        Events events = new Events();
        SerialRawCapture series = new SerialRawCapture(5, false, 3, events);
        series.start();
        assertEquals("three queued at once", Arrays.asList(0, 1, 2), events.submitted);
        series.started(0, 100);
        series.started(1, 110);
        series.started(2, 120);
        // the second finishes first: nothing new until the first one is done (the order of the series)
        series.raw(110); series.result(1, 110); series.sequenceCompleted(1);
        assertEquals(3, events.submitted.size());
        assertEquals(0, series.index());
        assertTrue(series.acceptsRaw(100));
        assertFalse("a RAW of no still", series.acceptsRaw(105));
        series.raw(100); series.result(0, 100); series.sequenceCompleted(0);
        assertEquals("two done: two more queued", Arrays.asList(0, 1, 2, 3, 4), events.submitted);
        assertEquals(2, series.index());
        series.started(3, 130);
        series.started(4, 140);
        for (long t : new long[]{120, 130, 140}) {
            int i = (int) ((t - 100) / 10);
            series.raw(t); series.result(i, t); series.sequenceCompleted(i);
        }
        assertEquals(1, events.completed);
        assertEquals(0, events.failed);
        assertEquals(5, series.index());
        // a result whose timestamp is not its own still's start fails the series
        events = new Events();
        series = new SerialRawCapture(3, false, 3, events);
        series.start();
        series.started(0, 100);
        series.started(1, 110);
        series.result(1, 100);
        assertEquals(1, events.failed);
    }

    /**
     * P222: the listener hears when a still becomes the oldest one in flight (its timeout starts then): one in flight right
     * after its submit, as the timer before P222; several in flight only when the ones ahead of it are done.
     */
    @Test public void tellsWhenEachStillBecomesTheOldestInFlight() {
        Events events = new Events();
        SerialRawCapture series = new SerialRawCapture(3, false, 1, events);
        series.start();
        assertEquals(Arrays.asList(0), events.heads);
        for (int i = 0; i < 3; i++) {
            long t = 100 + 10 * i;
            series.started(i, t); series.raw(t); series.result(i, t);
            assertEquals("not before the still is done", i + 1, events.heads.size());
            series.sequenceCompleted(i);
            assertEquals("each one the oldest right after its submit", events.submitted, events.heads);
        }
        assertEquals(Arrays.asList(0, 1, 2), events.heads);
        assertEquals(1, events.completed);

        events = new Events();
        series = new SerialRawCapture(5, false, 3, events);
        series.start();
        assertEquals(Arrays.asList(0, 1, 2), events.submitted);
        assertEquals("the queued ones are not the oldest yet", Arrays.asList(0), events.heads);
        series.started(0, 100); series.started(1, 110); series.started(2, 120);
        series.raw(110); series.result(1, 110); series.sequenceCompleted(1);
        assertEquals("the second done before the first: still the first", Arrays.asList(0), events.heads);
        series.raw(100); series.result(0, 100); series.sequenceCompleted(0);
        assertEquals("both done: the third is the oldest", Arrays.asList(0, 2), events.heads);
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), events.submitted);
        series.started(3, 130); series.started(4, 140);
        series.raw(120); series.result(2, 120); series.sequenceCompleted(2);
        assertEquals(Arrays.asList(0, 2, 3), events.heads);
        series.raw(130); series.result(3, 130); series.sequenceCompleted(3);
        assertEquals("the last one, nothing left to submit", Arrays.asList(0, 2, 3, 4), events.heads);
        series.fail("timeout at frame 4");
        series.raw(140); series.result(4, 140); series.sequenceCompleted(4);
        assertEquals("nothing after a failure", Arrays.asList(0, 2, 3, 4), events.heads);
        assertEquals(0, events.completed);
        assertEquals(1, events.failed);
    }

    private static final class Events implements SerialRawCapture.Listener {
        final List<Integer> submitted = new ArrayList<>(), heads = new ArrayList<>();
        int completed, failed;
        public void submit(int index) { submitted.add(index); }
        public void complete() { completed++; }
        public void failed(String reason) { failed++; }
        @Override public void head(int index) { heads.add(index); }
    }

    @Test public void requiresMatchedRawResultJpegAndSequenceBeforeAdvancing() {
        Events events = new Events();
        SerialRawCapture series = new SerialRawCapture(3, events);
        series.start();
        for (int i = 0; i < 3; i++) {
            long timestamp = 100 + i;
            series.started(i, timestamp);
            series.sequenceCompleted(i);
            series.jpeg(timestamp);
            series.result(i, timestamp);
            assertEquals(i + 1, events.submitted.size());
            assertFalse(series.acceptsRaw(timestamp - 1));
            series.raw(timestamp - 1);
            series.raw(timestamp);
        }
        assertEquals(Arrays.asList(0, 1, 2), events.submitted);
        assertEquals(1, events.completed);
        assertEquals(0, events.failed);
        series.jpeg(102);
        series.sequenceCompleted(2);
        assertEquals(1, events.completed);
    }

    @Test public void acceptsRawBeforeResultButRejectsDuplicateAndStaleCallbacks() {
        Events events = new Events();
        SerialRawCapture series = new SerialRawCapture(2, events);
        series.start();
        series.started(0, 100);
        series.raw(100);
        assertFalse(series.acceptsRaw(100));
        series.result(0, 100);
        series.sequenceCompleted(0);
        series.jpeg(100);
        series.result(0, 100);
        series.sequenceCompleted(0);
        series.raw(100);
        assertEquals(2, events.submitted.size());
        assertEquals(0, events.failed);
        assertFalse(series.finished());
    }

    @Test public void rejectsMetadataMismatchAndRepeatedSensorTimestamp() {
        Events events = new Events();
        SerialRawCapture series = new SerialRawCapture(2, events);
        series.start();
        series.started(0, 100);
        series.result(0, 101);
        series.fail("timeout");
        assertEquals(1, events.failed);
        assertEquals(1, events.submitted.size());

        events = new Events();
        series = new SerialRawCapture(2, events);
        series.start();
        series.started(0, 100);
        series.raw(100);
        series.result(0, 100);
        series.jpeg(100);
        series.sequenceCompleted(0);
        series.started(1, 100);
        assertEquals(1, events.failed);
    }

    @Test public void cancelPreventsSubmissionAndCompletionFromLateEvents() {
        Events events = new Events();
        SerialRawCapture series = new SerialRawCapture(2, events);
        series.start();
        series.started(0, 100);
        series.cancel();
        series.raw(100);
        series.result(0, 100);
        series.jpeg(100);
        series.sequenceCompleted(0);
        series.fail("closed");
        assertEquals(Arrays.asList(0), events.submitted);
        assertEquals(0, events.completed);
        assertEquals(0, events.failed);
    }

    @Test public void earlyJpegMustMatchTheStillSensorTimestamp() {
        Events events = new Events();
        SerialRawCapture series = new SerialRawCapture(2, events);
        series.start();
        series.jpeg(99);
        series.started(0, 100);
        series.raw(100);
        series.result(0, 100);
        series.sequenceCompleted(0);
        assertEquals(Arrays.asList(0), events.submitted);
        series.jpeg(100);
        assertEquals(Arrays.asList(0, 1), events.submitted);
        series.jpeg(101);
        series.started(1, 101);
        series.raw(101);
        series.result(1, 101);
        series.sequenceCompleted(1);
        assertEquals(1, events.completed);
    }
}
