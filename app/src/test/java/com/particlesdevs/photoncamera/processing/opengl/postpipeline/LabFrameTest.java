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
}
