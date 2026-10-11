package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.camera2.CameraCharacteristics;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.Dcg14Mode;
import com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P104 The «DCG 14 бит» switch of the vivo X500 Pro Max (owner, 2026-10-11: in the viewfinder, at the bottom right opposite the
 * manual button, square and smaller than it): a 40dp square in the manual strip's end slot (the toggle is 52 x 58dp), shown
 * while the strip is collapsed and only on cameras with the DCG HDR (Dcg14Mode.available). Off: the gear's card, the icon in the
 * accent; on: the card filled with the accent. A tap toggles Dcg14Mode and restarts the camera (its keys are session keys).
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
        if (!Dcg14Mode.device()) {
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
            button.setOnClickListener(v -> toggle(fragment, (ImageButton) v));
            slot.addView(button);
        }
        boolean here = Dcg14Mode.available(characteristics);
        button.setVisibility(here ? View.VISIBLE : View.GONE);
        style(button, Dcg14Mode.on());
        panel.refreshEndSlot();
    }

    private static void toggle(CameraFragment fragment, ImageButton button) {
        boolean on = !Dcg14Mode.on();
        Dcg14Mode.set(on);
        style(button, on);
        button.animate().cancel();
        button.setScaleX(0.86f);
        button.setScaleY(0.86f);
        button.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        fragment.showSnackBar(fragment.getString(on ? R.string.dcg14_on_toast : R.string.dcg14_off_toast));
        Log.i(TAG, "DCG 14 bit " + (on ? "on" : "off"));
        if (fragment.captureController != null) fragment.captureController.restartCamera();
    }

    private static void style(ImageButton button, boolean on) {
        Context c = button.getContext();
        int accent = ShadeStyle.accent(c);
        String name = c.getString(on ? R.string.dcg14_on : R.string.dcg14_off);
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setActivated(on);
        if (on) button.setBackground(ShadeStyle.pressable(c, ShadeStyle.tint(accent, 0.22f), accent, 14));
        else button.setBackgroundResource(R.drawable.shade_gear_card);
        button.setImageTintList(ColorStateList.valueOf(accent));
    }
}
