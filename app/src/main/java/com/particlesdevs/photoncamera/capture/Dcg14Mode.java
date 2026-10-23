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
 * <p>
 * P230: the Xiaomi 15 Ultra's main camera ({@link XiaomiDcg}: vendor operation mode 0x9203 on the session, current_mode 24,
 * 60 fps, the 512 empty rows cut, 14-bit levels).
 */
public final class Dcg14Mode {
    private static final String PREFS = "scam_dcg14";
    private static final String KEY_MODE = "mode";
    public static final int OFF = 0, DCG = 1;

    private Dcg14Mode() {}

    /** A phone with the switch: the vivo X300 Ultra, the X200 Ultra (its guarded routes), the Xiaomi 15 Ultra. */
    public static boolean device() {
        return VivoQcomIsz.enabled() || VivoQcomModes.device() || XiaomiDcg.enabled();
    }

    /** Whether this camera has a DCG mode (X300 Ultra: main, ultra-wide, tele; 15 Ultra: the main). */
    public static boolean available(@Nullable CameraCharacteristics c) {
        if (c == null || !device() || !PreferenceKeys.scamDevSwitch("dcg14", true)) return false;
        if (XiaomiDcg.device()) return XiaomiDcg.available(c);
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

    /**
     * P320: this camera's session asks for DCG frames: the switch's routes ({@link #active}) or the Pixel 10 Pro's main camera,
     * whose RAW is DCG in every format (owner, P108; {@link PixelPro10#dcgMain}). For the session's level learning and the settings
     * hint; a shot takes the DCG gates only when its frames are shown to be DCG ({@link #frames}).
     */
    public static boolean requested(@Nullable CameraCharacteristics c) {
        try {
            return active(c) || PixelPro10.dcgMain(c);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * P320: this camera's RAW frames are DCG now (one exposure, high- and low-gain reads fused), shown by the data, not by the
     * switch alone: the switch's routes once the session's samples resolve the mode's 14 bits (a HAL that ran its plain mode under
     * the request - the 15 Ultra's «NOT the DCG mode», the X200 Ultra's routes that never delivered - stays 10-bit and plain), or
     * the Pixel 10 Pro's main. For the processing gates (no exposure brackets, the histogram's linearity proof, two noise zones),
     * never for session keys.
     */
    public static boolean frames(@Nullable CameraCharacteristics c) {
        try {
            if (PixelPro10.dcgMain(c)) return true;
            return active(c) && com.particlesdevs.photoncamera.processing.RawLevels.current(c, null).bits >= 14;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * P320: the HAL gives no gain below the N frames' in this DCG session (P163, the X300 Ultra's DCG modes: ISO 1336 for 167);
     * the switch's routes only, not the Pixel (never shown to refuse a lower gain).
     */
    public static boolean gainFloor(@Nullable CameraCharacteristics c) {
        try {
            return active(c);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Sets the mode on the session parameters; returns what was set ("" when inactive). */
    public static String apply(CaptureRequest.Builder builder, @Nullable CameraCharacteristics c, @Nullable String physicalId) {
        if (builder == null || !active(c)) return "";
        if (XiaomiDcg.device()) return XiaomiDcg.applySession(builder, c);
        if (VivoQcomModes.device()) return VivoQcomModes.apply(builder, c, physicalId);
        return VivoQcomIsz.applyDcg(builder, c);
    }

    /** P230: the vendor operation mode the DCG session runs (Xiaomi 15 Ultra: 0x9203), 0 when it needs none or is inactive. */
    public static int operationMode(@Nullable CameraCharacteristics c) {
        return XiaomiDcg.device() && active(c) ? XiaomiDcg.operationMode() : 0;
    }

    /**
     * P230: a route whose mode keys go on every request as well (Xiaomi 15 Ultra: current_mode and the 60 fps range on the
     * viewfinder's and the shot's requests; X200 Ultra Telegram route: 60 fps); "" when there is none.
     */
    public static String applyRequest(CaptureRequest.Builder builder, @Nullable CameraCharacteristics c) {
        if (builder == null || !requestKeys(c)) return "";
        return XiaomiDcg.device() ? XiaomiDcg.applyRequest(builder, c) : VivoQcomModes.applyDcgRequest(builder);
    }

    /** P230: the active DCG route sets keys on every request (Xiaomi 15 Ultra; the X200 Ultra's guarded Telegram route). */
    public static boolean requestKeys(@Nullable CameraCharacteristics c) {
        return active(c) && (XiaomiDcg.device() || VivoQcomModes.telegramDcg());
    }

    /** P230: the depth the DCG mode's samples have when the route says so up front (Xiaomi 15 Ultra: 14), 0 to learn it. */
    public static int knownBits(@Nullable CameraCharacteristics c) {
        return XiaomiDcg.device() && active(c) ? XiaomiDcg.BITS : 0;
    }
}
