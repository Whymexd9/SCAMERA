package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.RawPayloadCheck;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Arrays;
import java.util.List;

/**
 * P104 / P107 «DCG 14 бит» and «LOFIC» on the vivo X500 Pro Max (V2602A / PD2602, MT6995; owner, 2026-10-11: «кнопку
 * переключения на режим с dcg 14 bit»; 2026-10-12: «сделай всё чтобы заставить работать DCG … и LOFIC»; the square button at
 * the right end of the manual strip).
 * <p>
 * P104 sent the HDR keys a third-party app has (session keys; values from scam_dev, -1 = not sent):
 * {@code com.mediatek.hdrfeature.hdrMode} / {@code SessionParamhdrMode} = dcg14_hdr_mode (3), {@code vivo.control.EnableDCGHDR} =
 * dcg14_dcghdr (1), {@code vivo.control.session.seamlesscontrol} = dcg14_seamless (1). The owner's log of 2026-10-12 shows them
 * accepted but the main camera still binned (sensorScenario [2, 1], dynamic white 1023). P107 adds the sensor-mode choice of
 * MediaTek's seamless feature: the tries of {@link DcgSearch} (session {@code com.mediatek.seamlessfeature.cameraScenario},
 * {@code sensorScenarioCustomHint} = [camera, 100 + sensor mode] of the FORCE_CAMERA_CHOOSE table, request
 * {@code sensorScenario} = [camera, type] swept), one try per session, until a result shows a 14-bit white level / wide RAW or a
 * moved sensor scenario; the try that worked is kept per mode. scam_dev dcg14_camera_scenario / dcg14_sensor_scenario still
 * force a value. Every try and its result go to the log («DCG search …»).
 */
public final class Dcg14Mode {
    private static final String TAG = "SCAM_CAPTURE";
    private static final String PREFS = "scam_dcg14";
    private static final String KEY_ON = "on", KEY_MODE = "mode";
    public static final int OFF = 0, DCG = 1, LOFIC = 2;
    /** The VHDR mode the main and tele cameras list besides stagger (8): the DCG one by its position in MediaTek's list. */
    static final int VHDR_DCG = 128;

    private static final String K_SCENARIO = "com.mediatek.seamlessfeature.cameraScenario";
    private static final String K_SENSOR = "com.mediatek.seamlessfeature.sensorScenario";
    private static final String K_HINT = "com.mediatek.seamlessfeature.sensorScenarioCustomHint";

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

    /** OFF, DCG or LOFIC (a P104 «on» is DCG). */
    public static int mode() {
        SharedPreferences p = prefs();
        if (p == null) return OFF;
        if (p.contains(KEY_MODE)) return p.getInt(KEY_MODE, OFF);
        return p.getBoolean(KEY_ON, false) ? DCG : OFF;
    }

