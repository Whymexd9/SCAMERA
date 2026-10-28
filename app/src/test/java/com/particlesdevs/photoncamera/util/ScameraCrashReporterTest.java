package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * P214b (engineering.md B4): a crash report names its build and process, and SCAMERA-crash.log is capped (it was
 * append-only with no limit); the newest report stays at the end of SCAMERA-crash.log, where ExitReasons reads it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScameraCrashReporterTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void reportNamesBuildAndProcess() {
        String report = ScameraCrashReporter.report("2026-10-18 10:00:00",
                "SCAMERA 0.98 (30966) android.camera.cts cab4480a CI", "android.camera.cts:scam", "android.camera.cts",
                "main", new IllegalStateException("boom"));
        assertTrue(report, report.startsWith("SCAMERA crash report\n"));
        assertTrue(report, report.contains("\nbuild     : SCAMERA 0.98 (30966) android.camera.cts cab4480a CI\n"));
        assertTrue(report, report.contains("\nprocess   : android.camera.cts:scam\n"));
        assertTrue(report, report.contains("java.lang.IllegalStateException: boom"));
        assertTrue("the report of this build carries its line", ScameraCrashReporter.report("t", BuildIdentity.line(),
                "p", "p", "main", new RuntimeException()).contains("build     : " + BuildIdentity.line() + "\n"));
    }

    /**
     * Audit 2026-10-27 (lab-app-5): after a reinstall the shared SCAMERA-crash.log is an earlier install's, which the File API cannot
     * write or read (P363); the report goes to this install's own file beside it, and ExitReasons reads it there.
     */
    @Test public void unwritableSharedFileFallsBackToInstallStem() throws Exception {
        final android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(c.getPackageManager()).getInternalMutablePackageInfo(c.getPackageName()).firstInstallTime = 1_792_000_000_000L;
        final String stem = RotatingLogFile.installStem(c, ScameraCrashReporter.STEM);
        assertTrue(stem, stem != null && stem.startsWith("SCAMERA-crash-"));
        final File shared = ScameraFiles.file("SCAMERA-crash.log"), own = ScameraFiles.file(stem + ScameraCrashReporter.EXTENSION);
        own.delete();
        assertTrue("a path the File API cannot write", shared.mkdirs() || shared.isDirectory());
        try {
            ScameraCrashReporter.write(c, Thread.currentThread(), new IllegalStateException("after a reinstall"));
            final String text = new String(Files.readAllBytes(own.toPath()), StandardCharsets.UTF_8);
            assertTrue(text, text.startsWith("SCAMERA crash report\n"));
            assertTrue(text, text.contains("java.lang.IllegalStateException: after a reinstall"));
            final String read = ExitReasons.lastCrashReport(c);
            assertTrue(read, read.startsWith("SCAMERA crash report"));
            assertTrue(read, read.contains("after a reinstall"));
        } finally {
            own.delete();
            shared.delete();
        }
    }

    /** Audit 2026-10-27: a newer file that shows but cannot be read gives way to the next one (it was "crash file not read"). */
    @Test public void lastCrashReportSkipsAnUnreadableFile() throws Exception {
        File dir = tmp.newFolder("reports");
        File readable = new File(dir, "SCAMERA-crash-20261027-100000.log");
        Files.write(readable.toPath(), "old text\nSCAMERA crash report\nthe stack\n".getBytes(StandardCharsets.UTF_8));
        // an earlier install's file: listed (isFile), newest, but the File API cannot open it
        File denied = new File(dir, "SCAMERA-crash.log") {
            @Override public boolean isFile() { return true; }
            @Override public long lastModified() { return Long.MAX_VALUE; }
            @Override public long length() { return 100; }
        };
        assertEquals("SCAMERA crash report\nthe stack", ExitReasons.lastCrashReport(Arrays.asList(denied, readable)));
        assertTrue(ExitReasons.lastCrashReport(Arrays.asList(denied)).startsWith("crash file not read: "));
        assertEquals("", ExitReasons.lastCrashReport(Arrays.asList(new File(dir, "none.log"))));
    }

    @Test public void theFileIsCappedAndTheNewestReportStaysInIt() throws Exception {
        File dir = tmp.newFolder("SCAMERA");
        File crash = new File(dir, "SCAMERA-crash.log");
        assertTrue(ScameraCrashReporter.append(crash, "SCAMERA crash report\nfirst\n"));
        assertTrue(ScameraCrashReporter.append(crash, "SCAMERA crash report\nsecond\n"));
        assertEquals("below the cap reports are appended", "SCAMERA crash report\nfirst\nSCAMERA crash report\nsecond\n",
                new String(Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8));

        byte[] full = new byte[(int) ScameraCrashReporter.CAP_BYTES];
        Arrays.fill(full, (byte) 'x');
        Files.write(crash.toPath(), full);
        assertTrue(ScameraCrashReporter.append(crash, "SCAMERA crash report\nthird\n"));
        assertArrayEquals("the full file moved to .1", full, Files.readAllBytes(new File(dir, "SCAMERA-crash.1.log").toPath()));
        assertEquals("SCAMERA crash report\nthird\n", new String(Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8));

        Files.write(crash.toPath(), full);
        assertTrue(ScameraCrashReporter.append(crash, "SCAMERA crash report\nfourth\n"));
        assertEquals("at most two files stay", 2, dir.list().length);
        assertEquals("SCAMERA crash report\nfourth\n", new String(Files.readAllBytes(crash.toPath()), StandardCharsets.UTF_8));
    }
}
