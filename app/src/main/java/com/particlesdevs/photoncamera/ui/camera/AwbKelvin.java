package com.particlesdevs.photoncamera.ui.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.RggbChannelVector;

import com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance;

/**
 * The colour temperature the camera's AWB is at, for the white-balance chip in auto: the kelvin of the manual
 * white-balance list (2000-10000 K, 100 K steps) whose gains have the red / blue ratio nearest to the AWB result's gains.
 * The same mapping the manual white balance uses, run backwards, so the ruler's first touch starts where AWB is. Unknown
 * (0) when the camera has no manual white balance or the result has no gains.
 */
public final class AwbKelvin {
    public static final int MIN = 2000, MAX = 10000, STEP = 100;
    /** ln(red / blue) of the manual gains per kelvin step; null when unknown. */
    private double[] table;

    public void setCharacteristics(CameraCharacteristics characteristics) {
        table = null;
        try {
            if (characteristics == null || !ManualWhiteBalance.isSupported(characteristics)) return;
            double[] t = new double[(MAX - MIN) / STEP + 1];
            for (int i = 0; i < t.length; i++) {
                RggbChannelVector g = ManualWhiteBalance.gains(characteristics, MIN + i * STEP);
                t[i] = Math.log(g.getRed() / g.getBlue());
            }
            table = t;
        } catch (RuntimeException e) {
            table = null; // no usable calibration: the chip says «Авто»
        }
    }

    /** The AWB colour temperature for the result's gains, 0 when unknown. */
    public int kelvin(RggbChannelVector gains) {
        if (table == null || gains == null || gains.getRed() <= 0 || gains.getBlue() <= 0) return 0;
        return nearest(table, Math.log(gains.getRed() / gains.getBlue()));
    }

    /** The kelvin whose table entry is nearest to {@code logRatio}. */
    static int nearest(double[] table, double logRatio) {
        int best = 0;
        for (int i = 1; i < table.length; i++)
            if (Math.abs(table[i] - logRatio) < Math.abs(table[best] - logRatio)) best = i;
        return MIN + best * STEP;
    }
}
