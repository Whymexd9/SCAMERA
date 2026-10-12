package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.camera2.CameraCharacteristics;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.Dcg14Mode;
import com.particlesdevs.photoncamera.capture.PixelPro10;
import com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P104 The «DCG 14 бит» switch of the vivo X500 Pro Max (owner, 2026-10-11: in the viewfinder, at the bottom right opposite the
 * manual button, square and smaller than it): a 40dp square in the manual strip's end slot (the toggle is 52 x 58dp), shown
 * while the strip is collapsed and only on cameras with the DCG HDR (Dcg14Mode.available). Off: the gear's card, the icon in the
 * accent; on: the card filled with the accent. A tap toggles Dcg14Mode and restarts the camera (its keys are session keys).
 * <p>
 * P107 (owner, 2026-10-12: «сделай всё чтобы заставить работать DCG … если возможно, то сделай и LOFIC»): a tap steps off -> DCG
 * -> LOFIC (its own icon) -> off; while the mode has no working try the camera restarts by itself through the tries of
 * DcgSearch (a snackbar says when one worked or none did); a long press searches on from the next try.
 * <p>
 * P108: the same button on the Pixel 10 Pro / Pro XL's main camera (RAW12 + sequoiaIdcgEnabled, PixelPro10): on / off.
 */
final class Dcg14Button {
    private static final String TAG = "Dcg14Button";
    static final int SIZE_DP = 40;

    private Dcg14Button() {}

    static void bind(CameraFragment fragment, View manualMode, CameraCharacteristics characteristics) {
        if (!(manualMode instanceof ExpandingManualPanel)) return;
        ExpandingManualPanel panel = (ExpandingManualPanel) manualMode;
        FrameLayout slot = panel.endSlot();
        if (slot == null) return;
        ImageButton button = slot.findViewById(R.id.dcg14_button);
        final boolean pixel = PixelPro10.device();
        if (!Dcg14Mode.device() && !pixel) {
            if (button != null) slot.removeView(button);
            panel.refreshEndSlot();
            return;
        }
        if (button == null) {
            Context c = slot.getContext();
            button = new ImageButton(c);
            button.setId(R.id.dcg14_button);
            int size = ShadeStyle.dp(c, SIZE_DP), pad = ShadeStyle.dp(c, 8);
            button.setLayoutParams(new FrameLayout.LayoutParams(size, size));
            button.setPadding(pad, pad, pad, pad);
            button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
            button.setImageResource(R.drawable.manual_ic_dcg14);
            button.setOnClickListener(v -> {
                if (PixelPro10.device()) togglePixel(fragment, (ImageButton) v);
                else toggle(fragment, (ImageButton) v);
            });
            button.setOnLongClickListener(v -> {
                searchAgain(fragment);
                return true;
            });
            slot.addView(button);
        }
        final ImageButton bound = button;
        Dcg14Mode.setListener(() -> {
            if (!fragment.isAdded()) return;
            style(bound, Dcg14Mode.mode());
            if (Dcg14Mode.found()) fragment.showSnackBar(fragment.getString(R.string.dcg14_found_toast));
            else if (Dcg14Mode.exhausted()) fragment.showSnackBar(fragment.getString(R.string.dcg14_none_toast));
        });
        boolean here = pixel ? PixelPro10.dcgAvailable(characteristics) : Dcg14Mode.available(characteristics);
        button.setVisibility(here ? View.VISIBLE : View.GONE);
        style(button, pixel ? (PixelPro10.dcgOn() ? Dcg14Mode.DCG : Dcg14Mode.OFF) : Dcg14Mode.mode());
        panel.refreshEndSlot();
    }

    private static void toggle(CameraFragment fragment, ImageButton button) {
        int mode = Dcg14Mode.next(Dcg14Mode.mode());
        Dcg14Mode.setMode(mode);
        style(button, mode);
        button.animate().cancel();
        button.setScaleX(0.86f);
        button.setScaleY(0.86f);
        button.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        fragment.showSnackBar(fragment.getString(mode == Dcg14Mode.LOFIC ? R.string.dcg14_lofic_toast
                : mode == Dcg14Mode.DCG ? R.string.dcg14_on_toast : R.string.dcg14_off_toast));
        Log.i(TAG, "DCG 14 bit mode " + Dcg14Mode.name(mode));
        if (fragment.captureController != null) fragment.captureController.restartCamera();
    }

    private static void togglePixel(CameraFragment fragment, ImageButton button) {
        boolean on = !PixelPro10.dcgOn();
        PixelPro10.setDcg(on);
        style(button, on ? Dcg14Mode.DCG : Dcg14Mode.OFF);
        fragment.showSnackBar(fragment.getString(on ? R.string.pixel_dcg_on_toast : R.string.pixel_dcg_off_toast));
        Log.i(TAG, "Pixel DCG (RAW12, iDCG) " + (on ? "on" : "off"));
        if (fragment.captureController != null) fragment.captureController.restartCamera();
    }

    private static void searchAgain(CameraFragment fragment) {
        if (PixelPro10.device() || !Dcg14Mode.on()) return;
        Dcg14Mode.searchAgain();
        fragment.showSnackBar(fragment.getString(R.string.dcg14_search_toast));
        if (fragment.captureController != null) fragment.captureController.restartCamera();
    }

    private static void style(ImageButton button, int mode) {
        Context c = button.getContext();
        int accent = ShadeStyle.accent(c);
        boolean on = mode != Dcg14Mode.OFF;
        button.setImageResource(mode == Dcg14Mode.LOFIC ? R.drawable.manual_ic_lofic : R.drawable.manual_ic_dcg14);
        String name = c.getString(mode == Dcg14Mode.LOFIC ? R.string.dcg14_lofic : on ? R.string.dcg14_on : R.string.dcg14_off);
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setActivated(on);
        if (on) button.setBackground(ShadeStyle.pressable(c, ShadeStyle.tint(accent, 0.22f), accent, 14));
        else button.setBackgroundResource(R.drawable.shade_gear_card);
        button.setImageTintList(ColorStateList.valueOf(accent));
    }
}
