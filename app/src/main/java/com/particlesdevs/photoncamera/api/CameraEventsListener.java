package com.particlesdevs.photoncamera.api;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.capture.CaptureEventsListener;
import com.particlesdevs.photoncamera.processing.ProcessingEventsListener;

import java.io.File;

/**
 * Class primarily responsible for interfacing between camera/capture/processing related events and the UI
 */
public abstract class CameraEventsListener implements CaptureEventsListener, ProcessingEventsListener {
    protected String TAG = "CameraEventsListener";

    protected void logD(String msg) {
        Log.d(TAG, msg);
    }

    protected void logE(String msg) {
        Log.e(TAG, msg);
    }

    public abstract void onOpenCamera(CameraManager cameraManager);

    public abstract void onCameraRestarted();

    /**
     * P240 (speed-ux C4): the camera is being brought back after an error ({@code on}), or that recovery ended (a session runs
     * again, or it gave up and said so). The camera screen shows «Камера перезапускается…» meanwhile. Any thread.
     */
    public void onCameraRecovery(boolean on, String why) {}

    public abstract void onCharacteristicsUpdated(CameraCharacteristics characteristics);

    public abstract void onError(Object o);

    public abstract void onFatalError(String errorMsg);

    public abstract void onRequestTriggerMediaScanner(Uri f);
}
