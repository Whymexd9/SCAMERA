package com.particlesdevs.photoncamera.manual;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;

public class MotionShutterTest {
    private static final long MS = 1_000_000L;
    // X500 Pro Max: ISO range of the rulers 50..62509, ring frame 33.3 ms, ceiling 1/250 s
    private static final int ISO_MIN = 50, ISO_MAX = 62509;
    private static final long FRAME = 33_333_333L, CAP = 4 * MS;
    private static final int CONVERGED = 2, SEARCHING = 1;
    /** Frames between a request and its first frame. */
    private static final int LATENCY = 4;

    /** A camera session: a scene (RAW level per ISO·s), the AE's frames, the ceiling's requests through the pipeline. */
    private static final class Sim {
        final MotionShutter.Controller c = new MotionShutter.Controller();
        final MotionShutter.Limits l = new MotionShutter.Limits();
        final ArrayDeque<long[]> pipe = new ArrayDeque<>(); // {ns, iso, aeOff}
        long ts = 1_000_000_000L;
        double scene;
        int frames;
        /** Row profiles of the ring frames: null none; false a scene without flicker; true lamps that band short frames. */
        Boolean lamps;

        Sim(boolean flickerSafe, double scene) {
            l.set(CAP, FRAME, ISO_MIN, ISO_MAX, flickerSafe, 0);
            this.scene = scene;
        }

        double level(long ns, int iso) {
            return Math.min(1, scene * iso * ns / 1e9);
        }

        int frame(long ns, int iso, boolean aeOff, Integer aeState, Integer flicker, double level) {
            ts += FRAME;
            frames++;
            final float[] profile = lamps == null ? null : profile(lamps && !MotionShutter.Controller.multiple(ns, 10 * MS), frames);
            return c.onResult(ts, ns, iso, aeOff, aeState, flicker, Double.isNaN(level) ? 0 : ts, level, profile == null ? 0 : ts,
                    profile, l, ts);
        }

        /** A frame of the camera AE, settled, at the level the scene gives it. */
        int ae(long ns, int iso) {
            return frame(ns, iso, false, CONVERGED, 0, level(ns, iso));
        }

        /** AE frames until the ceiling takes over (then the AE's frames still in flight); the frame count, -1 when never. */
        int engage(long ns, int iso) {
            for (int i = 1; i <= 20; i++) {
                if (ae(ns, iso) == MotionShutter.APPLY) {
                    for (int k = 0; k < LATENCY; k++) pipe.addLast(new long[]{ns, iso, 0});
                    return i;
                }
            }
            return -1;
        }

        /** One frame under the ceiling: it exposes the request of LATENCY frames ago. */
        int held() {
            pipe.addLast(new long[]{c.shutterNs(), c.iso(), 1});
            while (pipe.size() > LATENCY + 1) pipe.pollFirst();
            long[] r = pipe.peekFirst();
            return frame(r[0], (int) r[1], r[2] != 0, r[2] != 0 ? null : CONVERGED, 0, level(r[0], (int) r[1]));
        }

        double product() {
            return c.iso() * (c.shutterNs() / 1e9);
        }
    }

    /** A row profile of the scene (32 bins), with the bands 100 Hz lamps put into a short frame (phase moving per frame). */
    private static float[] profile(boolean banded, int frame) {
        float[] p = new float[32];
        for (int i = 0; i < p.length; i++) {
            double v = 1 + 0.25 * Math.sin(i / 3.0) + 0.1 * Math.cos(i / 7.0) + 0.002 * Math.sin(i * 7.3 + frame); // scene + noise
            if (banded) v *= 1 + 0.2 * Math.sin(2 * Math.PI * i / 14.0 + frame * 2.1);
            p[i] = (float) v;
        }
        return p;
    }

