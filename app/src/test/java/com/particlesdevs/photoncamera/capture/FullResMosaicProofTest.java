package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Owner, 2026-10-19 (X200 Ultra 200 MP, a blurred flat sheet: «Native FULLRES mosaic has not been verified from RAW pixels»,
 * the shot lost): a scene the detector cannot read no longer drops the shot when the HAL reported the sensor mode or the
 * camera's mosaic was proven before; a contradicting still still fails at once.
 */
public class FullResMosaicProofTest {
    @Test public void aProofIsRememberedPerProfile() {
        VivoSdkFullResCapture.MosaicProof.clear();
        String key = "16320x12288/7/4";
        assertFalse(VivoSdkFullResCapture.MosaicProof.known(key));
        VivoSdkFullResCapture.MosaicProof.remember(key);
        assertTrue(VivoSdkFullResCapture.MosaicProof.known(key));
        assertFalse("another profile is not vouched for", VivoSdkFullResCapture.MosaicProof.known("8192x6144/5/2"));
        VivoSdkFullResCapture.MosaicProof.clear();
    }

    @Test public void onlyAnUnreadableSceneIsVouchedFor() throws Exception {
        String s = new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/capture/VivoSdkFullResCapture.java").toPath()), StandardCharsets.UTF_8);
        int finish = s.indexOf("private void finish()");
        int remember = s.indexOf("if (nativeMosaicVerified) MosaicProof.remember(key);", finish);
        int vouch = s.indexOf("else if (nativeModeReported || MosaicProof.known(key))", finish);
        int fail = s.indexOf("else throw new IllegalStateException(\"Native FULLRES mosaic has not been verified from RAW pixels\");", finish);
        assertTrue(finish > 0 && remember > finish && vouch > remember && fail > vouch);
        assertTrue("a contradicting still fails at once", s.contains("serial.fail(\"Native FULLRES mosaic does not match its sensor profile\")"));
        assertTrue(s.contains("if (mode != null) nativeModeReported = true;"));
    }
}
