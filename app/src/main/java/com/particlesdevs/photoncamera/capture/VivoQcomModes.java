package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Locale;

public final class VivoQcomModes {
    private static final String TAG = "VivoQcomModes";
    private static final String MARK = "module_vivo_qcom_isz_";
    private static final String OFFERED = "module_vivo_qcom_isz_offered_";
    private static final String VIVO_CAMERA = "com.android.camera";
    private static final String K_MODE = "vcf.parameter.seamlessMode";
    private static final String K_UI = "com.vivo.current_ui_module";
    private static final String K_SHDR = "org.codeaurora.qcamera3.sessionParameters.EnableSHDR";
    private static final String K_STREAM_USAGE = "vivo.control.streamsUsage";
    /**
     * P230: the client as Telegram. PD2454's sensor-mode selector (vendor/etc/camera/sensormode-selector/PD2454.json) gives
     * AppType TELEGRAM (CameraService's package table: org.telegram.messenger = 307) DCGHDR60_1_4 at 50 / 60 fps on every
     * camera - {DCG1_4, 60 fps}: 4096 x 2632 on the main (modes 10 / 11) and the ultra-wide (8 / 9) - the only DCG branch a
     * third-party AppType reaches on the 200 MP lenses (DEFAULT / WEIXIN ... get PHOTO_V2H2 there; the 14-bit DCG_SENSORFUSION
     * LOG modes are AppType vivo only). There the selector's own geometry is the DCG mode's, unlike the forced-mode tries of
     * P109-P200 (selector PHOTO 4096 x 3072 against CHI's forced 4096 x 2632: no RAW frame). Guarded (scam_dev
     * vivo_dcg_telegram 1), not phone-checked: the RAW may be the ISP's 10-bit input, the frames may not come at all.
     */
    static final String TELEGRAM = "org.telegram.messenger";
    static final int DCG_FPS = 60;
    private static volatile String lastResult, lastActive;

    public static void onCameraOpened() {
        if (!device() || (spoofPackage() == null
                && !PreferenceKeys.scamDevSwitch("vivo_qcom_provider_app", false))) return;
        try {
            Class<?> api = Class.forName("android.hardware.vivocamera.VivoCameraManager");
            Object manager = api.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method get = api.getMethod("getInfo", int.class);
            Object app = get.invoke(manager, 1);
            Object vcf = get.invoke(manager, 33);
            Log.i(TAG, "provider AppType=" + app + " VCFAppType=" + vcf);
            if (PreferenceKeys.scamDevSwitch("vivo_qcom_provider_app", false)) {
                api.getMethod("setValue", int.class, int.class).invoke(manager, 1, 1);
                // setValue is a one-way Binder call on this firmware.
                android.os.SystemClock.sleep(100);
                Log.i(TAG, "provider after AppType request: AppType=" + get.invoke(manager, 1)
                        + " VCFAppType=" + get.invoke(manager, 33));
            }
            if (PreferenceKeys.scamDevSwitch("vivo_qcom_vcf_bypass", false)) {
                api.getMethod("setValue", int.class, int.class).invoke(manager, 33, 2);
                Log.i(TAG, "provider after native RAW request: AppType=" + get.invoke(manager, 1)
                        + " VCFAppType=" + get.invoke(manager, 33));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "vivo provider access unavailable: " + e);
        }
    }

    private VivoQcomModes() {}

    public static boolean device() {
        String manufacturer = Build.MANUFACTURER;
        String device = Build.DEVICE;
        return manufacturer != null && manufacturer.equalsIgnoreCase("vivo")
                && device != null && device.equalsIgnoreCase("PD2454");
    }

    public static boolean enabled() {
        return device() && PreferenceKeys.scamDevSwitch("vivo_qcom_modes", true);
    }

    /** P230: the DCG switch runs the Telegram-AppType route on this phone (scam_dev vivo_dcg_telegram 1). */
    static boolean telegramRoute() {
        return device() && PreferenceKeys.scamDevSwitch("vivo_dcg_telegram", false);
    }

    /** P230: the Telegram-AppType DCG route is on (the route and the DCG switch). */
    public static boolean telegramDcg() {
        return telegramRoute() && Dcg14Mode.on();
    }