    @Test
    public void bandAmplitudeSeesLampsNotTheScene() {
        assertEquals(0, MotionShutter.Controller.bandAmplitude(profile(false, 3), profile(false, 1)), 0.01);
        assertTrue(MotionShutter.Controller.bandAmplitude(profile(true, 3), profile(false, 1)) > 0.15);
        // an exposure step and vignetting (a linear trend) are no bands
        float[] ramp = profile(false, 1);
        for (int i = 0; i < ramp.length; i++) ramp[i] *= 1.5f * (1 + 0.004f * i);
        assertEquals(0, MotionShutter.Controller.bandAmplitude(ramp, profile(false, 1)), 0.01);
        assertTrue(Double.isNaN(MotionShutter.Controller.bandAmplitude(profile(true, 1), null)));
        assertTrue(Double.isNaN(MotionShutter.Controller.bandAmplitude(new float[32], profile(false, 1))));
    }

    @Test
    public void settingAndQuantization() {
        assertEquals(4 * MS, MotionShutter.settingNs(250f));
        assertEquals(1 * MS, MotionShutter.settingNs(1000f));
        assertEquals(0, MotionShutter.settingNs(0f)); // «Off»
        assertEquals(10 * MS, MotionShutter.quantize(4 * MS, 10 * MS, FRAME));
        assertEquals(20 * MS, MotionShutter.quantize(14 * MS, 10 * MS, FRAME));
        assertEquals(30 * MS, MotionShutter.quantize(32 * MS, 10 * MS, FRAME)); // not beyond the frame's whole half periods
        assertEquals(14 * MS, MotionShutter.quantize(14 * MS, 0, FRAME));
    }

    @Test
    public void nightTakesTheWholeAeExposureAtOnce() {
        // AE 25 ms at ISO 35000 (product 875): the shortest shutter ISO 62509 allows, about 14 ms, the same brightness
        Sim s = new Sim(false, 0.2 / 875);
        assertEquals(3, s.engage(25 * MS, 35000));
        assertTrue(s.c.active());
        assertEquals(13_998, s.c.shutterNs() / 1000, 2);
        assertEquals(ISO_MAX, s.c.iso(), 10);
        assertEquals(875.0, s.product(), 1.0);
    }

    @Test
    public void indoorsGoesToTheCeilingWithMoreIso() {
        // AE 22 ms at ISO 3700 (product 81.4): 1/250 s at ISO ~20350
        Sim s = new Sim(true, 0.2 / 81.4);
        assertEquals(3, s.engage(22 * MS, 3700));
        assertEquals(CAP, s.c.shutterNs());
        assertEquals(20350, s.c.iso(), 5);
    }

    @Test
    public void noRawLevelNoCeiling() {
        Sim s = new Sim(true, 0.2 / 81.4);
        for (int i = 0; i < 10; i++) assertEquals(MotionShutter.NONE, s.frame(22 * MS, 3700, false, CONVERGED, 0, Double.NaN));
        assertFalse(s.c.active());
    }

    @Test
    public void aRampingAeTeachesNothing() {
        // walking into a dim room: the AE searches from 2 ms up (x1.35 a frame) with the RAW level lagging behind
        Sim s = new Sim(false, 0.2 / 74.0);
        long ns = 2 * MS;
        for (int i = 0; i < 8; i++, ns = Math.round(ns * 1.35)) {
            assertEquals(MotionShutter.NONE, s.frame(ns, 3700, false, SEARCHING, 0, s.level(ns / 2, 3700)));
        }
        assertFalse(s.c.active());
        assertTrue(Double.isNaN(s.c.target()));
        // settled at 20 ms: learns the settled level, takes over at the AE's exposure
        assertEquals(3, s.engage(20 * MS, 3700));
        assertEquals(0.2, s.c.target(), 1e-6);
        assertEquals(74.0, s.product(), 0.5);
    }

