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
