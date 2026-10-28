package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.particlesdevs.photoncamera.R;

/**
 * P384: a row of the camera groups on the settings' main page (a module card's name, zoom, copy, photo settings rows; a module's
 * Camera ID row; the actions under the cards): a title, a summary, a value on the right (SettingsStyle.valueOf) and a chevron. It
 * stores nothing itself - its click opens the dialog or sheet that writes the module's keys (ModuleActions). A class of its own,
 * so SettingsStyle does not paint its title as an action, and so a row can be marked as a warning (deleting a copy) or dimmed
 * (a module's own settings while every module shares them).
 */
public class ModuleRowPreference extends Preference {
    private CharSequence value;
    private boolean warn, dimmed;

    public ModuleRowPreference(Context context) {
        super(context);
        setPersistent(false);
        setLayoutResource(R.layout.preference_card);
        setWidgetLayoutResource(R.layout.settings_widget_chevron);
        setIconSpaceReserved(true);
    }

    /** The value on the right; the row binds again only when it really changed. */
    public void setValue(@Nullable CharSequence value) {
        if (TextUtils.equals(this.value, value)) return;
        this.value = value;
        notifyChanged();
    }

    @Nullable public CharSequence value() { return value; }

    /** A row whose action loses something (the title and icon in the warning colour). */
    public void setWarn(boolean warn) {
        if (this.warn == warn) return;
        this.warn = warn;
        notifyChanged();
    }

    public boolean warn() { return warn; }

    /** A row that acts only after a question (dimmed, still tappable). */
    public void setDimmed(boolean dimmed) {
        if (this.dimmed == dimmed) return;
        this.dimmed = dimmed;
        notifyChanged();
    }

    public boolean dimmed() { return dimmed; }

    /** The title and the summary set together, binding once. */
    public void setTexts(CharSequence title, @Nullable CharSequence summary) {
        if (!TextUtils.equals(getTitle(), title)) setTitle(title);
        if (!TextUtils.equals(getSummary(), summary)) setSummary(summary);
    }
}