    @Test
    public void anAeWithoutStateSettlesWhenItHoldsStill() {
        Sim s = new Sim(false, 0.2 / 81.4);
        int engagedAt = -1;
        for (int i = 1; i <= 20 && engagedAt < 0; i++)
            if (s.frame(22 * MS, 3700, false, null, 0, s.level(22 * MS, 3700)) == MotionShutter.APPLY) engagedAt = i;
        // 8 still frames make it settled, then 3 over the ceiling
        assertEquals(MotionShutter.STABLE_FRAMES + MotionShutter.ENGAGE_FRAMES, engagedAt);
    }

    @Test
    public void darkerSceneConvergesThroughThePipelineWithoutOvershoot() {
        Sim s = new Sim(false, 0.2 / 81.4);
        s.engage(22 * MS, 3700);
        s.scene /= 2; // one stop darker: 4 ms at ISO ~40700
        int top = 0;
        for (int i = 0; i < 60; i++) {
            s.held();
            top = Math.max(top, s.c.iso());
        }
        assertEquals(CAP, s.c.shutterNs());
        assertEquals(0, Math.log(s.product() / 162.8) / Math.log(2), 1.0 / 6);
        assertTrue("overshoot to ISO " + top, top <= 40700 * 1.12);
    }

    @Test
    public void framesOfAnOlderRequestDoNotOvershoot() {
        Sim s = new Sim(false, 0.2 / 81.4);
        s.engage(22 * MS, 3700);
        s.scene /= 2;
        // the first darker frame (still the AE's exposure in flight) moves the request once
        assertEquals(MotionShutter.APPLY, s.held());
        final double after = s.product();
        // the next frames in flight say the same about the scene: the product moves on toward it, never past it
        for (int i = 0; i < LATENCY; i++) {
            s.held();
            assertTrue(s.product() >= after - 1e-9);
            assertTrue(s.product() <= 162.8 * Math.pow(2, 1.0 / 6));
        }
    }

    @Test
    public void noWindupAgainstACoveredLens() {
        Sim s = new Sim(false, 0.2 / 875);
        s.engage(25 * MS, 35000);
        final double night = s.scene;
        s.scene = night / 1000; // lens covered, phone in a pocket: 20 s of frames
        for (int i = 0; i < 600; i++) s.held();
        assertTrue(s.c.active());
        assertEquals(FRAME, s.c.shutterNs());
        assertEquals(ISO_MAX, s.c.iso(), 1);
        // uncovered: back to ~14 ms within a second, not seconds of blown frames
        s.scene = night;
        for (int i = 0; i < 30; i++) s.held();
        assertEquals(14.0, s.c.shutterNs() / 1e6, 14.0 * 0.12);
    }

    @Test
    public void handsBackWhenBright() {
        Sim s = new Sim(false, 0.2 / 81.4);
        s.engage(22 * MS, 3700);
        s.scene *= 1000; // outdoors: every frame clipped at first
        int action = MotionShutter.NONE, i = 0;
        for (; i < 40 && action != MotionShutter.RELEASE; i++) action = s.held();
        assertEquals(MotionShutter.RELEASE, action);
        assertFalse(s.c.active());
        assertTrue(Double.isNaN(s.c.target())); // learns the AE's level again before the next take-over
    }

    @Test
    public void clippedFramesNeverRaiseTheExposure() {
        Sim s = new Sim(false, 0.2 / 81.4);
        s.engage(22 * MS, 3700);
        final double before = s.product();
        // a clipped frame of a much longer exposure: says only «at least this bright»
        s.frame(40 * MS, 40000, true, null, 0, 1.0);
        assertTrue(s.product() <= before + 1e-9);
    }

    @Test
    public void exposureCompensationMovesTheLevel() {
        Sim s = new Sim(false, 0.2 / 81.4);
        s.engage(22 * MS, 3700);
        s.l.set(CAP, FRAME, ISO_MIN, ISO_MAX, false, 1); // EV +1 on the ruler
        for (int i = 0; i < 60; i++) s.held();
        assertEquals(1, Math.log(s.product() / 81.4) / Math.log(2), 1.0 / 6);
    }

