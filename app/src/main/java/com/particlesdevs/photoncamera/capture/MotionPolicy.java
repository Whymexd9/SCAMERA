package com.particlesdevs.photoncamera.capture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MotionPolicy {
    private MotionPolicy() {}

    public static long windowNs(double pixelsPerSecond) {
        if (!Double.isFinite(pixelsPerSecond) || pixelsPerSecond <= 0) return 250_000_000L;
        return Math.max(16_000_000L, Math.min(250_000_000L, Math.round(64e9 / pixelsPerSecond)));
    }

    public static long observedAtNs(long arrivalNs, long frameTimestamp, long latestSensorTimestamp) {
        // Only subtract timestamps from the sensor's own clock, never its absolute value from nanoTime.
        long age = latestSensorTimestamp > frameTimestamp ? latestSensorTimestamp - frameTimestamp : 0;
        return arrivalNs - Math.min(age, 2_000_000_000L);
    }

    public static List<Integer> blurredDonors(float[] scores, int base, float ratio) {
        List<Integer> out = new ArrayList<>();
        if (!(ratio > 0) || base < 0 || base >= scores.length) return out;
        float[] positive = new float[scores.length];
        int count = 0;
        for (float s : scores) if (Float.isFinite(s) && s > 0) positive[count++] = s;
        if (count < 1) return out;
        Arrays.sort(positive, 0, count);
        float median = (count & 1) == 1 ? positive[count / 2]
                : (positive[count / 2 - 1] + positive[count / 2]) * 0.5f;
        float reference = Float.isFinite(scores[base]) ? Math.max(median, scores[base]) : median;
        for (int i = 0; i < scores.length; i++)
            if (i != base && Float.isFinite(scores[i]) && scores[i] < ratio * reference) out.add(i);
        return out;
    }
}