    /**
     * P230: a mode at least 4000 wide, shorter than 4:3 and 2400 rows or more, at 60 fps: the 16:10 DCG modes of the main and the
     * ultra-wide (4096 x 2632). The tele has its 4080 x 2616 modes at 30 fps only, the front none.
     */
    static boolean dcg60Table(@Nullable int[] table) {
        if (table == null || table.length < 2) return false;
        for (int m = 0; m < table[0]; m++) {
            final int[] e = SensorModeBand.modeEntry(table, m);
            if (e != null && e[0] >= 4000 && e[1] >= 2400 && e[1] * 4 < e[0] * 3 && e[2] == DCG_FPS) return true;
        }
        return false;
    }

    @Nullable
    public static String spoofPackage() {
        if (telegramDcg()) return TELEGRAM;
        boolean qcom = Build.MANUFACTURER != null && Build.MANUFACTURER.equalsIgnoreCase("vivo")
                && (device() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ("Qualcomm".equalsIgnoreCase(Build.SOC_MANUFACTURER)
                || "QTI".equalsIgnoreCase(Build.SOC_MANUFACTURER)));
        return qcom && PreferenceKeys.scamDevSwitch("vivo_qcom_spoof", false) ? VIVO_CAMERA : null;
    }

    public static boolean dcgAvailable(@Nullable CameraCharacteristics c) {
        if (c == null) return false;
        if (telegramRoute()) return dcg60Table(SensorModeBand.table(c));
        return routesEnabled() && supported(c, K_SHDR);
    }

