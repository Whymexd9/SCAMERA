package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class LabFrameTest {
    /** The whole frame's statistics come back exactly: the fragment's exposure is decided from the same numbers as the shot's. */
    @Test public void theWholeFramesStatisticsComeBackExactly() throws Exception {
        final LabFrame f = new LabFrame();
        for (int m = 0; m < LabFrame.MODES; m++) {
            final ArkAe.Stats s = new ArkAe.Stats();
            s.gmax = 3.25f + m;
            s.hy[0] = 12345678901L;
            s.hy[4095] = 7 + m;
            s.hm[17] = 10;
            s.tail[3] = 4;
            s.tot = 1.5e7;
            s.sumY = 123456.789;
            s.sumLog = -98765.4321;
            f.stats[m] = s;
        }
        f.noiseScale = .75f;
        f.lowWidth = 2048;
        f.ccLo = new float[]{1f, .99f, .98f};
        f.ccHi = new float[]{0f, 0f, 0f};
        f.ccMax = new float[]{1.2f, 1.1f, 1f};
        f.nearClip = true;
        f.ccBento = true;
        f.p50 = .02f;
        f.p90 = .2f;
        f.lscNone = true;
        f.width = 8192;
        f.height = 6144;
        final LabFrame g = LabFrame.fromJson(new JSONObject(f.toJson().toString()));
        assertTrue(g.usable());
        for (int m = 0; m < LabFrame.MODES; m++) {
            assertEquals(f.stats[m].gmax, g.stats[m].gmax, 0);
            assertArrayEquals(f.stats[m].hy, g.stats[m].hy, 0);
            assertArrayEquals(f.stats[m].hm, g.stats[m].hm, 0);
            assertArrayEquals(f.stats[m].tail, g.stats[m].tail, 0);
            assertEquals(f.stats[m].tot, g.stats[m].tot, 0);
            assertEquals(f.stats[m].sumY, g.stats[m].sumY, 0);
            assertEquals(f.stats[m].sumLog, g.stats[m].sumLog, 0);
        }
        assertEquals(.75f, g.noiseScale, 0);
        assertEquals(2048, g.lowWidth);
        assertArrayEquals(f.ccLo, g.ccLo, 0);
        assertTrue(g.nearClip);
        assertTrue(g.ccBento);
        assertFalse(g.hiUnflagged);
        assertEquals(.02f, g.p50, 1e-7f);
        assertNull(g.lsc);
        assertTrue(g.lscNone);
        assertTrue(Float.isNaN(g.lensExpo));
        assertEquals(8192, g.width);
        final ArkAe.Stats c = LabFrame.copy(g.stats[1]);
        c.hy[0] = 0;
        assertEquals(12345678901.0, g.stats[1].hy[0], 0);
    }

    @Test public void aRunThatRecordedNoExposureIsNotUsed() {
        assertFalse(new LabFrame().usable());
    }

    /** The input scale the statistics were measured at comes back; a frame recorded before it reads NaN (the lab fills it in). */
    @Test public void theInputScaleIsKept() throws Exception {
        final LabFrame f = new LabFrame();
        f.stats[0] = new ArkAe.Stats();
        f.inScale = 2f;
        assertEquals(2f, LabFrame.fromJson(new JSONObject(f.toJson().toString())).inScale, 0f);
        f.inScale = Float.NaN;
        assertTrue(Float.isNaN(LabFrame.fromJson(new JSONObject(f.toJson().toString())).inScale));
    }

    /**
     * «Масштаб входа, EV» on a lab fragment (audit 2026-10-21): the whole frame's statistics at another input scale are those of
     * the frame scaled by it - the maximum and the sums exactly, the percentiles to a histogram bin, so the AE decides as on the
     * whole photo with that value (it partly evens the scale out) instead of from the shot's statistics.
     */
    @Test public void scaledStatisticsAreThoseOfTheScaledFrame() {
        final int w = 160, h = 120;
        for (float k : new float[]{2f, 0.5f, 0.25f, 4f}) {
            final ArkAe.Stats base = ArkAe.stats(scene(w, 1f), 3, w, h, 0);
            final ArkAe.Stats truth = ArkAe.stats(scene(w, k), 3, w, h, 0);
            final ArkAe.Stats scaled = LabFrame.scaled(base, k);
            assertEquals("gmax x" + k, truth.gmax, scaled.gmax, truth.gmax * 1e-6f);
            assertEquals("sumY x" + k, truth.sumY, scaled.sumY, Math.abs(truth.sumY) * 1e-5);
            assertEquals("sumLog x" + k, truth.sumLog, scaled.sumLog, Math.abs(truth.sumLog) * 1e-4 + 1);
            assertEquals(truth.tot, scaled.tot, 0);
            double hy = 0, hm = 0;
            for (int i = 0; i < ArkAe.BINS; i++) { hy += scaled.hy[i]; hm += scaled.hm[i]; }
            assertEquals("every sample kept", truth.tot, hy, 1e-6);
            assertEquals("every sample kept", truth.tot, hm, 1e-6);
            final ArkAe.Settings s = new ArkAe.Settings();
            s.bentoHeadroom = true;
            final ArkAe.Result a = ArkAe.finish(truth, s, 100, 6400), b = ArkAe.finish(scaled, s, 100, 6400);
            final float bin = a.clip / ArkAe.BINS * 2;
            assertEquals("p50 x" + k, a.p50, b.p50, bin);
            // the max channel above the ceiling comes back from the tail's bins (12 an octave)
            assertEquals("p98 x" + k, a.p98, b.p98, Math.max(bin, a.p98 * 0.05f));
            assertEquals("ae x" + k, a.ae, b.ae, a.ae * 0.04f);
            assertEquals("hr x" + k, a.hr, b.hr, a.hr * 1e-5f);
            // and it matters: the shot's own statistics give another exposure
            assertTrue("ae x" + k, Math.abs(a.ae - ArkAe.finish(base, s, 100, 6400).ae) > a.ae * 0.05f);
        }
        // 1: the statistics as they are
        final ArkAe.Stats base = ArkAe.stats(scene(w, 1f), 3, w, h, 0);
        assertArrayEquals(base.hm, LabFrame.scaled(base, 1f).hm, 0);
    }

    /** A frame with shadows, mid-tones, coloured light and Bento highlights up to 6 x the clip, times {@code k}. */
    private static ArkAe.Rows scene(int w, float k) {
        return (y, row) -> {
            for (int x = 0; x < w; x++) {
                final float t = (x + 0.5f) / w, u = (y + 0.5f) / 120f;
                float v = (float) (0.002 * Math.pow(3000.0, t)) * (0.6f + 0.8f * u);
                row[x * 3] = v * k;
                row[x * 3 + 1] = v * 0.8f * k;
                row[x * 3 + 2] = v * (y % 3 == 0 ? 1.4f : 0.5f) * k;
            }
        };
    }
}
