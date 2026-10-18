package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * Owner, 2026-10-19 (vivo X300, V2515): every camera's first session of an app run was refused with the vivo EIS session key
 * (endConfigure -38), the HAL closed the device (onError 4) and the owner saw «Сбой камеры (код 4), перезапуск» or
 * «Модуль 3× не запускается … возврат к предыдущему». The refusal is kept per firmware, and the error that follows a refused
 * configuration under retry reopens the camera without a message.
 */
public class PlainPreviewCamerasTest {
    @Test public void storedCamerasLastForTheirFirmwareOnly() {
        assertEquals(new TreeSet<>(Arrays.asList("2", "3", "4")), PlainPreviewCameras.stored("fw1", "fw1", "2,3, 4,"));
        assertTrue(PlainPreviewCameras.stored("fw2", "fw1", "2,3").isEmpty());
        assertTrue(PlainPreviewCameras.stored("fw1", null, "2").isEmpty());
        assertTrue(PlainPreviewCameras.stored("fw1", "fw1", null).isEmpty());
        Set<String> s = new TreeSet<>(Arrays.asList("5", "2"));
        assertEquals("2,5", PlainPreviewCameras.join(s));
        assertEquals(s, PlainPreviewCameras.stored("fw", "fw", PlainPreviewCameras.join(s)));
    }

    @Test public void theX300NeverGetsTheKey() {
        assertTrue(PlainPreviewCameras.knownRefuser("PD2502", "V2502A"));
        assertTrue(PlainPreviewCameras.knownRefuser("V2515", "V2515"));
        assertFalse(PlainPreviewCameras.knownRefuser("PD2454", "V2454A"));
        assertFalse(PlainPreviewCameras.knownRefuser(null, null));
    }

    @Test public void theFallbackStepIsUndoneWhenTheHalDied() throws Exception {
        String s = src();
        int quiet = s.indexOf("if (afterRetry) {");
        assertTrue("the configure fallback step of a device the HAL closed is undone", s.indexOf("undoFallbackStep(", quiet) > quiet);
        int fallback = s.indexOf("private boolean retryConfigureFallback(");
        assertTrue(s.indexOf("mFallbackUndo = ", fallback) > fallback);
        assertTrue("an open failure right after a quiet reopen keeps the overlay off", s.contains("quietRecovery()"));
    }

    @Test public void theSetWorksWithoutStorage() {
        PlainPreviewCameras.resetForTest();
        assertFalse(PlainPreviewCameras.contains("2"));
        assertTrue(PlainPreviewCameras.addRefused("2"));
        assertFalse(PlainPreviewCameras.addRefused("2"));
        assertTrue(PlainPreviewCameras.contains("2"));
        assertTrue(PlainPreviewCameras.add("3"));
        assertFalse(PlainPreviewCameras.add("3"));
        PlainPreviewCameras.resetForTest();
    }

    private static String src() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java").toPath()), StandardCharsets.UTF_8);
    }

    @Test public void theErrorAfterARefusedConfigurationIsQuiet() throws Exception {
        String s = src();
        int onError = s.indexOf("public void onError(@NonNull CameraDevice cameraDevice, int error)");
        assertTrue(onError > 0);
        int quiet = s.indexOf("if (afterRetry) {", onError);
        int revert = s.indexOf("revertToLastGoodModule(\"error \"", onError);
        int message = s.indexOf("Сбой камеры (код ", onError);
        assertTrue("the quiet reopen comes before the module revert and the message", quiet > 0 && quiet < revert && quiet < message);
        assertTrue("no overlay after a refused configuration", s.contains("if (isCameraResumed && !afterRetry) recovering(true"));
        assertTrue("onError waits for the session's own callback", s.indexOf("mConfiguringDevice == cameraDevice", onError) > onError);
        int failed = s.indexOf("private void configureFailed(");
        int mark = s.indexOf("mRetryDevice = sessionDevice;", failed);
        int firstRetry = s.indexOf("retryWithoutDcgRoute(cameraCaptureSession", failed);
        int unmark = s.indexOf("mRetryDevice = null; // nothing left to retry", failed);
        int lastRetry = s.indexOf("retryConfigureFallback(cameraCaptureSession)", failed);
        assertTrue("the retry chain is marked before its first step and unmarked after its last",
                failed > 0 && mark > failed && mark < firstRetry && unmark > lastRetry);
        assertTrue("the vivo-key refusal is kept per firmware", s.contains("PlainPreviewCameras.addRefused(physicalID)"));
        assertFalse("no process-only set left", s.contains("sPlainPreviewCameras"));
    }
}
