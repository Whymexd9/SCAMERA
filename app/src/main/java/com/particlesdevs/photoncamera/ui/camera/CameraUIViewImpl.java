package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import com.particlesdevs.photoncamera.app.PhotonCamera;
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
    private final ProgressBar mProcessingProgressBar;
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
        this.mVideoRecordingInfo = cameraFragment.cameraFragmentBinding.getRoot().findViewById(R.id.video_recording_info);
        Context context = cameraFragment.cameraFragmentBinding.getRoot().getContext();
        this.shutterRing = shutter(context, ShadeStyle.accent(context));
        this.initListeners();
        migrateMode();
        styleControls(context);
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

    /** Accent icons on the gear, the gallery placeholder and the front / back switch. */
    private void styleControls(Context context) {
        ColorStateList accent = ColorStateList.valueOf(ShadeStyle.accent(context));
        topbar.settingsButton.setImageTintList(accent);
        bottombuttons.galleryPlaceholder.setImageTintList(accent);
        bottombuttons.flipCameraButton.setImageTintList(accent);
        mShutterButton.setBackground(shutterRing);
    }

    /**
     * The shutter (P25 concept): a ring in TEXT around a filled centre. Pressed: the centre in the accent; the self-timer
     * counting (hovered): the ring in the accent; busy (not activated): the centre dimmed.
     */
    static Drawable shutter(Context context, int accent) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, ring(context, ShadeStyle.TEXT, accent));
        states.addState(new int[]{android.R.attr.state_hovered}, ring(context, accent, ShadeStyle.TEXT));
        states.addState(new int[]{android.R.attr.state_activated}, ring(context, ShadeStyle.TEXT, ShadeStyle.TEXT));
        states.addState(new int[]{}, ring(context, ShadeStyle.TEXT, (ShadeStyle.TEXT & 0x00FFFFFF) | 0x73000000));
        return states;
    }

    private static Drawable ring(Context context, int ringColor, int centreColor) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0);
        ring.setStroke(ShadeStyle.dp(context, 3), ringColor);
        GradientDrawable centre = new GradientDrawable();
        centre.setShape(GradientDrawable.OVAL);
        centre.setColor(centreColor);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{ring, centre});
        int inset = ShadeStyle.dp(context, 8);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    @Override
    public void updateBadges() {
        if (topbar != null) bindBadges(topbar);
    }

    /** The top bar's badges: «Склейка Hybrid / SCAM HDR» (the route in effect) and the save format with its icon. */
    public static void bindBadges(LayoutMainTopbarBinding topbar) {
        Context context = topbar.getRoot().getContext();
        int accent = ShadeStyle.accent(context);
        String route = PreferenceKeys.isScamHdrRoute() ? "SCAM HDR" : "Hybrid";
        SpannableStringBuilder text = new SpannableStringBuilder(context.getString(R.string.shade_badge_route, route));
        int at = text.toString().lastIndexOf(route);
        if (at >= 0) {
            text.setSpan(new ForegroundColorSpan(accent), at, at + route.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), at, at + route.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        topbar.routeBadge.setText(text);
        badgeIcon(topbar.routeBadge, R.drawable.settings_ic_layers, accent);
        int format = Math.max(0, Math.min(2, PreferenceKeys.isSaveRaw()));
        int[] icons = {R.drawable.ic_shade_jpeg, R.drawable.ic_shade_rawjpeg, R.drawable.ic_shade_raw};
        topbar.formatBadge.setText(context.getResources().getStringArray(R.array.shade_s_format)[format]);
        topbar.formatBadge.setContentDescription(context.getResources().getStringArray(R.array.shade_l_format)[format]);
        badgeIcon(topbar.formatBadge, icons[format], accent);
    }

    private static void badgeIcon(TextView badge, int icon, int accent) {
        Drawable d = badge.getContext().getDrawable(icon);
        if (d == null) return;
        d = d.mutate();
        d.setTint(accent);
        int size = ShadeStyle.dp(badge.getContext(), 18);
        d.setBounds(0, 0, size, size);
        // Left, not start: the camera screen is laid out left to right, and a start drawable waits for the layout
        // direction to resolve.
        badge.setCompoundDrawables(d, null, null, null);
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

    @Override
    public void setProcessingProgressBarIndeterminate(boolean indeterminate) {
        this.mProcessingProgressBar.post(() -> {
            this.mProcessingProgressBar.setIndeterminate(indeterminate);
            this.mProcessingProgressBar.setVisibility(indeterminate ? View.VISIBLE : View.INVISIBLE);
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
            if(PhotonCamera.getSettings().aspect169 || mode == CameraMode.RAWVIDEO) {
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
            cameraFragment.cameraFragmentBinding.layoutViewfinder.frameTimer.setVisibility(View.VISIBLE);
            cameraFragment.cameraFragmentBinding.layoutViewfinder.captureProgressBar.setVisibility(View.VISIBLE);
            setVideoRecordingInfoVisible(false);
            mShutterButton.setBackground(shutterRing);

            if(PhotonCamera.getSettings().aspect169) {
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
