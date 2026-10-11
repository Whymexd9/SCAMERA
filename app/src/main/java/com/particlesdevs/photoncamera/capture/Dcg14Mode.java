package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Arrays;

/**
 * P104 «DCG 14 бит» on the vivo X500 Pro Max (V2602A / PD2602, MT6995; owner, 2026-10-11: «кнопку переключения на режим с dcg
 * 14 bit»; the square button at the right end of the manual strip).
 * <p>
 * From the phone's SCAM TEST report: the main IMX09C's scenario table (/odm/etc/camera/scenario_table_imx09cpd2602.xml) gives
 * third-party apps CUSTOM_CAMERA_SCENARIO_3RD_APP (sensor mode 14, FAEC) and CUSTOM_CAMERA_SCENARIO_3RD_APP_HDR with
 * MTK_SEAMLESS_FEATURE_SENSOR_SCENARIO_DCG_SENSOR_MERGE_14BIT_BAYER (mode 33, «dcg 1:16»: the sensor merges its two
 * conversion gains into one 14-bit Bayer frame); the telephotos' 3RD_APP_HDR is DCG AP-merge (2 exposures). The main and tele
 * cameras list com.mediatek.hdrfeature.availableVhdrModes {8, 128, 0} and HDR photo modes {0, 3, 1}. Which request makes the HAL
 * pick 3RD_APP_HDR is not in the report, so the switch sends the HDR keys a third-party app has (session keys; values from
 * scam_dev, -1 = not sent):
 * <ul>
 *   <li>{@code com.mediatek.hdrfeature.hdrMode} and {@code SessionParamhdrMode} = dcg14_hdr_mode (default 3, «video HDR on»);</li>
 *   <li>{@code vivo.control.EnableDCGHDR} = dcg14_dcghdr (default 1);</li>
 *   <li>{@code vivo.control.session.seamlesscontrol} = dcg14_seamless (default 1: the selector's «seamless HDR, 1 exposure»);</li>
 *   <li>{@code com.mediatek.seamlessfeature.cameraScenario} = dcg14_camera_scenario (default -1) and
 *       {@code com.mediatek.seamlessfeature.sensorScenario} = [camera, dcg14_sensor_scenario] (default -1) for direct tries.</li>
 * </ul>
 * Results: the HAL's {@code com.mediatek.seamlessfeature.sensorScenario} (pairs camera, scenario; 1 = binned), the HDR results and
 * the dynamic white level are logged on change («DCG14 …»); a RAW wider than its declared white is taken as such (RawPayloadCheck
 * wide RAW). Nothing is verified on the phone yet.
 */
public final class Dcg14Mode {
    private static final String TAG = "SCAM_CAPTURE";
    private static final String PREFS = "scam_dcg14";
    private static final String KEY_ON = "on";
    /** The VHDR mode the main and tele cameras list besides stagger (8): the DCG one by its position in MediaTek's list. */
    static final int VHDR_DCG = 128;

    private Dcg14Mode() {}

    /** The vivo X500 Pro Max. */
    public static boolean device() {
        return device(Build.MODEL, Build.DEVICE);
    }

    static boolean device(@Nullable String model, @Nullable String device) {
        return (model != null && model.toUpperCase(java.util.Locale.ROOT).startsWith("V2602"))
                || (device != null && device.toUpperCase(java.util.Locale.ROOT).contains("PD2602"));
    }

