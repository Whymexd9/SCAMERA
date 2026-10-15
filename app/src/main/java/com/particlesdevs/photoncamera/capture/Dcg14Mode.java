package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * The «DCG 14 бит» switch of the manual strip (Dcg14Button): off / on, kept across starts; its keys are session keys, so a
 * change restarts the camera.
 * <p>
 * P122: the vivo X300 Ultra sets DCG by its Open Gate sensor modes on the main, the ultra-wide and the tele
 * ({@link VivoQcomIsz}, session key vivo.control.forceSensorMode). P123 (owner, 2026-10-13: «На виво на мтк не работает ISZ и
 * DCG»): the X500 Pro Max's MediaTek route (P104-P112: HDR / seamless keys, the DCG / LOFIC search, the vivo-camera package
 * spoof, the 16:9 video route) never gave a 14-bit RAW and is gone.
 */
public final class Dcg14Mode {
    private static final String PREFS = "scam_dcg14";
    private static final String KEY_MODE = "mode";
    public static final int OFF = 0, DCG = 1;

    private Dcg14Mode() {}

    /** A phone with the switch: the vivo X300 Ultra. */
    public static boolean device() {
        return VivoQcomIsz.enabled() || VivoQcomModes.device();
    }

    /** Whether this camera has a DCG mode (X300 Ultra: main, ultra-wide, tele). */
    public static boolean available(@Nullable CameraCharacteristics c) {
        if (c == null || !device() || !PreferenceKeys.scamDevSwitch("dcg14", true)) return false;
        if (VivoQcomModes.device()) return VivoQcomModes.dcgAvailable(c);
        return VivoQcomIsz.dcgAvailable(c);
    }

    /** OFF or DCG (P107's LOFIC, mode 2, reads as DCG). */
    public static int mode() {
        SharedPreferences p = prefs();
        return p == null || p.getInt(KEY_MODE, OFF) == OFF ? OFF : DCG;
    }

    public static void setMode(int mode) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(KEY_MODE, mode == OFF ? OFF : DCG).apply();
    }

    public static boolean on() {
        return mode() != OFF;
    }

    /** OFF -> DCG -> OFF. */
    public static int next(int mode) {
        return mode == OFF ? DCG : OFF;
    }

    public static String name(int mode) {
        return mode == OFF ? "off" : "DCG";
    }

    @Nullable
    private static SharedPreferences prefs() {
        Context c = ScameraApp.getAppContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The switch is on, this camera has the mode, and the module forces no sensor mode of its own (an ISZ module). */
    public static boolean active(@Nullable CameraCharacteristics c) {
        return on() && available(c) && VivoQcomIsz.plainModule();
    }

    /** Sets the mode on the session parameters; returns what was set ("" when inactive). */
    public static String apply(CaptureRequest.Builder builder, @Nullable CameraCharacteristics c, @Nullable String physicalId) {
        if (builder == null || !active(c)) return "";
        if (VivoQcomModes.device()) return VivoQcomModes.apply(builder, c, physicalId);
        return VivoQcomIsz.applyDcg(builder, c);
    }
}
