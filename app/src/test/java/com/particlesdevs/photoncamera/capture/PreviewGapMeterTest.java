package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P44: the viewfinder freeze around a shot from the preview timestamps (PREVIEW_GAP). */
public class PreviewGapMeterTest {
    private static final long FRAME = 33_333_333L;
    private static final long MS = 1_000_000L;

    private static long[] run(long start, int n) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = start + i * FRAME;
        return t;
    }

    /** vivo X200 Ultra tele, shot 14:01:40 (SCAMERA-debug.log (15)): flush, 5 frames, AE restore, then the P38 re-arm flush. */
    @Test
    public void theVivoShotSplitsIntoFlushRestartSeriesAndResume() {
        final long t0 = 128156227841192L;                      // the last preview frame before the press (ZSL cutoff)
        long[] before = run(t0 - 29 * FRAME, 30);
        long[] series = {128156696749221L, 128156763292346L, 128156793204109L, 128156826475567L, 128156859747234L};
        long restore = t0 + 989_500_000L;                      // STAB_TRACE: +995 ms dts=989.5 (AE restore, ae=0)
        long[] after = new long[32];
        after[0] = restore;
        after[1] = restore + 66_700_000L;                      // dts=66.7
        after[2] = after[1] + 163_100_000L;                    // the re-arm flush: dts=163.1
        for (int i = 3; i < after.length; i++) after[i] = after[i - 1] + FRAME;

        PreviewGapMeter.Result r = PreviewGapMeter.measure(before, after, series[0], series[4], series.length);
        assertEquals(989.5, r.gapMs, 0.1);
        assertEquals(468.9, r.leadMs, 0.1);                    // the flush restart: last preview -> first L/S/ES
        assertEquals(163.0, r.seriesMs, 0.1);                  // 5 frames, never on the viewfinder
        assertEquals(357.6, r.tailMs, 0.1);                    // last series frame -> the AE restore frame
        assertEquals(163.1, r.laterMs, 0.1);                   // the second freeze of the re-arm flush
        assertEquals(33.3, r.frameMs, 0.1);
    }

    /** OPPO: three preview frames in flight complete after the flush; the gap starts at the last of them. */
    @Test
    public void previewFramesCompletedAfterTheSubmitCountAsShownBeforeTheSeries() {
        final long t0 = 309328818736559L;
        long[] before = run(t0 - 15 * FRAME, 16);
        long[] inFlight = run(t0 + FRAME, 3);
        long first = 309329085375100L, last = 309329345292652L;
        long[] after = new long[3 + 31];
        System.arraycopy(inFlight, 0, after, 0, 3);
        long resume = last + 75 * MS;
        for (int i = 0; i < 31; i++) after[3 + i] = resume + i * FRAME;
        PreviewGapMeter.Result r = PreviewGapMeter.measure(before, after, first, last, 7);
        assertEquals((resume - inFlight[2]) / 1e6, r.gapMs, 0.01);
        assertEquals((first - inFlight[2]) / 1e6, r.leadMs, 0.01);
        assertEquals(75, r.tailMs, 0.01);
        assertEquals(33.3, r.laterMs, 0.1);                    // no second freeze
    }

    /** preview_lead 1: a preview frame after the flush, ahead of the series, splits the freeze in two. */
    @Test
    public void aLeadPreviewFrameSplitsTheFreezeAndTheLongerHalfIsTheGap() {
        long[] before = run(1_000 * MS, 10);
        long lastBefore = before[9];
        long lead = lastBefore + 250 * MS;                     // the HAL restart after the flush, shown on the viewfinder
        long first = lead + FRAME, last = first + 4 * FRAME;
        long[] after = new long[31];
        after[0] = lead;
        for (int i = 1; i < after.length; i++) after[i] = last + 67 * MS + (i - 1) * FRAME;
        PreviewGapMeter.Result r = PreviewGapMeter.measure(before, after, first, last, 5);
        assertEquals(33.3, r.leadMs, 0.1);                     // lead frame -> series
        assertEquals(67, r.tailMs, 0.01);
        assertEquals(250, r.gapMs, 0.01);                      // the wait for the lead frame is the longer standstill
        // a quick restart: the half with the series is the longer one
        long lead2 = lastBefore + 60 * MS, first2 = lead2 + FRAME, last2 = first2 + 4 * FRAME;
        long[] after2 = new long[31];
        after2[0] = lead2;
        for (int i = 1; i < after2.length; i++) after2[i] = last2 + 67 * MS + (i - 1) * FRAME;
        r = PreviewGapMeter.measure(before, after2, first2, last2, 5);
        assertEquals((after2[1] - lead2) / 1e6, r.gapMs, 0.01);
        assertEquals(233.7, r.gapMs, 0.1);
    }

    @Test
    public void aShotWithoutPostShutterFramesReportsTheLargestPreviewInterval() {
        long[] before = run(1_000 * MS, 10);
        long[] after = new long[12];
        long resume = before[9] + 250 * MS;
        for (int i = 0; i < after.length; i++) after[i] = resume + i * FRAME;
        PreviewGapMeter.Result r = PreviewGapMeter.measure(before, after, 0, 0, 0);
        assertEquals(250, r.gapMs, 0.01);
        assertEquals(-1, r.leadMs, 0);
        assertEquals(33.3, r.laterMs, 0.1);
    }

    @Test
    public void unknownPiecesStayUnknown() {
        // no preview frame came back after the series (camera closed): no gap, the series is still described
        PreviewGapMeter.Result r = PreviewGapMeter.measure(run(0, 5), new long[0], 10 * FRAME, 12 * FRAME, 3);
        assertEquals(-1, r.gapMs, 0);
        assertEquals(-1, r.tailMs, 0);
        assertTrue(r.leadMs > 0);
        String line = PreviewGapMeter.line(r, Double.NaN, 0, 3, "camera=0");
        assertTrue(line, line.contains("gap=? ms"));
        assertTrue(line, line.contains("series->preview ? ms"));
    }

    /** The live meter: one line per shot, closed AFTER_FRAMES preview frames after the series. */
    @Test
    public void theMeterLogsOneLinePerShotOnceThePreviewHasRunAfterTheSeries() {
        PreviewGapMeter m = new PreviewGapMeter();
        long t = 5_000 * MS;
        for (int i = 0; i < 20; i++) assertNull(m.preview(t += FRAME, t));
        final long lastBefore = t;
        assertNull(m.markShot("camera=5 flush=true lead=0", t, 2, true));
        m.previewFailed();
        m.previewFailed();
        long s0 = lastBefore + 300 * MS, s1 = s0 + FRAME;
        m.seriesStart(s0);
        m.seriesStart(s1);
        long resume = s1 + 100 * MS;
        String line = null;
        for (int i = 0; i < PreviewGapMeter.AFTER_FRAMES; i++) {
            assertNull("closed too early at frame " + i, line);
            line = m.preview(resume + i * FRAME, resume + i * FRAME);
        }
        assertNotNull(line);
        assertTrue(line, line.startsWith("camera=5 flush=true lead=0 gap=433 ms (frame 33 ms)"));
        assertTrue(line, line.contains("preview->series 300 ms, series 2 frames 33 ms, series->preview 100 ms"));
        assertTrue(line, line.contains("preview requests failed 2"));
        assertTrue(line, line.contains("first series frame +300 ms after the submit"));
        // the window is closed: later frames log nothing
        assertNull(m.preview(resume + 40 * FRAME, resume + 40 * FRAME));
    }

    @Test
    public void theWindowWaitsForEverySeriesFrame() {
        PreviewGapMeter m = new PreviewGapMeter();
        long t = 0;
        for (int i = 0; i < 10; i++) m.preview(t += FRAME, t);
        m.markShot("x", t, 3, false);
        m.seriesStart(t + 200 * MS);
        long p = t + 300 * MS;
        for (int i = 0; i < 40; i++) assertNull(m.preview(p + i * FRAME, p + i * FRAME));   // 1 of 3 series frames started
        m.seriesStart(t + 233 * MS);
        m.seriesStart(t + 266 * MS);
        assertNotNull(m.preview(p + 40 * FRAME, p + 40 * FRAME));
    }

    @Test
    public void aNextShotOrTheTimeoutClosesAnOpenWindow() {
        PreviewGapMeter m = new PreviewGapMeter();
        long t = 0;
        for (int i = 0; i < 10; i++) m.preview(t += FRAME, t);
        m.markShot("first", t, 5, true);
        String early = m.markShot("second", t + 100 * MS, 0, true);
        assertNotNull(early);
        assertTrue(early, early.startsWith("first "));
        assertTrue(early, early.contains("no series frame started (0/5)"));
        // the second shot (no post-shutter request) closes after AFTER_FRAMES frames, or at the timeout
        m.preview(t + 120 * MS, t + 120 * MS);
        String timeout = m.preview(t + 153 * MS, t + 100 * MS + PreviewGapMeter.TIMEOUT_NS);
        assertNotNull(timeout);
        assertTrue(timeout, timeout.startsWith("second gap="));
    }

    @Test
    public void aNewSessionForgetsTheOldFrames() {
        PreviewGapMeter m = new PreviewGapMeter();
        for (int i = 0; i < 10; i++) m.preview((i + 1) * FRAME, 0);
        m.markShot("s", 0, 1, false);
        m.reset();
        assertNull(m.preview(100 * FRAME, 0));
        // nothing open after the reset: a new shot starts from the new session's frames only
        m.markShot("t", 0, 0, false);
        String line = null;
        for (int i = 0; i < PreviewGapMeter.AFTER_FRAMES && line == null; i++) line = m.preview((200 + i) * FRAME, 0);
        assertNotNull(line);
        // one frame of the new session before the shot: no frame interval known (the old session's 33 ms are gone)
        assertTrue(line, line.startsWith("t gap=3333 ms (frame ? ms)"));
    }
}
