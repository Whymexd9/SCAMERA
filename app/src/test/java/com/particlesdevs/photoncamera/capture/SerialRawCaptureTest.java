package com.particlesdevs.photoncamera.capture;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SerialRawCaptureTest {
    private static final class Events implements SerialRawCapture.Listener {
        final List<Integer> submitted = new ArrayList<>();
        int completed, failed;
        public void submit(int index) { submitted.add(index); }
        public void complete() { completed++; }
        public void failed(String reason) { failed++; }
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
