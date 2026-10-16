package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.camera2.CameraCharacteristics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.PixelPro10;
import com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P108 The full-resolution switch of the Pixel 10 Pro / Pro XL (owner, 2026-10-12: «добавь кнопку сверху кнопки DCG для его
 * активации»): a square like the DCG button. With the DCG button in the manual strip's end slot it floats above it (an overlay
 * in the fragment's root, kept over the slot on every frame and shown while the slot is); on a camera without DCG (the 2x crop
 * module has no RAW12) it takes the slot itself. A tap toggles PixelPro10's full resolution and restarts the camera.
 * <p>
 * P179 (owner, 2026-10-16: «сделай отдельную кнопку на телевике для 50мп и для 200мп. По иконке 200мп должно быть понятно что
 * это выше разрешение»): on the X200 Ultra's telephoto (native build) the button is 50 MP and a second one floats above it,
 * 200 MP, its icon the same frame with twice as fine a grid; one of them or none is on.
 */
final class FullResButton {
    private static final String TAG = "FullResButton";
    private static final int GAP_DP = 8;

    private FullResButton() {}

    static void bind(CameraFragment fragment, ExpandingManualPanel panel, FrameLayout slot, CameraCharacteristics characteristics) {
        View rootView = fragment.getView();
        // the slot first: the strip may not be inside the fragment's view
        ImageButton button = slot.findViewById(R.id.full_res_button);
        if (button == null && rootView != null) button = rootView.findViewById(R.id.full_res_button);
        if (!(PixelPro10.device() || com.particlesdevs.photoncamera.capture.VivoQcomModes.device()) || !(rootView instanceof ViewGroup)) {
            detach(button);
            return;
        }
        ViewGroup root = (ViewGroup) rootView;
        Context c = slot.getContext();
        int size = ShadeStyle.dp(c, Dcg14Button.SIZE_DP);
        if (button != null && button.getParent() != null && button.getParent() != slot && button.getParent() != root) {
            detach(button);
            button = null;
        }
        if (button == null) button = create(fragment, c);
        boolean here = PixelPro10.fullResAvailable(characteristics)
                || com.particlesdevs.photoncamera.capture.VivoSdkFullRes.offered(
                        com.particlesdevs.photoncamera.app.ScameraApp.getSettings().mCameraID);
        View dcg = slot.findViewById(R.id.dcg14_button);
        boolean overDcg = dcg != null && dcg.getVisibility() == View.VISIBLE;
        ViewGroup wanted = overDcg ? root : slot;
        if (button.getParent() != wanted) {
            detach(button);
            // a parent's layout transition keeps a removed child attached until it ends (Pixel 10 Pro XL crash 2026-10-12:
            // «The specified child already has a parent» here after module switches): then a new button takes the place
            if (button.getParent() != null) button = create(fragment, c);
            if (overDcg) {
                wanted.addView(button, new ViewGroup.LayoutParams(size, size));
                follow(button, slot, root, size, ShadeStyle.dp(c, GAP_DP));
            } else {
                wanted.addView(button, new FrameLayout.LayoutParams(size, size));
                button.setTranslationX(0f);
                button.setTranslationY(0f);
            }
        }
        button.setTag(R.id.full_res_button, here);
        // P181: the FULLRES profiles of this camera's modules (the Pixel's FULLRES is known from its characteristics only)
        com.particlesdevs.photoncamera.settings.FullResProfiles.remember(com.particlesdevs.photoncamera.app.ScameraApp.getSettings().mCameraID, here);
        if (!overDcg) button.setVisibility(here ? View.VISIBLE : View.GONE);
        boolean two = here && com.particlesdevs.photoncamera.capture.VivoSdkFullRes.teleSizes(
                com.particlesdevs.photoncamera.app.ScameraApp.getSettings().mCameraID);
        bind200(fragment, root, slot, c, size, two);
        restyle(root);
        panel.refreshEndSlot();
    }

    /** P179: the tele's 200 MP button, an overlay one place above the 50 MP button (two above the slot over DCG). */
    private static void bind200(CameraFragment fragment, ViewGroup root, FrameLayout slot, Context c, int size, boolean two) {
        ImageButton button = root.findViewById(R.id.full_res_200_button);
        if (!two) {
            detach(button);
            return;
        }
        if (button == null || button.getParent() != root) {
            detach(button);
            button = new ImageButton(c);
            button.setId(R.id.full_res_200_button);
            int pad = ShadeStyle.dp(c, 8);
            button.setPadding(pad, pad, pad, pad);
            button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
            button.setImageResource(R.drawable.manual_ic_fullres_200);
            button.setOnClickListener(v -> toggle(fragment, (ImageButton) v, true));
            root.addView(button, new ViewGroup.LayoutParams(size, size));
            follow(button, slot, root, size, ShadeStyle.dp(c, GAP_DP), R.id.full_res_200_button, 1);
        }
        button.setTag(R.id.full_res_200_button, true);
    }

    /** Keeps the overlay above the slot (centred, a gap above it) and visible only while the slot is shown. */
    private static void follow(ImageButton button, View slot, ViewGroup root, int size, int gap) {
        follow(button, slot, root, size, gap, R.id.full_res_button, 0);
    }