    /** P230: the Telegram route's 60 fps on a request (the viewfinder's, a shot's, the session's); the log text, "" when off. */
    public static String applyDcgRequest(CaptureRequest.Builder builder) {
        if (builder == null || !telegramDcg()) return "";
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(DCG_FPS, DCG_FPS));
        return "AE fps [" + DCG_FPS + ", " + DCG_FPS + "]";
    }

    private static boolean routesEnabled() {
        return enabled() && PreferenceKeys.scamDevSwitch("vivo_qcom_sensor_routes", false);
    }

    public static boolean wantsStreamUsage() {
        return device() && spoofPackage() != null
                && PreferenceKeys.scamDevSwitch("vivo_qcom_stream_usage", true);
    }

    static int[] streamUsage(int[] roles) {
        int[] usage = new int[roles.length + 1];
        usage[0] = roles.length;
        System.arraycopy(roles, 0, usage, 1, roles.length);
        return usage;
    }

    public static String applyStreamUsage(CaptureRequest.Builder builder, int[] roles) {
        if (!wantsStreamUsage()) return "";
        StringBuilder out = new StringBuilder();
        // VCF needs a purpose for every output, in the configured surface order.
        set(builder, K_STREAM_USAGE, streamUsage(roles), out);
        set(builder, K_UI, new int[]{Dcg14Mode.on() ? 3 : 1}, out);
        return out.toString().trim();
    }

    public static int activeFactor() {
        if (!routesEnabled()) return 0;
        try {
            SharedPreferences p = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            String slot = ModuleRegistry.active();
            int factor = p.getInt(MARK + slot, 0);
            if (factor == 0) {
                String source = ModuleRegistry.duplicateOf(slot);
                if (source != null && ("3".equals(ModuleRegistry.camera(slot)) || "4".equals(ModuleRegistry.camera(slot)))) {
                    float base = ModuleRegistry.zoom(source);
                    float zoom = ModuleRegistry.zoom(slot);
                    if (base > 0f && Math.abs(zoom / base - 2f) < 0.08f) factor = 2;
                }
            }
            String state = "active=" + slot + " factor=" + factor;
            if (!state.equals(lastActive)) {
                lastActive = state;
                Log.i(TAG, state);
            }
            return factor;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public static void ensureModules(@Nullable Context context) {
        if (context == null || !routesEnabled()) return;
        try {
            SharedPreferences p = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            migrateMtkModules(p);
            for (String slot : ModuleRegistry.slots()) {
                if (!slot.startsWith("back") || !ModuleRegistry.visible(slot)
                        || ModuleRegistry.duplicateOf(slot) != null || p.getInt(MARK + slot, 0) != 0) continue;
                String camera = ModuleRegistry.camera(slot);
                if (!"3".equals(camera) && !"4".equals(camera)) continue;
                String offered = OFFERED + camera + "_2";
                if (p.getBoolean(offered, false)) continue;
                String copy = ModuleRegistry.duplicate(slot);
                if (copy == null) continue;
                float zoom = ModuleRegistry.zoom(slot) * 2f;
                p.edit().putBoolean(offered, true).putInt(MARK + copy, 2)
                        .putString("module_name_" + copy, label(zoom))
                        .putString("module_zoom_" + copy, String.format(Locale.US, "%.2f", zoom)).apply();
                Log.i(TAG, "ISZ module " + copy + " camera " + camera + " 2x");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "ISZ modules not created: " + e);
        }
    }

    private static void migrateMtkModules(SharedPreferences p) {
        for (String slot : ModuleRegistry.slots()) {
            int factor = p.getInt("module_vivo_isz_" + slot, 0);
            if (factor == 0) continue;
            String camera = ModuleRegistry.camera(slot);
            if (factor == 2 && ("3".equals(camera) || "4".equals(camera))) {
                p.edit().remove("module_vivo_isz_" + slot).putInt(MARK + slot, 2)
                        .putBoolean(OFFERED + camera + "_2", true).apply();
                Log.i(TAG, "migrated ISZ module " + slot + " camera " + camera);
            } else if (ModuleRegistry.duplicateOf(slot) != null) {
                ModuleRegistry.remove(slot);
                p.edit().remove("module_vivo_isz_" + slot).apply();
                Log.i(TAG, "removed MediaTek ISZ module " + slot + " camera " + camera);
            }
        }
    }

    public static String apply(CaptureRequest.Builder builder, @Nullable CameraCharacteristics c, @Nullable String physicalId) {
        if (!device() || builder == null) return "";
        StringBuilder out = new StringBuilder();
        if (telegramDcg() && dcgAvailable(c))
            out.append("AppType TELEGRAM (").append(TELEGRAM).append("), ").append(applyDcgRequest(builder))
                    .append(": the selector's DCGHDR60_1_4 ");
        if (wantsStreamUsage()) {
            set(builder, K_UI, new int[]{Dcg14Mode.on() ? 3 : 1}, out);
        }
        if (PreferenceKeys.scamDevSwitch("vivo_qcom_reprocess", false)) {
            set(builder, "vivo.control.reprocessMode", new int[]{1}, out);
        }
        if (!routesEnabled()) return out.toString().trim();
        if (activeFactor() == 2 && ("3".equals(physicalId) || "4".equals(physicalId))) {
            set(builder, K_UI, new int[]{1}, out);
            // Selector Parameter offsets are bytes: .4 addresses the second int.
            set(builder, K_MODE, new int[]{3, 5, 0}, out);
        }
        if (Dcg14Mode.on() && dcgAvailable(c)) {
            set(builder, K_UI, new int[]{3}, out);
            set(builder, K_SHDR, new int[]{1}, out);
        }
        return out.toString().trim();
    }

    public static void onResult(@Nullable CaptureResult result, @Nullable String physicalId) {
        if (!enabled() || result == null || activeFactor() == 0 && !Dcg14Mode.on()) return;
        String state = "camera " + physicalId + " mode=" + SensorModeBand.reportedMode(result)
                + " seamless=" + value(result, "vivo.feedback.seamlessMode")
                + " changed=" + value(result, "vivo.parameter.seamlessModeChanged")
                + " white=" + result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if (!state.equals(lastResult)) {
            lastResult = state;
            Log.i(TAG, state);
        }
    }

    private static boolean supported(CameraCharacteristics c, String name) {
        for (CaptureRequest.Key<?> key : c.getAvailableCaptureRequestKeys())
            if (name.equals(key.getName())) return true;
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(CaptureRequest.Builder builder, String name, int[] value, StringBuilder out) {
        try {
            CaptureRequest.Key<int[]> key = new CaptureRequest.Key<>(name, int[].class);
            builder.get(key);
            builder.set((CaptureRequest.Key) key, value);
            out.append(name).append('=').append(java.util.Arrays.toString(value)).append(' ');
        } catch (RuntimeException e) {
            out.append(name).append(" n/a ");
        }
    }

    private static String value(CaptureResult result, String name) {
        try {
            int[] v = result.get(new CaptureResult.Key<>(name, int[].class));
            return v == null ? "-" : java.util.Arrays.toString(v);
        } catch (RuntimeException ignored) {
            return "-";
        }
    }

    private static String label(float zoom) {
        return String.format(Locale.US, "%.1f×", zoom).replace(".0", "");
    }
}
