package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * One look for every alert dialog of the app (owner, 2026-10-28: «исправь стиль всех таких меню которые выбиваются из нашего
 * стиля приложения»): the settings card (a faint outline, 24dp corners), a white medium title, muted body text, sentence-case
 * buttons - a quiet text «Отмена», the confirm an accent pill like a chosen chip - and the settings' accent radio in lists
 * (R.style.AlertDialogTheme).
 * <p>
 * The dialogs are androidx / Material ones drawn from the app's own layouts and theme: OEM ROMs (ColorOS, OriginOS, HyperOS)
 * restyle the framework's android.app.AlertDialog, a grey Holo card with ALL-CAPS buttons on those phones, but not these.
 * The same theme is the base theme's alertDialogTheme, so the dialogs the preference library builds (backup, restore, reset)
 * look the same.
 */
public final class DialogStyle {
    private DialogStyle() {}

    /** The app's alert dialog builder: the Material builder on {@code c} with the user's accent and the app's dialog theme. */
    public static MaterialAlertDialogBuilder builder(Context c) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(accented(c), R.style.AlertDialogTheme);
        // the card with its outline (the builder's own surface has no stroke); the insets stay the theme's
        b.setBackground(AppCompatResources.getDrawable(b.getContext(), R.drawable.dialog_surface));
        return b;
    }

    /**
     * {@code c} with the overlay of the user's accent (as BaseActivity applies it), so a dialog over an activity that does not
     * apply it (LensDiscoveryActivity) or over a non-activity context has the same accent pill and radio.
     */
    static Context accented(Context c) {
        int style = PreferenceKeys.accentStyle(AccentPalette.selected(c));
        return style == 0 ? c : new ContextThemeWrapper(c, style);
    }

    /** A text field inside a dialog: the settings' edit-sheet field (dark fill, faint outline, 14dp corners), app colours. */
    public static <T extends EditText> T field(T field) {
        Context c = field.getContext();
        field.setBackground(AppCompatResources.getDrawable(c, R.drawable.dialog_field));
        field.setTextColor(SettingsStyle.TEXT);
        field.setHintTextColor(SettingsStyle.MUTED);
        field.setTextSize(16);
        field.setMinHeight(SettingsStyle.dp(c, 48));
        field.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 12));
        return field;
    }

    /** A check box or switch inside a dialog: the text colour, the accent when on and a muted box when off. */
    public static <T extends CompoundButton> T check(T box) {
        box.setTextColor(SettingsStyle.TEXT);
        box.setTextSize(15);
        box.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{SettingsStyle.accent(box.getContext()), SettingsStyle.MUTED}));
        return box;
    }

    /**
     * The custom view of a dialog: {@code views} in a column aligned with the title (24dp at the sides), 12dp apart.
     */
    public static LinearLayout body(Context c, View... views) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(SettingsStyle.dp(c, 24), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 24), SettingsStyle.dp(c, 4));
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (box.getChildCount() > 0) lp.topMargin = SettingsStyle.dp(c, 12);
            box.addView(v, lp);
        }
        return box;
    }
}
