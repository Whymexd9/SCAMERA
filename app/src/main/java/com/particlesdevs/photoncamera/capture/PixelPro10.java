package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;
import android.util.SizeF;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.ZoomLabels;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P108 Pixel 10 Pro (blazer) / 10 Pro XL (mustang): the in-sensor-zoom modules and the full-resolution switch (owner, 2026-10-12: «на Pixel
 * 10 pro xl не отображаются все модули, не хватает ISZ модулей, также на этом смарте доступен DCG с raw12, сделай его включение
 * аналогично 500 про макс по кнопке в видоискателе с перезапуском видоискателя»; «не только на 10 про хл, но и на 10 про»).
 * <p>
 * SCAM TEST report of the 10 Pro XL (2026-10-12): logical back camera 0 of physical 2 (main 6.9 mm, 4080x3072 binned of
 * 8160x6144), 3 (UW 2.02 mm), 4 (tele 17.906 mm), 5 / 6 / 9 = the same lenses on half the sensor width (physical size 4.896 /
 * 2.82 / 2.82 mm: the 2x in-sensor crops of main, tele and UW). The owner's viewfinder showed only 0.5x / 1x / 4.5x.
 * {@link #ensureModules} adds every physical camera of the back logical camera whose field of view no visible module has, as
 * "0-<id>" (session on the logical camera, readers on the physical one), labelled by its field against the main, once per
 * camera (a deleted one stays deleted). A crop module whose field is within 15 % of an existing one (UW 2x crop = 1.02x) is
 * left out.
 * <p>
 * DCG: no switch (owner, 2026-10-12: «у пикселя DCG работает в raw12 и raw sensor сразу же на основной камере, так что
 * переключатель можно убрать»): the main camera's RAW is DCG in every format; the P108 switch (RAW12 + iDCG) is gone.
 * <p>
 * Full resolution (owner: «добавь кнопку сверху кнопки DCG»; MotionCam Pro takes 50 MP on this phone): a RAW size of
 * SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION (as MotionCam lists it, no ULTRA_HIGH_RESOLUTION capability on the Pixel):
 * RAW10 8160x6144 @ 24 fps on 0 / 2 / 5, 8064x6048 @ 15 on 6 / 9. That stream takes MAXIMUM_RESOLUTION requests only, so every
 * stream of the session is configured for that mode with sizes of the max-resolution map (viewfinder / YUV 1440x1080 for 4:3)
 * and every request carries it: the ZSL ring keeps working (owner: «в режиме полного разрешения ZSL должен тоже работать»),
 * the RAW viewfinder shows the frame 2x2-binned (the ISP's own one is green in that mode), the readers hold 8 frames. A refused session runs binned again
 * (CaptureController.retryWithoutFullRes). Nothing is verified on the phone.
 */
public final class PixelPro10 {
    private static final String TAG = "PixelPro10";
    private static final String PREFS = "scam_pixel10";
    private static final String KEY_FULL = "full_res";
    /** P179: the telephoto's native FULLRES size on the X200 Ultra: 200 MP (mode 0, Tetra) or 50 MP (mode 1, Quad). */
    private static final String KEY_TELE_200 = "full_res_tele_200";
    private static final String OFFERED = "module_pixel_offered_";
    /** Fields closer than this to a visible module's are the same module. */
    static final float SAME_FIELD = 0.15f;

    private PixelPro10() {}

    public static boolean device() {
        return device(Build.MANUFACTURER, Build.DEVICE);
    }

    static boolean device(@Nullable String manufacturer, @Nullable String device) {
        if (manufacturer == null || device == null || !manufacturer.equalsIgnoreCase("google")) return false;
        String d = device.toLowerCase(Locale.ROOT);
        return d.equals("mustang") || d.equals("blazer");
    }

    /** Field of view against the main camera from focal length and sensor width (35 mm-equivalent ratio). */
    static float field(float focal, float width, float mainFocal, float mainWidth) {
        if (width <= 0f || mainWidth <= 0f || mainFocal <= 0f) return 0f;
        return (focal / width) / (mainFocal / mainWidth);
    }

    /** Whether a field is new against the visible modules' fields. */
    static boolean newField(float field, List<Float> known) {
        if (field <= 0f) return false;
        for (float k : known) if (k > 0f && Math.abs(field / k - 1f) < SAME_FIELD) return false;
        return true;
    }

    /** Adds the back logical camera's physical cameras the module strip lacks (in-sensor crops), once each. */
    public static void ensureModules(@Nullable Context context) {
        if (context == null || !device() || !PreferenceKeys.scamDevSwitch("pixel_modules", true)) return;
        try {
            SharedPreferences p = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            CameraCharacteristics logical = manager.getCameraCharacteristics("0");
            Set<String> physical = logical.getPhysicalCameraIds();
            if (physical.isEmpty()) return;
            String mainId = null;
            for (String slot : ModuleRegistry.slots())
                if (slot.startsWith("back") && ModuleRegistry.visible(slot) && Math.abs(ModuleRegistry.zoom(slot) - 1f) < 0.05f) {
                    mainId = toPhysical(ModuleRegistry.camera(slot));
                    break;
                }
            CameraCharacteristics main = manager.getCameraCharacteristics(mainId != null ? mainId : "2");
            float mainFocal = focal(main), mainWidth = width(main);
            List<Float> known = new ArrayList<>();
            List<String> knownIds = new ArrayList<>();
            for (String slot : ModuleRegistry.slots())
                if (slot.startsWith("back") && ModuleRegistry.visible(slot)) {
                    String id = toPhysical(ModuleRegistry.camera(slot));
                    knownIds.add(id);
                    try {
                        CameraCharacteristics c = manager.getCameraCharacteristics(id);
                        known.add(field(focal(c), width(c), mainFocal, mainWidth));
                    } catch (Exception ignored) {
                        known.add(ModuleRegistry.zoom(slot));
                    }
                }
            StringBuilder report = new StringBuilder();
            SharedPreferences.Editor e = p.edit();
            boolean changed = false;
            for (String id : physical) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                float f = field(focal(c), width(c), mainFocal, mainWidth);
                report.append(id).append('=').append(String.format(Locale.US, "%.2f", f)).append("x ");
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                if (knownIds.contains(id) || p.getBoolean(OFFERED + id, false) || !newField(f, known)) continue;
                String slot = freeBackSlot(p);
                e.putBoolean(OFFERED + id, true);
                changed = true;
                if (slot == null) {
                    Log.w(TAG, "no free module slot for camera 0-" + id);
                    continue;
                }
                String cameraId = "0-" + id;
                e.putString("module_auto_" + slot, cameraId).putBoolean("module_visible_" + slot, true)
                        .putString("module_label_" + slot, ZoomLabels.label(f, Build.BRAND))
                        .putString("module_zoom_" + slot, String.format(Locale.US, "%.2f", f))
                        .putBoolean("module_sensorcrop_" + slot, false)
                        .putFloat("module_fov_" + cameraId, f);
                known.add(f);
                // the camera list materializes it at the next start like a lens the owner chose (ModuleLensFragment)
                Set<String> user = new java.util.HashSet<>(p.getStringSet("user_camera_ids", new java.util.HashSet<>()));
                user.add(cameraId);
                Set<String> hidden = new java.util.HashSet<>(p.getStringSet("hidden_camera_ids", new java.util.HashSet<>()));
                hidden.remove(cameraId);
                e.putStringSet("user_camera_ids", user).putStringSet("hidden_camera_ids", hidden);
                Log.i(TAG, "module " + slot + " = camera " + cameraId + " (" + String.format(Locale.US, "%.2f", f) + "x, in-sensor crop)");
            }
            if (changed) e.apply();
            Log.i(TAG, "back logical 0 physical cameras: " + report.toString().trim() + "; modules on " + knownIds);
        } catch (Exception e) {
            Log.w(TAG, "modules not added: " + e);
        }
    }

    private static String freeBackSlot(SharedPreferences p) {
        for (int i = 0; i < 8; i++) if (!p.contains("module_auto_back" + i)) return "back" + i;
        for (int i = 0; i < 8; i++) {
            String slot = "back" + i;
            if (!ModuleRegistry.visible(slot) && ModuleRegistry.duplicateOf(slot) == null) return slot;
        }
        return null;
    }

    private static String toPhysical(String id) {
        if (id == null) return "";
        if (id.contains("-")) return id.split("-", 2)[1];
        if (id.contains("/")) return id.split("/", 2)[1];
        return id;
    }

    private static float focal(CameraCharacteristics c) {
        float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return f == null || f.length == 0 ? 0f : f[0];
    }

    private static float width(CameraCharacteristics c) {
        SizeF s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        return s == null ? 0f : s.getWidth();
    }

    @Nullable
    private static SharedPreferences prefs() {
        Context c = ScameraApp.getAppContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- full resolution ----

    /** The full-resolution RAW stream of a camera: format and size of its max-resolution map. */
    public static final class FullRes {
        public final int format;
        public final Size size;

        FullRes(int format, Size size) {
            this.format = format;
            this.size = size;
        }
    }

    public static boolean fullResOn() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(KEY_FULL, false);
    }

    public static void setFullRes(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(KEY_FULL, on).apply();
    }

    /** P179: the telephoto's FULLRES is 200 MP (the default, as before the choice) rather than 50 MP. */
    public static boolean tele200() {
        SharedPreferences p = prefs();
        return p == null || p.getBoolean(KEY_TELE_200, true);
    }

    public static void setTele200(boolean on) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(KEY_TELE_200, on).apply();
    }

    /** The largest RAW of the camera's max-resolution map (RAW_SENSOR, else RAW10, else RAW12), null without one. */
    @Nullable
    public static FullRes fullResStream(@Nullable CameraCharacteristics c) {
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        StreamConfigurationMap map;
        try {
            map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
        } catch (RuntimeException e) {
            return null;
        }
        if (map == null) return null;
        for (int format : new int[]{ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12}) {
            Size[] sizes;
            try {
                sizes = map.getOutputSizes(format);
            } catch (RuntimeException e) {
                continue;
            }
            Size best = largest(sizes);
            if (best != null) return new FullRes(format, best);
        }
        return null;
    }

    @Nullable
    static Size largest(@Nullable Size[] sizes) {
        Size best = null;
        if (sizes != null) for (Size s : sizes)
            if (s != null && (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight())) best = s;
        return best;
    }

    /**
     * The largest size of the max-resolution map for a format (format < 0: the SurfaceTexture viewfinder) with the aspect of
     * {@code like}, else the largest of any aspect; null without the map or sizes.
     */
    @Nullable
    public static Size maxResSize(@Nullable CameraCharacteristics c, int format, @Nullable Size like) {
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        Size[] sizes;
        try {
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            if (map == null) return null;
            sizes = format < 0 ? map.getOutputSizes(android.graphics.SurfaceTexture.class) : map.getOutputSizes(format);
        } catch (RuntimeException e) {
            return null;
        }
        return sameAspect(sizes, like);
    }

    /** The largest of {@code sizes} within 2 % of the aspect of {@code like}, else the largest. */
    @Nullable
    static Size sameAspect(@Nullable Size[] sizes, @Nullable Size like) {
        if (sizes == null || sizes.length == 0) return null;
        if (like != null && like.getHeight() > 0) {
            float aspect = (float) like.getWidth() / like.getHeight();
            java.util.List<Size> same = new ArrayList<>();
            for (Size s : sizes)
                if (s != null && s.getHeight() > 0 && Math.abs((float) s.getWidth() / s.getHeight() / aspect - 1f) < 0.02f) same.add(s);
            if (!same.isEmpty()) return largest(same.toArray(new Size[0]));
        }
        return largest(sizes);
    }

    public static boolean fullResAvailable(@Nullable CameraCharacteristics c) {
        return (device() || VivoQcomModes.device()) && PreferenceKeys.scamDevSwitch("pixel_full_res", true) && fullResStream(c) != null;
    }

    /** The full-resolution stream while the switch is on (null otherwise). */
    @Nullable
    public static FullRes fullRes(@Nullable CameraCharacteristics c) {
        return fullResOn() && (device() || VivoQcomModes.device()) && PreferenceKeys.scamDevSwitch("pixel_full_res", true) ? fullResStream(c) : null;
    }
}
