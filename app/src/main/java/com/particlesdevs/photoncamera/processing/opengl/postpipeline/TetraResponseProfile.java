package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.nio.FloatBuffer;
import java.util.Arrays;

/** Robust scene-derived response estimate, NOT an EEPROM/crosstalk calibration. */
public final class TetraResponseProfile {
    private TetraResponseProfile() {}

    public static float[] estimate(FloatBuffer grid, int width, int height) {
        float[] gains = new float[64];
        Arrays.fill(gains, 1f);
        int tw = width / 4, th = height / 4, capacity = tw * th;
        if (tw < 8 || th < 8) return gains;
        // Each row is one independent 32x32 tile's normalized 16-site profile.
        float[][] profiles = new float[capacity][16];
        int[] zones = new int[capacity];
        for (int q = 0; q < 4; q++) {
            int count = 0;
            int[] zoneCounts = new int[4];
            for (int ty = 0; ty < th; ty++) for (int tx = 0; tx < tw; tx++) {
                float mean = 0;
                for (int k = 0; k < 16; k++) {
                    float value = grid.get(((ty * 4 + k / 4) * width + tx * 4 + k % 4) * 4 + q);
                    profiles[count][k] = value;
                    mean += value / 16f;
                }
                // Sums cover up to sixteen cells. Exclude dark/near-clipped tiles.
                if (!(mean > 0.32f && mean < 14f)) continue;
                boolean valid = true;
                for (int k = 0; k < 16; k++) {
                    profiles[count][k] /= mean;
                    if (!(profiles[count][k] > 0.75f && profiles[count][k] < 1.25f)) valid = false;
                }
                if (!valid) continue;
                int zone = (ty >= th / 2 ? 2 : 0) + (tx >= tw / 2 ? 1 : 0);
                zones[count] = zone;
                zoneCounts[zone]++;
                count++;
            }
            if (count < 64) continue;
            boolean supported = true;
            for (int n : zoneCounts) if (n < 8) supported = false;
            if (!supported) continue;
            float[] median = new float[16], work = new float[count];
            for (int k = 0; k < 16 && supported; k++) {
                for (int i = 0; i < count; i++) work[i] = profiles[i][k];
                median[k] = median(work, count);
                // A response pattern must repeat across all four spatial regions.
                // Reject the whole colour quadrant if scene structure disagrees.
                for (int zone = 0; zone < 4; zone++) {
                    int n = 0;
                    for (int i = 0; i < count; i++) if (zones[i] == zone) work[n++] = profiles[i][k];
                    if (Math.abs(median(work, n) - median[k]) > 0.03f) supported = false;
                }
            }
            if (!supported) continue;
            float mean = 0;
            for (float v : median) mean += v / 16f;
            for (int k = 0; k < 16; k++) {
                float gain = mean / median[k];
                if (!(gain >= 0.8f && gain <= 1.25f)) supported = false;
            }
            if (supported) for (int k = 0; k < 16; k++) gains[q * 16 + k] = mean / median[k];
        }
        return gains;
    }

    private static float median(float[] values, int count) {
        Arrays.sort(values, 0, count);
        return (values[(count - 1) / 2] + values[count / 2]) * 0.5f;
    }
}
