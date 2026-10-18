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
 * <p>
 * P214h (speed-ux C2; P210: «all own rows fail, the size goes off, the button hides»): a camera (or a size of two) whose
 * sensor-mode rows all failed keeps its button, dimmed; a tap on it - a long press on any - shows what the trials remember
 * (FullResTrials.status: the rows, why each failed, the camera's own remosaic row) with «Попробовать снова», which forgets
 * the camera's trials and turns that size on. While a FULLRES merge runs (200 MP; 50 MP since P200) the switch names the
 * size being merged.
 */
final class FullResButton {
    private static final String TAG = "FullResButton";
    private static final int GAP_DP = 8;
    /** P214h: a button whose size has no row left (every one failed). */
    private static final float DEAD_ALPHA = 0.4f;

    private FullResButton() {}

    static void bind(CameraFragment fragment, ExpandingManualPanel panel, FrameLayout slot, CameraCharacteristics characteristics) {
        View rootView = fragment.getView();
        // the slot first: the strip may not be inside the fragment's view
        ImageButton button = slot.findViewById(R.id.full_res_button);
        if (button == null && rootView != null) button = rootView.findViewById(R.id.full_res_button);
        if (!(PixelPro10.device() || com.particlesdevs.photoncamera.capture.VivoQcomModes.device()
                || com.particlesdevs.photoncamera.capture.VivoNativeFullRes.device()
                || com.particlesdevs.photoncamera.capture.OppoNativeFullRes.device()) || !(rootView instanceof ViewGroup)) { // P192
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
        final String camera = camera();
        // P192: an OPPO camera's sensor-mode table, from the characteristics the app read
        com.particlesdevs.photoncamera.capture.OppoNativeFullRes.learn(camera, characteristics);
        boolean offered = PixelPro10.fullResAvailable(characteristics)
                || com.particlesdevs.photoncamera.capture.VivoSdkFullRes.offered(camera);
        // P214h (P210: «all own rows fail, the size goes off, the button hides»): a camera whose rows all failed keeps its
        // button, dimmed - a tap says why and offers «Попробовать снова» (FullResTrials)
        boolean off = !offered && com.particlesdevs.photoncamera.capture.VivoSdkFullRes.exhausted(camera);
        boolean here = offered || off;
        String trials = com.particlesdevs.photoncamera.capture.FullResTrials.dump(camera);
        if (!trials.isEmpty()) Log.i(TAG, "camera " + camera + " FULLRES " + (off ? "off by its trials: " : "trials: ") + trials);
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
        // P181: the FULLRES profiles of this camera's modules (the Pixel's FULLRES is known from its characteristics only);
        // P214h: only a camera whose FULLRES runs (a dimmed button has no profile to keep)
        com.particlesdevs.photoncamera.settings.FullResProfiles.remember(camera, offered);
        if (!overDcg) button.setVisibility(here ? View.VISIBLE : View.GONE);
        // P214h: both sizes' buttons whatever their trials (a size whose rows all failed stays, dimmed)
        boolean two = here && com.particlesdevs.photoncamera.capture.VivoSdkFullRes.teleButtons(camera);
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
            button.setOnLongClickListener(v -> explain(fragment, (ImageButton) v, true)); // P214h
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
        // P214h: a long press says what the camera's trials remember (the tooltip when nothing)
        button.setOnLongClickListener(v -> explain(fragment, (ImageButton) v, false));
        return button;
    }

    private static String camera() {
        return com.particlesdevs.photoncamera.app.ScameraApp.getSettings().mCameraID;
    }

    /** P214h: the size's button is dimmed - every row of that size ({@code tele200}: 200 MP of two buttons) or of the camera failed. */
    private static boolean dead(String camera, boolean two, boolean tele200) {
        return two ? com.particlesdevs.photoncamera.capture.VivoSdkFullRes.sizeExhausted(camera, tele200)
                : com.particlesdevs.photoncamera.capture.VivoSdkFullRes.exhausted(camera);
    }

    /**
     * P214h: the size that lights among two buttons while FULLRES is on - the chosen one, or the other when the chosen has no row
     * left (VivoNativeFullRes.profile gives way to the camera's other size); none when both have none.
     */
    static boolean lights200(boolean chosen200, boolean dead50, boolean dead200) {
        if (dead200) return false;
        return dead50 || chosen200;
    }

    /**
     * P214h (speed-ux C2): why the camera's FULLRES or this size is off - the rows tried and why each failed, the camera's own
     * remosaic row and «FULLRES: аппаратный ремозаик» (FullResTrials.status) - with «Попробовать снова», which forgets the
     * camera's trials and turns FULLRES on with that size (a size still running: the camera restarts at its first row). A tap
     * on a dimmed button, or a long press on any; false (the tooltip) for a long press with nothing remembered.
     */
    private static boolean explain(CameraFragment fragment, ImageButton button, boolean tele200) {
        final Context c = fragment.getContext();
        if (c == null) return false;
        final String camera = camera();
        final boolean dead = dead(camera, com.particlesdevs.photoncamera.capture.VivoSdkFullRes.teleButtons(camera), tele200);
        String status = com.particlesdevs.photoncamera.capture.FullResTrials.status(camera);
        if (status == null && !dead) return false;
        if (status == null) status = com.particlesdevs.photoncamera.util.Lang.t(c, "FULLRES недоступен на этой камере", "FULLRES is not available on this camera");
        Log.i(TAG, "camera " + camera + " FULLRES trials shown" + (dead ? " (off)" : ""));
        new androidx.appcompat.app.AlertDialog.Builder(c)
                .setTitle(dead ? com.particlesdevs.photoncamera.util.Lang.t(c, "FULLRES выключен", "FULLRES is off")
                        : com.particlesdevs.photoncamera.util.Lang.t(c, "FULLRES: пробы режимов", "FULLRES: mode trials"))
                .setMessage(status + "\n\n" + com.particlesdevs.photoncamera.util.Lang.t(c,
                        "«Попробовать снова» забывает эти пробы: режимы этой камеры проверяются заново с первого.",
                        "“Try again” forgets these trials: this camera's modes are tried again from the first."))
                .setNegativeButton(com.particlesdevs.photoncamera.util.Lang.t(c, "Закрыть", "Close"), null)
                .setPositiveButton(com.particlesdevs.photoncamera.util.Lang.t(c, "Попробовать снова", "Try again"),
                        (d, w) -> retry(fragment, button, tele200, dead))
                .show();
        return true;
    }

    /** P214h: «Попробовать снова»: the trials go; a dimmed size turns on (its first row), a running FULLRES restarts with it. */
    private static void retry(CameraFragment fragment, ImageButton button, boolean tele200, boolean dead) {
        com.particlesdevs.photoncamera.capture.FullResTrials.retry(camera());
        if (dead) {
            set(fragment, button, tele200, true);
            return;
        }
        View root = fragment.getView();
        if (root instanceof ViewGroup) restyle((ViewGroup) root);
        if (PixelPro10.fullResChosen() && fragment.captureController != null) fragment.captureController.restartCamera();
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
        if (PixelPro10.fullResSuspended()) { // P179: a FULLRES merge runs (200 MP; P200 50 MP too): the switch waits for it
            fragment.showSnackBar(PixelPro10.suspendedText()); // P214h: says which size is being merged
            return;
        }
        final String camera = camera();
        // P214h: both sizes' buttons whatever their trials; a dimmed one says why instead
        final boolean two = com.particlesdevs.photoncamera.capture.VivoSdkFullRes.teleButtons(camera);
        if (dead(camera, two, tele200)) {
            explain(fragment, button, tele200);
            return;
        }
        boolean lit200 = two && lights200(PixelPro10.tele200(), dead(camera, true, false), dead(camera, true, true));
        set(fragment, button, tele200, !(PixelPro10.fullResChosen() && (!two || lit200 == tele200)));
    }

    /** FULLRES {@code on} or off (P214h: «Попробовать снова» turns it on); on the tele with two sizes, the tapped size on. */
    private static void set(CameraFragment fragment, ImageButton button, boolean tele200, boolean on) {
        final boolean two = com.particlesdevs.photoncamera.capture.VivoSdkFullRes.teleButtons(camera());
        if (on && two) PixelPro10.setTele200(tele200);
        if (!com.particlesdevs.photoncamera.capture.SensorModeSwitches.setFullRes(on)) {
            fragment.showSnackBar(com.particlesdevs.photoncamera.util.Lang.t(
                    "Нет обычного модуля этой камеры для полного разрешения", "No plain module of this camera for full resolution"));
            return;
        }
        View root = fragment.getView();
        if (root instanceof ViewGroup) restyle((ViewGroup) root);
        else style(button, on, null, false);
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

    /**
     * Both buttons from the state: on the tele with two sizes only the chosen one lights; P214h a size (or a camera) whose rows
     * all failed is dimmed, and the size that runs instead lights.
     */
    private static void restyle(ViewGroup root) {
        boolean on = PixelPro10.fullResChosen();
        ImageButton fifty = root.findViewById(R.id.full_res_button), twoHundred = root.findViewById(R.id.full_res_200_button);
        boolean two = twoHundred != null && twoHundred.getParent() != null;
        String camera = camera();
        boolean dead50 = dead(camera, two, false), dead200 = two && dead(camera, true, true);
        boolean is200 = two && lights200(PixelPro10.tele200(), dead50, dead200);
        if (fifty != null) style(fifty, on && !is200 && !dead50, two ? "50" : null, dead50);
        if (two) style(twoHundred, on && is200, "200", dead200);
    }

    /**
     * {@code megapixels}: «50» / «200» on the tele with two sizes (in the name read out and the tooltip), else null; P214h
     * {@code dead}: every row of the size failed - dimmed, named so, a tap says why.
     */
    private static void style(ImageButton button, boolean on, @androidx.annotation.Nullable String megapixels, boolean dead) {
        Context c = button.getContext();
        int accent = ShadeStyle.accent(c);
        String name = c.getString(on ? R.string.full_res_on : R.string.full_res_off);
        if (megapixels != null) name = name + " · " + megapixels + com.particlesdevs.photoncamera.util.Lang.t(c, " Мп", " MP");
        if (dead) name = name + com.particlesdevs.photoncamera.util.Lang.t(c, " · недоступно, нажмите — почему", " · unavailable, tap for why");
        button.setContentDescription(name);
        button.setTooltipText(name);
        button.setActivated(on);
        if (on) button.setBackground(ShadeStyle.pressable(c, ShadeStyle.tint(accent, 0.22f), accent, 14));
        else button.setBackgroundResource(R.drawable.shade_gear_card);
        button.setImageTintList(ColorStateList.valueOf(accent));
        button.setAlpha(dead ? DEAD_ALPHA : 1f);
    }
}
