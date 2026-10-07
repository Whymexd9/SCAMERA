package com.particlesdevs.photoncamera.ui.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.CountDownTimer;

import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;
import android.view.View;

import androidx.lifecycle.Observer;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.control.CountdownTimer;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.ui.camera.model.TopBarSettingsData;
import com.particlesdevs.photoncamera.ui.camera.views.AuxButtonsLayout;

/**
 * Implementation of {@link CameraUIEventsListener}
 * <p>
 * Responsible for converting user inputs into actions
 */
final class CameraUIController implements CameraUIEventsListener,
        Observer<TopBarSettingsData<?, ?>>, AuxButtonsLayout.AuxButtonListener {
    private static final String TAG = "CameraUIController";
    private final CameraFragment cameraFragment;
    private CountDownTimer countdownTimer;
    private View shutterButton;

    public CameraUIController(CameraFragment cameraFragment) {
        this.cameraFragment = cameraFragment;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.shutter_button:
                shutterButton = view;
                switch (PhotonCamera.getSettings().selectedMode) {
                    case PHOTO:
                    case MOTION:
                    case NIGHT:
                        if (view.isHovered()) resetTimer();
                        else startTimer();
                        break;
                    case UNLIMITED:
                    case RAWVIDEO:
                        if (!cameraFragment.captureController.onUnlimited) {
                            cameraFragment.captureController.callUnlimitedStart();
                            view.setActivated(false);
                        } else {
                            cameraFragment.captureController.callUnlimitedEnd();
                            view.setActivated(true);
                        }
                        break;
                    case VIDEO:
                        if (!cameraFragment.captureController.mIsRecordingVideo) {
                            cameraFragment.captureController.VideoStart();
                            view.setActivated(false);
                        } else {
                            cameraFragment.captureController.VideoEnd();
                            view.setActivated(true);
                        }
                        break;
                }
                break;
            case R.id.settings_button:
                cameraFragment.launchSettings();
                break;

            case R.id.gallery_image_button:
                cameraFragment.launchGallery();
                break;

            // Flash, self-timer and grid are quick-settings tiles now (P25); HDRX, EIS, FPS and the Quad toggle are gone.
            case R.id.flip_camera_button:
                cameraFragment.onLensSwitch();
                view.animate().rotationBy(180).setDuration(450).start();
                //cameraFragment.textureView.animate().rotationBy(360).setDuration(450).start();
                //PreferenceKeys.setCameraID(cycler(PreferenceKeys.getCameraID()));
                setID(cameraFragment.flip(PreferenceKeys.getCameraID()));
                this.restartCamera();
                break;
        }
    }

    private int getTimerValue(Context context) {
        int[] timerValues = context.getResources().getIntArray(R.array.countdowntimer_entryvalues);
        return timerValues[PreferenceKeys.getCountdownTimerIndex()];
    }

    private void startTimer() {
        if (this.shutterButton != null) {
            this.shutterButton.setHovered(true);
            final int seconds = getTimerValue(this.shutterButton.getContext());
            // Before start(): a 0 s timer (no self-timer) finishes inside start() and takes the shot at once.
            if (seconds > 0) cameraFragment.sounds().timerStart();
            this.countdownTimer = new CountdownTimer(
                    cameraFragment.findViewById(R.id.frameTimer),
                    seconds * 1000L, 1000,
                    this::onTimerFinished).start();
        }
    }

    private void resetTimer() {
        if (this.countdownTimer != null) this.countdownTimer.cancel();
        cameraFragment.sounds().timerStop();
        if (this.shutterButton != null) this.shutterButton.setHovered(false);
    }

    @Override
    public void onAuxButtonClicked(String id) {
        Log.d(TAG, "onAuxButtonClicked() called with: id = [" + id + "]");
        android.content.Context context = cameraFragment.getContext();
        if (context != null && com.particlesdevs.photoncamera.api.CameraManager2.isAuxiliarySensor(
                (android.hardware.camera2.CameraManager) context.getSystemService(android.content.Context.CAMERA_SERVICE), id)) {
            // Not opened: the camera stays on the current module (see CameraManager2.isAuxiliarySensor).
            Log.w(TAG, "camera " + id + " is a MONO / NIR auxiliary stream, not opened");
            cameraFragment.showSnackBar(Lang.t(context, "Камера " + id + " — монохромный служебный поток, снимать с неё нельзя",
                    "Camera " + id + " is a monochrome auxiliary stream and cannot take photos"));
            return;
        }
        cameraFragment.onLensSwitch();
        setID(id);
        this.restartCamera();

    }

    private void setID(String input) {
        PreferenceKeys.setCameraID(String.valueOf(input));
    }

    @Override
    public void onCameraModeChanged(CameraMode cameraMode) {
        PreferenceKeys.setCameraModeOrdinal(cameraMode.ordinal());
        Log.d(TAG, "onCameraModeChanged() called with: cameraMode = [" + cameraMode + "]");
        switch (cameraMode) {
            case PHOTO:
            case MOTION:
            case NIGHT:
            case UNLIMITED:
            case RAWVIDEO:
            default:
                break;
            case VIDEO:
                PreferenceKeys.setCameraModeOrdinal(CameraMode.VIDEO.ordinal());
                break;
        }
        this.restartCamera();
    }

    @Override
    public void onPause() {
        this.resetTimer();
    }

    private void restartCamera() {
        this.resetTimer();
        cameraFragment.captureController.restartCamera();
    }

    private void onTimerFinished() {
        cameraFragment.sounds().timerStop(); // cut at the timer's end; the shutter sound follows at the shot
        this.shutterButton.setHovered(false);
        this.shutterButton.setActivated(false);
        this.shutterButton.setClickable(false);
        if (!cameraFragment.captureController.takePicture()
                || cameraFragment.captureController.niceShutterQueues()) {
            // A busy/not-ready controller did not accept a shot, or NICE queues
            // further presses during capture: the button stays usable.
            this.shutterButton.setActivated(true);
            this.shutterButton.setClickable(true);
        }
    }

    @Override
    public void onChanged(TopBarSettingsData<?, ?> topBarSettingsData) {
        if (topBarSettingsData != null && topBarSettingsData.getType() != null && topBarSettingsData.getValue() != null) {
            if (topBarSettingsData.getType() instanceof SettingType) {
                SettingType type = (SettingType) topBarSettingsData.getType();
                Object value = topBarSettingsData.getValue();
                switch (type) {
                    case FLASH:
                        PreferenceKeys.setAeMode((Integer) value); // 0 torch, 1 off
                        cameraFragment.captureController.setPreviewAEModeRebuild(PreferenceKeys.getAeMode());
                        break;
                    case HDRX:
                        PreferenceKeys.setHdrX(value.equals(1));
                        if (value.equals(1))
                            CaptureController.setTargetFormat(CaptureController.RAW_FORMAT);
                        else
                            CaptureController.setTargetFormat(CaptureController.YUV_FORMAT);
                        this.restartCamera();
                        break;
                    case QUAD:
                        PreferenceKeys.setQuadBayer(value.equals(1));
                        this.restartCamera();
                        break;
                    case GRID:
                        PreferenceKeys.setGridValue((Integer) value);
                        cameraFragment.invalidateSurfaceView();
                        break;
                    case FPS_60:
                        PreferenceKeys.setFpsMode((Integer) value);
                        cameraFragment.captureController.applyFpsRange();
                        break;
                    case TIMER:
                        PreferenceKeys.setCountdownTimerIndex((Integer) value);
                        break;
                    case EIS:
                        PreferenceKeys.setEisPhoto(value.equals(1));
                        break;
                    case RAW:
                        PreferenceKeys.setSaveRaw((Integer) value);
                        break;
                    case BRACKETING:
                        PreferenceKeys.setBracketingMode((Integer) value);
                        // Update HDR class to use the new bracketing mode
                        IsoExpoSelector.HDR = (Integer) value > 0;
                        break;
                    case AE_METERING_STD:
                        PreferenceKeys.setAeMeteringStd((Integer) value);
                        cameraFragment.captureController.applyAeMetering();
                        break;
                    case HYBRID_OUTPUT:
                        PreferenceKeys.setHybridOutputIndex((Integer) value);
                        break;
                    case HYBRID_DOWNSAMPLER:
                        PreferenceKeys.setHybridDownsamplerIndex((Integer) value);
                        break;

                }
                cameraFragment.cameraFragmentBinding.layoutTopbar.invalidateAll();
            }
        }

    }
}
