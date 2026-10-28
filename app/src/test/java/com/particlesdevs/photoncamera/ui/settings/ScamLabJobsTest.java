package com.particlesdevs.photoncamera.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabResults;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Audit 2026-10-27: the lab's render jobs. A failed render no longer shows another job's pictures under it (lab-app-1), is not
 * started again by every redraw, and «Повторить» runs it; a page opened again while its job renders shows the result when it is
 * ready (the render thread redraws the page on screen, not the one that started it).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class ScamLabJobsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private MockedStatic<ScameraApp> camera;
    private ActivityController<FragmentActivity> controller;
    private ScamLabFragment.Runner runnerBefore;
    private File burst;

    @Before public void setUp() throws Exception {
        final Context context = RuntimeEnvironment.getApplication();
        final SettingsManager manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        final ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        clearJobs();
        runnerBefore = ScamLabFragment.runner;
        burst = LabResults.burst(tmp);
        controller = Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.Theme_Scamera_SettingsActivity);
        controller.setup();
    }

    @After public void tearDown() throws Exception {
        ScamLabFragment.runner = runnerBefore;
        waitForJobs();
        clearJobs();
        if (controller != null) controller.close();
        if (camera != null) camera.close();
    }

    /** The jobs of an earlier test are not this one's. */
    private static void clearJobs() throws Exception {
        for (String name : new String[]{"running", "next", "shown", "failed", "shownResult", "runError", "runStage", "live"}) {
            final java.lang.reflect.Field f = ScamLabFragment.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, null);
        }
    }

    private static void waitForJobs() throws InterruptedException {
        final long until = System.currentTimeMillis() + 10_000;
        while (!ScamLabFragment.labIdle() && System.currentTimeMillis() < until) Thread.sleep(5);
        assertTrue("the lab's jobs ended", ScamLabFragment.labIdle());
        // the render thread's last redraw is posted after the hand-over
        for (Thread t : Thread.getAllStackTraces().keySet()) if ("scam-lab".equals(t.getName())) t.join(10_000);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The «Тогда и сейчас» page of the shot in folder {@code name}, on screen. */
    private ScamLabFragment open(String name) throws IOException {
        final File dir = LabResults.shot(new File(tmp.getRoot(), "Lab/" + name), burst);
        final ScamLabFragment f = new ScamLabFragment();
        final Bundle b = new Bundle();
        b.putString("page", "pipeline");
        b.putString("dir", dir.getAbsolutePath());
        f.setArguments(b);
        controller.get().getSupportFragmentManager().beginTransaction().replace(android.R.id.content, f).commitNow();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return f;
    }

    private static View tagged(ScamLabFragment f, String tag) {
        return f.requireView().findViewWithTag(tag);
    }

    @Test public void aFailedJobShowsItsErrorNotAnotherJobsPictures() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        ScamLabFragment.runner = (app, job, progress) -> {
            runs.incrementAndGet();
            return LabResults.of(10, 200);
        };
        ScamLabFragment x = open("2026-10-26_23-24-30");
        waitForJobs();
        assertNotNull("shot X rendered", tagged(x, "lab_compare_view"));
        assertEquals(1, runs.get());

        // shot Y while the camera processes a photo: its run fails
        ScamLabFragment.runner = (app, job, progress) -> {
            runs.incrementAndGet();
            throw new IOException("A shot is being processed.");
        };
        final ScamLabFragment y = open("2026-10-26_23-24-31");
        waitForJobs();
        assertNull("no pictures of shot X under shot Y", tagged(y, "lab_compare_view"));
        final TextView status = (TextView) tagged(y, "lab_status");
        assertNotNull(status);
        assertTrue(String.valueOf(status.getText()), String.valueOf(status.getText()).contains("A shot is being processed."));
        assertNotNull(tagged(y, "lab_retry"));
        assertEquals(2, runs.get());

        // a redraw does not run the failed job again
        for (int i = 0; i < 3; i++) y.render();
        waitForJobs();
        assertEquals(2, runs.get());

        // «Повторить» once the camera is done
        ScamLabFragment.runner = (app, job, progress) -> {
            runs.incrementAndGet();
            return LabResults.of(30, 120);
        };
        tagged(y, "lab_retry").performClick();
        waitForJobs();
        assertEquals(3, runs.get());
        assertNotNull("shot Y rendered", tagged(y, "lab_compare_view"));
        assertNull(tagged(y, "lab_retry"));
    }

    @Test public void aPageOpenedAgainDuringItsRenderShowsTheResult() throws Exception {
        final CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        ScamLabFragment.runner = (app, job, progress) -> {
            started.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return LabResults.of(10, 200);
        };
        open("2026-10-26_23-24-32");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        // Back and the same page again: a new fragment, the job still renders
        final ScamLabFragment again = open("2026-10-26_23-24-32");
        assertNull(tagged(again, "lab_compare_view"));
        release.countDown();
        waitForJobs();
        assertNotNull("the page on screen shows the result", tagged(again, "lab_compare_view"));
    }

    /**
     * The delete of a shot no longer waits for a render (it froze the UI): it is refused while that shot renders (the render
     * reads its burst), and a job of it waiting to run is dropped.
     */
    @Test public void aShotIsNotDeletedWhileItRenders() throws Exception {
        final CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        ScamLabFragment.runner = (app, job, progress) -> {
            runs.incrementAndGet();
            started.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return LabResults.of(10, 200);
        };
        open("2026-10-26_23-24-33");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        // another shot's page meanwhile: its job waits; then back to the list (the pages are gone)
        open("2026-10-26_23-24-34");
        controller.get().getSupportFragmentManager().beginTransaction().replace(android.R.id.content, new androidx.fragment.app.Fragment()).commitNow();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        final File rendered = new File(tmp.getRoot(), "Lab/2026-10-26_23-24-33"), waiting = new File(tmp.getRoot(), "Lab/2026-10-26_23-24-34");
        assertTrue(rendered.isDirectory());
        assertFalse("its render reads it", ScamLabFragment.deletable(rendered));
        assertTrue("a waiting job's shot can go", ScamLabFragment.deletable(waiting));
        release.countDown();
        waitForJobs();
        assertEquals("the dropped job did not run", 1, runs.get());
        assertTrue(ScamLabFragment.deletable(rendered));
    }

    /** lab-app-6: a second tap on «Открыть» while the series is imported starts no second import. */
    @Test public void aSeriesIsImportedOnceAtATime() {
        final File a = new File(tmp.getRoot(), "scam_burst-a.nch"), b = new File(tmp.getRoot(), "scam_burst-b.nch");
        assertTrue(ScamLabFragment.beginImport(a));
        try {
            assertFalse("a double tap", ScamLabFragment.beginImport(a));
            assertTrue("another series", ScamLabFragment.beginImport(b));
            ScamLabFragment.endImport(b);
        } finally {
            ScamLabFragment.endImport(a);
        }
        assertTrue("after its import", ScamLabFragment.beginImport(a));
        ScamLabFragment.endImport(a);
    }

    /** lab-app-8: the list writes Lab/.nomedia, so the folders of an earlier version leave the galleries before the next lab shot. */
    @Test public void theListHidesTheLabFoldersFromTheGalleries() throws Exception {
        final File root = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.root();
        final File dir = LabResults.shot(new File(root, "2026-10-26_23-24-35"), burst), marker = new File(root, ".nomedia");
        try {
            assertTrue(!marker.exists() || marker.delete());
            final ScamLabFragment list = new ScamLabFragment();
            list.setArguments(new Bundle());
            controller.get().getSupportFragmentManager().beginTransaction().replace(android.R.id.content, list).commitNow();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("the shot is listed", tagged(list, "lab_shot_" + dir.getName()));
            assertTrue(marker.isFile());
        } finally {
            com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.remove(dir);
        }
    }

    /** P363: a sandbox replaced or cleared goes through LabFiles (an earlier installation's file only goes through the folder grant). */
    @Test public void aSandboxIsDeletedThroughTheFolderGrant() throws Exception {
        final File dir = LabResults.shot(new File(tmp.getRoot(), "Lab/2026-10-26_23-24-36"), burst);
        final com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLabRun.Shot s =
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLabRun.Shot.load(dir);
        final java.util.TreeMap<String, Object> box = new java.util.TreeMap<>();
        box.put("pref_scam_hybrid_cdm", "0.4");
        ScamLabFragment.saveSandbox(s, box);
        final File f = new File(dir, ScamLabFragment.SANDBOX_FILE);
        assertTrue(f.isFile());
        try (MockedStatic<com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles> files =
                     mockStatic(com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            ScamLabFragment.saveSandbox(s, new java.util.TreeMap<>());
            files.verify(() -> com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.delete(
                    org.mockito.ArgumentMatchers.argThat(x -> x != null && ScamLabFragment.SANDBOX_FILE.equals(x.getName()))));
        }
        assertFalse("cleared", f.exists());
    }
}
