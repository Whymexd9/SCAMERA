package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.SizeF;

import com.particlesdevs.photoncamera.util.Log;

/**
 * Xiaomi 17 Ultra (P17): one zoom slider over the tele's continuous optical zoom and its in-sensor zoom.
 *
 * <ul>
 * <li>75-100 mm: optical zoom, {@code com.xiaomi.camera.userZoomRatio.userZoomRatio} = mm / 23.256 and
 *     {@code android.control.zoomRatio} = mm / 74.419 (the owner's values for 100 mm: 4.30000019 and 1.34375).</li>
 * <li>100-150 mm: a digital crop of the 100 mm frame (zoomRatio grows for the preview, the shot is cropped by mm / 100).</li>
 * <li>150-200 mm: ISZ, {@code org.codeaurora.qcamera3.sensor_meta_data.current_mode} = 9 (2x on the tele), with the same
 *     smooth optical zoom inside it: optical position mm / 2. Above 200 mm a crop of the ISZ frame.</li>
 * <li>ISZ turns on at 150 mm and off below 145 mm, so the slider does not toggle the sensor mode at the boundary; inside
 *     145-150 mm the ISZ frame stays at its widest (150 mm).</li>
 * </ul>
 * The sensor mode change is the owner's explicit exception to "never switch sensor modes", scoped to this phone and the switch
 * «Плавный оптический зум (Xiaomi 17 Ultra)». Without the vendor keys the module behaves as before.
 */
public final class XiaomiTeleZoom {
    private static final String TAG = "XiaomiTeleZoom";
    static final String KEY_USER_ZOOM = "com.xiaomi.camera.userZoomRatio.userZoomRatio";
    static final String KEY_SENSOR_MODE = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    static final float MM_PER_USER = 100f / 4.30000019f;
    static final float MM_PER_RATIO = 100f / 1.34375f;
    static final float OPT_MIN = 75f, OPT_MAX = 100f, ISZ_ON = 150f, ISZ_OFF = 145f, ISZ_MAX = 200f;
    static final int ISZ_MODE = 9;
    public static final String PREF = "pref_xiaomi_smooth_zoom";

    private static final CaptureRequest.Key<Float> USER_ZOOM = new CaptureRequest.Key<>(KEY_USER_ZOOM, Float.class);
    private static final CaptureRequest.Key<Integer> SENSOR_MODE = new CaptureRequest.Key<>(KEY_SENSOR_MODE, Integer.class);

    private XiaomiTeleZoom() {}

    public static final class Plan {
        public final float mm, userZoom, zoomRatio, residual;
        public final boolean isz;
        Plan(float mm, float userZoom, float zoomRatio, float residual, boolean isz) {
            this.mm = mm; this.userZoom = userZoom; this.zoomRatio = zoomRatio; this.residual = residual; this.isz = isz;
        }
        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "%.1f mm: userZoom %.4f zoomRatio %.4f crop %.3f%s", mm, userZoom, zoomRatio, residual, isz ? " ISZ" : "");
        }
    }

    /** The keys for an equivalent focal length (mm); {@code iszBefore}: ISZ was on (hysteresis). */
    static Plan plan(float mm, boolean iszBefore) {
        mm = Math.max(OPT_MIN, mm);
        boolean isz = iszBefore ? mm >= ISZ_OFF : mm >= ISZ_ON;
        if (!isz) {
            float optical = Math.min(mm, OPT_MAX);
            float crop = mm / optical;
            return new Plan(mm, optical / MM_PER_USER, Math.max(1f, optical / MM_PER_RATIO) * crop, crop, false);
        }
        float optical = Math.max(OPT_MIN, Math.min(mm / 2f, OPT_MAX));
        float crop = Math.max(1f, mm / (2f * optical));
        return new Plan(mm, optical / MM_PER_USER, Math.max(1f, optical / MM_PER_RATIO) * crop, crop, true);
    }

    private static String prop(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, name, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static Boolean phone;

    /** Xiaomi 17 Ultra, by its marketing name (Xiaomi sets ro.product.marketname) or the model string. */
    public static boolean phone() {
        if (phone != null) return phone;
        String market = prop("ro.product.marketname") + " " + prop("ro.product.vendor.marketname");
        boolean xiaomi = "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER);
        phone = xiaomi && (market.contains("17 Ultra") || String.valueOf(Build.MODEL).contains("17 Ultra"));
        Log.i(TAG, "phone=" + phone + " manufacturer=" + Build.MANUFACTURER + " model=" + Build.MODEL + " device=" + Build.DEVICE + " market=" + market.trim());
        return phone;
    }

    /** The tele with the continuous optical zoom: 35 mm equivalent of its shortest focal length 65-85 mm. */
    static boolean teleModule(CameraCharacteristics c) {
        return c != null && Math.abs(equivalent(c) - OPT_MIN) <= 10f;
    }

    static float equivalent(CameraCharacteristics c) {
        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focal == null || focal.length == 0 || sensor == null) return 0f;
        float f = focal[0];
        for (float v : focal) f = Math.min(f, v);
        double diagonal = Math.hypot(sensor.getWidth(), sensor.getHeight());
        return diagonal > 0 ? (float) (f * 43.27 / diagonal) : 0f;
    }

    static boolean supported(CaptureRequest.Builder b) {
        try {
            b.get(USER_ZOOM);
            b.get(SENSOR_MODE);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static volatile boolean isz;
    private static volatile Plan last;

    public static boolean isz() { return isz; }
    public static Plan last() { return last; }
    static void reset() { isz = false; last = null; }

    /**
     * Sets the zoom keys on the tele of this phone. {@code zoom} is the slider's ratio against the main lens, {@code moduleZoom}
     * the tele button's ratio (the slider position of its 75 mm). Returns the plan, or null when this is not the case (another
     * phone, the switch off, another module, no vendor keys): the caller zooms as for any module.
     */
    public static Plan apply(CaptureRequest.Builder b, CameraCharacteristics c, boolean switchOn, float moduleZoom, float zoom, String physicalId) {
        if (b == null || !switchOn || !phone() || !teleModule(c) || !supported(b) || moduleZoom <= 0f) return null;
        Plan p = plan(OPT_MIN * zoom / moduleZoom, isz);
        b.set(USER_ZOOM, p.userZoom);
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, p.zoomRatio);
        b.set(SENSOR_MODE, p.isz ? Integer.valueOf(ISZ_MODE) : null);
        if (physicalId != null && !physicalId.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            try {
                b.setPhysicalCameraKey(USER_ZOOM, p.userZoom, physicalId);
                b.setPhysicalCameraKey(SENSOR_MODE, p.isz ? Integer.valueOf(ISZ_MODE) : null, physicalId);
            } catch (RuntimeException ignored) {
                // a logical camera without that physical ID: the logical keys apply
            }
        }
        boolean changed = p.isz != isz;
        isz = p.isz;
        last = p;
        if (changed) Log.i(TAG, "ISZ " + (p.isz ? "on" : "off") + " at " + p);
        return p;
    }
}
