package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Locale;

/**
 * P212h (A7 = M14 phase 0, log only): an audit of the vivo HAL AE with our RAW meter. On vivo the HAL runs the AE on the mosaic
 * (aeState 2, it follows the EV: reports/pd2454-30831-main-fullres-ae-20261016/offset.txt) and nothing checks it - the own AE
 * (NativeRawExposure) and its log exist for the OPPO only. Every {@link #EVERY}th viewfinder RAW of a vivo session - the native FULLRES
 * mode's mosaic, an ISZ module's mosaic, P208's still-only viewfinder and the plain Bayer stream for the comparison - is metered by
 * the OPPO own AE's meter (NativeRawExposure.meter2: the photo's crop, centre weight, shading, white balance, per-site clip) and
 * one line a second (SCAM_AE) puts its median p50, p98, peak P and clipped share C with the scene's BV = log2(p50 / (t ISO / 100))
 * beside the HAL's shutter, ISO, aeState and EV, with the median BV of the second's samples. Offline, FULLRES against plain at the
 * same BV shows whether the HAL exposes the mosaic modes alike; nothing on the capture or the photo changes.
 * scam_dev.txt "vivo_ae_audit 0": none.
 */
final class VivoAeAudit {
    /** Every EVERY-th frame is metered (about 1 ms on the camera thread), one line every LOG_MS. */
    static final int EVERY = 8;
    static final long LOG_MS = 1000L;
    private static final int MAX_SAMPLES = 64;

    private int frames;
    private long loggedMs = Long.MIN_VALUE / 2;
    private final double[] bvs = new double[MAX_SAMPLES];
    private int samples;

    /** A vivo phone with the audit on. */
    static boolean wanted() {
        return "vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                && com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("vivo_ae_audit", true);
    }

    /** True for every EVERY-th call (the first one included). */
    boolean due() {
        final boolean due = frames % EVERY == 0;
        frames = (frames + 1) % EVERY;
        return due;
    }

    /** The scene's brightness value of a median {@code p50} (white 1) at {@code exposureNs} and {@code iso}; NaN when unknown. */
    static double bv(float p50, long exposureNs, int iso) {
        if (!(p50 > 0f) || exposureNs <= 0 || iso <= 0) return Double.NaN;
        return Math.log(p50 / (exposureNs / 1e9 * iso / 100.0)) / Math.log(2.0);
    }

    /** The median of the first {@code n} values (NaN for none). */
    static double median(double[] values, int n) {
        if (n <= 0) return Double.NaN;
        final double[] v = Arrays.copyOf(values, n);
        Arrays.sort(v);
        return (n & 1) == 1 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
    }

    /**
     * One metered frame; the log line when a second has passed since the last one (it carries this frame and the median BV of the
     * samples since), else null.
     */
    @Nullable
    String add(String camera, String kind, int block, float p50, float p98, float peak, float clip, boolean shaded, long exposureNs,
               int iso, @Nullable Integer aeState, float ev, long nowMs) {
        final double bv = bv(p50, exposureNs, iso);
        if (!Double.isNaN(bv) && samples < MAX_SAMPLES) bvs[samples++] = bv;
        if (nowMs - loggedMs < LOG_MS) return null;
        loggedMs = nowMs;
        final double median = median(bvs, samples);
        final int n = samples;
        samples = 0;
        return String.format(Locale.ROOT,
                "vivo AE audit camera %s %s block %d: p50 %.5f p98 %.3f P %.3f C %.4f%s BV %.2f | HAL %.3f ms ISO %d aeState %s ev %+.2f | %d samples, BV median %.2f",
                camera, kind, block, p50, p98, peak, clip, shaded ? " lsc" : "", bv, exposureNs / 1e6, iso,
                aeState == null ? "-" : String.valueOf(aeState), ev, n, median);
    }
}
