package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Size;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.ModuleSensorSettings;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P276 (owner, 2026-10-19, a video of the new WhyCam: «вот так это реализовано в новой Why камере, мы сможем сделать схоже?»,
 * «я в видео имел ввиду работу с сенсормодами», «учитывай всю новую информацию которую я кидал тебе по оппо и виво»): a module's
 * «Режим сенсора» picked from the camera's own org.quic.camera2.sensormode.info.SensorModeTable (Auto + "Mode #N: W x H @ fps").
 * A picked row makes the module's session
 * <ul>
 *   <li>open its RAW stream at the row's size (also a size StreamConfigurationMap does not list: an ImageReader of usage 0, as
 *       the native FULLRES route and WhyCam do);</li>
 *   <li>send the brand's mode key on the session parameters AND on every request: vivo vivo.control.forceSensorMode as int[]
 *       (Integer when the HAL refuses the array), OPPO / OnePlus / realme com.oplus.engineercamera.agingtest.mode.select as a
 *       byte (int32 when refused, OppoNativeFullRes.setMode), any other Qualcomm phone
 *       org.codeaurora.qcamera3.sensor_meta_data.current_mode;</li>
 *   <li>leave the ISP stream out of the session («Поток ISP» Auto: the RAW viewfinder);</li>
 *   <li>on vivo open the camera under the SDK lease (VCF 33 = 2, SDK RAW 40 = 1, VivoSdkRawSession).</li>
 * </ul>
 * Stored per module slot as pref_sensorconfig_&lt;slot&gt;_sensormoderow (-1 Auto), so the module's copy / reset take it along.
 */
public final class SensorModePick {
    private SensorModePick() {}

    public static final String SUFFIX = "sensormoderow";
    static final String VIVO_KEY = VivoQcomIsz.KEY;
    static final String QCOM_KEY = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    private static final CaptureRequest.Key<int[]> VIVO_ARRAY = new CaptureRequest.Key<>(VIVO_KEY, int[].class);
    private static final CaptureRequest.Key<Integer> VIVO_INT = new CaptureRequest.Key<>(VIVO_KEY, Integer.class);
    private static final CaptureRequest.Key<Integer> QCOM_INT = new CaptureRequest.Key<>(QCOM_KEY, Integer.class);
    private static volatile boolean vivoInt;

    /** The brand's way of asking for a sensor mode. */
    enum Brand { VIVO, OPLUS, QCOM }

    static Brand brand(@Nullable String manufacturer) {
        final String m = manufacturer == null ? "" : manufacturer.toLowerCase(Locale.ROOT);
        if (m.equals("vivo") || m.equals("iqoo")) return Brand.VIVO;
        if (m.equals("oppo") || m.equals("oneplus") || m.equals("realme")) return Brand.OPLUS;
        return Brand.QCOM;
    }

    static Brand brand() {
        return brand(Build.MANUFACTURER);
    }

    /** The rows of a table as {index, width, height, fps}. */
    static List<int[]> rows(@Nullable int[] table) {
        final List<int[]> out = new ArrayList<>();
        if (table == null || table.length < 2) return out;
        for (int m = 0; m < table[0]; m++) {
            final int[] e = SensorModeBand.modeEntry(table, m);
            if (e != null && e[0] > 0 && e[1] > 0) out.add(new int[]{m, e[0], e[1], e[2]});
        }
        return out;
    }

    public static List<int[]> rows(@Nullable CameraCharacteristics c) {
        return rows(SensorModeBand.table(c));
    }

    /** "Mode #4: 8192x6144 @ 30fps", as WhyCam lists them. */
    public static String label(int[] row) {
        return "Mode #" + row[0] + ": " + row[1] + "x" + row[2] + " @ " + row[3] + "fps";
    }

    public static String prefKey(String slot) {
        return ModuleSensorSettings.prefix(slot) + SUFFIX;
    }

