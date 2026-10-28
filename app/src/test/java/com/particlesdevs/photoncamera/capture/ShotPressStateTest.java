package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.control.ZoomController;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A queued shot is processed with what its own press saw - its rotation, its camera, its digital-zoom crop - not what the camera
 * holds when its processing starts (behind earlier merges on SCAM_PROCESSING, after the gyro wait). Bugs
 * capture-queued-shot-press-state and capture-zoom-crop-not-per-shot.
 */
public class ShotPressStateTest {
    private static String src(String path) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/com/particlesdevs/photoncamera/" + path).toPath()),
                StandardCharsets.UTF_8);
    }

    private static String between(String s, String from, String to) {
        final int a = s.indexOf(from);
        assertTrue(from, a > 0);
        final int b = s.indexOf(to, a);
        assertTrue(to, b > a);
        return s.substring(a, b);
    }

    @Test
    public void theQueuedShotKeepsItsPressRotationAndCamera() throws Exception {
        final String s = src("capture/CaptureController.java");
        final String shot = between(s, "private void captureStillPicture() {", "\n    private ");
        final int press = shot.indexOf("cameraRotation = ScameraApp.getGravity().getCameraRotation(mSensorOrientation);");
        final int rotation = shot.indexOf("final int shotRotation = cameraRotation;");
        final int camera = shot.indexOf("final CameraCharacteristics pressCharacteristics = mCameraCharacteristics;");
        assertTrue(press > 0 && rotation > press && camera > press);
        final String completion = between(shot, "final ImageSaver saver = shotSaver;", "} catch (Exception e){");
        assertTrue(completion.contains("final CameraCharacteristics shotCharacteristics = pressCharacteristics;"));
        assertFalse("no live camera field in the shot's processing", completion.contains("= mCameraCharacteristics;"));
        assertEquals("both runRaw calls take the press's rotation", 2, count(completion, "shotShakiness, shotRotation, shotExposures);"));
        assertFalse(completion.contains("cameraRotation, shotExposures"));
    }

    @Test
    public void everyShotRecordsItsZoomAndCameraAtThePress() throws Exception {
        final String s = src("capture/CaptureController.java");
        final String shot = between(s, "private void captureStillPicture() {", "\n    private ");
        final int saver = shot.indexOf("mImageSaver = new ImageSaver(cameraEventsListener);");
        assertTrue("on the shot's own saver (after the drain: rawCropOfShot included)",
                shot.indexOf("mImageSaver.zoomResidual = com.particlesdevs.photoncamera.control.ZoomController.shotResidual();") > saver
                        && saver > shot.indexOf("drainZslNormalFrames("));
        assertTrue(shot.indexOf("mImageSaver.cameraIdAtPress = ScameraApp.getSettings().mCameraID;") > saver);
        // FULLRES marked no crop of its own before
        final String fullRes = between(s, "private void captureVivoSdkFullResChecked() {", "\n    private ");
        final int mark = fullRes.indexOf("ZoomController.markShot();");
        assertTrue(mark > 0 && mark < fullRes.indexOf("takeNativeZsl("));
        assertTrue(fullRes.indexOf("saver.zoomResidual = com.particlesdevs.photoncamera.control.ZoomController.shotResidual();") > mark);
        assertTrue(fullRes.contains("saver.cameraIdAtPress = ScameraApp.getSettings().mCameraID;"));
        // carried through the queue to the processing
        final String detach = between(src("processing/ImageSaver.java"), "public synchronized void detachForQueue(Boolean hybridRoute)", "\n    }");
        assertTrue(detach.contains("saver.ownedZoomResidual = zoomResidual;") && detach.contains("saver.ownedCameraId = cameraIdAtPress;"));
        final String run = src("processing/DefaultSaver.java");
        assertTrue(run.contains("scamProcessor.ownedZoomResidual = ownedZoomResidual != null ? ownedZoomResidual : zoomResidual;"));
        assertTrue(run.contains("scamProcessor.ownedCameraId = ownedFrames != null ? ownedCameraId : cameraIdAtPress;"));
        final String processor = src("processing/processor/ScamProcessor.java");
        assertTrue(processor.contains("ZoomController.beginProcessing(zoomResidual);"));
        assertTrue(processor.contains("final float zoomCrop = zoomResidual;"));
        assertTrue(processor.contains("processingParameters.cameraIdAtPress = cameraIdAtPress;"));
        assertFalse("nothing in the processing reads the last press's crop", processor.contains("ZoomController.shotResidual()"));
        assertFalse(between(src("settings/PreferenceKeys.java"), "public static boolean cropShot()", "\n    }")
                .contains("shotResidual()"));
    }

    @Test
    public void theProcessingCropIsTheShotsNotTheNextPresss() {
        try {
            ZoomController.beginProcessing(2f);
            ZoomController.markShot(); // the next press at 1x meanwhile
            assertEquals(2f, ZoomController.processingResidual(), 0f);
            ZoomController.beginProcessing(0.5f);
            assertEquals("never below 1", 1f, ZoomController.processingResidual(), 0f);
        } finally {
            ZoomController.beginProcessing(1f);
        }
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }
}