    public static void setMode(int mode) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(KEY_MODE, mode).putBoolean(KEY_ON, mode != OFF).apply();
    }

    public static boolean on() {
        return mode() != OFF;
    }

    /** OFF -> DCG -> LOFIC -> OFF. */
    public static int next(int mode) {
        return mode == OFF ? DCG : mode == DCG ? LOFIC : OFF;
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

    // ---- P107 search state (one try per session) ----
    private static volatile DcgSearch.Step sStep;
    private static volatile int sStepIndex = -1, sSweepPos, sSweepValue = -1, sPrevSweepValue = -1, sResults, sInBlock;
    private static volatile boolean sSearching, sRestartPending;
    @Nullable private static volatile Runnable sListener;

    /** The viewfinder's button: called on the main thread when a try worked or the search ended. */
    public static void setListener(@Nullable Runnable listener) {
        sListener = listener;
    }

    /** Whether the current mode has a try that worked. */
    public static boolean found() {
        SharedPreferences p = prefs();
        return p != null && p.getInt("found_" + mode(), -1) >= 0;
    }

    /** Whether the current mode's search ran out of tries. */
    public static boolean exhausted() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean("exhausted_" + mode(), false);
    }

    /** Searches on from the try after the kept one (or from the start when the last search ran out). */
    public static void searchAgain() {
        SharedPreferences p = prefs();
        if (p == null) return;
        int m = mode(), found = p.getInt("found_" + m, -1);
        boolean exhausted = p.getBoolean("exhausted_" + m, false);
        SharedPreferences.Editor e = p.edit().putInt("found_" + m, -1).putInt("found_sweep_" + m, -1).putBoolean("exhausted_" + m, false);
        if (found >= 0) e.putInt("next_" + m, found + 1);
        else if (exhausted) e.putInt("next_" + m, 0);
        e.commit();
        Log.i(TAG, "DCG search (" + name(m) + "): again from try " + p.getInt("next_" + m, 0));
    }

    /** Picks this session's try before its session parameters are built; remembers the next try first (crash-safe). */
    public static void beginSession(@Nullable CameraCharacteristics c, @Nullable String physicalId) {
        sStep = null;
        sStepIndex = -1;
        sSearching = false;
        sRestartPending = false;
        sSweepValue = sPrevSweepValue = -1;
        sResults = sInBlock = sSweepPos = 0;
        SharedPreferences p = prefs();
        if (p == null || !active(c) || !"2".equals(physicalId) && mode() == LOFIC) return;
        int m = mode();
        List<DcgSearch.Step> steps = DcgSearch.steps(m == LOFIC);
        int found = p.getInt("found_" + m, -1);
        if (found >= 0 && found < steps.size()) {
            sStep = steps.get(found);
            sStepIndex = found;
            sSweepValue = p.getInt("found_sweep_" + m, -1);
            Log.i(TAG, "DCG search (" + name(m) + "): using try " + found + ": " + sStep + (sSweepValue >= 0 ? ", sensorScenario " + sSweepValue : ""));
            return;
        }
        if (p.getBoolean("exhausted_" + m, false)) return;
        // Owner's log 2026-10-13 (all 66 DCG and 44 LOFIC tries): a sensor-mode hint (100 + mode) with our streams stalls the
        // preview and kills the vivo camera provider («unknown device», then «Error configuring streams: Function not
        // implemented / Broken pipe», onError 4) on most tries; the camera scenarios that stream (504, 508, 509, 512, 522,
        // 523, 525, 527, 543, 548) report sensor scenario 0 with white 1023 (no DCG). The automatic search is off by
        // default; scam_dev "dcg14_search 1" runs it again (each crash restarts the camera service).
        if (!PreferenceKeys.scamDevSwitch("dcg14_search", false)) return;
        int i = p.getInt("next_" + m, 0);
        if (i >= steps.size()) {
            p.edit().putBoolean("exhausted_" + m, true).commit();
            Log.i(TAG, "DCG search (" + name(m) + "): all " + steps.size() + " tries done, no 14-bit result");
            notifyListener();
            return;
        }
        p.edit().putInt("next_" + m, i + 1).commit();
        sStep = steps.get(i);
        sStepIndex = i;
        sSweepValue = sStep.sweep.isEmpty() ? -1 : sStep.sweep.get(0);
        sSearching = true;
        Log.i(TAG, "DCG search (" + name(m) + "): try " + i + "/" + steps.size() + " on camera " + physicalId + ": " + sStep);
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
        int camera = cameraNumber(physicalId);
        DcgSearch.Step step = sStep;
        int scenario = Math.round(PreferenceKeys.scamDevNumber("dcg14_camera_scenario", -1f));
        if (scenario < 0 && step != null) scenario = step.cameraScenario;
        if (scenario >= 0) set(builder, K_SCENARIO, new int[]{scenario}, out);
        if (camera >= 0) {
            if (step != null && step.hint >= 0) set(builder, K_HINT, new int[]{camera, step.hint}, out);
            int sensor = Math.round(PreferenceKeys.scamDevNumber("dcg14_sensor_scenario", -1f));
            if (sensor < 0) sensor = sSweepValue;
            if (sensor >= 0) set(builder, K_SENSOR, new int[]{camera, sensor}, out);
        }
        return out.toString().trim();
    }

    private static int cameraNumber(@Nullable String physicalId) {
        if (physicalId == null) return -1;
        try {
            return Integer.parseInt(physicalId);
        } catch (NumberFormatException e) {
            return -1;
        }
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

    private static final CaptureResult.Key<int[]> RES_SENSOR_SCENARIO = new CaptureResult.Key<>(K_SENSOR, int[].class);
    private static final CaptureResult.Key<int[]> RES_HINT = new CaptureResult.Key<>(K_HINT, int[].class);
    private static final CaptureResult.Key<int[]> RES_VHDR = new CaptureResult.Key<>("com.mediatek.hdrfeature.vhdrResult", int[].class);
    private static final CaptureResult.Key<int[]> RES_HDR_DETECT = new CaptureResult.Key<>("com.mediatek.hdrfeature.hdrDetectionResult", int[].class);
    private static volatile String sLastState;

    /**
     * Logs the HAL's sensor scenario, hint, HDR results and white level when they change (on the phone, on or off; the black
     * level rounded, it drifts every frame), and runs the search: a 14-bit result or a moved scenario keeps the try, else the
     * next swept value or, after the try's results, the next try in a new session.
     */
    public static void onResult(@Nullable CaptureController controller, @Nullable CaptureResult r, @Nullable String physicalId) {
        if (r == null || !device()) return;
        int[] scenario = read(r, RES_SENSOR_SCENARIO);
        Integer white = r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        float[] black = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        String state = "sensorScenario " + str(scenario) + ", hint " + str(read(r, RES_HINT)) + ", vhdrResult " + str(read(r, RES_VHDR))
                + ", hdrDetection " + str(read(r, RES_HDR_DETECT)) + ", dynamic white " + white
                + ", black " + (black == null || black.length == 0 ? "-" : Math.round(black[0]));
        if (!state.equals(sLastState)) {
            sLastState = state;
            Log.i(TAG, "DCG14 camera " + physicalId + " (" + name(mode()) + "): " + state);
        }
        DcgSearch.Step step = sStep;
        if (!sSearching || step == null || controller == null) return;
        sResults++;
        sInBlock++;
        int camera = cameraNumber(physicalId);
        boolean bits = DcgSearch.fourteenBit(white, RawPayloadCheck.wideWhite(), 1023);
        boolean moved = sResults > 3 && DcgSearch.scenarioMoved(scenario, camera);
        if (bits || moved) {
            // a swept value takes a few frames to reach the results: early in a block the previous value did it
            int value = step.sweep.isEmpty() ? -1 : (sInBlock <= 4 && sPrevSweepValue >= 0 ? sPrevSweepValue : sSweepValue);
            SharedPreferences p = prefs();
            int m = mode();
            if (p != null) p.edit().putInt("found_" + m, sStepIndex).putInt("found_sweep_" + m, value).commit();
            sSearching = false;
            sSweepValue = value;
            Log.i(TAG, "DCG search (" + name(m) + "): FOUND by " + (bits ? "14-bit white" : "sensor scenario") + " at try " + sStepIndex
                    + ": " + step + (value >= 0 ? ", sensorScenario " + value : "") + "; result " + state);
            notifyListener();
            return;
        }
        if (!step.sweep.isEmpty()) {
            if (sInBlock < DcgSearch.RESULTS_PER_SWEEP) return;
            sInBlock = 0;
            if (++sSweepPos < step.sweep.size()) {
                sPrevSweepValue = sSweepValue;
                sSweepValue = step.sweep.get(sSweepPos);
                CaptureRequest.Builder b = controller.mPreviewRequestBuilder;
                if (b != null && camera >= 0) {
                    StringBuilder out = new StringBuilder();
                    set(b, K_SENSOR, new int[]{camera, sSweepValue}, out);
                    controller.rebuildPreviewBuilder();
                    Log.i(TAG, "DCG search: request " + out.toString().trim());
                }
                return;
            }
        } else if (sResults < DcgSearch.RESULTS_PER_TRY) {
            return;
        }
        if (sRestartPending) return;
        sRestartPending = true;
        sSearching = false;
        Log.i(TAG, "DCG search: try " + sStepIndex + " no 14-bit result (" + state + "), next try");
        new Handler(Looper.getMainLooper()).post(controller::restartCamera);
    }

    private static void notifyListener() {
        Runnable l = sListener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l);
    }

    public static String name(int mode) {
        return mode == LOFIC ? "LOFIC" : mode == DCG ? "DCG" : "off";
    }

    @Nullable
    private static int[] read(CaptureResult r, CaptureResult.Key<int[]> key) {
        try {
            return r.get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(@Nullable int[] v) {
        return v == null ? "-" : Arrays.toString(v);
    }
}
