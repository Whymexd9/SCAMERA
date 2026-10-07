package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.util.SizeF;

import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Xiaomi 17 Ultra (P17, P41): one zoom slider over the tele's continuous optical zoom and its in-sensor zoom, as the stock camera
 * does it (stock dial: 3.2x = 75 mm, 4.3x = 100 mm, 8.6x = 200 mm, 17.2x = 400 mm, no jump and no colour change anywhere).
 *
 * <p>Owner's request keys: {@code com.xiaomi.camera.userZoomRatio.userZoomRatio} and {@code android.control.zoomRatio} for the
 * optical zoom (4.30000019 and 1.34375 at 100 mm), {@code org.codeaurora.qcamera3.sensor_meta_data.current_mode} = 9 for the 2x ISZ
 * of the tele. Owner's ranges:
 * <ul>
 * <li>75-100 mm: optical, userZoomRatio = mm / 23.256, zoomRatio = mm / 74.419;</li>
 * <li>100-150 mm: the optics stay at 100 mm, the rest is a crop (zoomRatio keeps growing: mm / 74.419);</li>
 * <li>150 mm: the optics go back to 75 mm and ISZ turns on in the same request (75 mm x 2 = 150 mm, the field of view does not
 *     jump); 150-200 mm is the optical 75-100 mm again, inside ISZ; above 200 mm a crop of the ISZ frame, up to 400 mm.</li>
 * <li>Back below 150 mm the reverse at the same point (on at 150, off below 149 mm; a toggle waits {@link #TOGGLE_MS} after the
 *     previous one unless the zoom is already more than {@link #BAND} mm past the switch).</li>
 * </ul>
 * The sensor mode change is the owner's explicit exception to "never switch sensor modes", scoped to this phone and the switch
 * «Плавный оптический зум (Xiaomi 17 Ultra)»; the switch owns current_mode on the tele (a module's own tunable current_mode is
 * overridden while it is on). Without the vendor keys the module behaves as before.
 *
 * <p>P41, from the owner's 17U screen recording: the preview did not zoom at all between 75 and 100 mm nor between 150 and 200 mm,
 * zoomed by crop between 100 and 150 mm (to ~124 mm instead of 150), and jumped x1.33 at 150 mm: the HAL crops by
 * zoomRatio / (userZoomRatio / 3.225) (it trusts userZoomRatio as the optical part) but the lens never moved. So the results are
 * watched: when the reported focal length never follows the commanded optics, the module switches to crop mode for the process
 * (userZoomRatio tells the HAL the real lens position, the HAL then crops the whole way and the field of view is right at every
 * zoom, also across the ISZ switch). Dev switch {@code xiaomi_lens_check 0} keeps the optical command anyway.
 *
 * <p>Owner's 17U recording of build 30595 (2026-10-07): the results DID report the focal length following userZoomRatio
 * (20.05 -> 26.5 mm), yet the preview did not zoom between 75 and 100 mm nor inside ISZ (measured frame to frame: x1.0 where
 * x1.34 was due), while the crop part was exact. The reported focal length follows the claim, not the glass, so the follow check
 * cannot see it: crop mode is the default (dev switch {@code xiaomi_crop_mode 0} tries the optics). The same recording: after
 * "ISZ off" the sensor stayed in mode 9 until the camera restarted (a request without current_mode keeps the last one), so once
 * ISZ was on, the way back requests the mode the tele reported before explicitly; and the ISP preview of mode 9 is the raw
 * colour mosaic (purple), so ISZ shows the developed RAW viewfinder.
 */
public final class XiaomiTeleZoom {
    private static final String TAG = "XiaomiTeleZoom";
    static final String KEY_USER_ZOOM = "com.xiaomi.camera.userZoomRatio.userZoomRatio";
    static final String KEY_SENSOR_MODE = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    /** mm per userZoomRatio unit: the main camera's 23.256 mm (owner: 100 mm = 4.30000019). */
    static final float MM_PER_USER = 100f / 4.30000019f;
    /** mm per zoomRatio unit: the tele's 74.419 mm (owner: 100 mm = 1.34375). */
    static final float MM_PER_RATIO = 100f / 1.34375f;
    static final float OPT_MIN = 75f, OPT_MAX = 100f, ISZ_ON = 150f, ISZ_OFF = 149f, ISZ_MAX = 200f, MAX_MM = 400f;
    /** The tele's button ratio as the stock camera labels it: 75 mm / 23.256 mm = 3.225 («3.2×»). */
    static final float STOCK_RATIO = OPT_MIN / MM_PER_USER;
    static final int ISZ_MODE = 9;
    /** A new ISZ toggle waits this long after the previous one (the mode change takes the HAL a few frames). */
    static final long TOGGLE_MS = 350;
    /** ... unless the zoom is this far past the switch point. */
    static final float BAND = 10f;
    /** The lens has this long to follow a commanded optical position before the module goes to crop mode. */
    static final long FOLLOW_MS = 900;
    /** Frames an in-session mode change waits at most for a frame that reports the new mode. */
    static final int BARRIER_MAX_FRAMES = 30;
    /** Frames after the first request in the new mode when the results do not report the mode. */
    static final int BARRIER_BLIND_FRAMES = 4;
    public static final String PREF = "pref_xiaomi_smooth_zoom";

    private static final CaptureRequest.Key<Float> USER_ZOOM = new CaptureRequest.Key<>(KEY_USER_ZOOM, Float.class);
    private static final CaptureRequest.Key<Integer> SENSOR_MODE = new CaptureRequest.Key<>(KEY_SENSOR_MODE, Integer.class);
    private static final CaptureResult.Key<Integer> RESULT_MODE = new CaptureResult.Key<>(KEY_SENSOR_MODE, Integer.class);

    private XiaomiTeleZoom() {}

    public static final class Plan {
        /** Equivalent focal length of the output. */
        public final float mm;
        public final float userZoom, zoomRatio;
        /** The crop the camera does not do on the RAW (the shot is cropped by it). */
        public final float residual;
        public final boolean isz;
        /** Commanded optical position (mm). */
        public final float opticalMm;
        /** Field of view of the RAW frame (mm): lens position x ISZ factor. */
        public final float rawMm;
        Plan(float mm, float userZoom, float zoomRatio, float residual, boolean isz, float opticalMm, float rawMm) {
            this.mm = mm; this.userZoom = userZoom; this.zoomRatio = zoomRatio; this.residual = residual; this.isz = isz;
            this.opticalMm = opticalMm; this.rawMm = rawMm;
        }
        @Override public String toString() {
            return String.format(Locale.ROOT, "%.1f mm: userZoom %.4f zoomRatio %.4f crop %.3f optics %.1f raw %.1f%s",
                    mm, userZoom, zoomRatio, residual, opticalMm, rawMm, isz ? " ISZ" : "");
        }
    }

    // ---------------------------------------------------------------- the mapping (pure)

    /** ISZ for {@code mm} with hysteresis only: on from 150 mm, off below 149 mm. */
    static boolean nextIsz(float mm, boolean iszBefore) {
        return iszBefore ? mm >= ISZ_OFF : mm >= ISZ_ON;
    }

    /** With the debounce: a toggle within {@link #TOGGLE_MS} of the previous one waits unless the zoom is past the band. */
    static boolean nextIsz(float mm, boolean iszBefore, long sinceToggleMs) {
        boolean want = nextIsz(mm, iszBefore);
        if (want == iszBefore || sinceToggleMs >= TOGGLE_MS) return want;
        return Math.abs(mm - ISZ_ON) > BAND ? want : iszBefore;
    }

    /** The keys for an equivalent focal length (mm); {@code iszBefore}: ISZ was on (hysteresis). */
    static Plan plan(float mm, boolean iszBefore) {
        float m = clamp(mm, OPT_MIN, MAX_MM);
        return planFor(m, nextIsz(m, iszBefore), 0f);
    }

    /**
     * The keys for {@code mm} in the given mode. {@code fixedLensMm} > 0: the lens does not move (crop mode), it stands at that
     * equivalent focal length; userZoomRatio then reports it, so the HAL crops by mm / lens (its crop is zoomRatio relative to
     * the optics userZoomRatio claims).
     */
    static Plan planFor(float mm, boolean isz, float fixedLensMm) {
        mm = clamp(mm, OPT_MIN, MAX_MM);
        final float factor = isz ? 2f : 1f;
        final float optical = clamp(mm / factor, OPT_MIN, OPT_MAX);
        final float lens = fixedLensMm > 0f ? fixedLensMm : optical;
        final float zoomRatio = Math.max(OPT_MIN, mm / factor) / MM_PER_RATIO;
        final float residual = Math.max(1f, mm / (lens * factor));
        return new Plan(mm, lens / MM_PER_USER, zoomRatio, residual, isz, optical, lens * factor);
    }

    /**
     * Field of view (mm) the HAL delivers for a plan when the lens really stands at {@code lensMm}: the model the 17U recording
     * fits (crop = zoomRatio relative to the optics userZoomRatio claims, never below 1).
     */
    static float halFieldOfView(Plan p, float lensMm) {
        float claimed = p.userZoom * MM_PER_USER;
        float crop = Math.max(1f, p.zoomRatio * MM_PER_RATIO / claimed);
        return lensMm * (p.isz ? 2f : 1f) * crop;
    }

    /** Equivalent focal length of a reported physical focal length ({@code fMin} = the 75 mm position). */
    static float equivalentOf(float focal, float fMin) {
        return fMin > 0f ? OPT_MIN * focal / fMin : Float.NaN;
    }

    /**
     * The lens is declared fixed: it never moved in this process, and the commanded position has been more than 5 mm away
     * from the reported one for {@link #FOLLOW_MS}.
     */
    static boolean lensDoesNotFollow(float commandedMm, float reportedMm, long farForMs, boolean everMoved) {
        return !everMoved && !Float.isNaN(reportedMm) && Math.abs(commandedMm - reportedMm) > 5f && farForMs >= FOLLOW_MS;
    }

    /**
     * Lens position (equivalent mm, 75-100) a reported focal length stands for, or NaN when it cannot be this tele's lens: in
     * ISZ a HAL may report the 2x field of view (above 100 mm), anything else outside the lens range belongs to another camera
     * (the logical camera's result of a physical stream) and must not count as "the lens does not follow".
     */
    static float lensPosition(float reportedMm, boolean isz) {
        if (Float.isNaN(reportedMm)) return Float.NaN;
        float mm = isz && reportedMm > OPT_MAX + 5f ? reportedMm / 2f : reportedMm;
        return mm >= OPT_MIN - 10f && mm <= OPT_MAX + 10f ? mm : Float.NaN;
    }

    /**
     * A result counts for the follow check only when its request carried the mode now applied and the last ISZ toggle is at
     * least {@link #TOGGLE_MS} old (the HAL may hold the lens while it changes the sensor mode).
     */
    static boolean countsForFollow(Integer requestMode, boolean isz, long sinceToggleMs) {
        return Integer.valueOf(ISZ_MODE).equals(requestMode) == isz && sinceToggleMs >= TOGGLE_MS;
    }

    /**
     * A RAW frame after an in-session ISZ change belongs to the new mode: its request asked for it and, when the result reports
     * the mode, the result says so; with no reported mode a few frames after the first matching request. Never more than
     * {@link #BARRIER_MAX_FRAMES} frames are dropped.
     */
    static boolean frameReady(Integer requestMode, Integer resultMode, boolean isz, int framesSinceChange, int matchingFrames) {
        if (framesSinceChange >= BARRIER_MAX_FRAMES) return true;
        boolean requested = Integer.valueOf(ISZ_MODE).equals(requestMode);
        if (requested != isz) return false;
        if (resultMode != null) return (resultMode == ISZ_MODE) == isz;
        return matchingFrames >= BARRIER_BLIND_FRAMES;
    }

    /** Stock-like zoom marks on the ruler for the tele at {@code teleRatio}: 100 mm (4.3×), 200 mm (8.6×), 400 mm (17.2×). */
    static float[] stopsFor(float teleRatio) {
        return new float[]{teleRatio * OPT_MAX / OPT_MIN, teleRatio * ISZ_MAX / OPT_MIN, teleRatio * MAX_MM / OPT_MIN};
    }

    /** The automatic label of the 17U tele («3.3×»: 78.4 mm / 23.9 mm) stands for the stock camera's 75 mm. */
    static boolean isAutoTeleRatio(float ratio) {
        return ratio >= 3.15f && ratio <= 3.45f;
    }

    static String label(float ratio) {
        return String.format(Locale.US, "%.1f×", ratio).replace(".0×", "×");
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- device and settings

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

    /** The switch «Плавный оптический зум (Xiaomi 17 Ultra)» on this phone. */
    public static boolean enabled() {
        if (!phone()) return false;
        try {
            return com.particlesdevs.photoncamera.app.PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().getBoolean(PREF, true);
        } catch (RuntimeException notReady) {
            return false;
        }
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

    // ---------------------------------------------------------------- UI (zoom ladder like the stock camera)

    private static volatile float teleRatio;

    /** ModuleRegistry: the automatic ratio of the tele module reads as the stock 3.2× (75 mm) while the switch is on. */
    public static float stockRatio(float autoRatio) {
        return isAutoTeleRatio(autoRatio) && enabled() ? STOCK_RATIO : autoRatio;
    }

    /** ModuleRegistry: the automatic label of the tele module («3.3×») as the stock camera shows it («3.2×»). */
    public static String stockLabel(String autoLabel) {
        if (autoLabel == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9]*\\.?[0-9]+").matcher(autoLabel.replace(',', '.'));
        try {
            if (m.find() && isAutoTeleRatio(Float.parseFloat(m.group())) && enabled()) return label(STOCK_RATIO);
        } catch (NumberFormatException ignored) {
            // not a ratio label
        }
        return autoLabel;
    }

    private static boolean isTele(float ratio) {
        float known = teleRatio;
        return known > 0f ? Math.abs(ratio - known) < 0.01f : isAutoTeleRatio(ratio) || Math.abs(ratio - STOCK_RATIO) < 0.01f;
    }

    /** ZoomController: the top of the zoom range when the widest-to-longest ladder ends on this tele (400 mm = 17.2×), else 0. */
    public static float maxZoom(float topRatio) {
        return isTele(topRatio) && enabled() ? topRatio * MAX_MM / OPT_MIN : 0f;
    }

    /** Ruler marks (ratios) the stock dial shows past the tele button: 4.3×, 8.6×, 17.2×; empty on other phones. */
    public static List<Float> stops(List<Float> moduleRatios) {
        List<Float> out = new ArrayList<>();
        if (moduleRatios == null || moduleRatios.isEmpty()) return out;
        float top = moduleRatios.get(moduleRatios.size() - 1);
        if (!isTele(top) || !enabled()) return out;
        for (float s : stopsFor(top)) out.add(s);
        return out;
    }

    public static String stopLabel(float ratio) { return label(ratio); }

    // ---------------------------------------------------------------- session state

    private static volatile boolean isz;
    private static volatile Plan last;
    private static long lastToggleMs;
    private static long lastTraceMs;
    // lens as the results report it
    private static volatile float fMin;
    private static volatile float[] focalList;
    private static volatile float lensMm = Float.NaN;
    private static float firstLensMm = Float.NaN;
    private static boolean lensMoved;
    private static volatile boolean lensFixed;
    /** Where the lens stood when it was declared fixed (75-100 mm). */
    private static volatile float fixedLensMm = Float.NaN;
    private static long farSinceMs;
    private static boolean focalReported, focalMissingLogged, implausibleLogged;
    private static Integer reportedMode;
    /** Sensor mode the tele reported outside ISZ (the mode to go back to), and whether ISZ was on in this session. */
    private static volatile Integer normalMode;
    private static volatile boolean iszUsed;
    private static boolean tunableWarned;
    private static String describedCamera;

    public static boolean isz() { return isz; }
    public static Plan last() { return last; }
    /** The current session is the Xiaomi tele under this switch. */
    public static boolean active() { return last != null; }
    /**
     * P41: outside ISZ the preview is the ISP's; in ISZ (mode 9) the HAL's preview is the raw colour mosaic (purple on the
     * owner's 17U), so the developed RAW viewfinder takes over there.
     */
    public static boolean ispPreview() { return last != null && !isz; }

    /**
     * Crop the HAL applies to the preview on top of the RAW frame's field of view (zoomRatio relative to the optics
     * userZoomRatio claims); the developed RAW viewfinder crops the same. 1 outside this tele.
     */
    public static float previewCrop() {
        return cropOf(last);
    }

    static float cropOf(Plan p) {
        return p == null ? 1f : Math.max(1f, p.zoomRatio * MM_PER_RATIO / (p.userZoom * MM_PER_USER));
    }
    static void reset() {
        isz = false; last = null; lastToggleMs = 0; lastTraceMs = 0; teleRatio = 0f;
        fMin = 0f; focalList = null; lensMm = Float.NaN; firstLensMm = Float.NaN; lensMoved = false; lensFixed = false;
        fixedLensMm = Float.NaN;
        normalMode = null; iszUsed = false;
        farSinceMs = 0; focalReported = false; focalMissingLogged = false; implausibleLogged = false; reportedMode = null; tunableWarned = false;
        describedCamera = null;
    }

    /** A new camera session: the ISZ state of the previous one does not carry over (its first request sets the mode). */
    public static void startSession() {
        isz = false;
        iszUsed = false;
        last = null;
        lastToggleMs = 0;
        farSinceMs = 0;
        reportedMode = null;
        tunableWarned = false;
    }

    private static void describe(CameraCharacteristics c, String physicalId) {
        String id = String.valueOf(physicalId);
        if (id.equals(describedCamera)) return;
        describedCamera = id;
        StringBuilder keys = new StringBuilder();
        try {
            for (CaptureResult.Key<?> k : c.getAvailableCaptureResultKeys()) {
                String n = k.getName();
                if (n.contains("current_mode") || n.contains("userZoomRatio") || n.contains("zoom") || n.contains("Zoom")) keys.append(n).append(' ');
            }
        } catch (RuntimeException ignored) {
            // no key list
        }
        Object range = null;
        try { if (Build.VERSION.SDK_INT >= 30) range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE); } catch (RuntimeException ignored) { /* none */ }
        Log.i(TAG, "tele " + id + ": focal lengths " + Arrays.toString(focalList) + " (75 mm = " + fMin + " mm), equivalent "
                + equivalent(c) + " mm, zoomRatio range " + range + ", result keys: " + keys.toString().trim()
                + (lensFixed ? " [crop mode: the lens did not follow earlier]" : ""));
    }

    /**
     * Sets the zoom keys on the tele of this phone. {@code zoom} is the slider's ratio against the main lens, {@code moduleZoom}
     * the tele button's ratio (the slider position of its 75 mm). Returns the plan, or null when this is not the case (another
     * phone, the switch off, another module, no vendor keys): the caller zooms as for any module.
     */
    public static Plan apply(CaptureRequest.Builder b, CameraCharacteristics c, boolean switchOn, float moduleZoom, float zoom,
                             String physicalId, boolean lensCheck) {
        return apply(b, c, switchOn, moduleZoom, zoom, physicalId, lensCheck, false);
    }

    /**
     * {@code forceCrop}: crop mode from the start, the lens standing at 75 mm (default: the 17U's HAL reports a focal length that
     * follows userZoomRatio while the glass stays; dev switch {@code xiaomi_crop_mode 0} tries the optics).
     */
    public static Plan apply(CaptureRequest.Builder b, CameraCharacteristics c, boolean switchOn, float moduleZoom, float zoom,
                             String physicalId, boolean lensCheck, boolean forceCrop) {
        if (b == null || !switchOn || !phone() || !teleModule(c) || !supported(b) || moduleZoom <= 0f) return null;
        if (fMin <= 0f) {
            float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focal != null && focal.length > 0) {
                float f = focal[0];
                for (float v : focal) f = Math.min(f, v);
                fMin = f;
                focalList = focal.clone();
            }
        }
        describe(c, physicalId);
        teleRatio = moduleZoom;
        final long now = android.os.SystemClock.elapsedRealtime();
        final float mm = clamp(OPT_MIN * zoom / moduleZoom, OPT_MIN, MAX_MM);
        final boolean nextIsz = nextIsz(mm, isz, last == null ? Long.MAX_VALUE : now - lastToggleMs);
        // forced crop mode: the lens stands at 75 mm (its reported position follows the claim, so it is not read back)
        final float fixed = forceCrop ? OPT_MIN : lensFixed && lensCheck
                ? (!Float.isNaN(fixedLensMm) ? fixedLensMm : Float.isNaN(lensMm) ? OPT_MIN : clamp(lensMm, OPT_MIN, OPT_MAX)) : 0f;
        final Plan p = planFor(mm, nextIsz, fixed);
        set(b, p, physicalId);
        boolean changed = last != null && p.isz != isz;
        if (changed) lastToggleMs = now;
        if (p.isz) iszUsed = true;
        isz = p.isz;
        last = p;
        if (changed) Log.i(TAG, "ISZ " + (p.isz ? "on" : "off") + " at " + p + lensNote());
        else if (now - lastTraceMs >= 300) {
            lastTraceMs = now;
            Log.d(TAG, "zoom " + p + lensNote());
        }
        return p;
    }

    /** Backwards-compatible form (lens check on). */
    public static Plan apply(CaptureRequest.Builder b, CameraCharacteristics c, boolean switchOn, float moduleZoom, float zoom, String physicalId) {
        return apply(b, c, switchOn, moduleZoom, zoom, physicalId, true);
    }

    /** True when the ISZ state wanted for the current zoom differs from the one applied (a toggle waits for the debounce). */
    public static boolean togglePending(float moduleZoom, float zoom) {
        Plan p = last;
        if (p == null || moduleZoom <= 0f) return false;
        float mm = clamp(OPT_MIN * zoom / moduleZoom, OPT_MIN, MAX_MM);
        return nextIsz(mm, isz) != isz;
    }

    private static String lensNote() {
        return (Float.isNaN(lensMm) ? "" : String.format(Locale.ROOT, " lens %.1f mm", lensMm)) + (lensFixed ? " (crop mode)" : "");
    }

    /**
     * current_mode for a plan: 9 in ISZ; after ISZ was on in this session the mode the tele reported before (a request
     * without the key leaves the sensor in mode 9); else none (the HAL's own mode, as before).
     */
    static Integer modeFor(boolean isz, boolean iszUsed, Integer normalMode) {
        if (isz) return ISZ_MODE;
        return iszUsed && normalMode != null && normalMode != ISZ_MODE ? normalMode : null;
    }

    private static void set(CaptureRequest.Builder b, Plan p, String physicalId) {
        final Integer mode = modeFor(p.isz, iszUsed, normalMode);
        b.set(USER_ZOOM, p.userZoom);
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, p.zoomRatio);
        b.set(SENSOR_MODE, mode);
        Float focal = focalRequest(p);
        if (focal != null) b.set(CaptureRequest.LENS_FOCAL_LENGTH, focal);
        if (physicalId != null && !physicalId.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            try {
                b.setPhysicalCameraKey(USER_ZOOM, p.userZoom, physicalId);
                b.setPhysicalCameraKey(SENSOR_MODE, mode, physicalId);
            } catch (RuntimeException ignored) {
                // a logical camera without that physical stream: the logical keys apply
            }
        }
    }

    /**
     * The standard optical zoom as well, when the tele lists more than one focal length: the nearest listed one (Camera2
     * accepts only values from LENS_INFO_AVAILABLE_FOCAL_LENGTHS, so never an interpolated value).
     */
    private static Float focalRequest(Plan p) {
        float[] list = focalList;
        if (list == null || list.length < 2 || fMin <= 0f) return null;
        float want = fMin * (p.userZoom * MM_PER_USER) / OPT_MIN;
        float best = list[0];
        for (float v : list) if (Math.abs(v - want) < Math.abs(best - want)) best = v;
        return best;
    }

    /**
     * Sets the last plan's keys again (after the module's vendor and tunable keys were applied to the same builder): the switch
     * owns current_mode on the tele. Returns false when this session is not the Xiaomi tele.
     */
    public static boolean applyLast(CaptureRequest.Builder b, String physicalId) {
        Plan p = last;
        if (b == null || p == null) return false;
        try {
            Integer before = b.get(SENSOR_MODE);
            Integer planned = modeFor(p.isz, iszUsed, normalMode);
            if (before != null && !before.equals(planned) && !tunableWarned) {
                tunableWarned = true;
                Log.w(TAG, "module tunable current_mode=" + before + " overridden: «Плавный оптический зум» owns the tele's sensor mode ("
                        + (planned == null ? "none" : planned) + " now; only 9 = 2x ISZ)");
            }
            set(b, p, physicalId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Sensor mode the result reports, or null when the HAL does not report it. */
    static Integer resultMode(CaptureResult r) {
        try {
            return r.get(RESULT_MODE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sensor mode the frame's request asked for (null = none). */
    static Integer requestMode(CaptureResult r) {
        try {
            return r.getRequest().get(SENSOR_MODE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@link #frameReady} for a captured frame. */
    public static boolean frameReady(CaptureResult r, int framesSinceChange, int matchingFrames) {
        if (r == null) return framesSinceChange >= BARRIER_MAX_FRAMES;
        return frameReady(requestMode(r), resultMode(r), isz, framesSinceChange, matchingFrames);
    }

    /** True when the frame's request asked for the mode now applied (counts the frames of the blind barrier). */
    public static boolean requestMatches(CaptureResult r) {
        return r != null && Integer.valueOf(ISZ_MODE).equals(requestMode(r)) == isz;
    }

    /** {@link #onResult(CaptureResult, CaptureResult, boolean)} with the focal length from the same result. */
    public static boolean onResult(CaptureResult r, boolean lensCheck) {
        return onResult(r, r, lensCheck);
    }

    /**
     * A preview result of the Xiaomi tele session: follows the reported lens position and sensor mode (logged when they change)
     * and decides crop mode when the lens does not follow. {@code lensResult}: the tele's physical result when the stream is a
     * physical one of a logical camera (its focal length is the tele's; the logical one may describe another lens), else
     * {@code r}. Returns true when the zoom keys must be applied again.
     */
    public static boolean onResult(CaptureResult r, CaptureResult lensResult, boolean lensCheck) {
        Plan p = last;
        if (p == null || r == null) return false;
        if (lensResult == null) lensResult = r;
        long now = android.os.SystemClock.elapsedRealtime();
        Integer mode = resultMode(r);
        if (mode != null && !p.isz && mode != ISZ_MODE && !Integer.valueOf(ISZ_MODE).equals(requestMode(r))) normalMode = mode;
        if (mode != null && !mode.equals(reportedMode)) {
            reportedMode = mode;
            Integer asked = modeFor(p.isz, iszUsed, normalMode);
            Log.i(TAG, "sensor mode reported " + mode + " (requested " + (asked == null ? "none" : asked) + ")");
        }
        Float f = null;
        try { f = lensResult.get(CaptureResult.LENS_FOCAL_LENGTH); } catch (RuntimeException ignored) { /* none */ }
        if (f == null || fMin <= 0f) {
            if (!focalMissingLogged) {
                focalMissingLogged = true;
                Log.i(TAG, "results report no focal length: the lens position is not known, optical commands are not checked");
            }
            return false;
        }
        final Integer frameMode = requestMode(r);
        final float mm = lensPosition(equivalentOf(f, fMin), Integer.valueOf(ISZ_MODE).equals(frameMode));
        if (Float.isNaN(mm)) {
            if (!implausibleLogged) {
                implausibleLogged = true;
                Log.i(TAG, String.format(Locale.ROOT, "reported focal length %.2f mm is not a position of this tele's lens: not checked", f));
            }
            farSinceMs = 0;
            return false;
        }
        if (Float.isNaN(firstLensMm)) firstLensMm = mm;
        if (!lensMoved && Math.abs(mm - firstLensMm) > 1f) {
            lensMoved = true;
            Log.i(TAG, String.format(Locale.ROOT, "the lens moves: %.1f -> %.1f mm (f %.2f mm)", firstLensMm, mm, f));
        }
        if (!focalReported || Math.abs(mm - lensMm) >= 2f) {
            Log.i(TAG, String.format(Locale.ROOT, "lens %.1f mm (f %.2f mm), commanded %.1f mm%s", mm, f, p.opticalMm, p.isz ? " ISZ" : ""));
        }
        focalReported = true;
        lensMm = mm;
        // frames of the other mode or right after an ISZ toggle do not count (the lens may be held while the mode changes)
        if (!countsForFollow(frameMode, p.isz, now - lastToggleMs)) farSinceMs = 0;
        else if (Math.abs(p.opticalMm - mm) > 5f) {
            if (farSinceMs == 0) farSinceMs = now;
        } else farSinceMs = 0;
        if (lensCheck && !lensFixed && farSinceMs != 0 && lensDoesNotFollow(p.opticalMm, mm, now - farSinceMs, lensMoved)) {
            fixedLensMm = clamp(mm, OPT_MIN, OPT_MAX);
            lensFixed = true;
            Log.w(TAG, String.format(Locale.ROOT, "the lens does not follow userZoomRatio (commanded %.1f mm for %d ms, reported %.1f mm"
                    + " and never moved): crop mode from now on (userZoomRatio = lens position, the HAL crops)", p.opticalMm, now - farSinceMs, mm));
            return true;
        }
        return false;
    }
}