    @Test
    public void noLevelForTwoSecondsHandsBack() {
        Sim s = new Sim(false, 0.2 / 81.4);
        s.engage(22 * MS, 3700);
        int action = MotionShutter.NONE, i = 0;
        for (; i < 120 && action != MotionShutter.RELEASE; i++)
            action = s.frame(CAP, s.c.iso(), true, null, 0, Double.NaN);
        assertEquals(MotionShutter.RELEASE, action);
        assertEquals(MotionShutter.NO_LEVEL_NS / FRAME + 1, i, 1);
    }

    @Test
    public void aLevelPairsWithItsOwnFrame() {
        // the image of a frame arrives after the next result: its level still counts with its own exposure
        Sim s = new Sim(false, 0.2 / 81.4);
        long first = 0;
        for (int i = 0; i < 4; i++) {
            long prev = s.ts;
            s.ts += FRAME;
            if (first == 0) first = s.ts;
            // this result, the level of the previous frame (same AE exposure)
            s.c.onResult(s.ts, 22 * MS, 3700, false, CONVERGED, 0, i == 0 ? 0 : prev, i == 0 ? Double.NaN : 0.2, s.l, s.ts);
        }
        assertEquals(0.2, s.c.target(), 1e-9);
    }

    @Test
    public void mainsFlickerDecidesBeforeTheCeiling() {
        // X500 Pro Max indoors: the AE at exactly 20.00 ms (anti-banding), STATISTICS_SCENE_FLICKER 0, ceiling 1/250 s: the vote
        // decides first, the short frames then show whether the lamps band
        Sim s = new Sim(true, 0.2 / 74.0);
        s.lamps = true;
        assertEquals(MotionShutter.FLICKER_FRAMES, s.engage(20 * MS, 3700));
        assertEquals(10 * MS, s.c.suspectHalfNs());
        assertEquals(CAP, s.c.shutterNs());
        for (int i = 0; i < LATENCY + MotionShutter.BAND_HITS; i++) s.held();
        assertEquals(10 * MS, s.c.flickerHalfNs()); // bands on the short frames: no stripes, 1/100 s at least
        assertEquals(10 * MS, s.c.shutterNs());
        assertEquals(7400, s.c.iso());
        // the anti-banding setting alone, no flickering lamps: the short frames have no bands, 1/250 s stays
        Sim t = new Sim(true, 0.2 / 74.0);
        t.lamps = false;
        t.engage(20 * MS, 3700);
        for (int i = 0; i < LATENCY + MotionShutter.BAND_MISSES; i++) t.held();
        assertTrue(t.c.bandFree());
        assertEquals(0, t.c.flickerHalfNs());
        assertEquals(CAP, t.c.shutterNs());
        // no row profiles at all: latched once the wait is over
        Sim u = new Sim(true, 0.2 / 74.0);
        u.engage(20 * MS, 3700);
        for (int i = 0; i < 60; i++) u.held();
        assertEquals(10 * MS, u.c.flickerHalfNs());
        assertEquals(10 * MS, u.c.shutterNs());
    }

    @Test
    public void theFramesLongestShutterIsNoFlickerVote() {
        // at night the AE sits at one ring frame, 33.33 ms = 4 x 8.33 ms by chance: no 60 Hz latch, take-over at 3 frames
        Sim s = new Sim(true, 0.2 / 2083);
        assertEquals(MotionShutter.ENGAGE_FRAMES, s.engage(FRAME, ISO_MAX));
        assertEquals(0, s.c.flickerHalfNs());
    }

    @Test
    public void reportedFlickerLatchesAtOnceAndTheSwitchClearsIt() {
        Sim r = new Sim(true, 0.2 / 0.5);
        r.frame(5 * MS, 100, false, CONVERGED, MotionShutter.FLICKER_60HZ, 0.2);
        assertEquals(8_333_333L, r.c.flickerHalfNs());
        r.l.set(CAP, FRAME, ISO_MIN, ISO_MAX, false, 0);
        r.frame(5 * MS, 100, false, CONVERGED, MotionShutter.FLICKER_60HZ, 0.2);
        assertEquals(0, r.c.flickerHalfNs());
        // an AE shutter that is no multiple does not vote
        Sim n = new Sim(true, 0.2 / 18.4);
        for (int i = 0; i < 10; i++) n.frame(23 * MS, 800, false, CONVERGED, 0, 0.2);
        assertEquals(0, n.c.flickerHalfNs());
    }

