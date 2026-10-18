package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.api.CameraReflectionApi;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P192 (owner, 2026-10-17: «Продолжи реализацию FULLRES без аппаратного ремозаика для OPPO Find X7 Ultra»): the OPPO Find X7 Ultra
 * (PHY110, firmware PHY110_16.0.0.202(CN01), measured 2026-10-17 by the diagnostic probe, evidence/oppo_phy110_fullres_20261016)
 * gives its sensors' own Quad mosaic at 8192x6144 with no hardware remosaic when the session parameter
 * com.oplus.engineercamera.agingtest.mode.select (a byte) names the sensor's NoRemosaic mode: camera 2 (main, IMX06A) 35,
 * 3 (ultra-wide, IMX882) 16, 4 (3x, IMX890) 19, 5 (6x, IMX858) 17 - a plain Camera2 session of type 0 with one RAW_SENSOR reader
 * of usage 0 (usage 3 is refused for the full size by the camera service). It runs the native FULLRES route of the vivo phones
 * (VivoNativeFullRes: one RAW stream, the own RAW viewfinder, matched serial stills, SHDR with the own remosaic); every shot still
 * proves the mosaic from the pixels and the mode from the result. Another OPPO phone takes the same route with the candidates
 * of its own sensor-mode table ({@link #fullModes}), each tried and checked in turn.
 * <p>
 * In that mode the HAL has no statistics: its AE stays SEARCHING (54 results, the exposure never moved) and its neutral is NaN, so
 * the route runs its own AE on the RAW frames ({@link NativeRawExposure}) and its own white balance (MosaicNeutral on the frames,
 * put on the camera's illuminant curve learned in its plain sessions); the colour transform and the lens shading map of the
 * camera's last plain session stand in for the ones the mode does not report. Its telephotos are listed only to the package
 * OplusCameraManager is told ({@link #TELE_PACKAGE}, saveOpPackageName and setPackageName, OplusCameraPackageSpoof).
 */
public final class OppoNativeFullRes {
    private OppoNativeFullRes() { }

    static final String MODEL = "PHY110";
    /** The package the telephotos are listed to (the probe: Meitu; the app keeps its own package and UID). */
    public static final String TELE_PACKAGE = "com.meitu.meiyancamera";
    static final String MODE_KEY = "com.oplus.engineercamera.agingtest.mode.select";
    private static final CaptureRequest.Key<Byte> MODE = new CaptureRequest.Key<>(MODE_KEY, Byte.class);
    private static final CaptureResult.Key<int[]> MODE_INDEX = new CaptureResult.Key<>("com.qti.sensorbps.mode_index", int[].class);

    /** The OPPO Find X7 Ultra (PHY110): the phone of the measured modes. */
    public static boolean model() {
        return model(android.os.Build.MANUFACTURER, android.os.Build.MODEL);
    }

    static boolean model(@Nullable String manufacturer, @Nullable String model) {
        return brand(manufacturer) && MODEL.equals(model);
    }

    /** P192 (owner, 2026-10-17: «Добавь все смартфоны оппо в список»): any OPPO phone. */
    public static boolean brand() {
        return brand(android.os.Build.MANUFACTURER);
    }

    static boolean brand(@Nullable String manufacturer) {
        return manufacturer != null && manufacturer.equalsIgnoreCase("OPPO");
    }

    /** Native FULLRES may run on an OPPO phone (scam_dev.txt "oppo_fullres 0" turns it off); a camera still needs {@link #profile}. */
    public static boolean device() {
        return brand() && PreferenceKeys.scamDevSwitch("oppo_fullres", true);
    }

    /** The telephotos are listed to {@link #TELE_PACKAGE} (scam_dev.txt "oppo_tele_access 0": not). */
    public static boolean teleAccess() {
        return brand() && PreferenceKeys.scamDevSwitch("oppo_tele_access", true);
    }

    /** The NoRemosaic mode measured on the PHY110 for an Android camera, -1 for another camera. */
    static int mode(@Nullable String cameraId) {
        if (cameraId == null) return -1;
        switch (cameraId) {
            case "2": return 35;
            case "3": return 16;
            case "4": return 19;
            case "5": return 17;
            default: return -1;
        }
    }

    /**
     * The Qualcomm sensor-mode table of a camera (characteristics 0x80390000: {count, values per mode, then width, height, fps
     * of each mode}). On the PHY110 the measured NoRemosaic modes are exactly the full-size rows after row 0 (the hardware-remosaic
     * one): main {0, 35, 37}, ultra-wide {0, 16}, 3x {0, 19}, 6x {0, 17, 20}. Another OPPO phone tries its own full-size rows after
     * row 0 in this order; each one proves itself (the result's mode, the pixels' mosaic) or is noted as failed and the next is
     * tried at the next FULLRES (as the X100 / X300 Ultra rows, P186).
     */
    static final String TABLE_KEY = "org.quic.camera2.sensormode.info.SensorModeTable";
    private static final android.hardware.camera2.CameraCharacteristics.Key<int[]> TABLE =
            new android.hardware.camera2.CameraCharacteristics.Key<>(TABLE_KEY, int[].class);

    /** {width, height, fps, mode...}: the full size of the table and its full-size modes after row 0; null without one. */
    @Nullable
    static int[] fullModes(@Nullable int[] table) {
        if (table == null || table.length < 2) return null;
        final int count = table[0], stride = table[1];
        if (count < 2 || stride < 3 || table.length < 2 + count * stride) return null;
        long best = 0;
        int w = 0, h = 0, fps = 0;
        for (int i = 0; i < count; i++) {
            final int mw = table[2 + i * stride], mh = table[3 + i * stride];
            if ((long) mw * mh > best) { best = (long) mw * mh; w = mw; h = mh; fps = table[4 + i * stride]; }
        }
        final java.util.ArrayList<Integer> modes = new java.util.ArrayList<>();
        for (int i = 1; i < count; i++)
            if (table[2 + i * stride] == w && table[3 + i * stride] == h) modes.add(i);
        if (modes.isEmpty() || w < 4000) return null;
        final int[] out = new int[3 + modes.size()];
        out[0] = w;
        out[1] = h;
        out[2] = fps;
        for (int i = 0; i < modes.size(); i++) out[3 + i] = modes.get(i);
        return out;
    }

    /** What the camera offers: {width, height, fps, candidate modes...}; none without the mode key or a table. */
    private static final Map<String, int[]> OFFER = new ConcurrentHashMap<>();
    private static final int[] NONE = new int[0];

    /**
     * The camera's characteristics as the app read them (the FULLRES button, the session setup): the table is taken from them.
     * A CameraManager of its own failed on the PHY110 with the owner's package spoof (BadParcelableException), so nothing is read
     * here but what the app hands over; a failure is not remembered.
     */
    public static void learn(@Nullable String cameraId, @Nullable android.hardware.camera2.CameraCharacteristics c) {
        if (cameraId == null || c == null || OFFER.containsKey(cameraId) || !brand()) return;
        try {
            boolean key = false;
            for (CaptureRequest.Key<?> k : c.getAvailableSessionKeys())
                if (MODE_KEY.equals(k.getName())) { key = true; break; }
            int[] table = null;
            if (key) try { table = c.get(TABLE); } catch (IllegalArgumentException noTag) { table = null; }
            int[] full = key ? fullModes(table) : null;
            if (full == null && key && model() && mode(cameraId) >= 0) full = new int[]{8192, 6144, 24, mode(cameraId)};
            OFFER.put(cameraId, full == null ? NONE : full);
            com.particlesdevs.photoncamera.util.Log.i("OppoNativeFullRes", "camera " + cameraId + ": mode key " + key
                    + ", table " + (table == null ? "none" : table.length + " values")
                    + (full == null ? ", no full-size NoRemosaic candidate" : String.format(Locale.ROOT, ", %dx%d at %d fps, modes %s",
                    full[0], full[1], full[2], java.util.Arrays.toString(java.util.Arrays.copyOfRange(full, 3, full.length)))));
        } catch (RuntimeException e) {
            com.particlesdevs.photoncamera.util.Log.w("OppoNativeFullRes", "camera " + cameraId + ": characteristics unreadable: " + e);
        }
    }

    @Nullable
    static int[] offer(@Nullable String cameraId) {
        if (cameraId == null) return null;
        final int[] known = OFFER.get(cameraId);
        return known == null || known.length == 0 ? null : known;
    }

    /** The frame time of the camera's full-size mode (24 fps when unknown). */
    static long frameNs(@Nullable String cameraId) {
        final int[] o = offer(cameraId);
        return o != null && o[2] > 0 ? 1_000_000_000L / o[2] : 41_666_666L;
    }

    /** The profile the camera tries now: the first candidate not yet failed; Tetra above 100 MP, else Quad; null: none left. */
    @Nullable
    static VivoSdkFullRes.Profile profile(@Nullable String cameraId) {
        final int[] o = offer(cameraId);
        if (o == null) return null;
        final int tried = failures(cameraId);
        if (tried < 0 || 3 + tried >= o.length) return null;
        final int block = (long) o[0] * o[1] > 100_000_000L ? 4 : 2;
        return new VivoSdkFullRes.Profile(o[0], o[1], o[3 + tried], block);
    }

    private static final String FAILED = "module_oppo_fullres_fail_";
    /**
     * P214h: the route revision of the trials' stamp (FullResTrials, with Build.FINGERPRINT: a firmware update tries a camera's
     * modes again); a build that changes the route bumps it to try every camera's modes again.
     */
    static final int ROW_TRIALS = 1;

    /** P214h: the base of a camera's trial keys (the tried count itself, its reasons, its stamp). */
    static String base(String cameraId) {
        return FAILED + cameraId;
    }

    /** P214h: the stamp of this build's trials on this firmware. */
    static String stamp() {
        return FullResTrials.stamp(ROW_TRIALS);
    }

    @Nullable
    private static android.content.SharedPreferences prefs() {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int failures(String cameraId) {
        return failures(prefs(), cameraId, stamp());
    }

    /** P214h: {@link #failures(String)} in {@code p} under the stamp {@code stamp} (another stamp's trials are passed over). */
    static int failures(@Nullable android.content.SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return 0;
        try {
            final String key = base(cameraId);
            return FullResTrials.counts(p, key, key, stamp) ? p.getInt(key, 0) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** P214h: the reasons (mode:code,...) of the camera's failed modes; null for none or another stamp's. */
    @Nullable
    static String reasons(android.content.SharedPreferences p, String cameraId, String stamp) {
        final String key = base(cameraId) + FullResTrials.WHY;
        return FullResTrials.counts(p, base(cameraId), key, stamp) ? FullResTrials.string(p, key) : null;
    }

    /** P214h: the tried count after {@code mode} failed for {@code why}, and that mode's reason, under the stamp {@code stamp}. */
    static void note(@Nullable android.content.SharedPreferences p, String cameraId, int tried, int mode, @Nullable String why,
                     String stamp) {
        if (p == null || cameraId == null) return;
        try {
            final String reasons = FullResTrials.withReason(reasons(p, cameraId, stamp), mode, FullResTrials.code(why));
            FullResTrials.edit(p, base(cameraId), stamp).putInt(base(cameraId), tried)
                    .putString(base(cameraId) + FullResTrials.WHY, reasons).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /** P214h: «Попробовать снова» - the camera's trials are forgotten; its next FULLRES starts at its first mode. Keys removed. */
    static int clearFailures(@Nullable android.content.SharedPreferences p, @Nullable String cameraId) {
        if (p == null || cameraId == null) return 0;
        return FullResTrials.clear(p, base(cameraId));
    }

    /** P214h: the camera offers full-size modes (its table, read this run) but every one failed: its button stays, dimmed. */
    static boolean exhausted(@Nullable String cameraId) {
        return offer(cameraId) != null && profile(cameraId) == null;
    }

    /**
     * P214h: the camera's trials in words - «FULLRES 50 Мп выключен: режимы 35, 37 — не та мозаика.»; the modes the reasons
     * name when its table is not known in this run; null without a failed mode.
     */
    @Nullable
    static String status(@Nullable android.content.SharedPreferences p, @Nullable String cameraId, String stamp) {
        if (p == null || cameraId == null) return null;
        final int tried = failures(p, cameraId, stamp);
        if (tried <= 0) return null;
        final int[] o = offer(cameraId);
        return FullResTrials.sizeLine(o == null ? 0 : com.particlesdevs.photoncamera.settings.FullResProfiles.megapixels(o[0], o[1]),
                o == null ? null : java.util.Arrays.copyOfRange(o, 3, o.length), tried, FullResTrials.reasons(reasons(p, cameraId, stamp)));
    }

    /**
     * A native session of this camera failed (no frame, the mode not reported, a Bayer or wrong-block frame): its mode is noted and
     * the next candidate is tried at the next FULLRES. Returns the owner's message.
     */
    public static String failed(String cameraId, @Nullable VivoSdkFullRes.Profile profile, String why) {
        final int[] o = offer(cameraId);
        final int tried = failures(cameraId);
        if (o != null && profile != null && 3 + tried < o.length && o[3 + tried] == profile.sensorMode)
            note(prefs(), cameraId, tried + 1, profile.sensorMode, why, stamp()); // P214h: with the mode's reason, stamped
        final VivoSdkFullRes.Profile next = profile(cameraId);
        final int mode = profile == null ? -1 : profile.sensorMode;
        com.particlesdevs.photoncamera.util.Log.w("OppoNativeFullRes", "camera " + cameraId + " mode " + mode + " failed (" + why
                + "); next " + (next == null ? "none" : "mode " + next.sensorMode));
        return next != null
                ? com.particlesdevs.photoncamera.util.Lang.t("FULLRES: режим " + mode + " не работает на этой камере; в следующий раз попробую режим " + next.sensorMode,
                        "FULLRES: mode " + mode + " does not work on this camera; the next time mode " + next.sensorMode + " is tried")
                : com.particlesdevs.photoncamera.util.Lang.t("FULLRES без аппаратного ремозаика недоступен на этой камере",
                        "FULLRES without hardware remosaic is not available on this camera");
    }

    /** The camera of the running native session (null: none), for the requests and the results of its stills. */
    private static volatile String camera;
    private static volatile NativeRawExposure exposure;

    static void begin(String cameraId, NativeRawExposure ae) {
        camera = cameraId;
        exposure = ae;
    }

    static void end() {
        camera = null;
        exposure = null;
    }

    /** A native session of this phone runs (its results carry the own neutral: not a point of the camera's curve). */
    public static boolean running() {
        return camera != null;
    }

    /** The mode on a request (the session parameters and every request), AE off at the own exposure, the HAL's AWB left on. */
    static void apply(CaptureRequest.Builder builder, VivoSdkFullRes.Profile profile) {
        builder.set(MODE, (byte) profile.sensorMode);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        NativeRawExposure ae = exposure;
        if (ae != null) {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ae.exposureNs());
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, ae.iso());
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, ae.frameDurationNs());
        }
    }

    /** The result reports the profile's mode (the sensor's own mode index), or does not say. */
    static boolean modeMatches(CaptureResult result, VivoSdkFullRes.Profile profile) {
        int[] mode = null;
        try { mode = result.get(MODE_INDEX); } catch (IllegalArgumentException ignored) { }
        return mode == null || mode.length == 0 || mode[0] == profile.sensorMode;
    }

    /** What the camera's last plain session reported (the ISP's colour transform, the shading map), per Android camera. */
    private static final Map<String, ColorSpaceTransform> TRANSFORMS = new ConcurrentHashMap<>();
    private static final Map<String, LensShadingMap> SHADING = new ConcurrentHashMap<>();
    /** The own neutral (r / g, 1, b / g) of the running native session, from its frames. */
    private static final Map<String, float[]> NEUTRALS = new ConcurrentHashMap<>();
    /** The ISP's settled neutral of the camera's last plain session: the start of a native session's white balance. */
    private static final Map<String, float[]> PLAIN_NEUTRALS = new ConcurrentHashMap<>();

    /** A plain session's result of this phone (AWB settled): its colour transform and shading map are kept for the camera. */
    static void rememberPlain(String cameraId, CaptureResult result) {
        if (cameraId == null || result == null || camera != null) return;
        final Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);
        if (awb != null && awb != CaptureResult.CONTROL_AWB_STATE_CONVERGED && awb != CaptureResult.CONTROL_AWB_STATE_LOCKED) return;
        final ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (transform != null && !identity(transform)) TRANSFORMS.put(cameraId, transform);
        final Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (n != null && n.length >= 3 && n[1].floatValue() > 0f && n[0].floatValue() > 0f && n[2].floatValue() > 0f)
            PLAIN_NEUTRALS.put(cameraId, new float[]{n[0].floatValue() / n[1].floatValue(), 1f, n[2].floatValue() / n[1].floatValue()});
        final LensShadingMap map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (map != null && map.getColumnCount() > 1 && map.getRowCount() > 1) SHADING.put(cameraId, map);
    }

    static boolean identity(ColorSpaceTransform t) {
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
            if (Math.abs(t.getElement(c, r).floatValue() - (r == c ? 1f : 0f)) > 1e-4f) return false;
        return true;
    }

    /** The own neutral of the camera's frames, smoothed in the log domain (a new session starts from its first measurement). */
    static void measuredNeutral(String cameraId, float r, float b) {
        if (cameraId == null || !(r > 0.05f && r < 20f && b > 0.05f && b < 20f)) return;
        final float[] last = NEUTRALS.get(cameraId);
        if (last == null) { NEUTRALS.put(cameraId, new float[]{r, 1f, b}); return; }
        final float k = 0.3f;
        NEUTRALS.put(cameraId, new float[]{(float) Math.exp((1 - k) * Math.log(last[0]) + k * Math.log(r)), 1f,
                (float) Math.exp((1 - k) * Math.log(last[2]) + k * Math.log(b))});
    }

    static void forgetNeutral(String cameraId) {
        if (cameraId != null) NEUTRALS.remove(cameraId);
    }

    /** The own neutral of the camera's native frames, else its last plain session's (null: neither yet). */
    @Nullable
    static float[] neutral(String cameraId) {
        if (cameraId == null) return null;
        final float[] own = NEUTRALS.get(cameraId);
        return own != null ? own : PLAIN_NEUTRALS.get(cameraId);
    }

    /**
     * A native result gets what the mode does not report, before anything reads it: the own neutral (and the white-balance gains it
     * gives) for its NaN neutral, the plain session's colour transform for the identity one, the plain session's shading map
     * when it has none. Returns what was filled, for the log.
     */
    static String complete(String cameraId, CaptureResult result) {
        if (cameraId == null || result == null) return "";
        final StringBuilder filled = new StringBuilder();
        final float[] own = neutral(cameraId);
        final Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        final boolean missing = n == null || n.length < 3 || !(n[0].floatValue() > 0f && n[1].floatValue() > 0f && n[2].floatValue() > 0f);
        if (missing && own != null) {
            if (CameraReflectionApi.setCached(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT, new Rational[]{rational(own[0]), new Rational(1, 1), rational(own[2])}, result)
                    & CameraReflectionApi.setCached(CaptureResult.COLOR_CORRECTION_GAINS, new RggbChannelVector(1f / own[0], 1f, 1f, 1f / own[2]), result))
                filled.append(String.format(Locale.ROOT, " neutral %.3f / %.3f", own[0], own[2]));
        }
        final ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        final ColorSpaceTransform plain = TRANSFORMS.get(cameraId);
        if (plain != null && (transform == null || identity(transform))) {
            if (CameraReflectionApi.setCached(CaptureResult.COLOR_CORRECTION_TRANSFORM, plain, result)) filled.append(" transform");
        }
        final LensShadingMap shading = SHADING.get(cameraId);
        if (shading != null && result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) == null) {
            CameraReflectionApi.setCached(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP, shading, result);
            // the map is a synthesized key: only a read-back shows whether it took
            if (result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) != null)
                filled.append(" shading ").append(shading.getColumnCount()).append('x').append(shading.getRowCount());
            else filled.append(" (shading map not settable)");
        }
        return filled.toString();
    }

    static Rational rational(float v) {
        return new Rational(Math.round(v * 1_000_000f), 1_000_000);
    }
}
