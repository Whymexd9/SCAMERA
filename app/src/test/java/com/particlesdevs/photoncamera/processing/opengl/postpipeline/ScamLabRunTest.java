package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;
import android.content.Context;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;

/**
 * The lab's sides read what the shot read (audit 2026-10-21, owner: «проверь что все настройки рабочие»): the scam_dev.txt lines of
 * the shot without the compared setting's own line (a line "hybrid_&lt;key&gt;" made A = B), the side's «Для разработчика» lines,
 * and the shot's own burst lines that fixed a setting (a conservative retry, a step a crashed worker died in) said, not hidden.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamLabRunTest {
    private static final String H = "pref_scam_hybrid_";
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        final Context context = RuntimeEnvironment.getApplication();
        final SettingsManager manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        PreferenceKeys.initialise(manager);
    }

    @After public void tearDown() {
        PreferenceKeys.labDevOverlay(null);
        if (camera != null) camera.close();
    }

    @Test public void aSidesDevLinesLeaveOutTheComparedSettings() {
        final Map<String, Float> shot = new HashMap<>();
        shot.put("hybrid_motion_boost", 0f);   // the merge row compared
        shot.put("hybrid_boost_value", 3f);    // a «Движение» shot's row reads this line before its own value
        shot.put("texture_g0", 1.1f);          // a plain line a hybrid shot reads too (routeInternalValue)
        shot.put("hybrid_texture_g1", 2f);     // another setting's line: kept
        shot.put("vf_priority", 0f);
        final Map<String, Object> side = new HashMap<>();
        side.put(H + "motion_boost", true);
        side.put(H + "mm_boost_value", "4");
        side.put(H + "texture_g0", "1.3");
        final Map<String, Float> dev = ScamLabRun.sideDev(shot, side.keySet(), side);
        assertFalse(dev.containsKey("hybrid_motion_boost"));
        assertFalse(dev.containsKey("hybrid_boost_value"));
        assertFalse(dev.containsKey("texture_g0"));
        assertEquals(2f, dev.get("hybrid_texture_g1"), 0f);
        assertEquals(0f, dev.get("vf_priority"), 0f);
        assertEquals(Arrays.asList("hybrid_boost_value 3", "hybrid_motion_boost 0", "texture_g0 1.1"),
                ScamLabRun.overriding(side.keySet(), shot));
        // a side's own developer lines: a number, a switch, none (the code's value)
        final Map<String, Object> devSide = new HashMap<>();
        devSide.put(ScamLabRun.DEV + "hybrid_texture_g1", "2.5");
        devSide.put(ScamLabRun.DEV + "hybrid_bento_blend", false);
        devSide.put(ScamLabRun.DEV + "vf_priority", null);
        final Map<String, Float> own = ScamLabRun.sideDev(shot, devSide.keySet(), devSide);
        assertEquals(2.5f, own.get("hybrid_texture_g1"), 0f);
        assertEquals(0f, own.get("hybrid_bento_blend"), 0f);
        assertFalse(own.containsKey("vf_priority"));
        assertEquals(1.1f, own.get("texture_g0"), 0f);
        // the shot's lines themselves stay as they were
        assertEquals(5, shot.size());
    }

    @Test public void theLabOverlayReplacesTheFileWhileASideRenders() {
        assertEquals(6f, PreferenceKeys.hybridValue("boost_value", 6f), 0f);
        PreferenceKeys.labDevOverlay(Collections.singletonMap("hybrid_boost_value", 3f));
        assertEquals(3f, PreferenceKeys.hybridValue("boost_value", 6f), 0f);
        assertTrue(PreferenceKeys.scamDevOverrides("hybrid_boost_value"));
        assertEquals(7f, PreferenceKeys.scamDevNumber("ae_clamp_stack", 7f), 0f);
        // what a shot records is the file's, never the overlay
        assertFalse(PreferenceKeys.scamDevLines().containsKey("hybrid_boost_value"));
        PreferenceKeys.labDevOverlay(null);
        assertEquals(6f, PreferenceKeys.hybridValue("boost_value", 6f), 0f);
    }

    /**
     * Audit 2026-10-27 (lab-app-3): the overlay of a side is the render's own - the camera thread, the settings and every other
     * thread read the file meanwhile; the render's thread and the threads it starts read the overlay.
     */
    @Test public void theLabOverlayIsTheRendersOwn() throws Exception {
        final java.util.concurrent.ExecutorService camera = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // a thread that runs before the render (the camera's)
            assertEquals(6f, camera.submit(() -> PreferenceKeys.hybridValue("boost_value", 6f)).get(), 0f);
            PreferenceKeys.labDevOverlay(Collections.singletonMap("hybrid_boost_value", 3f));
            assertEquals("the render's thread", 3f, PreferenceKeys.hybridValue("boost_value", 6f), 0f);
            assertEquals("another thread reads the file", 6f, camera.submit(() -> PreferenceKeys.hybridValue("boost_value", 6f)).get(), 0f);
            final float[] helper = new float[1];
            final Thread t = new Thread(() -> helper[0] = PreferenceKeys.hybridValue("boost_value", 6f));
            t.start();
            t.join();
            assertEquals("a thread the render starts", 3f, helper[0], 0f);
        } finally {
            PreferenceKeys.labDevOverlay(null);
            camera.shutdownNow();
        }
        assertEquals(6f, PreferenceKeys.hybridValue("boost_value", 6f), 0f);
    }

    /**
     * Audit 2026-10-27: forget() runs on the main thread (the lab's taps); it waited on the class's monitor, which run() and
     * pipeline() hold for a whole render (an ANR).
     */
    @Test public void forgetDoesNotWaitForARender() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isSynchronized(ScamLabRun.class.getMethod("forget").getModifiers()));
        final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        final Thread render = new Thread(() -> {
            synchronized (ScamLabRun.class) {
                held.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        render.start();
        try {
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final java.util.concurrent.ExecutorService main = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                main.submit(ScamLabRun::forget).get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally {
                main.shutdownNow();
            }
        } finally {
            release.countDown();
            render.join(5000);
        }
    }

    @Test public void theBurstsOwnLinesNameTheSettingsTheyFixed() {
        // a conservative retry (ScamHybridBurst.CONSERVATIVE_TUNING) after the remosaic line, then the steps a crashed worker died in
        final String burst = "mosaicRemosaic 1\nlocalAlign 0\nsabre61 0\nrimRatio 0\nchromaDiff 0\nhotSigma 0\nhotBaseSigma 0\n"
                + "bentoChromaSigma 0\nbentoBlend 0\nmosaicUsFill 0\ngrid 2\nauto2x 0\nseriesFrames 8\n";
        final Map<String, String> fixed = ScamLabRun.burstLines(burst);
        assertEquals("localAlign 0", ScamLabRun.burstLine(fixed, H + "local_align"));
        assertEquals("sabre61 0", ScamLabRun.burstLine(fixed, H + "sabre61"));
        assertEquals("chromaDiff 0", ScamLabRun.burstLine(fixed, H + "chroma_diff"));
        assertEquals("hotSigma 0", ScamLabRun.burstLine(fixed, H + "hot_sigma"));
        assertEquals("hotBaseSigma 0", ScamLabRun.burstLine(fixed, H + "hot_base_sigma"));
        assertEquals("bentoChromaSigma 0", ScamLabRun.burstLine(fixed, H + "bento_chroma_sigma"));
        assertEquals("auto2x 0", ScamLabRun.burstLine(fixed, H + "auto2x"));
        // the developer lines of the same worker values
        assertEquals("bentoBlend 0", ScamLabRun.burstLine(fixed, ScamLabRun.DEV + "hybrid_bento_blend"));
        assertEquals("mosaicUsFill 0", ScamLabRun.burstLine(fixed, ScamLabRun.DEV + "hybrid_us_fill"));
        // lines of no setting, and settings the burst left alone
        assertEquals(new java.util.HashSet<>(Arrays.asList("local_align", "sabre61", "chroma_diff", "hot_sigma", "hot_base_sigma",
                "bento_chroma_sigma", "bento_blend", "us_fill", "auto2x")), fixed.keySet());
        assertNull(ScamLabRun.burstLine(fixed, H + "cdm"));
        assertNull(ScamLabRun.burstLine(fixed, "pref_film_lut"));
        // a «Движение» row of a value the burst fixed
        assertEquals("bento 0", ScamLabRun.burstLine(ScamLabRun.burstLines("bento 0\n"), H + "mm_bento"));
        assertTrue(ScamLabRun.burstLines("").isEmpty());
    }

    @Test public void twoMergesDifferOnlyByWhatTheWorkerTakes() {
        // the burst's own line last: a setting it replaces gives the same merge
        assertEquals(ScamLabRun.effective("kernelScale 1\nlocalAlign 0\n"), ScamLabRun.effective("localAlign 1\nkernelScale 1\nlocalAlign 0\n"));
        assertNotEquals(ScamLabRun.effective("localAlign 1\n"), ScamLabRun.effective("localAlign 2\n"));
        assertEquals(1f, ScamLabRun.devNumber(true), 0f);
        assertEquals(0f, ScamLabRun.devNumber(Boolean.FALSE), 0f);
        assertEquals(0.75f, ScamLabRun.devNumber("0,75"), 0f);
        assertNull(ScamLabRun.devNumber(null));
        assertNull(ScamLabRun.devNumber("x"));
    }
}
