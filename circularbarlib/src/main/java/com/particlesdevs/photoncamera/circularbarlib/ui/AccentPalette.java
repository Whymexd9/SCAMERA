package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One persisted accent shared by settings and camera controls. P130: «Из системы» (also the default, never chosen) takes the
 * phone's own accent - Android 12+ dynamic colours, system_accent1_200 (the tone the fixed pastel accents sit at on the dark
 * UI); before Android 12 it is the old default (lavender in the settings, yellow on the camera controls).
 */
public final class AccentPalette {
    public static final String KEY="pref_theme_accent_key";
    public static final String SYSTEM="system";
    public static final String[] VALUES={SYSTEM,"lavender","blue","green","amber","red","pink","cyan","white"};
    /** The names shown for {@link #VALUES} (string resources: English, Russian in values-ru). */
    public static final int[] NAMES={com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_system,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_lavender,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_blue,com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_green,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_amber,com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_red,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_pink,com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_cyan,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_white};
    public static String selected(Context c){return android.preference.PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext()).getString(KEY,"default");}
    /** «Из системы» chosen, or nothing chosen yet. */
    public static boolean isSystem(String value){return SYSTEM.equals(value)||"default".equals(value);}
    /** The phone's accent (Android 12+), 0 when it has none. */
    public static int systemColor(Context c){
        if(android.os.Build.VERSION.SDK_INT<31)return 0;
        try{return c.getResources().getColor(android.R.color.system_accent1_200,null);}catch(RuntimeException e){return 0;}
    }
    public static int color(Context c){String v=selected(c);if(isSystem(v)){int s=systemColor(c);if(s!=0)return s;}return color(v);}
    /** The swatch of one choice of the accent page. */
    public static int swatch(Context c,String value){if(isSystem(value)){int s=systemColor(c);if(s!=0)return s;}return color(value);}
    public static int color(String value){
        switch(value){
            case "blue":return 0xFF90C7FF;case "green":return 0xFFABE0BA;
            case "amber":case "orange":return 0xFFFFD47E;case "red":return 0xFFFFA99E;
            case "pink":return 0xFFF1ACD8;case "cyan":case "teal":return 0xFF93DEDF;
            case "white":return 0xFFE7E9F0;
            default:return 0xFFC7B5FF;
        }
    }
    public static int camera(Context c){String v=selected(c);if(isSystem(v)){int s=systemColor(c);return s!=0?s:0xFFFFD447;}return color(v);}
    public static void select(Context c,String value){android.preference.PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext()).edit().putString(KEY,value).apply();}
}