    @Test
    public void flickerShuttersStayWholeHalfPeriods() {
        // night under lamps: AE 30 ms at ISO 35000 (product 1050) -> ~16.8 ms at the top ISO -> bands -> 20 ms
        Sim s = new Sim(true, 0.2 / 1050);
        s.lamps = true;
        assertEquals(MotionShutter.FLICKER_FRAMES, s.engage(30 * MS, 35000));
        for (int i = 0; i < LATENCY + MotionShutter.BAND_HITS; i++) s.held();
        assertEquals(20 * MS, s.c.shutterNs());
        assertEquals(52500, s.c.iso());
    }

    @Test
    public void theHonouredIsoLengthensTheShutter() {
        // the HAL gives at most ISO 16000 for AE-off requests (ExposureLimits): night 875 -> one frame at ISO 16000
        Sim s = new Sim(false, 0.2 / 875);
        s.l.set(CAP, FRAME, ISO_MIN, 16000, false, 0);
        s.engage(25 * MS, 35000);
        assertEquals(FRAME, s.c.shutterNs());
        assertEquals(16000, s.c.iso());
    }

    @Test
    public void motionLevelsLowerTheCeilingByHalfStops() {
        // 1/250 s and 2 px of smear: 500 px/s fits, 1000 px/s needs 2 ms (two half stops), a sprint is capped at 6 half stops
        assertEquals(0, MotionShutter.Levels.wanted(CAP, 0));
        assertEquals(0, MotionShutter.Levels.wanted(CAP, Double.NaN));
        assertEquals(0, MotionShutter.Levels.wanted(CAP, 500));
        assertEquals(2, MotionShutter.Levels.wanted(CAP, 1000));
        assertEquals(MotionShutter.Levels.MAX_LEVEL, MotionShutter.Levels.wanted(CAP, 100_000));
        MotionShutter.Levels l = new MotionShutter.Levels();
        long t = 1_000_000_000L;
        assertEquals(CAP, l.capNs(CAP, 300, t, t));
        assertEquals(2 * MS, l.capNs(CAP, 1000, t, t)); // faster: at once
        assertEquals(500_000L, l.capNs(CAP, 4000, t, t)); // a sprint: 1/2000 s
        // the subject stops: one half stop back per 0.6 s, not at once
        t += 100 * MS;
        assertEquals(500_000L, l.capNs(CAP, 0, t, t));
        t += 600 * MS;
        assertEquals(707_107L, l.capNs(CAP, 0, t, t), 1);
        // no measurement for a second: still
        for (int i = 0; i < 10; i++) { t += 600 * MS; l.capNs(CAP, 4000, 0, t); }
        assertEquals(0, l.level());
        assertEquals(0, l.capNs(0, 4000, t, t)); // «Off»
        // never below 1/4000 s
        MotionShutter.Levels fast = new MotionShutter.Levels();
        assertEquals(MotionShutter.Levels.MIN_CAP_NS, fast.capNs(MS, 100_000, t, t));
    }

    @Test
    public void aTapLearnsTheNewPointFirst() {
        Sim s = new Sim(false, 0.2 / 81.4);
        s.engage(22 * MS, 3700);
        s.c.drop(); // ParamController on a tap
        assertFalse(s.c.active());
        assertTrue(Double.isNaN(s.c.target()));
        // the AE meters the tapped face one stop brighter: the ceiling takes that over
        assertEquals(3, s.engage(22 * MS, 7400));
        assertEquals(0.4, s.c.target(), 1e-6);
        assertEquals(162.8, s.product(), 0.5);
    }
}
