package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;

import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class VivoPd2454NativeRaw {
    public static final String PACKAGE = "android.camera.cts";
    private static final String OFFERED = "module_pd2454_native_isz_";
    private static final CaptureRequest.Key<int[]> FORCE =
            new CaptureRequest.Key<>(VivoQcomIsz.KEY, int[].class);

    private VivoPd2454NativeRaw() { }

    static int factor(String camera, int mode) {
        if (("3".equals(camera) || "4".equals(camera)) && mode == 3) return 2;
        if ("5".equals(camera)) return mode == 5 ? 2 : mode == 7 ? 4 : 0;
        return 0;
    }

    static boolean eligible(boolean device, String applicationId, String camera, int mode,
                            boolean fullRes, boolean dcg) {
        return device && PACKAGE.equals(applicationId) && !fullRes && !dcg && factor(camera, mode) > 0;
    }

    public static boolean active(String physicalId) {
        Context context = ScameraApp.getAppContext();
        String slot = ModuleRegistry.active();
        CameraMode mode = ScameraApp.getSettings().selectedMode;
        return context != null && (mode == CameraMode.PHOTO || mode == CameraMode.MOTION || mode == CameraMode.NIGHT)
                && ModuleRegistry.visible(slot)
                && physicalId != null && physicalId.equals(ModuleRegistry.camera(slot))
                && eligible(VivoQcomModes.device(), context.getPackageName(), physicalId,
                ModuleRegistry.sensorMode(slot), PixelPro10.fullResOn(), Dcg14Mode.on());
    }

    public static void apply(CaptureRequest.Builder builder, String camera) {
        VivoSdkFullRes.Profile full = fullResProfile(camera);
        if (builder != null && full != null) {
            applyFullRes(builder, full);
            return;
        }
        if (builder != null && active(camera))
            builder.set(FORCE, new int[]{ModuleRegistry.sensorMode(ModuleRegistry.active())});
    }

    /** The native FULLRES profile the camera runs now (P180: on any phone of VivoNativeFullRes), null otherwise. */
    public static VivoSdkFullRes.Profile fullResProfile(String camera) {
        Context context = ScameraApp.getAppContext();
        return context != null && VivoNativeFullRes.device() && PACKAGE.equals(context.getPackageName())
                && PixelPro10.fullResOn() && !Dcg14Mode.on()
                ? VivoSdkFullRes.nativeProfile(camera) : null;
    }

    static void applyFullRes(CaptureRequest.Builder builder, VivoSdkFullRes.Profile profile) {
        if (profile == null || profile.nativeBlock < 2) throw new IllegalArgumentException("Native FULLRES profile missing");
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        builder.set(FORCE, new int[]{profile.sensorMode});
    }

    /**
     * P181 (owner, 2026-10-16: «у 0.8x/2x/4.8x/9.6x убери подпись "ISZ"»): the name an ISZ module of ours had, «2× ISZ», is
     * its zoom alone; any other name (the owner's own) stays.
     */
    static String plainName(String name, String label) {
        return name != null && name.equals(label + " ISZ") ? label : name;
    }

    public static void ensureModules(Context context) {
        if (context == null || !VivoQcomModes.device() || !PACKAGE.equals(context.getPackageName())) return;
        SharedPreferences prefs = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        for (String slot : ModuleRegistry.slots()) {
            String marker = OFFERED + ModuleRegistry.camera(slot) + "_" + ModuleRegistry.sensorMode(slot);
            String label = VivoQcomIsz.label(ModuleRegistry.zoom(slot));
            String name = prefs.getString("module_name_" + slot, "");
            String plain = plainName(name, label);
            if (ModuleRegistry.duplicateOf(slot) != null && prefs.getBoolean(marker, false) && !plain.equals(name))
                prefs.edit().putString("module_name_" + slot, plain).apply();
        }
        for (String source : new ArrayList<>(ModuleRegistry.slots())) {
            if (!source.startsWith("back") || !ModuleRegistry.visible(source)
                    || ModuleRegistry.duplicateOf(source) != null || ModuleRegistry.sensorMode(source) != 0
                    || ModuleRegistry.sensorCrop(source) || Math.abs(ModuleRegistry.crop(source) - 1f) > .0001f) continue;
            String camera = ModuleRegistry.camera(source);
            int[] modes = "5".equals(camera) ? new int[]{5, 7}
                    : "3".equals(camera) || "4".equals(camera) ? new int[]{3} : new int[0];
            for (int mode : modes) {
                String offered = OFFERED + camera + "_" + mode;
                if (prefs.getBoolean(offered, false)) continue;
                boolean exists = false;
                for (String slot : ModuleRegistry.slots())
                    if (camera.equals(ModuleRegistry.camera(slot)) && ModuleRegistry.sensorMode(slot) == mode
                            && ModuleRegistry.visible(slot)) { exists = true; break; }
                if (exists) { prefs.edit().putBoolean(offered, true).apply(); continue; }
                String target = ModuleRegistry.duplicate(source);
                if (target == null) continue;
                float zoom = ModuleRegistry.zoom(source) * factor(camera, mode);
                List<VendorTagUtils.TunableKey> keys = new ArrayList<>(TunableKeyManager.loadKeys(context, target));
                keys.removeIf(key -> VivoQcomIsz.KEY.equals(key.name));
                keys.add(VivoQcomIsz.sessionKey(mode));
                TunableKeyManager.saveKeys(context, target, keys);
                prefs.edit().putString("module_name_" + target, VivoQcomIsz.label(zoom))
                        .putString("module_zoom_" + target, String.format(Locale.US, "%.3f", zoom))
                        .putBoolean("module_sensorcrop_" + target, true)
                        .putBoolean(offered, true).apply();
            }
        }
    }
}