    /** The row picked on a module slot, -1 for Auto. */
    public static int picked(@Nullable String slot) {
        if (slot == null) return -1;
        try {
            final String v = ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getString(prefKey(slot), "-1");
            return Integer.parseInt(v == null ? "-1" : v.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** What a session on {@code physicalId} runs: the active module's picked row and its size; null without one. */
    public static final class Pick {
        public final int mode, width, height;
        public final Size size;

        Pick(int mode, int width, int height) {
            this.mode = mode;
            this.width = width;
            this.height = height;
            this.size = new Size(width, height);
        }

        @Override public String toString() {
            return "mode " + mode + " " + width + "x" + height;
        }
    }

    /** Session-scoped: the pick of the session being configured (CaptureController sets it, the request builders read it). */
    private static volatile Pick sSession;
    private static volatile String sSessionCamera;

    @Nullable
    public static Pick active(@Nullable String physicalId, @Nullable CameraCharacteristics c) {
        try {
            if (physicalId == null || !PreferenceKeys.scamDevSwitch("sensor_mode_pick", true)) return null;
            final String slot = ModuleRegistry.active();
            if (!physicalId.equals(ModuleRegistry.camera(slot))) return null;
            return of(picked(slot), SensorModeBand.table(c));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The pick of a row of a table, null for Auto or a row the table does not have (a firmware whose rows moved). */
    @Nullable
    static Pick of(int mode, @Nullable int[] table) {
        if (mode < 0) return null;
        final int[] e = SensorModeBand.modeEntry(table, mode);
        return e == null || e[0] <= 0 || e[1] <= 0 ? null : new Pick(mode, e[0], e[1]);
    }

    /** CaptureController: the session being configured runs {@code pick} (null: none). */
    public static void session(@Nullable String physicalId, @Nullable Pick pick) {
        sSession = pick;
        sSessionCamera = pick == null ? null : physicalId;
    }

    @Nullable
    public static Pick session() {
        return sSession;
    }

    static final String SERIES_REASON = "a sensor mode picked on an OPPO-family camera: ring frames only (the X8U's HAL failed every"
            + " request after the shutter in its agingtest mode and died; scam_dev sensor_mode_series 1 sends the series)";

    /**
     * P315: whether a shot of the session sends its frames after the shutter (the Hybrid series). Not in a picked sensor mode on an
     * OPPO / OnePlus / realme camera: the OPPO Find X8 Ultra's 2x ISZ row (mode 8) failed every post-shutter request and its camera
     * provider died at each shot (owner's log 2026-10-21 13:33 / 13:36, two restarts); the reference app takes such a shot
     * from its ring alone. scam_dev "sensor_mode_series 1" sends the series again (to test a phone).
     */
    public static boolean seriesAllowed() {
        return seriesAllowed(sSession != null, brand(), PreferenceKeys.scamDevSwitch("sensor_mode_series", false));
    }

    static boolean seriesAllowed(boolean picked, Brand brand, boolean forced) {
        return !picked || brand != Brand.OPLUS || forced;
    }

    /** P316 (settings audit): a row picked on this phone makes its shots ring-only ({@link #seriesAllowed}): the row's hint says so. */
    public static boolean pickTakesRingOnly() {
        return !seriesAllowed(true, brand(), PreferenceKeys.scamDevSwitch("sensor_mode_series", false));
    }

    /**
     * P316 (settings audit): a shot whose ring gave no usable frame (the drain dropped every RAW: a payload no plain / known
     * packed layout, no exposure, after the shutter's cutoff) is refused where {@link #seriesAllowed} forbids the series - the
     * normal-back plan would expose its N, Bento and Shasta frames after the shutter, the series the X8U's HAL died on.
     */
    public static boolean emptyRingRefused(boolean ringEmpty) {
        return emptyRingRefused(ringEmpty, seriesAllowed());
    }

    static boolean emptyRingRefused(boolean ringEmpty, boolean seriesAllowed) {
        return ringEmpty && !seriesAllowed;
    }

    /** The brand's mode key of the session's pick on a builder (session parameters or a request); the log text, "" without. */
    public static String apply(@Nullable CaptureRequest.Builder builder) {
        final Pick p = sSession;
        if (builder == null || p == null) return "";
        return apply(builder, p.mode, brand());
    }

    static String apply(CaptureRequest.Builder builder, int mode, Brand brand) {
        try {
            switch (brand) {
                case VIVO:
                    if (!vivoInt) {
                        try {
                            builder.set(VIVO_ARRAY, new int[]{mode});
                            return VIVO_KEY + " = [" + mode + "]";
                        } catch (IllegalArgumentException | UnsupportedOperationException arrayRefused) {
                            vivoInt = true;
                            Log.w("SensorModePick", VIVO_KEY + " refused as int[] (" + arrayRefused + "): an Integer from now on");
                        }
                    }
                    builder.set(VIVO_INT, mode);
                    return VIVO_KEY + " = " + mode;
                case OPLUS:
                    OppoNativeFullRes.setMode(builder, mode);
                    return OppoNativeFullRes.MODE_KEY + " = " + mode;
                default:
                    builder.set(QCOM_INT, mode);
                    return QCOM_KEY + " = " + mode;
            }
        } catch (RuntimeException refused) {
            return "refused: " + refused.getMessage();
        }
    }

    /** vivo: the camera of a picked mode opens under the SDK lease (CaptureController.openIszLease). */
    public static boolean leaseWanted(@Nullable String cameraId) {
        if (brand() != Brand.VIVO || cameraId == null || !PreferenceKeys.scamDevSwitch("sensor_mode_pick_lease", true)) return false;
        try {
            final String slot = ModuleRegistry.active();
            return cameraId.equals(ModuleRegistry.camera(slot)) && picked(slot) >= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The colour block the picked row's frames have: a row of the table's largest size, while a row of half or a quarter of it
     * exists (the binned modes), is the sensor's own Quad (Tetra above 100 MP) mosaic; 0 otherwise (the merge measures it).
     */
    static int declaredBlock(@Nullable int[] table, int mode) {
        final int[] e = SensorModeBand.modeEntry(table, mode);
        if (e == null) return 0;
        long largest = 0;
        for (int[] r : rows(table)) largest = Math.max(largest, (long) r[1] * r[2]);
        if ((long) e[0] * e[1] != largest) return 0;
        for (int[] r : rows(table))
            if ((r[1] * 2 == e[0] && r[2] * 2 == e[1]) || (r[1] * 4 == e[0] && r[2] * 4 == e[1]))
                return OppoNativeFullRes.block(e[0], e[1]);
        return 0;
    }

    /** The declared block of the session's pick (0: none / unknown). */
    public static int sessionBlock(@Nullable CameraCharacteristics c) {
        final Pick p = sSession;
        return p == null ? 0 : declaredBlock(SensorModeBand.table(c), p.mode);
    }
}
