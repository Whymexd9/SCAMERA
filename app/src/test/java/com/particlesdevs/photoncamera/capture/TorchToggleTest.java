package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Owner, 2026-10-19 (X200 Ultra): «если я оставляю кнопку с фонариком включенной и перезагружаю приложение, то фонарик
 * включается, но если после этого я его выключу и опять включу то он не работает». Switched on in the running repeating
 * request the vivo HAL kept the torch dark; a session created with it lights it. The shot keeps the torch on as well.
 */
public class TorchToggleTest {
    private static String src() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java").toPath()), StandardCharsets.UTF_8);
    }

    @Test public void switchingTheTorchOnRecreatesTheVivoSession() throws Exception {
        String s = src();
        int toggle = s.indexOf("public void setPreviewAEModeRebuild(int aeMode)");
        int end = s.indexOf("\n    }", toggle);
        assertTrue(toggle > 0);
        String body = s.substring(toggle, end);
        assertTrue(body.contains("torchNeedsSession()") && body.contains("restartSessionForTorch(0)"));
        int restart = s.indexOf("private void restartSessionForTorch(int attempt)");
        assertTrue("never under a shot", s.indexOf("shotInFlight()", restart) > restart);
        assertTrue(s.indexOf("createCameraPreviewSession(false)", restart) > restart);
    }

    @Test public void theShotKeepsTheTorchOn() throws Exception {
        assertTrue(src().contains("if (mFlashed || (mFlashSupported && PreferenceKeys.getAeMode() == 0)) captureBuilder.set(FLASH_MODE, FLASH_MODE_TORCH);"));
    }
}
