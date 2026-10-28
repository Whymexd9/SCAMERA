package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P383 (owner, 2026-10-28, a screen recording: «экспозиция меняется не плавно, а ступенчато»): the RAW viewfinder's shown tone
 * eases every drawn frame, by time, towards the last AE run's result (LiveWhyTone ran it every fourth frame and moved by 0.35 of the
 * gap then: steps of 0.12, 0.11, 0.08 EV four frames apart on the recording, nothing between), in the scene's terms (a frame is
 * drawn against its own exposure).
 */
public class ToneEaseTest {
    private static final double LOG2 = Math.log(2);
    private static final float[] EM = {0.5f, 1f, 0.7f, 2f};

    static WhyAe.Result result(float ae) {
        WhyAe.Result r = new WhyAe.Result();
        r.ae = ae;
        r.clip = 1f;
        r.slope = 2.7f;
        r.sp = 1.35f;
        r.tp = 1.6f;
        r.sat = 1f;
        r.minEv = -8.5f;
        r.maxEv = 3.5f;
        r.ev = 0.3f;
        return r;
    }

    private static double ev(float v) {
        return Math.log(v) / LOG2;
    }

    /**
     * The drawn ae over {@code frames} frames at {@code fps} of one exposure: the AE meets the new scene ({@code to}) on its first
     * run after frame 0 and every fourth frame after (the old cadence; the result stays).
     */
    private static double[] step(float from, float to, int fps, int frames) {
        final ToneEase e = new ToneEase();
        e.toward(result(from), from, EM, 0, 0);
        final long dt = 1_000_000_000L / fps;
        final double[] shown = new double[frames];
        for (int i = 0; i < frames; i++) {
            e.toward(result(to), to, EM, 0, dt);
            shown[i] = ev(e.aeFor(0));
        }
        return shown;
    }

    @Test
    public void aSceneChangeMovesEveryFrameGentlyNotEveryFourth() {
        // the recording's first step: shown ae 2.8 -> the new scene's 1.3 (-1.1 EV), 30 frames a second
        final double gap = Math.abs(ev(1.3f) - ev(2.8f));
        final double[] shown = step(2.8f, 1.3f, 30, 45);
        double prev = ev(2.8f), largest = 0;
        int still = 0;
        for (int i = 0; i < shown.length && Math.abs(shown[i] - ev(1.3f)) > 0.05; i++) {
            final double d = Math.abs(shown[i] - prev);
            largest = Math.max(largest, d);
            if (d < 1e-4) still++;
            prev = shown[i];
        }
        assertEquals("no frame stands still while the tone moves (the old law: three of every four)", 0, still);
        assertTrue("the first frame starts gently " + (ev(2.8f) - shown[0]), Math.abs(shown[0] - ev(2.8f)) <= 0.07 * gap);
        assertTrue("a frame moves at most 0.105 of the gap (the old law: 0.35 at once) " + largest, largest <= 0.105 * gap);
        // 90 % of the way in ~0.47 s (the old law: 6 runs, 0.8 s at 30 frames a second, 2.4 s at 10)
        int at90 = 0;
        while (Math.abs(shown[at90] - ev(1.3f)) > 0.1 * gap) at90++;
        assertTrue("90 % of the way after " + at90 + " frames", at90 >= 12 && at90 <= 16);
        // and no overshoot
        for (double s : shown) assertTrue(s >= ev(1.3f) - 1e-4);
    }

    @Test
    public void theSpeedDoesNotDependOnTheFrameRate() {
        final double[] at30 = step(1f, 4f, 30, 18), at10 = step(1f, 4f, 10, 6), at20 = step(1f, 4f, 20, 12);
        // 0.6 s: frame 18 at 30, 12 at 20 and 6 at 10 frames a second (the exact step of the pair of lags)
        assertEquals(at30[17], at10[5], 1e-4);
        assertEquals(at30[17], at20[11], 1e-4);
        assertTrue(at10[5] > 1.8 && at10[5] < 2);
    }

    @Test
    public void aStallEasesInsteadOfJumping() {
        final ToneEase e = new ToneEase();
        e.toward(result(1f), 1f, EM, 0, 0);
        e.toward(result(4f), 4f, EM, 0, 2_000_000_000L); // a merge held the viewfinder for 2 s
        assertTrue("a stall counts as MAX_DT_NS " + e.aeFor(0), ev(e.aeFor(0)) < 0.5);
    }

    @Test
    public void aMoveOfTheCamerasExposureIsCancelledInItsFrame() {
        // the scene holds; the camera's AE moves the exposure 8.33 ms ISO 1900 -> 0.25 EV more in one frame: the frame is 0.25 EV
        // brighter, it is drawn 0.25 EV darker - before the meter has seen it, and after
        final ToneEase e = new ToneEase();
        final double p0 = FrameExposure.log2Product(8_333_333L, 1900, 6400), p1 = p0 + 0.25;
        e.toward(result(2f), 2f, EM, p0, 0);
        final float before = e.aeFor(p0);
        e.toward(result(2f), 2f, EM, p0, 33_333_333L);
        assertEquals(before / Math.pow(2, 0.25), e.aeFor(p1), 1e-5);
        // the meter's next run sees the brighter frame: ae / 2^0.25 at p1 - the same scene target, nothing moves
        e.toward(result((float) (2 / Math.pow(2, 0.25))), (float) (2 / Math.pow(2, 0.25)), EM, p1, 33_333_333L);
        assertEquals(before / Math.pow(2, 0.25), e.aeFor(p1), 1e-5);
        // the same product by another split (shutter for ISO) is the same exposure: nothing to cancel
        assertEquals(p0, FrameExposure.log2Product(16_666_666L, 950, 6400), 1e-6);
    }

