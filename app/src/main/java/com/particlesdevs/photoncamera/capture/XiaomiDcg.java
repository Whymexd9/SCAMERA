package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.util.Range;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * P230 DCG 14 bit on the Xiaomi 15 Ultra (25010PN30*, «xuanyuan», SM8750) without root, as the MotionCam community found it
 * (owner, 2026-10-18: «Изучи внимательно и реализуй на 15 Ultra»):
 * <ul>
 * <li>the capture session in vendor operation mode 37379 (0x9203; Xiaomi's com.qti.chi.override.so tests it in
 * CheckVideoNightModeHint). That mode alone selects sensor mode 23 (14 bit, 4096 x 2560);</li>
 * <li>org.codeaurora.qcamera3.sensor_meta_data.current_mode (int32) = 24, the DCG mode itself;</li>
 * <li>60 fps only (the AE target range of the viewfinder, and so of the shot's frames, which take the viewfinder's range);</li>
 * <li>the 4096 x 3072 RAW_SENSOR buffer holds the 2560 rows of the mode: the 512 rows at the bottom are empty and are cut off
 * the shot's frames ({@link #padding}, called by ScamProcessor's RAW crop);</li>
 * <li>14-bit samples under the declared white 1023: the session's levels are 14 bit (RawLevels.noteKnownBits: white 16383,
 * black the frame's dynamic one in its units, else the static 64 x 16), confirmed or taken back by the mode the results report
 * ({@link #onResult}).</li>
 * </ul>
 * The 15U's SCAM TEST dump (2026-10-11): logical camera 0 (physical 2 main, 3 ultra-wide, 4 3x tele, 5 periscope), the main's
 * org.quic.camera2.sensormode.info.SensorModeTable = {41 modes, 3 values each: width, height, fps}; modes 17-24 4096 x 2560 at
 * 30, 28-32 4096 x 2560 at 60; the ultra-wide (15 modes), the tele (14) and the front (9) have no mode 24. So the switch is
 * offered on a camera whose table has the DCG mode at 4096 wide (cameras 0, 2, 6, 7 - the main): never on the tele 4, which
 * alone takes the camera provider down. current_mode is neither a listed session key nor a listed request key there; the
 * camera service drops unlisted session parameters, so it goes on the session parameters (as the post says) and on every
 * request too (as the 17 Ultra's HAL takes its ISZ mode 9, P41).
 * <p>
 * scam_dev: {@code xiaomi_dcg 0} hides the switch, {@code xiaomi_dcg_opmode N} / {@code xiaomi_dcg_mode N} / {@code xiaomi_dcg_fps N}
 * try other values (0 = leave out), {@code xiaomi_dcg_crop} 0 = no crop, 1 = cut the band when it is empty (default), 2 = always cut
 * the bottom band.
 */
public final class XiaomiDcg {
    static final String TAG = "XiaomiDcg";
    /** The session's vendor operation mode (37379). */
    public static final int OPERATION_MODE = 0x9203;
    /** The DCG sensor mode; {@link #OP_MODE_ALONE} is what the operation mode gives by itself (14 bit as well). */
    public static final int DCG_MODE = 24, OP_MODE_ALONE = 23;
    /** The only frame rate of the DCG mode. */
    public static final int FPS = 60;
    /** Depth of the mode's samples. */
    public static final int BITS = 14;
    static final String KEY_MODE = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    static final String KEY_MODE_INDEX = "com.qti.sensorbps.mode_index";
    static final String KEY_TABLE = "org.quic.camera2.sensormode.info.SensorModeTable";
    /** Crop settings ({@code xiaomi_dcg_crop}). */
    static final int CROP_OFF = 0, CROP_EMPTY = 1, CROP_ALWAYS = 2;

    private static final CaptureRequest.Key<Integer> MODE = new CaptureRequest.Key<>(KEY_MODE, Integer.class);
    private static final CaptureResult.Key<int[]> RESULT_MODE = new CaptureResult.Key<>(KEY_MODE, int[].class);
    private static final CaptureResult.Key<int[]> RESULT_MODE_INDEX = new CaptureResult.Key<>(KEY_MODE_INDEX, int[].class);
    private static final CameraCharacteristics.Key<int[]> TABLE = new CameraCharacteristics.Key<>(KEY_TABLE, int[].class);

    private XiaomiDcg() {}

    // ---------------------------------------------------------------- device and camera

    /** Xiaomi 15 Ultra of any region: model 25010PN30* (G global, C China, I / E ...) or device «xuanyuan». */
    static boolean ultra15(@Nullable String manufacturer, @Nullable String model, @Nullable String device) {
        if (manufacturer == null || !manufacturer.equalsIgnoreCase("Xiaomi")) return false;
        final String m = model == null ? "" : model.toUpperCase(Locale.ROOT);
        return m.startsWith("25010PN30") || "xuanyuan".equalsIgnoreCase(device);
    }

    private static volatile Boolean sDevice;

    public static boolean device() {
        Boolean d = sDevice;
        if (d == null) sDevice = d = ultra15(Build.MANUFACTURER, Build.MODEL, Build.DEVICE);
        return d;
    }

    public static boolean enabled() {
        return device() && PreferenceKeys.scamDevSwitch("xiaomi_dcg", true);
    }

    /**
     * One mode of a Qualcomm SensorModeTable ({count, values per mode, then count x (width, height, fps, ...)}): {width, height,
     * fps}, null when the table has no such mode.
     */
    @Nullable
    static int[] modeEntry(@Nullable int[] table, int mode) {
        if (table == null || table.length < 2 || mode < 0) return null;
        final int count = table[0], stride = table[1];
        if (stride < 3 || mode >= count) return null;
        final int at = 2 + mode * stride;
        if (at + 2 >= table.length) return null;
        return new int[]{table[at], table[at + 1], table[at + 2]};
    }

    /** The camera's sensor mode table, null when it has none. */
    @Nullable
    static int[] table(@Nullable CameraCharacteristics c) {
        if (c == null) return null;
        try {
            return c.get(TABLE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The main sensor of the 15U: its table has the DCG mode, a 4096-wide one (the ultra-wide, the teles and the front have none). */
    static boolean mainTable(@Nullable int[] table, int mode) {
        final int[] e = modeEntry(table, mode);
        return e != null && e[0] >= 4000 && e[1] >= 2000 && e[1] <= e[0];
    }

    /** The switch is shown: a back camera of the 15U whose sensor mode table has the DCG mode (the main). */
    public static boolean available(@Nullable CameraCharacteristics c) {
        if (c == null || !enabled()) return false;
        final Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) return false;
        return mainTable(table(c), dcgMode());
    }

    // ---------------------------------------------------------------- the values (scam_dev overrides for the owner's tests)

    /** The session's operation mode: 0x9203 (scam_dev xiaomi_dcg_opmode; anything outside the vendor range = none). */
    public static int operationMode() {
        return CaptureController.sessionOperationMode(Math.round(PreferenceKeys.scamDevNumber("xiaomi_dcg_opmode", OPERATION_MODE)), false);
    }

    /** current_mode asked for: 24 (scam_dev xiaomi_dcg_mode; negative = none). */
    public static int dcgMode() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi_dcg_mode", DCG_MODE));
    }

    /** The frame rate: 60 (scam_dev xiaomi_dcg_fps; 0 = the viewfinder's own range). */
    public static int fps() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi_dcg_fps", FPS));
    }

    static int cropSetting() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi_dcg_crop", CROP_EMPTY));
    }

    /**
     * The AE target range for {@code fps} among the camera's ranges ({low, high} pairs): the fixed [fps, fps], else the range
     * ending at fps with the highest floor, else [fps, fps] as asked. Null for fps 0 (none).
     */
    @Nullable
    static int[] pickFps(@Nullable int[][] ranges, int fps) {
        if (fps <= 0) return null;
        int[] best = null;
        if (ranges != null) for (int[] r : ranges) {
            if (r == null || r.length < 2 || r[1] != fps) continue;
            if (r[0] == fps) return new int[]{fps, fps};
            if (best == null || r[0] > best[0]) best = r;
        }
        return best != null ? new int[]{best[0], best[1]} : new int[]{fps, fps};
    }

    /** The mode's AE target range on this camera (null: none to set). */
    @Nullable
    public static Range<Integer> fpsRange(@Nullable CameraCharacteristics c) {
        final Range<Integer>[] listed = c == null ? null : c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int[][] ranges = null;
        if (listed != null) {
            ranges = new int[listed.length][];
            for (int i = 0; i < listed.length; i++) ranges[i] = new int[]{listed[i].getLower(), listed[i].getUpper()};
        }
        final int[] r = pickFps(ranges, fps());
        return r == null ? null : new Range<>(r[0], r[1]);
    }

    // ---------------------------------------------------------------- the session and its requests

    /** Sets the DCG keys on the session parameters; the log text of what was set. */
    public static String applySession(CaptureRequest.Builder session, @Nullable CameraCharacteristics c) {
        if (session == null) return "off";
        final StringBuilder out = new StringBuilder("operation mode 0x" + Integer.toHexString(operationMode()));
        out.append(", ").append(applyRequest(session, c));
        final int mode = dcgMode();
        final int[] e = modeEntry(table(c), mode);
        if (e != null) out.append(" (table: mode ").append(mode).append(" = ").append(e[0]).append('x').append(e[1]).append(" at ").append(e[2]).append(" fps)");
        return out.toString();
    }

    /** current_mode and the AE range on a request (the viewfinder's, a shot's, the session's); the log text. */
    public static String applyRequest(CaptureRequest.Builder b, @Nullable CameraCharacteristics c) {
        if (b == null) return "";
        final StringBuilder out = new StringBuilder();
        final int mode = dcgMode();
        if (mode >= 0) {
            try {
                b.set(MODE, mode);
                out.append(KEY_MODE).append(' ').append(mode);
            } catch (RuntimeException e) {
                out.append(KEY_MODE).append(" refused: ").append(e.getMessage());
            }
        }
        final Range<Integer> fps = fpsRange(c);
        if (fps != null) {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            out.append(out.length() > 0 ? ", " : "").append("AE fps ").append(fps);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- results: the mode the HAL runs

    /** The sensor mode a result reports: current_mode, else com.qti.sensorbps.mode_index; null when neither is there. */
    @Nullable
    public static Integer reportedMode(@Nullable CaptureResult result) {
        if (result == null) return null;
        Integer v = first(result, RESULT_MODE);
        return v != null ? v : first(result, RESULT_MODE_INDEX);
    }

    @Nullable
    private static Integer first(CaptureResult result, CaptureResult.Key<int[]> key) {
        try {
            final int[] v = result.get(key);
            return v == null || v.length == 0 ? null : v[0];
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A 14-bit mode of this route: the DCG mode asked for, or 23, which the operation mode gives by itself. */
    static boolean fourteenBit(int reported, int asked) {
        return reported == asked || reported == OP_MODE_ALONE;
    }

    /**
     * The depth a result proves for a DCG session: 14 for a 14-bit mode, 0 for another one (the HAL ran its normal mode: the
     * declared levels again), -1 when the result reports no mode (the session's assumption stays).
     */
    static int bitsOfReport(@Nullable Integer reported, int asked) {
        if (reported == null) return -1;
        return fourteenBit(reported, asked) ? BITS : 0;
    }

    private static volatile String sLastReport;

    /** A viewfinder result of a DCG session: the mode the HAL runs, logged on change, and the session's depth follows it. */
    public static void onResult(@Nullable CaptureResult result, @Nullable CameraCharacteristics c, String cameraId) {
        if (result == null) return;
        final Integer current = first(result, RESULT_MODE), index = first(result, RESULT_MODE_INDEX);
        final Integer reported = current != null ? current : index;
        final int asked = dcgMode();
        final int bits = bitsOfReport(reported, asked);
        final Integer dynWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        final String state = "camera " + cameraId + ": current_mode " + current + ", mode_index " + index + " (asked " + asked
                + ")" + (bits == BITS ? " = 14-bit DCG route" : bits == 0 ? " = NOT the DCG mode" : "") + ", dynamic white " + dynWhite;
        if (state.equals(sLastReport)) return;
        sLastReport = state;
        Log.i("SCAM_CAPTURE", "Xiaomi DCG " + state);
        if (bits >= 0) com.particlesdevs.photoncamera.processing.RawLevels.noteKnownBits(
                com.particlesdevs.photoncamera.processing.RawLevels.staticBlack(c),
                com.particlesdevs.photoncamera.processing.RawLevels.staticWhite(c), bits,
                bits == BITS ? "Xiaomi DCG mode " + reported : "Xiaomi DCG asked, HAL mode " + reported);
    }

    /** A new session: the next report is logged again. */
    public static void newSession() {
        sLastReport = null;
    }

    // ---------------------------------------------------------------- the shot's frames: the mode's rows in a taller buffer

    /**
     * The rows a {@code w} x {@code h} frame holds beyond the {@code modeWidth} x {@code modeHeight} sensor mode, on the side where
     * they are empty: {left, top, right, bottom} (sensor px) to cut. With {@link #CROP_EMPTY} a band counts only when every one of
     * its rows is one value (zeros or a constant fill: a row of the image is never one value over thousands of samples); the
     * bottom is tried first (the 15U's place), then the top. {@link #CROP_ALWAYS} cuts the bottom band unchecked. Nothing when
     * the mode fills the frame, is wider or narrower than it, or the setting is off.
     */
    public static int[] padding(@Nullable ByteBuffer raw, int w, int h, int modeWidth, int modeHeight, int setting) {
        final int[] none = new int[4];
        if (setting == CROP_OFF || modeWidth != w || modeHeight <= 0 || modeHeight >= h || modeHeight < h / 2) return none;
        final int band = h - modeHeight;
        if (setting == CROP_ALWAYS) return new int[]{0, 0, 0, band};
        if (raw == null || raw.capacity() < (long) w * h * 2) return none;
        final ShortBuffer s = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        if (constantRows(s, w, modeHeight, band)) return new int[]{0, 0, 0, band};
        if (constantRows(s, w, 0, band)) return new int[]{0, band, 0, 0};
        return none;
    }

    /** Every row of {@code count} rows from {@code from} holds a single value. */
    static boolean constantRows(ShortBuffer s, int w, int from, int count) {
        for (int y = from; y < from + count; y++) {
            final int base = y * w;
            final short v = s.get(base);
            for (int x = 1; x < w; x++) if (s.get(base + x) != v) return false;
        }
        return true;
    }

    /**
     * The shot's cut for the DCG mode (ScamProcessor's RAW crop): the mode its result reports (or the one asked for while this
     * route runs, when the result reports none) against the frame; {left, top, right, bottom} sensor px, zeros for none. Only
     * on the 15U; CROP_ALWAYS only cuts while the DCG route runs (a forced cut of a normal shot would lose image rows).
     */
    public static int[] shotPadding(@Nullable CameraCharacteristics c, @Nullable CaptureResult result, @Nullable ByteBuffer raw, int w, int h) {
        if (!device() || c == null) return new int[4];
        final int[] table = table(c);
        final Integer reported = reportedMode(result);
        final int mode = reported != null ? reported : sSessionActive ? dcgMode() : -1;
        // any mode shorter than the buffer leaves its band empty: cut whatever the HAL runs (checked empty unless forced)
        final int[] e = modeEntry(table, mode);
        if (e == null) return new int[4];
        int setting = cropSetting();
        if (setting == CROP_ALWAYS && !sSessionActive) setting = CROP_EMPTY;
        final int[] sides = padding(raw, w, h, e[0], e[1], setting);
        if (e[1] < h && e[0] == w)
            Log.i("SCAM_HDR", "Xiaomi DCG: mode " + mode + " is " + e[0] + "x" + e[1] + " in a " + w + "x" + h + " frame: "
                    + (sides[1] + sides[3] > 0 ? "cut " + (sides[3] > 0 ? sides[3] + " rows at the bottom" : sides[1] + " rows at the top")
                    : setting == CROP_OFF ? "crop off (scam_dev xiaomi_dcg_crop 0)" : "no empty band found, frame kept whole"));
        return sides;
    }

    private static volatile boolean sSessionActive;
    /** The size of the mode the running DCG session asked for (0: none / not in the table). */
    private static volatile int sModeWidth, sModeHeight;

    /** The running session is (true) or is not a DCG session of this route, on the camera of {@code c}. */
    public static void sessionActive(boolean active, @Nullable CameraCharacteristics c) {
        final int[] e = active ? modeEntry(table(c), dcgMode()) : null;
        sModeWidth = e == null ? 0 : e[0];
        sModeHeight = e == null ? 0 : e[1];
        sSessionActive = active;
    }

    /** As above, keeping the mode's size (a refusal ends the session's route). */
    public static void sessionActive(boolean active) {
        if (!active) sModeWidth = sModeHeight = 0;
        sSessionActive = active;
    }

    /**
     * The rows of a {@code width} x {@code height} frame of the running DCG session that hold the image: the mode's height when the
     * buffer is taller (the 15U: 2560 of 3072, at the top), else all of them. The «16:9» crop (Wide169) takes its centred rows
     * out of these, not out of the empty band.
     */
    public static int imageRows(int width, int height) {
        return imageRows(width, height, sSessionActive ? sModeWidth : 0, sModeHeight, cropSetting());
    }

    static int imageRows(int width, int height, int modeWidth, int modeHeight, int setting) {
        if (setting == CROP_OFF || modeWidth != width || modeHeight <= 0 || modeHeight >= height || modeHeight < height / 2) return height;
        return modeHeight;
    }

    public static boolean sessionActive() {
        return sSessionActive;
    }
}
