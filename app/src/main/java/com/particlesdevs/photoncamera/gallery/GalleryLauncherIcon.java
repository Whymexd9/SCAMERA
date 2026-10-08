package com.particlesdevs.photoncamera.gallery;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.particlesdevs.photoncamera.util.Log;

/**
 * The gallery as its own app in the launcher (P59b, owner): the activity-alias GalleryActivityLauncher, enabled or disabled
 * by the setting «Скрыть иконку галереи» (pref_hide_gallery_icon_key, default off = the icon is shown). One place for the app
 * start and the settings screen.
 */
public final class GalleryLauncherIcon {
    static final String ALIAS = "com.particlesdevs.photoncamera.gallery.ui.GalleryActivityLauncher";

    private GalleryLauncherIcon() {}

    /** The component state for the setting. */
    static int stateFor(boolean hide) {
        return hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    /** True when the current state already matches (the manifest default counts as shown). */
    static boolean matches(int current, boolean hide) {
        return current == stateFor(hide) || (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !hide);
    }

    /** Applies the setting; returns true when the launcher state changed. Throws when the package manager refuses. */
    public static boolean apply(Context context, boolean hide) {
        PackageManager pm = context.getPackageManager();
        ComponentName alias = new ComponentName(context.getPackageName(), ALIAS);
        if (matches(pm.getComponentEnabledSetting(alias), hide)) return false;
        pm.setComponentEnabledSetting(alias, stateFor(hide), PackageManager.DONT_KILL_APP);
        Log.i("GalleryLauncherIcon", "gallery launcher icon " + (hide ? "hidden" : "shown"));
        return true;
    }
}
