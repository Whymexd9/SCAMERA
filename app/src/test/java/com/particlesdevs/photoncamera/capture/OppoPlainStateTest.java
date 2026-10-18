package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P212e (W1 = M2): an OPPO camera's plain-session neutral, transform and shading map survive the process (per camera and native
 * size, forgotten by another firmware), so a cold start straight into FULLRES has a lens shading map and a neutral.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class OppoPlainStateTest {
    private static final String FW = "OPPO/PHY110/OP5D0DL1:16/202";
    private SharedPreferences prefs;
    private final AtomicLong wall = new AtomicLong(1_800_000_000_000L);

    @Before public void setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("oppo_plain_state_test", 0);
        prefs.edit().clear().commit();
        OppoPlainState.useStoreForTest(prefs, FW, wall::get);
    }

    @After public void tearDown() {
        OppoPlainState.resetForTest();
    }

    /** A 17 x 13 map like the PHY110 plain one: 1 in the centre, up to ~4.7 at the corners, per channel a little apart. */
    static float[] map(int cols, int rows, float corner, float blueTilt) {
        final float[] g = new float[4 * cols * rows];
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            final float u = (x - (cols - 1) / 2f) / ((cols - 1) / 2f), v = (y - (rows - 1) / 2f) / ((rows - 1) / 2f);
            final float r2 = (u * u + v * v) / 2f, base = 1f + (corner - 1f) * r2;
            final int i = 4 * (y * cols + x);
            g[i] = base * 1.02f;
            g[i + 1] = base;
            g[i + 2] = base;
            g[i + 3] = base * (1f + blueTilt * r2);
        }
        return g;
    }

    private static final float[] T = {1.8f, -0.6f, -0.2f, -0.3f, 1.6f, -0.3f, 0f, -0.6f, 1.6f};

    /** Stored in one process, read in the next: neutral, transform and map exactly (the map in copyGainFactors order). */
    @Test public void theStateSurvivesTheProcess() {
        final float[] g = map(17, 13, 4.7f, 0.03f);
        assertTrue(OppoPlainState.due("2", 8192, 6144, 1_000L));
        assertTrue(OppoPlainState.remember("2", 8192, 6144, 0.408f, 0.614f, T, g, 17, 13, 1_000L));
        OppoPlainState.useStoreForTest(prefs, FW, wall::get); // a new process
        assertArrayEquals(new float[]{0.408f, 1f, 0.614f}, OppoPlainState.neutral("2", 8192, 6144), 1e-4f);
        assertArrayEquals(T, OppoPlainState.transform("2", 8192, 6144), 1e-5f);
        final OppoPlainState.Shading s = OppoPlainState.shading("2", 8192, 6144, null);
        assertNotNull(s);
        assertEquals(17, s.cols);
        assertEquals(13, s.rows);
        assertArrayEquals(g, s.gains, 0f);
        assertEquals("17x13", s.toString());
        assertNull("another camera", OppoPlainState.shading("4", 8192, 6144, null));
        assertNull("another size (the plain 12 MP RAW is not the key)", OppoPlainState.shading("2", 4096, 3072, null));
        assertNull(OppoPlainState.neutral("2", 4096, 3072));
    }

    /** Another firmware forgets every stored line (its ISP tables may differ). */
    @Test public void anotherFirmwareForgetsTheState() {
        OppoPlainState.remember("2", 8192, 6144, 0.408f, 0.614f, T, map(17, 13, 4.7f, 0f), 17, 13, 1_000L);
        OppoPlainState.useStoreForTest(prefs, "OPPO/PHY110/OP5D0DL1:16/203", wall::get);
        assertNull(OppoPlainState.neutral("2", 8192, 6144));
        assertNull(OppoPlainState.shading("2", 8192, 6144, null));
        assertNull(OppoPlainState.transform("2", 8192, 6144));
        assertFalse("the lines are gone from the store", prefs.contains("2/8192x6144/n"));
        assertFalse(prefs.contains("2/8192x6144/m0"));
    }

    /** At most one write every 5 s per camera, and only of what changed. */
    @Test public void writesAreThrottled() {
        assertTrue(OppoPlainState.due("2", 8192, 6144, 1_000L));
        OppoPlainState.remember("2", 8192, 6144, 0.408f, 0.614f, null, map(17, 13, 4.7f, 0f), 17, 13, 1_000L);
        assertFalse(OppoPlainState.due("2", 8192, 6144, 3_000L));
        assertTrue(OppoPlainState.due("2", 8192, 6144, 6_000L));
        assertTrue("another camera has its own clock", OppoPlainState.due("4", 8192, 6144, 3_000L));
        // the same neutral and map 5 s later: nothing to write
        assertFalse(OppoPlainState.remember("2", 8192, 6144, 0.408f, 0.614f, null, map(17, 13, 4.7f, 0f), 17, 13, 6_000L));
        // a minute later the neutral's time is refreshed
        wall.addAndGet(OppoPlainState.NEUTRAL_REFRESH_MS);
        assertTrue(OppoPlainState.remember("2", 8192, 6144, 0.408f, 0.614f, null, map(17, 13, 4.7f, 0f), 17, 13, 70_000L));
    }

    /** Maps are kept per light: a lookup takes the one taken nearest to the light now; the same light overwrites its bin. */
    @Test public void theMapOfTheNearestLight() {
        final float[] day = map(17, 13, 4.7f, 0.03f), tungsten = map(17, 13, 4.6f, -0.05f);
        OppoPlainState.remember("2", 8192, 6144, 0.408f, 0.614f, null, day, 17, 13, 1_000L); // ln(r / b) -0.41
        wall.addAndGet(10_000L);
        OppoPlainState.remember("2", 8192, 6144, 0.773f, 0.286f, null, tungsten, 17, 13, 11_000L); // +0.99
        OppoPlainState.useStoreForTest(prefs, FW, wall::get);
        assertArrayEquals(day, OppoPlainState.shading("2", 8192, 6144, new float[]{0.42f, 1f, 0.60f}).gains, 0f);
        assertArrayEquals(tungsten, OppoPlainState.shading("2", 8192, 6144, new float[]{0.70f, 1f, 0.30f}).gains, 0f);
        assertArrayEquals("no light: the newest", tungsten, OppoPlainState.shading("2", 8192, 6144, null).gains, 0f);
        assertArrayEquals("a NaN neutral: the newest", tungsten, OppoPlainState.shading("2", 8192, 6144,
                new float[]{Float.NaN, Float.NaN, Float.NaN}).gains, 0f);
        // a daylight map that changed (focus, a slightly other light) replaces the daylight bin, not the tungsten one
        final float[] day2 = map(17, 13, 4.8f, 0.03f);
        OppoPlainState.remember("2", 8192, 6144, 0.40f, 0.62f, null, day2, 17, 13, 30_000L);
        OppoPlainState.useStoreForTest(prefs, FW, wall::get);
        assertArrayEquals(day2, OppoPlainState.shading("2", 8192, 6144, new float[]{0.42f, 1f, 0.60f}).gains, 0f);
        assertArrayEquals(tungsten, OppoPlainState.shading("2", 8192, 6144, new float[]{0.70f, 1f, 0.30f}).gains, 0f);
    }

    /** A NaN map, a NaN neutral or a 1 x 1 map is never stored. */
    @Test public void nothingUnusableIsStored() {
        final float[] bad = map(17, 13, 4.7f, 0f);
        bad[40] = Float.NaN;
        OppoPlainState.remember("2", 8192, 6144, 0.408f, 0.614f, null, bad, 17, 13, 1_000L);
        assertNull(OppoPlainState.shading("2", 8192, 6144, null));
        assertFalse(OppoPlainState.remember("3", 8192, 6144, Float.NaN, 0.614f, null, map(17, 13, 4.7f, 0f), 17, 13, 1_000L));
        assertNull(OppoPlainState.neutral("3", 8192, 6144));
        OppoPlainState.remember("4", 8192, 6144, 0.45f, 0.63f, null, new float[]{1, 1, 1, 1}, 1, 1, 1_000L);
        assertNull(OppoPlainState.shading("4", 8192, 6144, null));
        assertNotNull("its neutral still counts", OppoPlainState.neutral("4", 8192, 6144));
    }

    @Test public void floatsRoundTrip() {
        final float[] v = {0f, 1f, -2.5f, 4.666748f, 1e-7f, 123456.78f};
        assertArrayEquals(v, OppoPlainState.decode(OppoPlainState.encode(v)), 0f);
        // a 17 x 13 map stays a few KB a bin
        assertTrue(OppoPlainState.encode(map(17, 13, 4.7f, 0f)).length() < 5_000);
    }
}