    @Test
    public void theSettledGainIsThePhotosAeAcrossItsWholeRange() {
        // review: «Мин. затемнение» goes down to 0.1 and the film's exposure boost up to ~18 - the guard never clips a settled ae
        for (float ae : new float[]{0.1f, 0.167f, 0.5f, 9f, 18f}) {
            final ToneEase e = new ToneEase();
            for (int i = 0; i < 100; i++) e.toward(result(ae), ae, EM, 3.2, 33_333_333L);
            assertEquals(ae, e.aeFor(3.2), ae * 1e-4f);
        }
    }

    @Test
    public void theDrawnGainStaysBounded() {
        final ToneEase e = new ToneEase();
        e.toward(result(1f), 1f, EM, 0, 0);
        assertEquals(ToneEase.AE_MAX, e.aeFor(-10), 0f);
        assertEquals(ToneEase.AE_MIN, e.aeFor(10), 0f);
    }

    @Test
    public void theFirstResultIsShownAtOnceAndAgainAfterAReset() {
        final ToneEase e = new ToneEase();
        e.toward(result(3f), 4f, EM, 1.5, 33_333_333L);
        assertEquals(3f, e.aeFor(1.5), 1e-5f);
        assertEquals(4f / 3f, e.filmOverAe(), 1e-5f);
        assertEquals(2f, e.em[3], 1e-5f);
        e.toward(result(1f), 1f, EM, 1.5, 33_333_333L);
        assertTrue(e.aeFor(1.5) > 2.8f);
        e.reset();
        e.toward(result(1f), 1f, EM, 1.5, 33_333_333L);
        assertEquals(1f, e.aeFor(1.5), 1e-5f);
    }

    @Test
    public void exposuresEaseAsRatios() {
        // a long dt, one lag only on the way: the shown value of a ratio stays a ratio of the two ends
        final ToneEase e = new ToneEase();
        e.toward(result(1f), 1f, new float[]{0.25f, 1f, 0.5f, 1f}, 0, 0);
        for (int i = 0; i < 300; i++) e.toward(result(4f), 16f, new float[]{1f, 1f, 1f, 4f}, 0, 33_333_333L);
        assertEquals(4f, e.aeFor(0), 1e-3f);
        assertEquals(4f, e.filmOverAe(), 1e-3f);
        assertEquals(1f, e.em[0], 1e-3f);
        assertEquals(4f, e.em[3], 1e-3f);
    }

    @Test
    public void theAgxAndClipValuesEaseToo() {
        final ToneEase e = new ToneEase();
        e.toward(result(1f), 1f, EM, 0, 0);
        final WhyAe.Result r = result(1f);
        r.clip = 0.5f;
        r.ev = 1.3f;
        e.toward(r, 1f, EM, 0, 33_333_333L);
        assertTrue(e.clip < 1f && e.clip > 0.9f);
        assertTrue(e.ev > 0.3f && e.ev < 0.4f);
        assertEquals(2.7f, e.slope, 1e-5f);
        for (int i = 0; i < 300; i++) e.toward(r, 1f, EM, 0, 33_333_333L);
        assertEquals(0.5f, e.clip, 1e-4f);
        assertEquals(1.3f, e.ev, 1e-4f);
    }

    @Test
    public void theCurveEasesAndThenStopsUploading() {
        final ToneEase e = new ToneEase();
        final float[] a = new float[FusedCurve.SIZE], b = new float[FusedCurve.SIZE];
        for (int i = 0; i < a.length; i++) {
            a[i] = i / (float) a.length;
            b[i] = Math.min(1f, a[i] * 1.5f);
        }
        e.curveToward(a, 33_333_333L);
        assertTrue("the first curve is uploaded", e.curveMoved());
        assertEquals(a[100], e.curve[100], 0f);
        e.curveToward(b, 33_333_333L);
        assertTrue(e.curveMoved());
        assertTrue("a gentle start", e.curve[200] > a[200] && e.curve[200] - a[200] < 0.07f * (b[200] - a[200]));
        for (int i = 0; i < 400 && e.curveMoved(); i++) e.curveToward(b, 33_333_333L);
        assertFalse("settled: not uploaded every frame for ever", e.curveMoved());
        assertEquals(b[200], e.curve[200], 1e-4f);
    }

    @Test
    public void everyDrawPathEasesAgainstItsFrameAndMetersOnTime() throws IOException {
        File f = new File("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/LiveWhyTone.java");
        if (!f.isFile()) f = new File("app/" + f.getPath());
        final String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        // draw (POINT and LOCAL) and drawFused ease the shown tone and take its gain for the drawn frame
        final Matcher m = Pattern.compile("easeShown\\((true|false)\\)").matcher(src);
        int calls = 0;
        while (m.find()) calls++;
        assertEquals(3, calls);
        assertTrue(src.contains("return shown.aeFor(track.logP());"));
        assertFalse("no per-run easing left", src.contains("AE_SMOOTH"));
        assertFalse("the uniforms read the shown values", src.contains("r.clip") || src.contains("r.slope"));
        // vf_why 1 meters on time; the frames are observed (their exposure tracked) on every draw path
        assertEquals(2, count(src, "if (meterDue(observe(view)))"));
        assertEquals(1, count(src, "observe(drawnView);"));
        File cc = new File("src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java");
        if (!cc.isFile()) cc = new File("app/" + cc.getPath());
        assertTrue("the frame carries its exposure time, from its own result only", new String(Files.readAllBytes(cc.toPath()), StandardCharsets.UTF_8)
                .contains("if (resultTs != null && resultTs == img.getTimestamp()) m.exposureNs = colorResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);"));
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }
}
