package com.particlesdevs.photoncamera.util;

import android.app.Application;

import com.particlesdevs.photoncamera.settings.ScamDevFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P191 (owner, 2026-10-17: «переноси всё туда чтобы в data ничего не хранилось»): the app's own files live in Download/SCAMERA,
 * the folder grant reaches its root, the old Android/data file is moved only after a verified copy and never over a new one.
 * Robolectric: the android.jar stubs do not hold Environment.DIRECTORY_DOWNLOADS.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScameraFilesTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void rootFolderIsDownloadScameraItself() {
        assertEquals("Download/SCAMERA", ScameraFolder.relativeOf(""));
        assertEquals("Download/SCAMERA/XML", ScameraFolder.relativeOf("XML"));
        assertEquals("Download/SCAMERA", ScameraFolder.ROOT.relative);
        assertEquals("no trailing slash", "primary:Download/SCAMERA", ScameraFolder.ROOT.documentId());
        assertEquals("the sub-folders unchanged", "primary:Download/SCAMERA/XML", ScameraFolder.CONFIGS.documentId());
        assertEquals("Download/SCAMERA/ToneCurves", ScameraFolder.CURVES.relative);
        // the grant of Download/SCAMERA (or of Download) reaches the root; a sibling folder does not
        assertTrue(ScameraFolder.covers("primary:Download/SCAMERA", ScameraFolder.ROOT.documentId()));
        assertTrue(ScameraFolder.covers("primary:Download", ScameraFolder.ROOT.documentId()));
        assertFalse(ScameraFolder.covers("primary:Download/SCAMERA/XML", ScameraFolder.ROOT.documentId()));
        // every name of the root: scam_dev.txt and the adb markers without an extension
        assertTrue(ScameraFolder.ROOT.accepts("scam_dev.txt"));
        assertTrue(ScameraFolder.ROOT.accepts("dump-forward"));
        assertEquals("Download/SCAMERA", ScameraFiles.RELATIVE);
        assertEquals("Download/SCAMERA/scam_dev.txt", ScamDevFile.SHOWN);
    }

    @Test public void moveCopiesVerifiesAndDeletesTheOldFile() throws Exception {
        File old = tmp.newFile("scam_dev.txt");
        byte[] data = "hybrid 1\nkeep_burst 1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(old.toPath(), data);
        File target = new File(tmp.newFolder("SCAMERA"), "scam_dev.txt");
        assertTrue(ScameraFiles.move(old, target));
        assertArrayEquals(data, Files.readAllBytes(target.toPath()));
        assertFalse("nothing stays in the old place", old.exists());
    }

    @Test public void moveNeverReplacesTheNewFile() throws Exception {
        File old = tmp.newFile("old.txt");
        Files.write(old.toPath(), "hybrid 0\n".getBytes(StandardCharsets.UTF_8));
        File target = tmp.newFile("new.txt");
        Files.write(target.toPath(), "hybrid 1\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(ScameraFiles.move(old, target));
        assertEquals("hybrid 1\n", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertTrue("the old file stays when nothing was moved", old.isFile());
    }
}
