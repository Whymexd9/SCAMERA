package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Audit 2026-10-27: the lab's folders in Download/SCAMERA/Lab - two kept series of one second open in two folders (lab-app-6),
 * the lab's working photos and a kept series' photo sidecar are no photos of the galleries (lab-app-8).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamLabFoldersTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private MockedStatic<ScameraApp> camera;
    private SettingsManager manager;

    @Before public void setUp() {
        final Context context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
    }

    @After public void tearDown() {
        if (camera != null) camera.close();
    }

    private File base;

    /** A kept series beside its sidecar metadata, with its file time. */
    private File series(String name, long time) throws Exception {
        if (base == null) base = new ScamLabBurstTest() {{ tmp = ScamLabFoldersTest.this.tmp; }}.burst(12);
        final File f = new File(tmp.getRoot(), name);
        Files.copy(base.toPath(), f.toPath());
        Files.write(ScamLab.sidecar(f, ".json").toPath(), "{\"prefs\":{}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(f.setLastModified(time));
        return f;
    }

    @Test public void twoSeriesOfOneSecondOpenInTwoFolders() throws Exception {
        final long time = 1_792_000_000_000L;
        final File a = series("scam_burst-a.nch", time), b = series("scam_burst-b.nch", time);
        final File first = ScamLab.importSeries(a), second = ScamLab.importSeries(b);
        assertNotEquals(first, second);
        assertTrue(first.getName(), first.getName().endsWith("_imp"));
        assertTrue(second.getName(), second.getName().endsWith("_2_imp"));
        assertEquals(a.getAbsoluteFile(), ScamLab.burstOf(first).getAbsoluteFile());
        assertEquals(b.getAbsoluteFile(), ScamLab.burstOf(second).getAbsoluteFile());
        assertTrue("the lab's folders are no gallery albums", new File(ScamLab.root(), ".nomedia").isFile());
        ScamLab.remove(first);
        ScamLab.remove(second);
    }

    @Test public void theKeptSeriesPhotoSidecarIsNoImageFile() throws Exception {
        final File b = new File("/x/Download/SCAMERA/scam_burst-20261026-232432.bin");
        final String name = ScamLab.sidecar(b, ScamLab.PREVIEW_SIDECAR).getName().toLowerCase(Locale.ROOT);
        assertEquals("scam_burst-20261026-232432.preview", name);
        for (String ext : new String[]{".jpg", ".jpeg", ".png", ".webp", ".heic"}) assertFalse(name, name.endsWith(ext));

        // an import takes the .preview sidecar, and the .jpg of a series kept before it
        final File kept = series("scam_burst-new.nch", 1_792_000_100_000L), older = series("scam_burst-old.nch", 1_792_000_200_000L);
        Files.write(ScamLab.sidecar(kept, ScamLab.PREVIEW_SIDECAR).toPath(), new byte[]{1, 2, 3});
        Files.write(ScamLab.sidecar(older, ".jpg").toPath(), new byte[]{4, 5});
        final File a = ScamLab.importSeries(kept), o = ScamLab.importSeries(older);
        assertEquals(3, Files.readAllBytes(new File(a, ScamLab.PREVIEW).toPath()).length);
        assertEquals(2, Files.readAllBytes(new File(o, ScamLab.PREVIEW).toPath()).length);
        ScamLab.remove(a);
        ScamLab.remove(o);
    }

    /** P363: «Как снято» again goes through LabFiles (an earlier installation's marker only goes through the folder grant). */
    @Test public void settingsAsShotAgainDeletesThroughTheFolderGrant() throws Exception {
        final File dir = tmp.newFolder("2026-10-26_23-24-40");
        ScamLab.settingsNow(dir, true);
        assertTrue(ScamLab.settingsNow(dir));
        try (MockedStatic<LabFiles> files = mockStatic(LabFiles.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            ScamLab.settingsNow(dir, false);
            files.verify(() -> LabFiles.delete(new File(dir, ScamLab.NOW)));
        }
        assertFalse(ScamLab.settingsNow(dir));
    }

    @Test public void aLabShotsFolderIsNoGalleryAlbum() {
        final File marker = new File(ScamLab.root(), ".nomedia");
        marker.delete();
        manager.getDefaultPreferences().edit().putBoolean(ScamLab.PREF, true).commit();
        ScamLab.beginShot(true);
        try {
            assertTrue(marker.isFile());
        } finally {
            ScamLab.endShot(); // incomplete: its folder goes
        }
    }
}
