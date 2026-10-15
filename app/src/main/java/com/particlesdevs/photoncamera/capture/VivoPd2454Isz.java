package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.util.List;
import java.util.Locale;

public final class VivoPd2454Isz {
    static final String OFFERED = "module_pd2454_tele_remosaic_4x_offered";
    static final int TELE_REMOSAIC_4X = 6;
    private static final String TAG = "VivoPd2454Isz";
    private static final CaptureRequest.Key<Integer> FORCE =
            new CaptureRequest.Key<>(VivoQcomIsz.KEY, Integer.class);

    private VivoPd2454Isz() { }

    public static void ensureModules(@Nullable Context context) {
        if (context == null || !VivoQcomModes.device() || !VivoQcomModes.enabled()) return;
        SharedPreferences prefs = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        if (prefs.getBoolean(OFFERED, false)) return;
        for (String slot : ModuleRegistry.slots()) {
            if (!slot.startsWith("back") || !"5".equals(ModuleRegistry.camera(slot))) continue;
            for (VendorTagUtils.TunableKey key : TunableKeyManager.loadKeys(context, slot)) {
                if (forceValue(key, false) == TELE_REMOSAIC_4X) {
                    prefs.edit().putBoolean(OFFERED, true).apply();
                    return;
                }
            }
        }
        String source = null;
        for (String slot : ModuleRegistry.slots()) {
            if (slot.startsWith("back") && ModuleRegistry.visible(slot)
                    && "5".equals(ModuleRegistry.camera(slot)) && ModuleRegistry.duplicateOf(slot) == null
                    && ModuleRegistry.sensorMode(slot) == 0 && !ModuleRegistry.sensorCrop(slot)) {
                source = slot;
                break;
            }
        }
        if (source == null) return;
        float zoom = ModuleRegistry.zoom(source) * 4f;
        if (!Float.isFinite(zoom) || zoom <= 0f) return;
        String target = ModuleRegistry.duplicate(source);
        if (target == null) {
            Log.w(TAG, "no free module slot for tele4x HW remosaic");
            return;
        }
        String number = String.format(Locale.US, "%.1f×", zoom).replace(".0×", "×");
        String label = number + Lang.t(context, " (ремозаик)", " (remosaic)");
        prefs.edit().putString("module_name_" + target, label)
                .putString("module_zoom_" + target, String.format(Locale.US, "%.3f", zoom))
                .putBoolean("module_sensorcrop_" + target, true).apply();
        List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(context, target);
        keys.add(VivoQcomIsz.sessionKey(TELE_REMOSAIC_4X));
        TunableKeyManager.saveKeys(context, target, keys);
        prefs.edit().putBoolean(OFFERED, true).apply();
        Log.i(TAG, "offered " + target + " camera5 sensor crop4x HW remosaic, zoom=" + zoom);
    }

    public static boolean active(@Nullable String physicalId) {
        if (!VivoQcomModes.device() || !"5".equals(physicalId)
                || PixelPro10.fullResOn() || Dcg14Mode.on()) return false;
        String slot = ModuleRegistry.active();
        if (!ModuleRegistry.visible(slot) || !"5".equals(ModuleRegistry.camera(slot))) return false;
        int mode = 0;
        for (VendorTagUtils.TunableKey key : TunableKeyManager.loadKeys(
                ScameraApp.getSettingsManagerStatic().getContext(), slot)) {
            int value = forceValue(key, true);
            if (value >= 0) mode = value;
        }
        return mode == TELE_REMOSAIC_4X;
    }

    public static void mirrorRequest(CaptureRequest.Builder builder, String physicalId) {
        if (builder != null && active(physicalId)) builder.set(FORCE, TELE_REMOSAIC_4X);
    }

    private static int forceValue(@Nullable VendorTagUtils.TunableKey key, boolean sessionOnly) {
        if (key == null || !key.enabled || !VivoQcomIsz.KEY.equals(key.name)
                || !"Integer".equals(key.valueType)
                || !(VendorTagUtils.TunableKey.TYPE_SESSION.equals(key.type)
                || !sessionOnly && "CaptureRequest".equals(key.type))) return -1;
        try {
            return (Integer) key.parseValue();
        } catch (RuntimeException invalid) {
            return -1;
        }
    }
}
