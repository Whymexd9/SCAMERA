package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CaptureRequest;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P411: «Мерцание ламп» reaches the camera's AE as the reference app's (CONTROL_AE_ANTIBANDING_MODE from the setting on the preview
 * and the capture requests, the repeating request rebuilt on a change); a mode the camera does not list falls back to AUTO.
 */
public class AntibandingTest {
    private static String read(String path) throws Exception {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void theSettingIsTheCamerasMode() {
        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO, Antibanding.mode(-1));
        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, Antibanding.mode(100));
        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ, Antibanding.mode(120));
        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF, Antibanding.mode(0));
    }

    @Test public void anUnlistedModeFallsBackToAuto() {
        final int[] all = {0, 1, 2, 3}, autoOnly = {3}, offOnly = {0};
        assertEquals(1, Antibanding.supported(1, all));
        assertEquals(3, Antibanding.supported(1, autoOnly));
        assertEquals(-1, Antibanding.supported(1, offOnly));
        assertEquals(2, Antibanding.supported(2, null));
    }

    @Test public void everyRequestTakesIt() throws Exception {
        final String cc = read("src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java");
        assertTrue("the preview", cc.contains("Antibanding.apply(mPreviewRequestBuilder, mCameraCharacteristics); // P411"));
        assertTrue("the capture", cc.contains("Antibanding.apply(captureBuilder, mCameraCharacteristics); // P411"));
        assertTrue("the SDK FULLRES capture", read("src/main/java/com/particlesdevs/photoncamera/capture/VivoSdkFullResCapture.java")
                .contains("Antibanding.apply(builder, null); // P411"));
        assertTrue("the vivo stock preview", read("src/main/java/com/particlesdevs/photoncamera/capture/ScamPreview.java")
                .contains("Antibanding.mode(com.particlesdevs.photoncamera.settings.PreferenceKeys.antibandingSetting())"));
        assertTrue("a change in the shade", read("src/main/java/com/particlesdevs/photoncamera/ui/camera/CameraFragment.java")
                .contains("captureController.applyAntibanding();"));
    }
}