    /**
     * As above, {@code above} more places up (P179: the 200 MP button over the 50 MP one), one more over a shown DCG
     * button; shown while the tag {@code tag} is true.
     */
    private static void follow(ImageButton button, View slot, ViewGroup root, int size, int gap, int tag, int above) {
        final int[] s = new int[2], r = new int[2];
        ViewTreeObserver.OnPreDrawListener l = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (button.getParent() != root) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }
                boolean here = Boolean.TRUE.equals(button.getTag(tag));
                boolean show = here && slot.isShown() && slot.getWidth() > 0;
                View dcg = slot.findViewById(R.id.dcg14_button);
                int level = above + (above == 0 || (dcg != null && dcg.getVisibility() == View.VISIBLE) ? 1 : 0);
                int vis = show ? View.VISIBLE : View.GONE;
                if (button.getVisibility() != vis) button.setVisibility(vis);
                if (show) {
                    slot.getLocationInWindow(s);
                    root.getLocationInWindow(r);
                    float x = s[0] - r[0] + (slot.getWidth() - size) / 2f, y = s[1] - r[1] - level * (size + gap);
                    if (button.getX() != x) button.setX(x);
                    if (button.getY() != y) button.setY(y);
                }
                return true;
            }
        };
        root.getViewTreeObserver().addOnPreDrawListener(l);
    }

    private static ImageButton create(CameraFragment fragment, Context c) {
        ImageButton button = new ImageButton(c);
        button.setId(R.id.full_res_button);
        int pad = ShadeStyle.dp(c, 8);
        button.setPadding(pad, pad, pad, pad);
        button.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        button.setImageResource(R.drawable.manual_ic_fullres);
        button.setOnClickListener(v -> toggle(fragment, (ImageButton) v));
        return button;
    }

    /** Off its parent, past the parent's layout transition; a child the transition still holds loses the id (no longer found). */
    private static void detach(ImageButton button) {
        if (button == null) return;
        ViewParent p = button.getParent();
        if (!(p instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) p;
        g.removeView(button);
        g.endViewTransition(button);
        if (button.getParent() != null) {
            button.setId(View.NO_ID);
            button.setVisibility(View.GONE);
        }
    }

    /** The camera's one button (the 50 MP one on the tele with two sizes). */
    private static void toggle(CameraFragment fragment, ImageButton button) {
        toggle(fragment, button, false);
    }

    /** A tap: FULLRES on or off; on the tele with two sizes, the tapped size on (the other off), or off when it was on. */
    private static void toggle(CameraFragment fragment, ImageButton button, boolean tele200) {
        boolean two = com.particlesdevs.photoncamera.capture.VivoSdkFullRes.teleSizes(
                com.particlesdevs.photoncamera.app.ScameraApp.getSettings().mCameraID);
        if (PixelPro10.fullResSuspended()) { // P179: a 200 MP merge runs: the switch waits for it
            fragment.showSnackBar(com.particlesdevs.photoncamera.util.Lang.t("Снимок 200 Мп ещё обрабатывается", "A 200 MP shot is still being processed"));
            return;
        }
        boolean active = PixelPro10.fullResChosen() && (!two || PixelPro10.tele200() == tele200);
        boolean on = !active;
        if (on && two) PixelPro10.setTele200(tele200);
        if (!com.particlesdevs.photoncamera.capture.SensorModeSwitches.setFullRes(on)) {
            fragment.showSnackBar(com.particlesdevs.photoncamera.util.Lang.t(
                    "Нет обычного модуля этой камеры для полного разрешения", "No plain module of this camera for full resolution"));
            return;
        }
        View root = fragment.getView();
        if (root instanceof ViewGroup) restyle((ViewGroup) root);
        else style(button, on);
        button.animate().cancel();
        button.setScaleX(0.86f);
        button.setScaleY(0.86f);
        button.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        if (on && two) fragment.showSnackBar(tele200 ? com.particlesdevs.photoncamera.util.Lang.t(
                "Полное разрешение: 200 Мп", "Full resolution: 200 MP") : com.particlesdevs.photoncamera.util.Lang.t(
                "Полное разрешение: 50 Мп", "Full resolution: 50 MP"));
        else fragment.showSnackBar(fragment.getString(on ? R.string.full_res_on_toast : R.string.full_res_off_toast));
        Log.i(TAG, "full resolution " + (on ? "on" : "off") + (two ? (tele200 ? " 200 MP" : " 50 MP") : ""));
        // P181: FULLRES 50 / 200 MP and the module itself keep their own settings: the camera restarts with the right profile
        com.particlesdevs.photoncamera.settings.ModuleRegistry.syncProfile();
        if (fragment.captureController != null) fragment.captureController.restartCamera();
    }

    /** Both buttons from the state: on the tele with two sizes only the chosen one lights. */
    private static void restyle(ViewGroup root) {
        boolean on = PixelPro10.fullResChosen();
        ImageButton fifty = root.findViewById(R.id.full_res_button), twoHundred = root.findViewById(R.id.full_res_200_button);
        boolean two = twoHundred != null && twoHundred.getParent() != null;
        boolean is200 = two && PixelPro10.tele200();
        if (fifty != null) style(fifty, on && !is200, two ? "50" : null);
        if (two) style(twoHundred, on && is200, "200");
    }

    private static void style(ImageButton button, boolean on) {
        style(button, on, null);
    }

    /** {@code megapixels}: «50» / «200» on the tele with two sizes (in the name read out and the tooltip), else null. */
    private static void style(ImageButton button, boolean on, @androidx.annotation.Nullable String megapixels) {
        Context c = button.getContext();
        int accent = ShadeStyle.accent(c);
        String name = c.getString(on ? R.string.full_res_on : R.string.full_res_off);
        if (megapixels != null) name = name + " · " + megapixels + com.particlesdevs.photoncamera.util.Lang.t(c, " Мп", " MP");
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setActivated(on);
        if (on) button.setBackground(ShadeStyle.pressable(c, ShadeStyle.tint(accent, 0.22f), accent, 14));
        else button.setBackgroundResource(R.drawable.shade_gear_card);
        button.setImageTintList(ColorStateList.valueOf(accent));
    }
}
