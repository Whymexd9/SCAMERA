package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.content.res.Resources;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;

import java.util.Locale;

/**
 * UI language. The interface is Russian when the system language is Russian and English otherwise: XML text lives in
 * {@code values/} (English) and {@code values-ru/} (Russian); text built in Java goes through {@link #t(String, String)}.
 * The choice follows the resources Android resolved for the app ({@code R.string.ui_language} is "ru" only in
 * {@code values-ru}), so Java text and XML text always agree, also with a locale list like "kk, ru". Without an app context
 * (unit tests, early start) the system / default locale decides.
 */
public final class Lang {
    private Lang() {}

    /** True when the UI is Russian. */
    public static boolean ru() {
        return ru(PhotonCamera.getAppContext());
    }

    /** True when the UI of {@code context}'s resources is Russian ({@code context} may be null). */
    public static boolean ru(Context context) {
        if (context != null) {
            try {
                return "ru".equals(context.getString(R.string.ui_language));
            } catch (RuntimeException ignored) {
                // no resources (stubbed context): fall through to the locale
            }
        }
        try {
            Locale system = Resources.getSystem().getConfiguration().getLocales().get(0);
            if (system != null) return "ru".equals(system.getLanguage());
        } catch (RuntimeException | LinkageError ignored) {
            // plain JVM without Android: the default locale
        }
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    /** The Russian or the English text, by the UI language. */
    public static String t(String ru, String en) {
        return ru() ? ru : en;
    }

    /** As {@link #t(String, String)} for the resources of {@code context}. */
    public static String t(Context context, String ru, String en) {
        return ru(context) ? ru : en;
    }
}
