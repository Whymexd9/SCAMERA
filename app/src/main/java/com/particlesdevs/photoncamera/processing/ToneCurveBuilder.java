package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.Math2;

/**
 * The exposure response as a 1D curve, shared by the shot pipeline and the live
 * viewfinder so both place shadows and highlights the same way.
 *
 * <p>The response itself is small: a gamma lift, a gain, an extended Reinhard
 * with a white point, a second gamma lift, and a soft shoulder. Everything
 * expensive in AutoExposureCurve is the measurement that produces the gain and
 * the white point, not the curve built from them - which is why the viewfinder
 * can afford to rebuild this many times a second off a downsampled histogram
 * while the full node runs once per shot.
 */
public final class ToneCurveBuilder {

    private ToneCurveBuilder() {}

    /**
     * @param mpy          gain applied before the Reinhard compression
     * @param whiteEff     effective white point of the Reinhard term
     * @param applyGammaMix blend between linear and square-root working space
     * @param knee         shoulder start; 1.0 disables the shoulder
     */
    public static float[] build(int size, float mpy, float whiteEff,
                                float applyGammaMix, float knee) {
        float[] curve = new float[size];
        for (int i = 0; i < size; i++) {
            float x = i / (size - 1.0f);
            float g = Math2.mix(x, (float) Math.sqrt(x), applyGammaMix);
            float r = g * mpy;
            r = r * (1.0f + r / (whiteEff * whiteEff)) / (1.0f + r);
            float o = Math2.mix(r, r * r, applyGammaMix);
            if (knee < 1.0f) o = softShoulder(o, knee);
            curve[i] = Math.min(Math.max(o, 0.0f), 1.0f);
        }
        return curve;
    }

    /** Identical to AutoExposureCurve.softShoulder. */
    public static float softShoulder(float x, float knee) {
        if (x <= knee) return x;
        float t = x - knee;
        float s = 1.0f - knee;
        if (s <= 1.0e-4f) return Math.min(x, 1.0f);
        float g = t / s;
        return knee + s * g * g;
    }

    /**
     * Gain that puts the mean of a display-encoded histogram at the target.
     *
     * @param hist   bin counts of the display-encoded luminance
     * @param target mean the response should land on, in [0,1]
     * @param gainMax upper clamp
     */
    public static float gainFromHistogram(int[] hist, float target, float gainMax) {
        double sum = 0.0;
        long cnt = 0;
        for (int i = 0; i < hist.length; i++) {
            sum += (double) hist[i] * (i / (hist.length - 1.0));
            cnt += hist[i];
        }
        if (cnt == 0) return 1.0f;
        double avg = sum / cnt;
        if (!(avg > 1.0e-5)) return gainMax;
        // The curve is built in the same encoded domain, so the gain that maps
        // the measured mean onto the target is the ratio of the two, corrected
        // for the Reinhard compression that follows by the same normalisation
        // AutoExposureCurve uses.
        float mpy = (float) (target / avg);
        return Math.min(Math.max(mpy, 0.05f), gainMax);
    }

    /**
     * Reinhard normalisation: the gain alone overshoots, because the compression
     * that follows pulls the midtones back down. AutoExposureCurve measures the
     * ratio of the linear response to the compressed one over the whole range
     * and scales the gain by it; the same correction is needed here or the
     * viewfinder sits darker than the shot.
     */
    public static float normalizeGain(float mpy, int bins) {
        float normL = 0.0f;
        float normR = 0.0f;
        for (int i = 0; i < bins; i++) {
            float val = ((float) i / (bins - 1.0f)) * mpy;
            normL += Math.min(val, 1.0f);
            normR += (val * (1.0f + (val / (mpy * mpy)))) / (1.0f + val);
        }
        if (!(normR > 0.0f) || !Float.isFinite(normR) || !Float.isFinite(normL)) return mpy;
        return mpy * normL / normR;
    }
}
