package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * P230: a Qualcomm sensor mode shorter than the RAW buffer it is written to. The DCG modes of the Snapdragon 8 Elite phones
 * are 16:10 windows of their 4:3 sensors - Xiaomi 15 Ultra 4096 x 2560, vivo X200 Ultra 4096 x 2632 (main, ultra-wide) /
 * 4080 x 2616 (tele) - and land at the top of the 4096 x 3072 RAW_SENSOR buffer: the rows below them are empty (MotionCam
 * community on the 15U: «normal 4096 x 3072 frames have 512 empty rows at the bottom»).
 * <p>
 * The mode is read from org.quic.camera2.sensormode.info.SensorModeTable ({count, values per mode, then width, height, fps, ...})
 * and the mode a result reports (org.codeaurora.qcamera3.sensor_meta_data.current_mode, else com.qti.sensorbps.mode_index).
 * During a DCG session the payload check samples only the mode's rows ({@link #noteResult}), the «16:9» crop takes its rows out
 * of them ({@link #imageRows}) and the shot's frames lose the band ({@link #shotPadding}, ScamProcessor's RAW crop) - only
 * when its rows are one value each, unless forced. scam_dev {@code dcg_band_crop}: 0 = keep the band, 1 = cut it when empty
 * (default), 2 = always cut the bottom band while a DCG route runs.
 */
public final class SensorModeBand {
    static final String KEY_MODE = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    static final String KEY_MODE_INDEX = "com.qti.sensorbps.mode_index";
    static final String KEY_TABLE = "org.quic.camera2.sensormode.info.SensorModeTable";
    /** Crop settings ({@code dcg_band_crop}). */
    static final int CROP_OFF = 0, CROP_EMPTY = 1, CROP_ALWAYS = 2;

    private static final CaptureResult.Key<int[]> RESULT_MODE = new CaptureResult.Key<>(KEY_MODE, int[].class);
    private static final CaptureResult.Key<int[]> RESULT_MODE_INDEX = new CaptureResult.Key<>(KEY_MODE_INDEX, int[].class);
    private static final CameraCharacteristics.Key<int[]> TABLE = new CameraCharacteristics.Key<>(KEY_TABLE, int[].class);

    private SensorModeBand() {}

    // ---------------------------------------------------------------- the table and the reported mode

    /** One mode of a SensorModeTable: {width, height, fps}, null when the table has no such mode. */
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

    /** current_mode of a result, null when it has none. */
    @Nullable
    static Integer currentMode(@Nullable CaptureResult result) {
        return first(result, RESULT_MODE);
    }

    /** com.qti.sensorbps.mode_index of a result, null when it has none. */
    @Nullable
    static Integer modeIndex(@Nullable CaptureResult result) {
        return first(result, RESULT_MODE_INDEX);
    }

    /** The sensor mode a result reports: current_mode, else com.qti.sensorbps.mode_index; null when neither is there. */
    @Nullable
    public static Integer reportedMode(@Nullable CaptureResult result) {
        final Integer v = currentMode(result);
        return v != null ? v : modeIndex(result);
    }

    @Nullable
    private static Integer first(@Nullable CaptureResult result, CaptureResult.Key<int[]> key) {
        if (result == null) return null;
        try {
            final int[] v = result.get(key);
            return v == null || v.length == 0 ? null : v[0];
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int cropSetting() {
        return Math.round(PreferenceKeys.scamDevNumber("dcg_band_crop", CROP_EMPTY));
    }

    /** The phones whose DCG modes are shorter than their RAW buffer: the Xiaomi 15 Ultra and the vivo X200 Ultra. */
    static boolean device() {
        return XiaomiDcg.device() || VivoQcomModes.device();
    }

    // ---------------------------------------------------------------- the band

    /** The image rows of a {@code height}-row buffer in a mode {@code modeWidth} x {@code modeHeight}: the mode's when it is shorter. */
    static int imageRows(int width, int height, int modeWidth, int modeHeight, int setting) {
        if (setting == CROP_OFF || modeWidth != width || modeHeight <= 0 || modeHeight >= height || modeHeight < height / 2) return height;
        return modeHeight;
    }

    /**
     * The rows a {@code w} x {@code h} frame holds beyond the {@code modeWidth} x {@code modeHeight} sensor mode, on the side where
     * they are empty: {left, top, right, bottom} (sensor px) to cut. With {@link #CROP_EMPTY} a band counts only when every one of
     * its rows is one value (zeros or a constant fill: a row of the image is never one value over thousands of samples); the
     * bottom is tried first (where the 15U puts it), then the top. {@link #CROP_ALWAYS} cuts the bottom band unchecked. Nothing
     * when the mode fills the frame, is wider or narrower than it, or the setting is off.
     */
    public static int[] padding(@Nullable ByteBuffer raw, int w, int h, int modeWidth, int modeHeight, int setting) {
        final int[] none = new int[4];
        if (imageRows(w, h, modeWidth, modeHeight, setting) == h) return none;
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

    // ---------------------------------------------------------------- the running DCG session

    private static volatile boolean sActive;
    /** The size of the mode the running DCG session asked for (0: none or not in the table). */
    private static volatile int sModeWidth, sModeHeight, sAskedMode = -1;
    private static volatile String sLastHint;

    /** A new session: a DCG session ({@code dcg}) of the camera of {@code c} that asks for {@code askedMode} (-1: none asked). */
    public static void sessionStart(boolean dcg, @Nullable CameraCharacteristics c, int askedMode) {
        final int[] e = dcg ? modeEntry(table(c), askedMode) : null;
        sModeWidth = e == null ? 0 : e[0];
        sModeHeight = e == null ? 0 : e[1];
        sAskedMode = dcg ? askedMode : -1;
        sActive = dcg;
        sLastHint = null;
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.setImageRows(0);
    }

    /** The DCG route of the running session ended (refused). */
    public static void sessionEnd() {
        sActive = false;
        sModeWidth = sModeHeight = 0;
        sAskedMode = -1;
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.setImageRows(0);
    }

    public static boolean sessionActive() {
        return sActive;
    }

    /**
     * The rows of a {@code width} x {@code height} frame of the running DCG session that hold the image: the asked mode's height
     * when the buffer is taller (the 15U: 2560 of 3072, at the top), else all of them. The «16:9» crop (Wide169) takes its
     * centred rows out of these, not out of the empty band.
     */
    public static int imageRows(int width, int height) {
        return imageRows(width, height, sActive ? sModeWidth : 0, sModeHeight, cropSetting());
    }

    /**
     * A viewfinder result of a DCG session: the mode the HAL runs against the {@code rawWidth} x {@code rawHeight} RAW stream. A
     * shorter mode makes the payload check sample its rows only (the empty band is no packed tail and no black floor); logged on
     * change.
     */
    public static void noteResult(@Nullable CameraCharacteristics c, @Nullable CaptureResult result, int rawWidth, int rawHeight,
                                  String cameraId) {
        if (!sActive || result == null) return;
        final Integer reported = reportedMode(result);
        if (reported == null) return;
        final int[] e = modeEntry(table(c), reported);
        final int rows = e == null ? rawHeight : imageRows(rawWidth, rawHeight, e[0], e[1], cropSetting());
        final String hint = cameraId + "/" + reported + "/" + rows;
        if (hint.equals(sLastHint)) return;
        sLastHint = hint;
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.setImageRows(rows < rawHeight ? rows : 0);
        Log.i("SCAM_CAPTURE", "DCG camera " + cameraId + ": HAL mode " + reported
                + (e == null ? " (not in the table)" : " = " + e[0] + "x" + e[1] + " at " + e[2] + " fps") + " in the " + rawWidth + "x"
                + rawHeight + " RAW stream" + (rows < rawHeight ? ": rows " + rows + "-" + rawHeight + " are its empty band" : ""));
    }

    /**
     * The shot's cut (ScamProcessor's RAW crop): the mode its result reports (or the one the DCG session asked for, when the result
     * reports none) against the frame; {left, top, right, bottom} sensor px, zeros for none. Any mode shorter than the buffer
     * leaves its band empty, so whatever the HAL runs is cut, checked empty; CROP_ALWAYS cuts unchecked only while a DCG route
     * runs (a forced cut of a normal shot would lose image rows).
     */
    public static int[] shotPadding(@Nullable CameraCharacteristics c, @Nullable CaptureResult result, @Nullable ByteBuffer raw, int w, int h) {
        if (!device() || c == null) return new int[4];
        final Integer reported = reportedMode(result);
        final int mode = reported != null ? reported : sActive ? sAskedMode : -1;
        final int[] e = modeEntry(table(c), mode);
        if (e == null) return new int[4];
        int setting = cropSetting();
        if (setting == CROP_ALWAYS && !sActive) setting = CROP_EMPTY;
        final int[] sides = padding(raw, w, h, e[0], e[1], setting);
        if (e[1] < h && e[0] == w)
            Log.i("SCAM_HDR", "DCG band: mode " + mode + " is " + e[0] + "x" + e[1] + " in a " + w + "x" + h + " frame: "
                    + (sides[1] + sides[3] > 0 ? "cut " + (sides[3] > 0 ? sides[3] + " rows at the bottom" : sides[1] + " rows at the top")
                    : setting == CROP_OFF ? "crop off (scam_dev dcg_band_crop 0)" : "no empty band found, frame kept whole"));
        return sides;
    }
}
