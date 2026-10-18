package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.widget.TextView;

import com.particlesdevs.photoncamera.app.ScameraApp;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.databinding.LayoutBottombuttonsBinding;
import com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Utilities;

/**
 * This Class is a dumb 'View' which contains view components visible in the main Camera User Interface
 * <p>
 * It gets instantiated in {@link CameraFragment#onViewCreated(View, Bundle)}
 * <p>
 * P25 chrome: the top bar shows two card badges (the merge route in effect, the save format) and the settings gear; the
 * shutter row is gallery card | ring shutter | front / back switch. Photo is the only mode: the «Фото | Ночь» switch is
 * gone and a stored Night mode reads as Photo (CameraMode.valueOf).
 */
public class CameraUIViewImpl implements CameraUIView {
    private static final String TAG = "CameraUIView";

    private final CameraFragment cameraFragment;
    private final ProgressBar mCaptureProgressBar;
    private final ImageButton mShutterButton;
    private final com.particlesdevs.photoncamera.ui.camera.views.ProcessingRing mProcessingProgressBar;
    private final TextView mVideoRecordingInfo;
    private LayoutMainTopbarBinding topbar;
    private LayoutBottombuttonsBinding bottombuttons;
    private CameraUIEventsListener uiEventsListener;
    private CameraModeState currentState;
    /** The ring shutter in the camera accent (pressed, self-timer, busy). */
    private final Drawable shutterRing;

