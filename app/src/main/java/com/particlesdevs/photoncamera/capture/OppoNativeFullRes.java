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

    static int failures(String cameraId) {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getInt(FAILED + cameraId, 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * A native session of this camera failed (no frame, the mode not reported, a Bayer or wrong-block frame): its mode is noted and
     * the next candidate is tried at the next FULLRES. Returns the owner's message.
     */
    public static String failed(String cameraId, @Nullable VivoSdkFullRes.Profile profile, String why) {
        final int[] o = offer(cameraId);
        final int tried = failures(cameraId);
        if (o != null && profile != null && 3 + tried < o.length && o[3 + tried] == profile.sensorMode) {
            try {
                com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit()
                        .putInt(FAILED + cameraId, tried + 1).apply();
            } catch (RuntimeException ignored) {
            }
        }
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
        // P212e (W3): the session's white balance starts afresh, from the plain neutral just before it when there is one
        if (cameraId != null && OppoNativeWb.on()) {
            NEUTRALS.remove(cameraId); // P192's average never carries over into a session again
            final long now = android.os.SystemClock.elapsedRealtime();
            final float[] plain = PLAIN_NEUTRALS.get(cameraId);
            final Long at = PLAIN_AT.get(cameraId);
            com.particlesdevs.photoncamera.processing.color.MosaicNeutral.init(com.particlesdevs.photoncamera.app.ScameraApp.getAppContext());
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "native white balance camera " + cameraId + ": "
                    + OppoNativeWb.begin(cameraId, now, plain, plain == null || at == null ? -1L : now - at, storedNeutral(cameraId),
                    com.particlesdevs.photoncamera.processing.color.MosaicNeutral.middle(cameraId)));
        }
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
    /** P212e: when it was reported (elapsedRealtime ms): fresh, it seeds and anchors the native session's white balance. */
    private static final Map<String, Long> PLAIN_AT = new ConcurrentHashMap<>();

    /**
     * A plain session's result of this phone (AWB settled): its colour transform and shading map are kept for the camera.
     * P212e (W1): on any OPPO phone (the caller's guard was the PHY110 only, while FULLRES runs on every OPPO phone), and stored for
     * the camera's native size (OppoPlainState) at most every 5 s, so a cold start straight into FULLRES has them too.
     */
    static void rememberPlain(String cameraId, CaptureResult result) {
        if (cameraId == null || result == null || camera != null) return;
        final Integer awb = result.get(CaptureResult.CONTROL_AWB_STATE);
        if (awb != null && awb != CaptureResult.CONTROL_AWB_STATE_CONVERGED && awb != CaptureResult.CONTROL_AWB_STATE_LOCKED) return;
        final ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        final boolean ispTransform = transform != null && !identity(transform);
        if (ispTransform) TRANSFORMS.put(cameraId, transform);
        final Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        float[] neutral = null;
        if (n != null && n.length >= 3 && n[1].floatValue() > 0f && n[0].floatValue() > 0f && n[2].floatValue() > 0f) {
            neutral = new float[]{n[0].floatValue() / n[1].floatValue(), 1f, n[2].floatValue() / n[1].floatValue()};
            if (!OppoNativeWb.valid(neutral)) neutral = null; // P212e: a NaN / Inf ratio is no neutral (NaN > 0 is false, Inf is not)
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (neutral != null) {
            PLAIN_NEUTRALS.put(cameraId, neutral);
            PLAIN_AT.put(cameraId, now);
        }
        final LensShadingMap map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        final boolean hasMap = map != null && map.getColumnCount() > 1 && map.getRowCount() > 1;
        if (hasMap) SHADING.put(cameraId, map);
        // P212e: the store (a map under the light of its own neutral only)
        final int[] o = offer(cameraId);
        if (neutral == null || o == null || !OppoPlainState.due(cameraId, o[0], o[1], now)) return;
        float[] gains = null;
        if (hasMap) {
            gains = new float[map.getGainFactorCount()];
            map.copyGainFactors(gains, 0);
        }
        float[] t = null;
        if (ispTransform) {
            t = new float[9];
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) t[r * 3 + c] = transform.getElement(c, r).floatValue();
        }
        OppoPlainState.remember(cameraId, o[0], o[1], neutral[0], neutral[2], t, gains, hasMap ? map.getColumnCount() : 0,
                hasMap ? map.getRowCount() : 0, now);
    }

    /** P212e (W1): the stored plain neutral of the camera's native size (null: none, or the store is off). */
    @Nullable
    static float[] storedNeutral(@Nullable String cameraId) {
        final int[] o = offer(cameraId);
        return o == null ? null : OppoPlainState.neutral(cameraId, o[0], o[1]);
    }

    /** P212e (W1): the stored plain map of the camera's native size nearest to the light of {@code neutral} (null: none). */
    @Nullable
    static OppoPlainState.Shading storedShading(@Nullable String cameraId, @Nullable float[] neutral) {
        final int[] o = offer(cameraId);
        return o == null ? null : OppoPlainState.shading(cameraId, o[0], o[1], neutral);
    }

    static boolean identity(ColorSpaceTransform t) {
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
            if (Math.abs(t.getElement(c, r).floatValue() - (r == c ? 1f : 0f)) > 1e-4f) return false;
        return true;
    }

    /**
     * The own neutral of the camera's frames, smoothed in the log domain (a new session starts from its first measurement). P212e:
     * the P192 law, used only with scam_dev.txt "oppo_wb_filter 0" (else OppoNativeWb).
     */
    static void measuredNeutral(String cameraId, float r, float b) {
        if (cameraId == null || !(r > 0.05f && r < 20f && b > 0.05f && b < 20f)) return;
        final float[] last = NEUTRALS.get(cameraId);
        if (last == null) { NEUTRALS.put(cameraId, new float[]{r, 1f, b}); return; }
        final float k = 0.3f;
        NEUTRALS.put(cameraId, new float[]{(float) Math.exp((1 - k) * Math.log(last[0]) + k * Math.log(r)), 1f,
                (float) Math.exp((1 - k) * Math.log(last[2]) + k * Math.log(b))});
    }

    /**
     * P212f (W2 = M3): a hand-set white balance (P201) is the own neutral as it is, not through the k 0.3 average (about 10 frames
     * to arrive after a Kelvin change, the ring frames inside them carried the blend): complete() fills exactly the inverse of its
     * gains from the next frame on. Only the P192 law ("oppo_wb_filter 0") reads it; the filter holds the hand-set one itself.
     */
    static void setNeutral(String cameraId, float r, float b) {
        if (cameraId == null || !(r > 0.05f && r < 20f && b > 0.05f && b < 20f)) return;
        NEUTRALS.put(cameraId, new float[]{r, 1f, b});
    }

    static void forgetNeutral(String cameraId) {
        if (cameraId != null) NEUTRALS.remove(cameraId);
    }

    /**
     * The own neutral of the camera's native frames, else its last plain session's, else (P212e, W1) its stored plain one (null:
     * none yet). P212e (W3): the own neutral is OppoNativeWb's state (reset and seeded at begin), the P192 EMA only with
     * "oppo_wb_filter 0".
     */
    @Nullable
    static float[] neutral(String cameraId) {
        if (cameraId == null) return null;
        float[] own = OppoNativeWb.on() ? OppoNativeWb.neutral(cameraId) : null;
        if (own == null) own = NEUTRALS.get(cameraId); // with the filter on, begin() cleared it (only the P192 law writes it)
        if (own != null) return own;
        final float[] plain = PLAIN_NEUTRALS.get(cameraId);
        return plain != null ? plain : storedNeutral(cameraId);
    }

    /** P212e: the held AWB gains of a native result (the HAL keeps the plain session's last ones, or its init values). */
    private static final CaptureResult.Key<float[]> AWB_R = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlRGain", float[].class);
    private static final CaptureResult.Key<float[]> AWB_G = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlGGain", float[].class);
    private static final CaptureResult.Key<float[]> AWB_B = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlBGain", float[].class);
    private static final CaptureResult.Key<int[]> AWB_CCT = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlCCT", int[].class);
    /** P212e: the raw keys behind the synthesized lens shading map (the gains, the map size the result carries). */
    private static final CaptureResult.Key<float[]> LSM_GAINS = new CaptureResult.Key<>("android.statistics.lensShadingMap", float[].class);
    private static final CaptureResult.Key<android.util.Size> LSM_SIZE = new CaptureResult.Key<>("android.lens.info.shadingMapSize", android.util.Size.class);

    @Nullable
    private static <T> T vendor(CaptureResult result, CaptureResult.Key<T> key) {
        try {
            return result.get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {R, G, B} of the result's held AWB gains, then its CCT (0: none); null without them. */
    @Nullable
    static float[] heldAwb(CaptureResult result) {
        final float[] r = vendor(result, AWB_R), g = vendor(result, AWB_G), b = vendor(result, AWB_B);
        final int[] cct = vendor(result, AWB_CCT);
        if (r == null || b == null || r.length == 0 || b.length == 0) return null;
        return new float[]{r[0], g == null || g.length == 0 ? 1f : g[0], b[0], cct == null || cct.length == 0 ? 0f : cct[0]};
    }

    /**
     * A native result gets what the mode does not report, before anything reads it: the own neutral (and the white-balance gains it
     * gives) for its NaN neutral, the plain session's colour transform for the identity one, the plain session's shading map
     * when it has none. Returns what was filled, for the log.
     * P212e (W1): with nothing in memory (a cold start straight into FULLRES) the stored plain transform and map (OppoPlainState;
     * the map through the raw keys behind the synthesized one - no hidden constructor; Parameters, the viewfinder and the meter
     * read the stored floats themselves when even that does not take); the first result of a session without any neutral seeds
     * the white balance from the held AWB gains (not the 3745 K init signature) or the curve middle; a neutral is written only
     * when it is a finite white point (never NaN), else the HAL's stays and the photo keeps its own estimate.
     */
    static String complete(String cameraId, CaptureResult result) {
        if (cameraId == null || result == null) return "";
        final StringBuilder filled = new StringBuilder();
        float[] own = neutral(cameraId);
        if (own == null) {
            final float[] held = heldAwb(result);
            if (OppoNativeWb.on()) {
                final String how = OppoNativeWb.lateSeed(cameraId, held, held == null ? 0 : (int) held[3],
                        android.os.SystemClock.elapsedRealtime());
                if (how != null) {
                    com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "native white balance camera " + cameraId + ": " + how);
                    own = OppoNativeWb.neutral(cameraId);
                }
            } else if (held != null) {
                own = OppoNativeWb.heldNeutral(held, (int) held[3]);
                if (own != null) filled.append(" (held AWB ").append((int) held[3]).append(" K)");
            }
        }
        final Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        final boolean missing = n == null || n.length < 3 || !(n[0].floatValue() > 0f && n[1].floatValue() > 0f && n[2].floatValue() > 0f)
                || !OppoNativeWb.valid(new float[]{n[0].floatValue(), n[1].floatValue(), n[2].floatValue()});
        if (missing && OppoNativeWb.valid(own)) {
            final float r = own[0] / own[1], b = own[2] / own[1];
            if (CameraReflectionApi.setCached(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT, new Rational[]{rational(r), new Rational(1, 1), rational(b)}, result)
                    & CameraReflectionApi.setCached(CaptureResult.COLOR_CORRECTION_GAINS, new RggbChannelVector(1f / r, 1f, 1f, 1f / b), result))
                filled.append(String.format(Locale.ROOT, " neutral %.3f / %.3f", r, b));
        }
        final ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        ColorSpaceTransform plain = TRANSFORMS.get(cameraId);
        boolean storedTransform = false;
        if (plain == null && (transform == null || identity(transform))) {
            final int[] o = offer(cameraId);
            final float[] t = o == null ? null : OppoPlainState.transform(cameraId, o[0], o[1]);
            if (t != null) {
                final Rational[] e = new Rational[9];
                for (int i = 0; i < 9; i++) e[i] = new Rational(Math.round(t[i] * 10_000f), 10_000);
                plain = new ColorSpaceTransform(e);
                storedTransform = true;
            }
        }
        if (plain != null && (transform == null || identity(transform))) {
            if (CameraReflectionApi.setCached(CaptureResult.COLOR_CORRECTION_TRANSFORM, plain, result))
                filled.append(storedTransform ? " transform (stored)" : " transform");
        }
        final LensShadingMap shading = SHADING.get(cameraId);
        if (result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) == null) {
            if (shading != null && !PreferenceKeys.scamDevSwitch("oppo_shading_keys", true)) {
                // P192 (scam_dev.txt "oppo_shading_keys 0"): the synthesized key's setter - it stores the size as rows x columns,
                // so the photos read the 17 x 13 map back as 13 x 17 (log31 23:10:54 / 23:12:58 / 23:14:42, log17, log-2026-10-17)
                CameraReflectionApi.setCached(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP, shading, result);
                // the map is a synthesized key: only a read-back shows whether it took
                if (result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) != null)
                    filled.append(" shading ").append(shading.getColumnCount()).append('x').append(shading.getRowCount());
                else filled.append(" (shading map not settable)");
            } else if (shading != null) {
                // P212e: through the raw keys, read back in the plain map's own orientation
                final float[] gains = gainsOf(shading);
                filled.append(putShading(result, gains, shading.getColumnCount(), shading.getRowCount())
                        ? " shading " + shading.getColumnCount() + "x" + shading.getRowCount() : " (shading map not settable)");
            } else {
                final OppoPlainState.Shading stored = storedShading(cameraId, own);
                if (stored != null) filled.append(" shading ").append(stored)
                        .append(putShading(result, stored.gains, stored.cols, stored.rows) ? " (stored)" : " (stored, read from the store)");
            }
        }
        return filled.toString();
    }

    /** P212e: the last in-memory plain map and its gains (copyGainFactors order), copied once per map. */
    private static volatile Object[] lastGains;

    private static float[] gainsOf(LensShadingMap map) {
        final Object[] last = lastGains;
        if (last != null && last[0] == map) return (float[]) last[1];
        final float[] g = new float[map.getGainFactorCount()];
        map.copyGainFactors(g, 0);
        lastGains = new Object[]{map, g};
        return g;
    }

    /**
     * P212e (W1): a shading map ({@code cols} x {@code rows} points, copyGainFactors order) into a result through the raw keys behind
     * the synthesized one - the gains and the map size - and read back: true only when it reads back as {@code cols} x
     * {@code rows}. The size is tried as (columns, rows), then the other way round; one that reads back wrong is taken out again
     * (nothing then reads a transposed map: Parameters, the viewfinder and the meter fall back to the stored floats). The
     * synthesized key's own setter writes the size as rows x columns: every OPPO native photo filled by it was developed with
     * the plain 17 x 13 map read as 13 x 17 ('lens shading map 13x17' in the pipeline, a plain photo's is '17x13').
     */
    static boolean putShading(CaptureResult result, float[] gains, int cols, int rows) {
        if (result == null || gains == null || cols < 2 || rows < 2 || gains.length < 4 * cols * rows) return false;
        final float[] copy = java.util.Arrays.copyOf(gains, 4 * cols * rows);
        boolean set = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            final android.util.Size size = attempt == 0 ? new android.util.Size(cols, rows) : new android.util.Size(rows, cols);
            if (!CameraReflectionApi.setCached(LSM_SIZE, size, result) || !CameraReflectionApi.setCached(LSM_GAINS, copy, result)) break;
            set = true;
            LensShadingMap back;
            try { back = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP); } catch (RuntimeException e) { back = null; }
            if (back != null && back.getColumnCount() == cols && back.getRowCount() == rows) return true;
        }
        if (set) CameraReflectionApi.setCached(LSM_GAINS, null, result);
        return false;
    }

    /** P212e (W3): {@link #complete} for a ring frame or a still - its white balance is recorded for the photo (OppoNativeWb.fuse). */
    static String completeFrame(String cameraId, CaptureResult result) {
        final String filled = complete(cameraId, result);
        if (cameraId != null && result != null && OppoNativeWb.on())
            OppoNativeWb.record(cameraId, result.get(CaptureResult.SENSOR_TIMESTAMP), android.os.SystemClock.elapsedRealtime());
        return filled;
    }

    static Rational rational(float v) {
        return new Rational(Math.round(v * 1_000_000f), 1_000_000);
    }

    /** Tests: a new process (nothing remembered, no session, no offers). */
    static void resetForTest() {
        OFFER.clear();
        TRANSFORMS.clear();
        SHADING.clear();
        NEUTRALS.clear();
        PLAIN_NEUTRALS.clear();
        PLAIN_AT.clear();
        lastGains = null;
        camera = null;
        exposure = null;
    }

    /** Tests: what a camera offers ({width, height, fps, modes...}), as learn() would find it. */
    static void offerForTest(String cameraId, int[] offer) {
        OFFER.put(cameraId, offer);
    }
}
