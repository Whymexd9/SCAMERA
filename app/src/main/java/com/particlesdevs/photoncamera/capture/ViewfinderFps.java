package com.particlesdevs.photoncamera.capture;

import android.util.Range;

import androidx.annotation.Nullable;

/**
 * P224 (owner, 2026-10-18: «Минимальный фпс для видоискателя ограничить на 20фпс чтобы при плохом освещении не было слишком
 * медленного видоискателя»): the viewfinder's automatic AE range never goes below 20 fps - in the dark the camera adds gain instead
 * of stretching the frame past 1/20 s. Only the automatic ranges are raised (the normal one, vivo's stock-profile one, the FULLRES /
 * ISZ sessions'); a fixed 24 / 30 / 60 fps choice, «Движение» and DCG keep theirs, stills keep their own exposures. scam_dev
 * «vf_min_fps» changes the floor (0: the camera's own lowest rate, as before).
 * <p>
 * Owner, 2026-10-19 («Убери тогда ограничение по фпс, но на виво без него ночью фпс у видоискателя падает вплоть до примерно
 * 5ти фпс»): the floor is vivo's only (its night viewfinder fell to ~5 fps); every other phone keeps the camera's own range
 * (the OPPO's [20, 30] was not one of its listed ranges).
 */
public final class ViewfinderFps {
    public static final int DEFAULT_FLOOR = 20;

    private ViewfinderFps() {}

    /** The floor without a scam_dev line: {@link #DEFAULT_FLOOR} on vivo, none elsewhere. */
    static int defaultFloor(@Nullable String manufacturer) {
        return manufacturer != null && manufacturer.trim().equalsIgnoreCase("vivo") ? DEFAULT_FLOOR : 0;
    }

    /** The floor from scam_dev.txt («vf_min_fps»), else {@link #defaultFloor} of this phone; 0 = none. */
    public static int floor() {
        int def = defaultFloor(android.os.Build.MANUFACTURER);
        float v = com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("vf_min_fps", def);
        return Float.isNaN(v) ? def : Math.max(0, Math.round(v));
    }

    /** {@link #raise(Range, int, double)} for a sensor mode of unknown speed. */
    @Nullable
    public static Range<Integer> raise(@Nullable Range<Integer> range, int floor) {
        return raise(range, floor, 0);
    }

    /**
     * {@code range} with its lower bound raised to {@code floor}: [7, 30] -> [20, 30]. Where the camera cannot reach the floor
     * (owner, 2026-10-18: «пускай использует максимум сколько возможно настолько близко к 20фпс насколько можно») it goes as close
     * as it can: a sensor mode of {@code maxFps} under the floor (0: not known) -> its whole frames a second ([15, 30] for a
     * 15 fps mode); a range whose top is at or under the floor -> one under its top ([10, 15] -> [14, 15]), never a fixed rate
     * (a fixed rate gave empty FULLRES RAW frames on the PD2454). A range already that high stays; null stays null.
     */
    @Nullable
    public static Range<Integer> raise(@Nullable Range<Integer> range, int floor, double maxFps) {
        if (range == null || floor <= 0) return range;
        int target = floor;
        if (maxFps > 0) target = Math.min(target, (int) Math.floor(maxFps + 1e-3));
        if (range.getUpper() > range.getLower()) target = Math.min(target, range.getUpper() - 1);
        if (target <= range.getLower()) return range;
        return new Range<>(target, range.getUpper());
    }

    /** {@link #raise(Range, int, double)} at the configured floor. */
    @Nullable
    public static Range<Integer> raise(@Nullable Range<Integer> range, double maxFps) {
        return raise(range, floor(), maxFps);
    }

    /** {@link #raise(Range, int)} at the configured floor. */
    @Nullable
    public static Range<Integer> raise(@Nullable Range<Integer> range) {
        return raise(range, floor());
    }

    /**
     * The fastest rate of a RAW stream of this size and format (its minimum frame duration in the configuration map, the maximum
     * resolution map when the session uses it); 0 when the map does not say.
     */
    public static double maxFps(@Nullable android.hardware.camera2.CameraCharacteristics c, int format, int width, int height,
                                boolean maximumResolution) {
        if (c == null) return 0;
        try {
            android.hardware.camera2.params.StreamConfigurationMap map = null;
            if (maximumResolution && android.os.Build.VERSION.SDK_INT >= 31)
                map = c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            if (map == null) map = c.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return 0;
            long ns = map.getOutputMinFrameDuration(format, new android.util.Size(width, height));
            return ns > 0 ? 1e9 / ns : 0;
        } catch (RuntimeException e) {
            return 0;   // a size the map does not list
        }
    }
}