    CameraUIViewImpl(CameraFragment cameraFragment) {
        this.cameraFragment = cameraFragment;
        this.topbar = cameraFragment.cameraFragmentBinding.layoutTopbar;
        this.bottombuttons = cameraFragment.cameraFragmentBinding.layoutBottombar.bottomButtons;
        this.mCaptureProgressBar = cameraFragment.cameraFragmentBinding.layoutViewfinder.captureProgressBar;
        this.mProcessingProgressBar = bottombuttons.processingProgressBar;
        this.mShutterButton = bottombuttons.shutterButton;
        // P111: the touch marks the moment of a «Движение» shot (the shot itself still fires on release)
        this.mShutterButton.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && cameraFragment.captureController != null)
                cameraFragment.captureController.notePressDown();
            return false;
        });
        this.mVideoRecordingInfo = cameraFragment.cameraFragmentBinding.getRoot().findViewById(R.id.video_recording_info);
        Context context = cameraFragment.cameraFragmentBinding.getRoot().getContext();
        this.shutterRing = shutter(context, ShadeStyle.accent(context));
        this.initListeners();
        migrateMode();
        styleControls(context);
        // SIMPLE_UI §1: a long press on the photo size opens the «Размер фото» sheet (500 ms, haptic)
        topbar.sizeBadge.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            TopBar.openSizeSheet(cameraFragment);
            return true;
        });
        this.currentState = new PhotoMotionModeState(); //init mode
        initModeState(CameraMode.valueOf(PreferenceKeys.getCameraModeOrdinal()));
        updateBadges();
    }

    /** Photo is the only mode on screen (owner's answer 4): a stored Night (or retired) mode is stored as Photo. */
    private static void migrateMode() {
        if (PreferenceKeys.getCameraModeOrdinal() != CameraMode.MOTION.ordinal())
            PreferenceKeys.setCameraModeOrdinal(CameraMode.MOTION.ordinal());
    }

    private void initModeState(CameraMode mode) {
        switch (mode) {
            case VIDEO:
                currentState = new VideoModeState();
                break;
            case UNLIMITED:
            case RAWVIDEO:
                currentState = new UnlimitedModeState();
                break;
            default:
                currentState = new PhotoMotionModeState();
                break;
        }
        currentState.reConfigureModeViews(mode);
    }

    private void initListeners() {
        this.topbar.setTopBarClickListener(v -> this.uiEventsListener.onClick(v));
        this.bottombuttons.setBottomBarClickListener(v -> this.uiEventsListener.onClick(v));
    }

    /** SIMPLE_UI: the concept's glyphs in the text colour (the gear, the front / back switch), the empty gallery card's icon muted. */
    private void styleControls(Context context) {

        bottombuttons.galleryPlaceholder.setImageTintList(ColorStateList.valueOf(ShadeStyle.MUTED));
        bottombuttons.flipCameraButton.setImageTintList(ColorStateList.valueOf(ShadeStyle.TEXT));
        mShutterButton.setBackground(shutterRing);
    }

    /**
     * The shutter as the concept draws it (SIMPLE_UI: 78dp, a 4dp white ring, 5dp of space, a white centre): ready (activated)
     * white; pressed the centre shrinks to 88 %; the self-timer counting (hovered) the ring in the accent; busy (not
     * activated) the centre in the accent.
     */
    static Drawable shutter(Context context, int accent) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, ring(context, SHUTTER_WHITE, SHUTTER_WHITE, true));
        states.addState(new int[]{android.R.attr.state_hovered}, ring(context, accent, SHUTTER_WHITE, false));
        states.addState(new int[]{android.R.attr.state_activated}, ring(context, SHUTTER_WHITE, SHUTTER_WHITE, false));
        states.addState(new int[]{}, ring(context, SHUTTER_WHITE, accent, false));
        return states;
    }

    static final int SHUTTER_WHITE = 0xFFFFFFFF;

    private static Drawable ring(Context context, int ringColor, int centreColor, boolean pressed) {
        // the concept's 78dp shutter times the screen's scale (BottomChrome.scale / UiScale)
        float f = com.particlesdevs.photoncamera.ui.camera.views.BottomChrome.scale(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0);
        int stroke = ShadeStyle.dp(context, 4 * f);
        ring.setStroke(stroke, ringColor);
        GradientDrawable centre = new GradientDrawable();
        centre.setShape(GradientDrawable.OVAL);
        centre.setColor(centreColor);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{ring, centre});
        // 4dp ring + 5dp space; pressed: the centre (78 - 18 = 60dp) at 88 %
        float inner = 9 * f + (pressed ? (78 - 18) * f * .06f : 0f);
        int inset = ShadeStyle.dp(context, inner);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    @Override
    public void updateBadges() {
        if (topbar != null) bindBadges(topbar);
    }

    /** SIMPLE_UI §1: the top bar from the settings (TopBar: torch, timer, Motion, format | size | DCG, the gear). */
    public static void bindBadges(LayoutMainTopbarBinding topbar) {
        TopBar.bind(topbar);
    }

    @Override
    public void activateShutterButton(boolean status) {
        this.mShutterButton.post(() -> {
            this.mShutterButton.setActivated(status);
            this.mShutterButton.setClickable(status);
        });
    }

    private void toggleConstraints(CameraMode mode) {
        if (cameraFragment.displayAspectRatio <= 16f / 9f) {
            ConstraintLayout.LayoutParams camera_containerLP =
                    (ConstraintLayout.LayoutParams) cameraFragment.cameraFragmentBinding
                            .textureHolder
                            .findViewById(R.id.camera_container)
                            .getLayoutParams();
            switch (mode) {
                case RAWVIDEO:
                case VIDEO:
                    camera_containerLP.topToTop = R.id.textureHolder;
                    camera_containerLP.topToBottom = -1;
                    break;
                case UNLIMITED:
                case PHOTO:
                case MOTION:
                case NIGHT:
                    camera_containerLP.topToTop = -1;
                    camera_containerLP.topToBottom = R.id.layout_topbar;
            }

        }
    }

    @Override
    public void refresh(boolean processing) {
        cameraFragment.cameraFragmentBinding.invalidateAll();
        currentState.reConfigureModeViews(CameraMode.valueOf(PreferenceKeys.getCameraModeOrdinal()));
        updateBadges();
        this.resetCaptureProgressBar();
        if (!processing) {
            this.activateShutterButton(true);
            this.setProcessingProgressBarIndeterminate(false);
            this.lockUIForBurst(false);
        }
    }

    /** P158: true - a shot's processing starts (the ring round the gallery card runs), false - it is done (the ring closes). */
    @Override
    public void setProcessingProgressBarIndeterminate(boolean indeterminate) {
        this.mProcessingProgressBar.post(() -> {
            if (indeterminate) this.mProcessingProgressBar.start();
            else this.mProcessingProgressBar.finish();
        });
    }

    /** P158: the pipeline's milestone (0..1) for the ring round the gallery card. */
    @Override
    public void setProcessingProgress(float fraction) {
        this.mProcessingProgressBar.post(() -> this.mProcessingProgressBar.setStage(fraction));
    }

    /** P220: the large-shot hint or the merge worker's measured progress for the ring round the gallery card. */
    @Override
    public void setProcessingProgress(com.particlesdevs.photoncamera.processing.ProcessingProgress progress) {
        this.mProcessingProgressBar.post(() -> {
            if (progress.large) this.mProcessingProgressBar.setLarge();
            else this.mProcessingProgressBar.setMeasured(progress.merge);
        });
    }

    @Override
    public void incrementCaptureProgressBar(int step) {
        this.mCaptureProgressBar.post(() -> this.mCaptureProgressBar.incrementProgressBy(step));
    }

    @Override
    public void resetCaptureProgressBar() {
        this.mCaptureProgressBar.post(() -> this.mCaptureProgressBar.setProgress(0));
        this.setCaptureProgressBarOpacity(0);
    }

    @Override
    public void setCaptureProgressBarOpacity(float alpha) {
        this.mCaptureProgressBar.post(() -> this.mCaptureProgressBar.setAlpha(alpha));
    }

    @Override
    public void setCaptureProgressMax(int max) {
        this.mCaptureProgressBar.post(() -> this.mCaptureProgressBar.setMax(max));
    }

    /** No flash on this lens: the flash tile of the shade is dimmed and says why (owner's answer 5). */
    @Override
    public void showFlashButton(boolean flashAvailable) {
        cameraFragment.cameraFragmentBinding.settingsBar.setFlashAvailable(flashAvailable);
    }

    @Override
    public void lockUIForBurst(boolean locked) {
        // The views are taken now: the posted actions can run after the fragment is destroyed (a rescued shot completes from
        // onPause / onDestroy), when its binding and bottom bar fields are already null.
        // Lock/unlock bottom bar buttons (except shutter button: it stays enabled for burst control)
        if (this.bottombuttons != null) {
            final View gallery = this.bottombuttons.galleryImageButton;
            gallery.post(() -> gallery.setEnabled(!locked));
        }
        final com.particlesdevs.photoncamera.databinding.CameraFragmentBinding binding = cameraFragment.cameraFragmentBinding;
        if (binding != null) {
            // aux buttons container: disabled and dimmed
            final View strip = binding.layoutBottombar.auxButtonsContainer;
            final com.particlesdevs.photoncamera.ui.camera.viewmodel.AuxButtonsViewModel aux = cameraFragment.auxButtonsViewModel;
            strip.post(() -> {
                strip.setEnabled(!locked);
                strip.setAlpha(locked ? 0.5f : 1.0f);
                if (aux != null) aux.setEnabled(!locked);
            });
            // settings bar and manual mode console: no touch, dimmed
            final View settingsBar = binding.settingsBar, manualMode = binding.manualMode;
            settingsBar.post(() -> {
                settingsBar.setEnabled(!locked);
                settingsBar.setAlpha(locked ? 0.5f : 1.0f);
            });
            manualMode.post(() -> {
                manualMode.setEnabled(!locked);
                manualMode.setAlpha(locked ? 0.5f : 1.0f);
            });
        }
        // touch focus: no focus / swipe on the viewfinder during a burst
        final View texture = cameraFragment.textureView;
        if (texture != null) texture.post(() -> texture.setEnabled(!locked));
    }

    @Override
    public void setCameraUIEventsListener(CameraUIEventsListener cameraUIEventsListener) {
        this.uiEventsListener = cameraUIEventsListener;
    }

    @Override
    @android.annotation.SuppressLint("DefaultLocale")
    public void updateVideoRecordingInfo(long elapsedMs, long estimatedBytes, long availableBytes) {
        if (mVideoRecordingInfo == null) return;
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        double estimatedGB = estimatedBytes / 1_073_741_824.0;
        double availableGB = availableBytes / 1_073_741_824.0;
        String text = String.format("%02d:%02d  %.2f/%.1f GB", minutes, seconds, estimatedGB, availableGB);
        mVideoRecordingInfo.post(() -> {
            mVideoRecordingInfo.setText(text);
            mVideoRecordingInfo.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void setVideoRecordingInfoVisible(boolean visible) {
        if (mVideoRecordingInfo == null) return;
        mVideoRecordingInfo.post(() ->
                mVideoRecordingInfo.setVisibility(visible ? View.VISIBLE : View.GONE));
    }

    @Override
    public void destroy() {
        topbar = null;
        bottombuttons = null;
    }

    public class VideoModeState implements CameraModeState {
        @Override
        public void reConfigureModeViews(CameraMode mode) {
            mShutterButton.setBackgroundResource(R.drawable.unlimitedbutton);
            cameraFragment.cameraFragmentBinding.layoutViewfinder.frameTimer.setVisibility(View.VISIBLE);
            cameraFragment.cameraFragmentBinding.layoutViewfinder.captureProgressBar.setVisibility(View.VISIBLE);
            setVideoRecordingInfoVisible(false);
            // Set the dummy view's aspect ratio to 16:9
            if(cameraFragment.displayAspectRatio <= 16f / 9f)
                cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio("3:4");
            else {
                float avg = ((4f/3f) + (16f / 9f)) / 2f;
                cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio(String.valueOf(1.0f/avg));
                //cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio("0.580");
            }
            cameraFragment.cameraFragmentBinding.layoutBottombar.layoutBottombar.setBackgroundResource(R.color.panel_transparency);
            cameraFragment.cameraFragmentBinding.getRoot().setBackgroundResource(R.drawable.gradient_vector_video);

            toggleConstraints(mode);
        }
    }

    //
    public class UnlimitedModeState implements CameraModeState {
        @Override
        public void reConfigureModeViews(CameraMode mode) {
            mShutterButton.setBackgroundResource(R.drawable.unlimitedbutton);
            if (mode == CameraMode.RAWVIDEO) {
                cameraFragment.cameraFragmentBinding.layoutViewfinder.frameTimer.setVisibility(View.GONE);
                cameraFragment.cameraFragmentBinding.layoutViewfinder.captureProgressBar.setVisibility(View.GONE);
            } else {
                cameraFragment.cameraFragmentBinding.layoutViewfinder.frameTimer.setVisibility(View.VISIBLE);
                cameraFragment.cameraFragmentBinding.layoutViewfinder.captureProgressBar.setVisibility(View.VISIBLE);
                setVideoRecordingInfoVisible(false);
            }
            if(ScameraApp.getSettings().aspect169 || mode == CameraMode.RAWVIDEO) {
                // Set the dummy view's aspect ratio to 16:9
                if(cameraFragment.displayAspectRatio <= 16f / 9f)
                    cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio("3:4");
                else {
                    float avg = ((4f/3f) + (16f / 9f)) / 2f;
                    cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio(String.valueOf(1.0f/avg));
                    //cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio("0.580");
                }
                cameraFragment.cameraFragmentBinding.layoutBottombar.layoutBottombar.setBackgroundResource(R.color.panel_transparency);
                cameraFragment.cameraFragmentBinding.getRoot().setBackgroundResource(R.drawable.gradient_vector_video);
            } else {
                cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio("3:4");
                cameraFragment.cameraFragmentBinding.layoutBottombar.layoutBottombar.setBackground(null);
                cameraFragment.cameraFragmentBinding.getRoot().setBackground(Utilities.resolveDrawable(cameraFragment.requireActivity(), R.attr.cameraFragmentBackground));
            }
            toggleConstraints(mode);
        }
    }

    /** Photo (the only mode on screen; a stored Night mode reads as Photo). */
    public class PhotoMotionModeState implements CameraModeState {
        @Override
        public void reConfigureModeViews(CameraMode mode) {
            // SIMPLE_UI §1: one progress indicator (the thumbnail's ring); the burst time is a line under the shutter
            cameraFragment.cameraFragmentBinding.layoutViewfinder.frameTimer.setVisibility(View.GONE);
            cameraFragment.cameraFragmentBinding.layoutViewfinder.captureProgressBar.setVisibility(View.GONE);
            setVideoRecordingInfoVisible(false);
            mShutterButton.setBackground(shutterRing);

            if(ScameraApp.getSettings().aspect169) {
                // Set the dummy view's aspect ratio to 16:9
                if(cameraFragment.displayAspectRatio <= 16f / 9f)
                    cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio("3:4");
                else {
                    float avg = ((4f/3f) + (16f / 9f)) / 2f;
                    cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio(String.valueOf(1.0f/avg));
                    //cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio("0.580");
                }
                cameraFragment.cameraFragmentBinding.layoutBottombar.layoutBottombar.setBackgroundResource(R.color.panel_transparency);
                cameraFragment.cameraFragmentBinding.getRoot().setBackgroundResource(R.drawable.gradient_vector_video);
            } else {
                cameraFragment.cameraFragmentBinding.getUimodel().setDummyAspectRatio("3:4");
                cameraFragment.cameraFragmentBinding.layoutBottombar.layoutBottombar.setBackground(null);
                cameraFragment.cameraFragmentBinding.getRoot().setBackground(Utilities.resolveDrawable(cameraFragment.requireActivity(), R.attr.cameraFragmentBackground));
            }

            toggleConstraints(mode);
        }
    }
}
