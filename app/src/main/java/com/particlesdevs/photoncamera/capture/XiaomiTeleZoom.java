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
 *
 * <p>Owner's recordings of build 30607 next to the stock camera (2026-10-08): the stock dial zooms 75-400 mm in one smooth
 * motion with the ISP's colours everywhere; ours flashed purple when mode 9 came on, the developed RAW viewfinder looked
 * greenish next to the ISP, and the mode flapped on/off around 150 mm. The stock camera's way to an ISP-processed mode-9
 * preview is not known (its request dump is missing), so by default the preview never forces current_mode: one continuous
 * zoomRatio crop from the 75 mm lens up to 400 mm, the ISP preview throughout (the HAL is free to use its in-sensor zoom by
 * itself; a reported mode change is logged). Dev switch {@code xiaomi_isz 1} brings back the forced 2x ISZ at 150 mm. The
 * tele's vendor keys around zoom / mode / remosaic are logged once to find the stock camera's switch. *
 * <p>Owner, 2026-10-08: userZoomRatio + zoomRatio ARE the optical zoom and the lens must move physically. The optical command is
 * the default again (userZoomRatio = mm / 23.256, zoomRatio = mm / 74.419, 4.30000019 / 1.34375 at 100 mm, then the crop
 * to 400 mm); dev switch {@code xiaomi_crop_mode 1} keeps the lens at 75 mm and crops instead.
 *
 * <p>Owner's 17U dumps (2026-10-08, research/xiaomi17u/ZOOM_DUMPS_REPORT.md): every tele result carries the HAL's own optical
 * zoom report, {@code com.xiaomi.optical.zoom.opticalZoomCurrentRatio} (from the zoom driver) / {@code opticalZoomTargetRatio}
 * / {@code opticalZoomState}, on the HAL's scale {@code optRealZoomRange} [3.4, 4.3], which its smartFOV map ties to the UI
 * scale {@code optUiZoomRange} [3.2, 4.3] (= userZoomRatio, 75-100 mm). It stood at 3.40 (the 75 mm end) in all 16 dumps, while
 * LENS_FOCAL_LENGTH follows the userZoomRatio claim. So the lens check reads that report (dev switch {@code xiaomi_hal_optics 0}:
 * the focal length as before) and logs it, and the request also names the HAL's own optical target
 * ({@code opticalZoomTargetRatio} for the commanded position; {@code xiaomi_opt_target 0} leaves it out): when the glass still
 * does not move, the follow check now sees it and the module crops instead (the right field of view at every zoom).
 *
 * <p>P41b, the owner's stock-camera capture (2026-10-09, research/xiaomi17u/STOCK_ZOOM_2026-10-09.md): the stock camera never
 * opens the tele alone. It runs the logical SAT camera (physical 3, 2, 4) with zoomRatio = userZoomRatio = the dial's ratio
 * (3.2-4.3 moves the glass, HAL target = 3.4 + (UI - 3.2) * 0.9 / 1.1; above 4.3 a crop; the HAL itself goes to the full-size
 * mode 2 at ~7x and to the in-sensor zoom mode 9 at ~8.5x), session keys ExtendedMaxZoom = 1 and EnableInsensorZoom = 1,
 * operation mode 0x9002. Third-party apps can open the same kind of logical camera (camera 0 on the 17U). So the tele module
 * now opens through it ({@link #logicalRoute}): the viewfinder is the logical camera's stream (the HAL's own optics, crop and
 * mode switches, as in the stock camera), the RAW stream stays the tele's physical stream, and the photo is cropped by what the
 * RAW frame lacks against the HAL's reported lens position ({@link #planLogical}). No current_mode is sent there (the HAL
 * switches the modes, as for the stock camera) and no package or clientName is claimed. When the logical camera refuses the
 * session, the next attempt uses a regular session, then the tele alone as before ({@link #stepDownRoute}). Dev switches:
 * {@code xiaomi_logical 0} (the tele alone), {@code xiaomi_opmode N} (operation mode N, 0 = regular),
 * {@code xiaomi_tele_fallback 1} (the HAL may show the main camera in low light, as for the stock camera).
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
    /** The HAL's optical zoom report (17U dumps 2026-10-08): the zoom driver's position, its target and state. */
    static final String KEY_OPT_CURRENT = "com.xiaomi.optical.zoom.opticalZoomCurrentRatio";
    static final String KEY_OPT_TARGET = "com.xiaomi.optical.zoom.opticalZoomTargetRatio";
    static final String KEY_OPT_STATE = "com.xiaomi.optical.zoom.opticalZoomState";
    static final String KEY_THIRD_PARTY = "xiaomi.thirdparty.isThirdParty";
    static final String KEY_REAL_RANGE = "com.xiaomi.camera.smoothTransition.optRealZoomRange";
    static final String KEY_UI_RANGE = "com.xiaomi.camera.smoothTransition.optUiZoomRange";
    /** The 17U tele's optical range on the HAL's scale and on the UI (userZoomRatio) scale, as its characteristics list them. */
    static final float[] REAL_RANGE = {3.4f, 4.3f}, UI_RANGE = {3.2f, 4.3f};

    private static final CaptureRequest.Key<Float> USER_ZOOM = new CaptureRequest.Key<>(KEY_USER_ZOOM, Float.class);
    private static final CaptureRequest.Key<Integer> SENSOR_MODE = new CaptureRequest.Key<>(KEY_SENSOR_MODE, Integer.class);
    private static final CaptureResult.Key<Integer> RESULT_MODE = new CaptureResult.Key<>(KEY_SENSOR_MODE, Integer.class);
    private static final CaptureRequest.Key<Float> OPT_TARGET_REQUEST = new CaptureRequest.Key<>(KEY_OPT_TARGET, Float.class);
    private static final CaptureResult.Key<Float> OPT_CURRENT = new CaptureResult.Key<>(KEY_OPT_CURRENT, Float.class);
    private static final CaptureResult.Key<Float> OPT_TARGET = new CaptureResult.Key<>(KEY_OPT_TARGET, Float.class);
    private static final CaptureResult.Key<Integer> OPT_STATE = new CaptureResult.Key<>(KEY_OPT_STATE, Integer.class);
    private static final CaptureResult.Key<Integer> THIRD_PARTY = new CaptureResult.Key<>(KEY_THIRD_PARTY, Integer.class);

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
        /** P41b: a plan of the logical SAT camera (zoomRatio on the main camera's scale, the HAL moves the lens itself). */
        public final boolean logical;
        Plan(float mm, float userZoom, float zoomRatio, float residual, boolean isz, float opticalMm, float rawMm) {
            this(mm, userZoom, zoomRatio, residual, isz, opticalMm, rawMm, false);
        }
        Plan(float mm, float userZoom, float zoomRatio, float residual, boolean isz, float opticalMm, float rawMm, boolean logical) {
            this.mm = mm; this.userZoom = userZoom; this.zoomRatio = zoomRatio; this.residual = residual; this.isz = isz;
            this.opticalMm = opticalMm; this.rawMm = rawMm; this.logical = logical;
        }
        @Override public String toString() {
            return String.format(Locale.ROOT, "%.1f mm: userZoom %.4f zoomRatio %.4f crop %.3f optics %.1f raw %.1f%s%s",
                    mm, userZoom, zoomRatio, residual, opticalMm, rawMm, isz ? " ISZ" : "", logical ? " (logical camera)" : "");
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
    /** A lens step from {@code beforeMm} to {@code nowMm} (> 1 mm) that brings it closer to the commanded position. */
    static boolean movedToward(float commandedMm, float beforeMm, float nowMm) {
        return Math.abs(nowMm - beforeMm) > 1f && Math.abs(commandedMm - nowMm) < Math.abs(commandedMm - beforeMm) - 0.5f;
    }

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

    /** A characteristics range {lo, hi}, or {@code fallback} when the HAL does not list a usable one. */
    static float[] rangeOr(float[] listed, float[] fallback) {
        return listed != null && listed.length >= 2 && listed[0] > 0f && listed[1] > listed[0]
                ? new float[]{listed[0], listed[1]} : fallback;
    }

    /**
     * Lens position (equivalent mm) of the HAL's optical zoom ratio ({@code opticalZoomCurrentRatio}): the HAL's scale
     * ({@code real}) mapped to the UI scale ({@code ui}, userZoomRatio) as its smartFOV map does (3.4 -> 3.2, 4.3 -> 4.3), times
     * the main camera's mm. NaN for a ratio that is not on this lens (more than 10 % outside the range).
     */
    static float opticalMmOf(float halRatio, float[] real, float[] ui) {
        if (Float.isNaN(halRatio)) return Float.NaN;
        float f = (halRatio - real[0]) / (real[1] - real[0]);
        if (f < -0.1f || f > 1.1f) return Float.NaN;
        return (ui[0] + clamp(f, 0f, 1f) * (ui[1] - ui[0])) * MM_PER_USER;
    }

    /** The HAL's optical ratio for a lens position (equivalent mm), the inverse of {@link #opticalMmOf}, clamped to the range. */
    static float halRatioOf(float lensMm, float[] real, float[] ui) {
        float f = clamp((lensMm / MM_PER_USER - ui[0]) / (ui[1] - ui[0]), 0f, 1f);
        return real[0] + f * (real[1] - real[0]);
    }

    /**
     * Lens position (equivalent mm) for the follow check: the HAL's optical zoom report when it is there and used ({@code useHal},
     * it comes from the zoom driver), else the reported focal length (on the 17U it follows the userZoomRatio claim, not the
     * glass). NaN when neither tells a position of this lens.
     */
    static float lensMmFor(Float halCurrent, boolean useHal, float[] real, float[] ui, Float focal, float fMin, boolean iszFrame) {
        if (useHal && halCurrent != null) {
            float mm = opticalMmOf(halCurrent, real, ui);
            if (!Float.isNaN(mm)) return mm;
        }
        if (focal == null || fMin <= 0f) return Float.NaN;
        return lensPosition(equivalentOf(focal, fMin), iszFrame);
    }

    /** The HAL's optical report changed enough to log: the lens by 1 mm or more, the target by 0.01 or the state. */
    static boolean opticsLogDue(float lastLensMm, float lensMm, float lastTarget, float target, int lastState, int state) {
        return Float.isNaN(lastLensMm) != Float.isNaN(lensMm) || Math.abs(lensMm - lastLensMm) >= 1f
                || Float.isNaN(lastTarget) != Float.isNaN(target) || Math.abs(target - lastTarget) >= 0.01f || lastState != state;
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

    // ---------------------------------------------------------------- P41b: the logical SAT camera (pure)

    /** The stock dial's ratio of a focal length (main camera = 1): the stock camera's userZoomRatio and logical zoomRatio. */
    static float dialRatio(float mm) {
        return mm / MM_PER_USER;
    }

    /**
     * The keys and the photo's crop on the logical SAT camera for {@code mm}, as the stock camera sends them: zoomRatio =
     * userZoomRatio = the dial's ratio (zoomRatio at most {@code maxZoomRatio}, the camera's range); the HAL moves the lens over
     * 75-100 mm and crops above. {@code lensMm}: the lens position the HAL reports (NaN: the position due for {@code mm}, the
     * stock camera's map); {@code isz}: the HAL reports mode 9 (the RAW frame is the centre half of the field). The photo is
     * cropped by what the tele's RAW frame lacks.
     */
    static Plan planLogical(float mm, float lensMm, boolean isz, float maxZoomRatio) {
        mm = clamp(mm, OPT_MIN, MAX_MM);
        final float optical = clamp(mm, OPT_MIN, OPT_MAX);
        final float lens = Float.isNaN(lensMm) ? optical : clamp(lensMm, OPT_MIN, OPT_MAX);
        final float factor = isz ? 2f : 1f;
        final float dial = dialRatio(mm);
        final float zoomRatio = maxZoomRatio > 0f ? Math.min(dial, maxZoomRatio) : dial;
        return new Plan(mm, dial, zoomRatio, Math.max(1f, mm / (lens * factor)), isz, optical, lens * factor, true);
    }

    /** The HAL's optical target for a dial ratio as the stock dumps show it (3.2 -> 3.4, 4.3 -> 4.3, held above). */
    static float stockOpticalTarget(float dial, float[] real, float[] ui) {
        return halRatioOf(dial * MM_PER_USER, real, ui);
    }

    /** The HAL reports a zoomRatio more than 1 % under the request: it clamps, the viewfinder is wider than the photo there. */
    static boolean clamped(float requested, Float reported) {
        return reported != null && reported < requested * 0.99f;
    }

    /**
     * The logical camera ({@code physicalIds}: camera id -> its physical cameras) that holds the tele {@code physicalId} and
     * lists the smooth-transition optics ({@code smooth}); the lowest id when several do, null when none.
     */
    static String pickLogical(String physicalId, java.util.Map<String, java.util.Set<String>> physicalIds, java.util.Set<String> smooth) {
        String best = null;
        for (java.util.Map.Entry<String, java.util.Set<String>> e : physicalIds.entrySet()) {
            if (e.getValue() == null || !e.getValue().contains(physicalId) || !smooth.contains(e.getKey())) continue;
            if (best == null || compareIds(e.getKey(), best) < 0) best = e.getKey();
        }
        return best;
    }

    static int compareIds(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    /** The operation mode after a failed logical session in {@code failed}: a regular session (0), then none (-1 = the tele alone). */
    static int nextOperationMode(int failed) {
        return failed != 0 ? 0 : -1;
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
    /** The forced 2x ISZ (current_mode 9 from 150 mm) is in use; off by default (dev switch xiaomi_isz 1). */
    private static volatile boolean iszEnabled;

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
    // the HAL's optical zoom report (dev switches xiaomi_hal_optics / xiaomi_opt_target)
    private static volatile boolean halReport = true, halTarget = true;
    private static volatile float[] realRange = REAL_RANGE, uiRange = UI_RANGE;
    private static volatile Boolean targetKeyOk;
    private static float loggedOptLens = Float.NaN, loggedOptTarget = Float.NaN;
    private static int loggedOptState = Integer.MIN_VALUE;
    private static boolean opticsMissingLogged, thirdPartyLogged;

    /**
     * Dev switches, set before {@link #apply}: {@code xiaomi_hal_optics} (the HAL's optical zoom report is the lens position of
     * the follow check; off = the reported focal length as before) and {@code xiaomi_opt_target} (the request also names the
     * HAL's optical target for the commanded position; only with the first). Both on by default.
     */
    public static void halOptics(boolean report, boolean target) {
        halReport = report;
        halTarget = report && target;
    }

    public static boolean isz() { return isz; }
    public static Plan last() { return last; }
    /** The current session is the Xiaomi tele under this switch. */
    public static boolean active() { return last != null; }
    /**
     * P41: outside ISZ the preview is the ISP's; in ISZ (mode 9) the HAL's preview is the raw colour mosaic (purple on the
     * owner's 17U), so the developed RAW viewfinder takes over there. P41b: on the logical camera the ISP preview throughout
     * (the stock camera shows the HAL's mode-9 preview).
     */
    public static boolean ispPreview() { return last != null && (routed || !isz); }

    /**
     * Crop the HAL applies to the preview on top of the RAW frame's field of view (zoomRatio relative to the optics
     * userZoomRatio claims); the developed RAW viewfinder crops the same. 1 outside this tele.
     */
    public static float previewCrop() {
        return cropOf(last);
    }

    static float cropOf(Plan p) {
        if (p == null) return 1f;
        // P41b: the tele's physical RAW frame is not cropped by the logical camera's zoom: what it lacks is the photo's crop
        if (p.logical) return p.residual;
        return Math.max(1f, p.zoomRatio * MM_PER_RATIO / (p.userZoom * MM_PER_USER));
    }
    static void reset() {
        isz = false; last = null; lastToggleMs = 0; lastTraceMs = 0; teleRatio = 0f;
        fMin = 0f; focalList = null; lensMm = Float.NaN; firstLensMm = Float.NaN; lensMoved = false; lensFixed = false;
        fixedLensMm = Float.NaN;
        normalMode = null; iszUsed = false;
        farSinceMs = 0; focalReported = false; focalMissingLogged = false; implausibleLogged = false; reportedMode = null; tunableWarned = false;
        describedCamera = null;
        halReport = true; halTarget = true; realRange = REAL_RANGE; uiRange = UI_RANGE; targetKeyOk = null;
        loggedOptLens = Float.NaN; loggedOptTarget = Float.NaN; loggedOptState = Integer.MIN_VALUE;
        opticsMissingLogged = false; thirdPartyLogged = false;
        routeDisabled = false; routeOpMode = Integer.MIN_VALUE; searchedFor = null; foundLogical = null; logicalMaxZoom = LOGICAL_MAX;
        routed = false; extendedZoom = false; teleFallback = false;
        routedLensMm = Float.NaN; routedMode = null; clampLogged = false; followLogged = false; logicalFarSinceMs = 0;
    }

    /** A new camera session: the ISZ state of the previous one does not carry over (its first request sets the mode). */
    public static void startSession() {
        startSession(false, false);
    }

    /**
     * A new camera session; {@code logical}: it runs on the logical SAT camera ({@link #logicalRoute}), {@code allowTeleFallback}:
     * the HAL may show the main camera there in low light (dev switch {@code xiaomi_tele_fallback 1}).
     */
    public static void startSession(boolean logical, boolean allowTeleFallback) {
        isz = false;
        iszUsed = false;
        last = null;
        lastToggleMs = 0;
        farSinceMs = 0;
        reportedMode = null;
        tunableWarned = false;
        loggedOptLens = Float.NaN;
        loggedOptTarget = Float.NaN;
        loggedOptState = Integer.MIN_VALUE;
        thirdPartyLogged = false;
        routed = logical;
        teleFallback = allowTeleFallback;
        // the logical session's parameters carry ExtendedMaxZoom (applySessionKeys clears this when the key is refused)
        extendedZoom = logical;
        routedLensMm = Float.NaN;
        routedMode = null;
        clampLogged = false;
        followLogged = false;
        logicalFarSinceMs = 0;
    }

    // ---------------------------------------------------------------- P41b: the logical SAT camera route

    static final String KEY_EXT_MAX_ZOOM = "org.codeaurora.qcamera3.sessionParameters.ExtendedMaxZoom";
    static final String KEY_ISZ_SESSION = "org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom";
    static final String KEY_TELE_FALLBACK_OFF = "com.xiaomi.teleFallback.isDisable";
    /** The stock camera's operation mode on the logical camera (its dumpsys: CUSTOM 36866). */
    public static final int STOCK_OPERATION_MODE = 0x9002;
    /** The logical camera's zoomRatio range ends here without ExtendedMaxZoom (17U camera 0: 0.6-10). */
    static final float LOGICAL_MAX = 10f;
    /** The 17U HAL's zoomRatio limit with ExtendedMaxZoom (platformCapabilities.ExtendedMaxZoom = 100); the dial ends at 17.2. */
    static final float EXTENDED_MAX = 100f;
    private static final CaptureRequest.Key<Integer> EXT_MAX_ZOOM = new CaptureRequest.Key<>(KEY_EXT_MAX_ZOOM, Integer.class);
    private static final CaptureRequest.Key<Integer> ISZ_SESSION = new CaptureRequest.Key<>(KEY_ISZ_SESSION, Integer.class);
    private static final CaptureRequest.Key<Byte> TELE_FALLBACK_OFF = new CaptureRequest.Key<>(KEY_TELE_FALLBACK_OFF, Byte.class);

    /** The route failed in this process: the tele opens alone (crop mode as before). */
    private static volatile boolean routeDisabled;
    /** Operation mode of the logical session; MIN_VALUE until the first session picks it from the dev switch. */
    private static volatile int routeOpMode = Integer.MIN_VALUE;
    private static String searchedFor, foundLogical;
    private static volatile float logicalMaxZoom = LOGICAL_MAX;
    /** The session runs on the logical camera; its session parameters carried ExtendedMaxZoom; tele fallback allowed. */
    private static volatile boolean routed, extendedZoom, teleFallback;
    /** Lens position (mm) the HAL reports on the logical camera in this session, and the sensor mode it reports. */
    private static volatile float routedLensMm = Float.NaN;
    private static volatile Integer routedMode;
    private static boolean clampLogged, followLogged;
    private static long logicalFarSinceMs;

    /** The current session runs on the logical SAT camera. */
    public static boolean routed() { return routed && last != null; }

    /**
     * P41b: the logical camera to open for the tele {@code physicalId} (the HAL's own optics and preview, as for the stock
     * camera), or null: another phone, the switch or {@code devOn} ({@code xiaomi_logical}) off, the route failed earlier in this
     * process, not the tele, no logical camera holds it.
     */
    public static String logicalRoute(android.hardware.camera2.CameraManager m, String physicalId, CameraCharacteristics physical, boolean devOn) {
        if (!devOn || routeDisabled || m == null || physicalId == null || physicalId.isEmpty() || Build.VERSION.SDK_INT < 28
                || !enabled() || !teleModule(physical)) return null;
        synchronized (XiaomiTeleZoom.class) {
            if (physicalId.equals(searchedFor)) return foundLogical;
            searchedFor = physicalId;
            foundLogical = null;
            java.util.Map<String, java.util.Set<String>> ids = new java.util.TreeMap<>();
            java.util.Set<String> smooth = new java.util.TreeSet<>();
            java.util.Map<String, Float> top = new java.util.HashMap<>();
            try {
                for (String id : m.getCameraIdList()) {
                    try {
                        CameraCharacteristics c = m.getCameraCharacteristics(id);
                        java.util.Set<String> physicals = c.getPhysicalCameraIds();
                        if (physicals == null || physicals.isEmpty()) continue;
                        ids.put(id, physicals);
                        if (floats(c, KEY_REAL_RANGE) != null) smooth.add(id);
                        if (Build.VERSION.SDK_INT >= 30) {
                            android.util.Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                            if (range != null) top.put(id, range.getUpper());
                        }
                    } catch (android.hardware.camera2.CameraAccessException | RuntimeException e) {
                        Log.w(TAG, "logical route: camera " + id + " unreadable: " + e.getMessage());
                    }
                }
            } catch (android.hardware.camera2.CameraAccessException | RuntimeException e) {
                Log.w(TAG, "logical route: no camera list: " + e.getMessage());
            }
            foundLogical = pickLogical(physicalId, ids, smooth);
            if (foundLogical != null) {
                Float upper = top.get(foundLogical);
                logicalMaxZoom = upper != null && upper > 1f ? upper : LOGICAL_MAX;
            }
            Log.i(TAG, "logical cameras " + ids + ", with smooth-transition optics " + smooth + ": " + (foundLogical == null
                    ? "none holds the tele " + physicalId + ", it opens alone"
                    : "the tele " + physicalId + " opens through logical camera " + foundLogical + " (zoomRatio up to " + logicalMaxZoom + ")"));
            return foundLogical;
        }
    }

    /** Operation mode of the logical session: the dev switch's ({@code xiaomi_opmode}, stock 0x9002) until a failure stepped down. */
    public static int operationMode(int devMode) {
        if (routeOpMode == Integer.MIN_VALUE) routeOpMode = Math.max(0, devMode);
        return routeOpMode;
    }

    /**
     * P41b: the logical session failed ({@code reason}: configuration, preview stall). Returns true when the logical camera is
     * tried again with a regular session, false when the tele opens alone for the rest of this process.
     */
    public static boolean stepDownRoute(String reason) {
        final int failed = routeOpMode == Integer.MIN_VALUE ? 0 : routeOpMode;
        final int next = nextOperationMode(failed);
        if (next >= 0) {
            routeOpMode = next;
            Log.w(TAG, "logical camera session failed (" + reason + ") with operation mode 0x" + Integer.toHexString(failed)
                    + ": a regular session next");
            return true;
        }
        disableRoute(reason);
        return false;
    }

    /** P41b: the logical camera does not open or run for this app: the tele alone from now on (this process). */
    public static void disableRoute(String reason) {
        routeDisabled = true;
        Log.w(TAG, "logical camera route off (" + reason + "): the tele opens alone for the rest of this process (crop mode as before)");
    }

    /**
     * P41b: the stock camera's session keys of the logical camera: ExtendedMaxZoom (zoomRatio past 10) and EnableInsensorZoom
     * (the HAL's mode 9), and teleFallback.isDisable unless allowed (the RAW stream is the tele's: the viewfinder must not show
     * the main camera). Returns the keys set, for the log.
     */
    public static String applySessionKeys(CaptureRequest.Builder b) {
        StringBuilder s = new StringBuilder();
        extendedZoom = trySet(b, EXT_MAX_ZOOM, 1);
        if (extendedZoom) s.append("ExtendedMaxZoom=1 ");
        if (trySet(b, ISZ_SESSION, 1)) s.append("EnableInsensorZoom=1 ");
        if (!teleFallback && trySet(b, TELE_FALLBACK_OFF, (byte) 1)) s.append("teleFallback.isDisable=1");
        return s.toString().trim();
    }

    private static <T> boolean trySet(CaptureRequest.Builder b, CaptureRequest.Key<T> key, T value) {
        if (b == null) return false;
        try {
            b.set(key, value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The logical camera's keys of a plan: the stock camera's zoomRatio = userZoomRatio, no sensor mode (the HAL's choice). */
    private static void setLogical(CaptureRequest.Builder b, Plan p) {
        b.set(USER_ZOOM, p.userZoom);
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, p.zoomRatio);
        b.set(SENSOR_MODE, null);
        // the session keys again with the session's values, as in the stock camera's requests (a different value in a request
        // would make the framework reconfigure the session)
        if (extendedZoom) trySet(b, EXT_MAX_ZOOM, 1);
        trySet(b, ISZ_SESSION, 1);
        if (!teleFallback) trySet(b, TELE_FALLBACK_OFF, (byte) 1);
    }

    private static float logicalLimit() {
        return extendedZoom ? EXTENDED_MAX : logicalMaxZoom;
    }

    private static float[] floats(CameraCharacteristics c, String name) {
        try {
            return c.get(new CameraCharacteristics.Key<>(name, float[].class));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void describe(CameraCharacteristics c, String physicalId) {
        String id = String.valueOf(physicalId);
        if (id.equals(describedCamera)) return;
        describedCamera = id;
        float[] real = floats(c, KEY_REAL_RANGE), ui = floats(c, KEY_UI_RANGE);
        realRange = rangeOr(real, REAL_RANGE);
        uiRange = rangeOr(ui, UI_RANGE);
        Log.i(TAG, "tele " + id + ": HAL optical range " + Arrays.toString(realRange) + " = UI " + Arrays.toString(uiRange)
                + (real == null || ui == null ? " (not listed: the 17U's values)" : "") + "; lens position from "
                + (halReport ? "the HAL's " + KEY_OPT_CURRENT : "LENS_FOCAL_LENGTH (xiaomi_hal_optics 0)")
                + (halTarget ? ", request names " + KEY_OPT_TARGET : ""));
        StringBuilder keys = new StringBuilder();
        try {
            for (CaptureResult.Key<?> k : c.getAvailableCaptureResultKeys()) {
                String n = k.getName();
                if (n.contains("current_mode") || n.contains("userZoomRatio") || n.contains("zoom") || n.contains("Zoom")) keys.append(n).append(' ');
            }
        } catch (RuntimeException ignored) {
            // no key list
        }
        // P41: the tele's vendor keys around zoom / sensor mode / remosaic, to find the stock camera's ISP-processed ISZ preview
        StringBuilder vendor = new StringBuilder();
        try {
            for (CaptureRequest.Key<?> k : c.getAvailableCaptureRequestKeys()) {
                String n = k.getName().toLowerCase(Locale.ROOT);
                if (!n.startsWith("android.") && (n.contains("isz") || n.contains("remosaic") || n.contains("insensor") || n.contains("zoom")
                        || n.contains("mode") || n.contains("sat") || n.contains("crop") || n.contains("binning") || n.contains("fullsize")))
                    vendor.append(k.getName()).append(' ');
            }
            if (Build.VERSION.SDK_INT >= 28) {
                StringBuilder session = new StringBuilder();
                for (CaptureRequest.Key<?> k : c.getAvailableSessionKeys()) session.append(k.getName()).append(' ');
                Log.i(TAG, "tele " + id + " session keys: " + session.toString().trim());
            }
        } catch (RuntimeException ignored) {
            // no key list
        }
        Log.i(TAG, "tele " + id + " vendor request keys (zoom/mode/remosaic): " + vendor.toString().trim());
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
     * {@code forceCrop}: crop mode from the start, the lens standing at 75 mm (dev switch {@code xiaomi_crop_mode 1}); the
     * default is the owner's optical command.
     */
    public static Plan apply(CaptureRequest.Builder b, CameraCharacteristics c, boolean switchOn, float moduleZoom, float zoom,
                             String physicalId, boolean lensCheck, boolean forceCrop) {
        return apply(b, c, switchOn, moduleZoom, zoom, physicalId, lensCheck, forceCrop, false);
    }

    /** {@code iszMode}: dev switch {@code xiaomi_isz 1}, the forced 2x ISZ (current_mode 9) from 150 mm. */
    public static Plan apply(CaptureRequest.Builder b, CameraCharacteristics c, boolean switchOn, float moduleZoom, float zoom,
                             String physicalId, boolean lensCheck, boolean forceCrop, boolean iszMode) {
        iszEnabled = iszMode;
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
        if (routed) {
            // P41b: the HAL drives the lens and the sensor mode from zoomRatio, as for the stock camera; the RAW frame's crop
            // follows the lens position and mode it reports
            final Integer mode = routedMode;
            final Plan p = planLogical(mm, routedLensMm, mode != null && mode == ISZ_MODE, logicalLimit());
            setLogical(b, p);
            final boolean changed = last != null && p.isz != isz;
            if (changed) lastToggleMs = now;
            isz = p.isz;
            last = p;
            if (changed) Log.i(TAG, "the HAL " + (p.isz ? "entered" : "left") + " the in-sensor zoom (mode 9) at " + p);
            else if (now - lastTraceMs >= 300) {
                lastTraceMs = now;
                Log.d(TAG, "zoom " + p + (Float.isNaN(routedLensMm) ? "" : String.format(Locale.ROOT, " lens %.1f mm (HAL)", routedLensMm)));
            }
            return p;
        }
        final boolean nextIsz = iszEnabled && nextIsz(mm, isz, last == null ? Long.MAX_VALUE : now - lastToggleMs);
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
        if (p == null || p.logical || moduleZoom <= 0f) return false;
        float mm = clamp(OPT_MIN * zoom / moduleZoom, OPT_MIN, MAX_MM);
        return (iszEnabled && nextIsz(mm, isz)) != isz;
    }

    private static String lensNote() {
        Plan p = last;
        return (Float.isNaN(lensMm) ? "" : String.format(Locale.ROOT, " lens %.1f mm", lensMm)) + (lensFixed ? " (crop mode)" : "")
                + (p != null && halTarget && Boolean.TRUE.equals(targetKeyOk)
                ? String.format(Locale.ROOT, " HAL target %.3f", halRatioOf(p.userZoom * MM_PER_USER, realRange, uiRange)) : "");
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
        // the HAL's own optical target for the position userZoomRatio claims (17U dumps: it stood at 3.4 = 75 mm)
        final boolean targetKey = targetKeyOk(b);
        final Float target = targetKey && halTarget ? halRatioOf(p.userZoom * MM_PER_USER, realRange, uiRange) : null;
        if (targetKey) b.set(OPT_TARGET_REQUEST, target);
        if (physicalId != null && !physicalId.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            try {
                b.setPhysicalCameraKey(USER_ZOOM, p.userZoom, physicalId);
                b.setPhysicalCameraKey(SENSOR_MODE, mode, physicalId);
                if (target != null) b.setPhysicalCameraKey(OPT_TARGET_REQUEST, target, physicalId);
            } catch (RuntimeException ignored) {
                // a logical camera without that physical stream: the logical keys apply
            }
        }
    }

    /** The request accepts the HAL's optical target key (a vendor tag of this HAL); checked once, logged. */
    private static boolean targetKeyOk(CaptureRequest.Builder b) {
        Boolean ok = targetKeyOk;
        if (ok == null) {
            try {
                b.get(OPT_TARGET_REQUEST);
                ok = true;
            } catch (IllegalArgumentException e) {
                ok = false;
            }
            targetKeyOk = ok;
            Log.i(TAG, "request key " + KEY_OPT_TARGET + (ok ? " accepted" : " not in this HAL's vendor tags")
                    + (halTarget ? "" : " (not sent: xiaomi_opt_target 0 / xiaomi_hal_optics 0)"));
        }
        return ok;
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
            if (p.logical) {
                // P41b: the zoom limit is known once the session keys are set (ExtendedMaxZoom refused: 10)
                if (p.zoomRatio > logicalLimit()) last = p = planLogical(p.mm, routedLensMm, p.isz, logicalLimit());
                if (before != null && !tunableWarned) {
                    tunableWarned = true;
                    Log.w(TAG, "module tunable current_mode=" + before + " not sent on the logical camera: the HAL switches the modes");
                }
                setLogical(b, p);
                return true;
            }
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
        // P41b: on the logical camera the HAL switched the mode by itself (nothing requested): its report decides
        if (routed) return frameReady(isz ? Integer.valueOf(ISZ_MODE) : null, resultMode(r), isz, framesSinceChange, matchingFrames);
        return frameReady(requestMode(r), resultMode(r), isz, framesSinceChange, matchingFrames);
    }

    /** True when the frame's request asked for the mode now applied (counts the frames of the blind barrier). */
    public static boolean requestMatches(CaptureResult r) {
        if (r != null && routed) return true;
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
        if (p.logical) return onLogicalResult(r, lensResult == null ? r : lensResult, p);
        if (lensResult == null) lensResult = r;
        long now = android.os.SystemClock.elapsedRealtime();
        Integer mode = resultMode(r);
        if (mode != null && !p.isz && mode != ISZ_MODE && !Integer.valueOf(ISZ_MODE).equals(requestMode(r))) normalMode = mode;
        if (mode != null && !mode.equals(reportedMode)) {
            reportedMode = mode;
            Integer asked = modeFor(p.isz, iszUsed, normalMode);
            Log.i(TAG, "sensor mode reported " + mode + " (requested " + (asked == null ? "none" : asked) + ")");
        }
        logThirdParty(r);
        // P41, 17U dumps: the HAL's optical zoom report is the glass; LENS_FOCAL_LENGTH follows the userZoomRatio claim
        final Float halCurrent = halReport ? floatOf(lensResult, r, OPT_CURRENT) : null;
        if (halReport) logOptics(lensResult, r, halCurrent, p);
        final boolean hal = halCurrent != null && !Float.isNaN(opticalMmOf(halCurrent, realRange, uiRange));
        Float f = null;
        try { f = lensResult.get(CaptureResult.LENS_FOCAL_LENGTH); } catch (RuntimeException ignored) { /* none */ }
        if (!hal && (f == null || fMin <= 0f)) {
            if (!focalMissingLogged) {
                focalMissingLogged = true;
                Log.i(TAG, "results report no focal length: the lens position is not known, optical commands are not checked");
            }
            return false;
        }
        final Integer frameMode = requestMode(r);
        final float mm = lensMmFor(halCurrent, halReport, realRange, uiRange, f, fMin, Integer.valueOf(ISZ_MODE).equals(frameMode));
        if (Float.isNaN(mm)) {
            if (!implausibleLogged) {
                implausibleLogged = true;
                Log.i(TAG, String.format(Locale.ROOT, "reported focal length %.2f mm is not a position of this tele's lens: not checked", f));
            }
            farSinceMs = 0;
            return false;
        }
        final String source = hal ? String.format(Locale.ROOT, "HAL opticalZoomCurrentRatio %.3f", halCurrent)
                : String.format(Locale.ROOT, "f %.2f mm", f);
        // the position userZoomRatio claims: the commanded optics, or the standing lens in crop mode (no false alarm there)
        final float claimed = p.userZoom * MM_PER_USER;
        if (Float.isNaN(firstLensMm)) firstLensMm = mm;
        // 17U, 2026-10-08 (owner's run of the dump script): the lens came from 88.7 mm (left there by the stock camera) and
        // the HAL drove it to its own target 74.4 mm while we commanded 100 mm (isThirdParty = 1): it "moved", so it was
        // trusted and crop mode never came. Only a move toward the command counts.
        if (!lensMoved && !Float.isNaN(lensMm) && movedToward(claimed, lensMm, mm)) {
            lensMoved = true;
            Log.i(TAG, String.format(Locale.ROOT, "the lens moves toward the command %.1f mm: %.1f -> %.1f mm (%s)", claimed, lensMm, mm, source));
        }
        if (!focalReported || Math.abs(mm - lensMm) >= 2f) {
            Log.i(TAG, String.format(Locale.ROOT, "lens %.1f mm (%s), commanded %.1f mm%s", mm, source, claimed, p.isz ? " ISZ" : ""));
        }
        focalReported = true;
        lensMm = mm;
        // frames of the other mode or right after an ISZ toggle do not count (the lens may be held while the mode changes)
        if (!countsForFollow(frameMode, p.isz, now - lastToggleMs)) farSinceMs = 0;
        else if (Math.abs(claimed - mm) > 5f) {
            if (farSinceMs == 0) farSinceMs = now;
        } else farSinceMs = 0;
        if (lensCheck && !lensFixed && farSinceMs != 0 && lensDoesNotFollow(claimed, mm, now - farSinceMs, lensMoved)) {
            fixedLensMm = clamp(mm, OPT_MIN, OPT_MAX);
            lensFixed = true;
            Log.w(TAG, String.format(Locale.ROOT, "the lens does not follow userZoomRatio (commanded %.1f mm for %d ms, reported %.1f mm"
                    + " by %s and never moved): crop mode from now on (userZoomRatio = lens position, the HAL crops)",
                    claimed, now - farSinceMs, mm, source));
            return true;
        }
        return false;
    }

    /**
     * Whether the lens follows the HAL's own command on the logical camera: 1 = it stands within 2 mm of the position due for
     * the zoom ({@code dueMm}), -1 = more than 5 mm off for {@link #FOLLOW_MS} with the zoom inside the optical range, 0 = not
     * decided yet. Only zooms more than 5 mm past 75 mm count (at 75 mm a pinned lens looks the same as a moving one).
     */
    static int logicalFollow(float dueMm, float lensMm, long farForMs) {
        if (Float.isNaN(lensMm) || dueMm <= OPT_MIN + 5f) return 0;
        if (Math.abs(dueMm - lensMm) <= 2f) return 1;
        return Math.abs(dueMm - lensMm) > 5f && farForMs >= FOLLOW_MS ? -1 : 0;
    }

    /**
     * P41b: a preview result on the logical camera. The HAL's optical report gives the lens position (the photo's crop follows
     * it: {@link #last} and the residual are updated in place, the request stays), its sensor mode report the in-sensor zoom
     * (returns true then: the keys are applied again and the RAW stream waits for the new mode). Logged once: whether the lens
     * follows for this app, whether the HAL clamps the zoomRatio.
     */
    private static boolean onLogicalResult(CaptureResult r, CaptureResult lensResult, Plan p) {
        final long now = android.os.SystemClock.elapsedRealtime();
        boolean again = false;
        Integer mode = intOf(lensResult, r, RESULT_MODE);
        if (mode != null && !mode.equals(routedMode)) {
            final boolean wasIsz = routedMode != null && routedMode == ISZ_MODE;
            routedMode = mode;
            Log.i(TAG, "sensor mode reported " + mode + " on the logical camera (the HAL's choice, none requested)"
                    + (mode == ISZ_MODE ? ": in-sensor zoom, the RAW frame is the centre half of the field" : ""));
            again = (mode == ISZ_MODE) != wasIsz;
        }
        logThirdParty(r);
        final Float current = floatOf(lensResult, r, OPT_CURRENT);
        logOptics(lensResult, r, current, p);
        final float lens = current == null ? Float.NaN : opticalMmOf(current, realRange, uiRange);
        if (!Float.isNaN(lens)) {
            if (Float.isNaN(routedLensMm) || Math.abs(lens - routedLensMm) >= 0.5f) {
                routedLensMm = lens;
                if (!again) {
                    // the request does not change with the lens: only the photo's crop (and the RAW viewfinder's) follows it
                    Plan np = planLogical(p.mm, lens, p.isz, logicalLimit());
                    last = np;
                    com.particlesdevs.photoncamera.control.ZoomController.overrideResidual(np.residual);
                }
            }
            if (!followLogged) {
                if (Math.abs(p.opticalMm - lens) > 5f) {
                    if (logicalFarSinceMs == 0) logicalFarSinceMs = now;
                } else logicalFarSinceMs = 0;
                int verdict = logicalFollow(p.opticalMm, lens, logicalFarSinceMs == 0 ? 0 : now - logicalFarSinceMs);
                if (verdict != 0) {
                    followLogged = true;
                    Log.i(TAG, String.format(Locale.ROOT, verdict > 0
                            ? "the lens follows on the logical camera: %.1f mm for %.1f mm (optical zoom works for this app)"
                            : "the lens stays at %.1f mm for %.1f mm on the logical camera too: the HAL keeps its optics from this"
                            + " app; the photo is cropped from the lens position", lens, p.opticalMm));
                }
            }
        } else if (!opticsMissingLogged) {
            opticsMissingLogged = true;
            Log.i(TAG, "logical camera: no com.xiaomi.optical.zoom report, the photo's crop assumes the stock lens map");
        }
        if (!clampLogged && Build.VERSION.SDK_INT >= 30) {
            Float reported = null;
            try { reported = r.get(CaptureResult.CONTROL_ZOOM_RATIO); } catch (RuntimeException ignored) { /* none */ }
            if (clamped(p.zoomRatio, reported)) {
                clampLogged = true;
                Log.w(TAG, String.format(Locale.ROOT, "the HAL clamps zoomRatio to %.3f (asked %.3f): past it the viewfinder is wider"
                        + " than the photo", reported, p.zoomRatio));
            }
        }
        return again;
    }

    private static Float floatOf(CaptureResult lensResult, CaptureResult r, CaptureResult.Key<Float> key) {
        Float v = null;
        try { v = lensResult.get(key); } catch (RuntimeException ignored) { /* not this HAL's tag */ }
        if (v == null && r != lensResult) {
            try { v = r.get(key); } catch (RuntimeException ignored) { /* not this HAL's tag */ }
        }
        return v;
    }

    private static Integer intOf(CaptureResult lensResult, CaptureResult r, CaptureResult.Key<Integer> key) {
        Integer v = null;
        try { v = lensResult.get(key); } catch (RuntimeException ignored) { /* not this HAL's tag */ }
        if (v == null && r != lensResult) {
            try { v = r.get(key); } catch (RuntimeException ignored) { /* not this HAL's tag */ }
        }
        return v;
    }

    /** Once per session: whether the HAL runs its third-party pipeline for this app (17U dumps: 1). */
    private static void logThirdParty(CaptureResult r) {
        if (thirdPartyLogged) return;
        Integer tp = intOf(r, r, THIRD_PARTY);
        if (tp == null) return;
        thirdPartyLogged = true;
        Log.i(TAG, "HAL " + KEY_THIRD_PARTY + " = " + tp + (tp != 0 ? " (the HAL's third-party pipeline for this app)" : ""));
    }

    /** The HAL's optical zoom report when it changes: where the zoom driver stands, its target and state, against our command. */
    private static void logOptics(CaptureResult lensResult, CaptureResult r, Float current, Plan p) {
        Float target = floatOf(lensResult, r, OPT_TARGET);
        Integer state = intOf(lensResult, r, OPT_STATE);
        if (current == null && target == null) {
            if (!opticsMissingLogged) {
                opticsMissingLogged = true;
                Log.i(TAG, "results carry no com.xiaomi.optical.zoom report: the lens check reads LENS_FOCAL_LENGTH");
            }
            return;
        }
        float lens = current == null ? Float.NaN : opticalMmOf(current, realRange, uiRange);
        float tgt = target == null ? Float.NaN : target;
        int st = state == null ? -1 : state;
        if (!opticsLogDue(loggedOptLens, lens, loggedOptTarget, tgt, loggedOptState, st)) return;
        loggedOptLens = lens;
        loggedOptTarget = tgt;
        loggedOptState = st;
        float claimed = p.logical ? p.opticalMm : p.userZoom * MM_PER_USER;
        Log.i(TAG, String.format(Locale.ROOT, "optics (HAL): current %.3f = lens %.1f mm, target %.3f = %.1f mm, state %d; commanded %.1f mm"
                        + " (HAL %.3f%s)%s", current == null ? Float.NaN : current, lens, tgt, opticalMmOf(tgt, realRange, uiRange), st,
                claimed, halRatioOf(claimed, realRange, uiRange),
                p.logical ? ", due by the stock map on the logical camera" : halTarget && Boolean.TRUE.equals(targetKeyOk) ? ", sent as target" : "",
                lensFixed && !p.logical ? " crop mode" : ""));
    }
}
