package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** One persisted accent shared by settings and camera controls. */
public final class AccentPalette {
    public static final String KEY="pref_theme_accent_key";
    public static final String[] VALUES={"lavender","blue","green","amber","red","pink","cyan","white"};
    /** The names shown for {@link #VALUES} (string resources: English, Russian in values-ru). */
    public static final int[] NAMES={com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_lavender,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_blue,com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_green,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_amber,com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_red,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_pink,com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_cyan,
            com.particlesdevs.photoncamera.circularbarlib.R.string.accent_name_white};
    public static String selected(Context c){return android.preference.PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext()).getString(KEY,"default");}
    public static int color(Context c){return color(selected(c));}
    public static int color(String value){
        switch(value){
            case "blue":return 0xFF90C7FF;case "green":return 0xFFABE0BA;
            case "amber":case "orange":return 0xFFFFD47E;case "red":return 0xFFFFA99E;
            case "pink":return 0xFFF1ACD8;case "cyan":case "teal":return 0xFF93DEDF;
            case "white":return 0xFFE7E9F0;case "eszdman":return 0xFFD999EC;
            default:return 0xFFC7B5FF;
        }
    }
    public static int camera(Context c){return "default".equals(selected(c))?0xFFFFD447:color(c);}
    public static void select(Context c,String value){android.preference.PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext()).edit().putString(KEY,value).apply();}
}
