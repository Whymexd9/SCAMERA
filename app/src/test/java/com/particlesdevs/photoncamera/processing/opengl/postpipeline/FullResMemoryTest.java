package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;
import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.PostGlMode;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * P187 (owner, 2026-10-17: less memory at full resolution, no loss of quality): the worker's trailers without the
 * effective-frame map (header flag 16), and the sizes whose pipeline gives each big texture back after its last reader.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FullResMemoryTest {
    /** Output-grid trailer, the RGB, and the merged RAW of a Sabre 2x grid (sensor grid: a quarter of the pixels, 2 B each). */
    private static final long P = 1000, RGB = 6 * P, RAW = P / 2;
    private MockedStatic<PreferenceKeys> prefs;
    private boolean devSwitch;

    @Before
    public void setUp() {
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.scamDevSwitch(anyString(), anyBoolean()))
                .thenAnswer(i -> "early_release".equals(i.getArgument(0)) ? devSwitch : i.getArgument(1));
    }

    @After
    public void tearDown() {
        prefs.close();
        PostGlMode.setLegacy(false);
    }

    @Test
    public void withTheMapTheLayoutsAreTheOldOnes() {
        assertArrayEquals(new long[]{RAW, P, P}, ScamNeuralClient.trailers(RGB + RAW + 2 * P, RGB, RAW, P, true, true, true));
        assertArrayEquals(new long[]{0, P, P}, ScamNeuralClient.trailers(RGB + 2 * P, RGB, RAW, P, true, true, true));
        assertArrayEquals(new long[]{0, P, 0}, ScamNeuralClient.trailers(RGB + P, RGB, RAW, P, false, true, true));
        assertArrayEquals(new long[]{0, 0, 0}, ScamNeuralClient.trailers(RGB, RGB, RAW, P, true, true, true));
        assertArrayEquals("on the sensor grid (RAW = 2 x P) the merged RAW wins a tie, as before", new long[]{2 * P, 0, 0},
                ScamNeuralClient.trailers(RGB + 2 * P, RGB, 2 * P, P, true, true, true));
        assertArrayEquals("clip flags only after the map", new long[]{0, P, 0},
                ScamNeuralClient.trailers(RGB + P, RGB, RAW, P, false, true, true));
        assertNull(ScamNeuralClient.trailers(RGB + 3, RGB, RAW, P, true, true, true));
    }

    @Test
    public void withoutTheMapTheClipFlagsFollowTheRgbOrTheRaw() {
        assertArrayEquals(new long[]{RAW, 0, P}, ScamNeuralClient.trailers(RGB + RAW + P, RGB, RAW, P, true, false, true));
        assertArrayEquals(new long[]{0, 0, P}, ScamNeuralClient.trailers(RGB + P, RGB, RAW, P, true, false, true));
        assertArrayEquals(new long[]{RAW, 0, 0}, ScamNeuralClient.trailers(RGB + RAW, RGB, RAW, P, true, false, true));
        assertArrayEquals(new long[]{0, 0, 0}, ScamNeuralClient.trailers(RGB, RGB, RAW, P, false, false, true));
        assertNull("no map is ever read without one asked for", ScamNeuralClient.trailers(RGB + 2 * P, RGB, RAW, P, false, false, true));
        assertNull("no clip flags without a request", ScamNeuralClient.trailers(RGB + P, RGB, RAW, P, false, false, false));
    }

    @Test
    public void bigTexturesGoBackEarlyAbove100MpOrOnTheDevSwitch() {
        assertTrue("200 MP", ScamRgb.earlyRelease(new Point(16320, 12288)));
        assertTrue("201 MP Sabre 2x of 50 MP", ScamRgb.earlyRelease(new Point(16384, 12288)));
        assertFalse("50 MP: as before", ScamRgb.earlyRelease(new Point(8192, 6144)));
        assertFalse(ScamRgb.earlyRelease(null));
        devSwitch = true;
        assertTrue("scam_dev early_release 1: any size, for a post_ab A/B", ScamRgb.earlyRelease(new Point(4096, 3072)));
        PostGlMode.setLegacy(true);
        assertFalse("post_ab's reference run keeps everything", ScamRgb.earlyRelease(new Point(16320, 12288)));
    }
}
