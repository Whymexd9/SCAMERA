package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.app.PhotonCamera;

/** Plain reads of stored string preferences (text and clamped numbers) for keys without a PreferenceKeys getter. */
public final class RawTherapeeSettings {
    public static String text(String key, String fallback) {
        try { return PhotonCamera.getSettingsManagerStatic().getString(SettingsManager.SCOPE_GLOBAL,key,fallback); }
        catch (RuntimeException e) { return fallback; }
    }
    public static float number(String key,float fallback,float lo,float hi) {
        try {
            float f=Float.parseFloat(text(key,Float.toString(fallback)));
            return Float.isFinite(f)?Math.max(lo,Math.min(hi,f)):fallback;
        } catch(RuntimeException e) { return fallback; }
    }
    private RawTherapeeSettings() {}
}