    /** Whether this camera has the DCG HDR (VHDR 128 listed): the main and the telephotos. */
    public static boolean available(@Nullable CameraCharacteristics c) {
        if (c == null || !device() || !PreferenceKeys.scamDevSwitch("dcg14", true)) return false;
        try {
            int[] modes = c.get(new CameraCharacteristics.Key<>("com.mediatek.hdrfeature.availableVhdrModes", int[].class));
            return HiddenFeatures.contains(modes, VHDR_DCG);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean on() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(KEY_ON, false);
    }

    public static void set(boolean value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(KEY_ON, value).apply();
    }

    @Nullable
    private static SharedPreferences prefs() {
        Context c = PhotonCamera.getAppContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The switch is on and this camera has the mode. */
    public static boolean active(@Nullable CameraCharacteristics c) {
        return on() && available(c);
    }

    /** Sets the mode's keys on a session-parameter or request builder; returns what was set ("" when inactive). */
    public static String apply(CaptureRequest.Builder builder, @Nullable CameraCharacteristics c, @Nullable String physicalId) {
        if (builder == null || !active(c)) return "";
        StringBuilder out = new StringBuilder();
        int hdr = Math.round(PreferenceKeys.scamDevNumber("dcg14_hdr_mode", 3f));
        if (hdr >= 0) {
            set(builder, "com.mediatek.hdrfeature.hdrMode", new int[]{hdr}, out);
            set(builder, "com.mediatek.hdrfeature.SessionParamhdrMode", new int[]{hdr}, out);
        }
        int dcg = Math.round(PreferenceKeys.scamDevNumber("dcg14_dcghdr", 1f));
        if (dcg >= 0) set(builder, "vivo.control.EnableDCGHDR", new int[]{dcg}, out);
        int seamless = Math.round(PreferenceKeys.scamDevNumber("dcg14_seamless", 1f));
        if (seamless >= 0) set(builder, "vivo.control.session.seamlesscontrol", new int[]{seamless}, out);
        int scenario = Math.round(PreferenceKeys.scamDevNumber("dcg14_camera_scenario", -1f));
        if (scenario >= 0) set(builder, "com.mediatek.seamlessfeature.cameraScenario", new int[]{scenario}, out);
        int sensor = Math.round(PreferenceKeys.scamDevNumber("dcg14_sensor_scenario", -1f));
        if (sensor >= 0 && physicalId != null) {
            try {
                set(builder, "com.mediatek.seamlessfeature.sensorScenario", new int[]{Integer.parseInt(physicalId), sensor}, out);
            } catch (NumberFormatException ignored) {
                // a non-numeric camera id: no pair to send
            }
        }
        return out.toString().trim();
    }

    private static void set(CaptureRequest.Builder builder, String name, int[] value, StringBuilder out) {
        CaptureRequest.Key<int[]> key = new CaptureRequest.Key<>(name, int[].class);
        try {
            builder.get(key); // unknown name: IllegalArgumentException
            builder.set(key, value);
            out.append(name).append('=').append(Arrays.toString(value)).append(' ');
        } catch (RuntimeException e) {
            out.append(name).append(" n/a ");
        }
    }

    private static final CaptureResult.Key<int[]> RES_SENSOR_SCENARIO =
            new CaptureResult.Key<>("com.mediatek.seamlessfeature.sensorScenario", int[].class);
    private static final CaptureResult.Key<int[]> RES_VHDR = new CaptureResult.Key<>("com.mediatek.hdrfeature.vhdrResult", int[].class);
    private static final CaptureResult.Key<int[]> RES_HDR_DETECT = new CaptureResult.Key<>("com.mediatek.hdrfeature.hdrDetectionResult", int[].class);
    private static volatile String sLastState;

    /** Logs the HAL's sensor scenario, HDR results and dynamic white level when they change (on the phone, on or off). */
    public static void onResult(@Nullable CaptureResult r, @Nullable String physicalId) {
        if (r == null || !device()) return;
        String state = "sensorScenario " + read(r, RES_SENSOR_SCENARIO) + ", vhdrResult " + read(r, RES_VHDR)
                + ", hdrDetection " + read(r, RES_HDR_DETECT) + ", dynamic white " + r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
                + ", black " + Arrays.toString(r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL));
        if (state.equals(sLastState)) return;
        sLastState = state;
        Log.i(TAG, "DCG14 camera " + physicalId + " (" + (on() ? "on" : "off") + "): " + state);
    }

    private static String read(CaptureResult r, CaptureResult.Key<int[]> key) {
        try {
            int[] v = r.get(key);
            return v == null ? "-" : Arrays.toString(v);
        } catch (RuntimeException e) {
            return "n/a";
        }
    }
}
