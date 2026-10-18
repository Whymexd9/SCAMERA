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
import com.particlesdevs.photoncamera.app.ScameraApp;
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
                switch (ScameraApp.getSettings().selectedMode) {
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
            case R.id.motion_mode_button: {
                // P86: the «Движение» mode; its AE frame rate is a session parameter, so the session starts again
                final boolean on = !com.particlesdevs.photoncamera.settings.MotionMode.on();
                com.particlesdevs.photoncamera.settings.MotionMode.set(on);
                cameraFragment.refreshTopBar();
                cameraFragment.showSnackBar(Lang.t(view.getContext(), on ? "Движение: вкл." : "Движение: выкл.", on ? "Motion: on" : "Motion: off"));
                Log.i(TAG, "motion mode " + (on ? "on" : "off"));
                this.restartCamera();
                break;
            }
            case R.id.torch_button: {
                // SIMPLE_UI §1: the torch (pref_ae_mode 0 torch, 1 off), as the quick-sheet tile
                int mode = PreferenceKeys.getAeMode() == 0 ? 1 : 0;
                PreferenceKeys.setAeMode(mode);
                cameraFragment.captureController.setPreviewAEModeRebuild(PreferenceKeys.getAeMode());
                cameraFragment.refreshTopBar();
                cameraFragment.showSnackBar(Lang.t(view.getContext(), mode == 0 ? "Фонарик: вкл." : "Фонарик: выкл.", mode == 0 ? "Torch: on" : "Torch: off"));
                break;
            }
            case R.id.timer_button: {
                // SIMPLE_UI §1: off -> 3 s -> 10 s -> off
                int[] values = view.getResources().getIntArray(R.array.countdowntimer_entryvalues);
                int index = (PreferenceKeys.getCountdownTimerIndex() + 1) % values.length;
                PreferenceKeys.setCountdownTimerIndex(index);
                cameraFragment.refreshTopBar();
                cameraFragment.showSnackBar(values[index] > 0 ? Lang.t(view.getContext(), "Таймер: " + values[index] + " с", "Timer: " + values[index] + " s")
                        : Lang.t(view.getContext(), "Таймер: выкл.", "Timer: off"));
                break;
            }
            case R.id.size_badge: {
                // SIMPLE_UI §1: the next photo size this module has (12 -> 50 -> 200 -> 12), the FULLRES switch
                int next = PhotoSize.next(PhotoSize.current(), PhotoSize.fullAvailable(), PhotoSize.maxAvailable());
                if (next == PhotoSize.current() && !PhotoSize.fullAvailable()) {
                    cameraFragment.showSnackBar(Lang.t(view.getContext(), "50 Мп — нет на этом объективе", "50 MP: not on this lens"));
                    break;
                }
                cameraFragment.showSnackBar(PhotoSize.set(cameraFragment, next));
                cameraFragment.refreshTopBar();
                break;
            }
            case R.id.dcg_badge: {
                // SIMPLE_UI §1: DCG 14 bit, the same switch as «Для опытных → 14 бит (DCG)» (Dcg14Mode)
                cameraFragment.showSnackBar(Dcg14Button.toggleMode(cameraFragment));
                cameraFragment.refreshTopBar();
                break;
            }
            case R.id.format_badge:
                cameraFragment.openFormatChooser();
                break;

            case R.id.gallery_image_button:
                cameraFragment.launchGallery();
                break;

            // Flash, self-timer and grid are quick-settings tiles now (P25); EIS, FPS and the Quad toggle are gone.
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
            Log.w(TAG, "camera " + id + " is an auxiliary stream (MONO / NIR or no lens / gain / exposure), not opened");
            cameraFragment.showSnackBar(Lang.t(context, "Камера " + id + " — служебный поток, снимать с неё нельзя",
                    "Camera " + id + " is an auxiliary stream and cannot take photos"));
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
                || cameraFragment.captureController.scamShutterQueues()) {
            // A busy/not-ready controller did not accept a shot, or SCAM queues
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
                    case FORMAT_CHOICE: {
                        // Save mode and codec together; the capture and saving read them as before.
                        com.particlesdevs.photoncamera.settings.FormatChoice before = com.particlesdevs.photoncamera.settings.FormatChoice.current();
                        com.particlesdevs.photoncamera.settings.FormatChoice.store(
                                com.particlesdevs.photoncamera.settings.FormatChoice.values()[(Integer) value]);
                        Log.i(TAG, "format choice " + before + " -> " + com.particlesdevs.photoncamera.settings.FormatChoice.current());
                        break;
                    }
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
