package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureResult;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * P118 «Частота мерцания» = «Авто»: the lamp flicker of the place (twice the mains frequency), for the long Shasta frames of the
 * hybrid (HybridPlan.snapAntibanding). Latched for the app's life from the first preview result whose STATISTICS_SCENE_FLICKER
 * says 50 / 60 Hz. Without it the AE's anti-banding decides: {@link #VOTES} AE shutters in a row that are whole multiples of one
 * flicker period and not of the other, at two different multiples at least (a shutter held at one value proves nothing). With no
 * evidence at all the region's mains (60 Hz in most of the Americas, Taiwan, South Korea, the Philippines, Saudi Arabia; 50 Hz
 * elsewhere, Japan included).
 */
public final class MainsFlicker {
    private MainsFlicker() {}

    static final long PERIOD_100HZ_NS = 10_000_000L, PERIOD_120HZ_NS = 8_333_333L;
    static final int VOTES = 6;
    private static final Set<String> SIXTY_HZ = new HashSet<>(Arrays.asList(
            "US", "CA", "MX", "GT", "BZ", "SV", "HN", "NI", "CR", "PA", "CU", "DO", "PR", "BS", "KN", "LC", "VC", "AG", "TT",
            "BM", "KY", "TC", "VG", "VI", "BR", "CO", "VE", "EC", "PE", "SR", "GY", "TW", "KR", "PH", "SA", "LR",
            "GU", "AS", "MP", "FM", "PW", "MH"));

    private static volatile int latched; // flicker in Hz (100 / 120), 0 = not known yet
    private static String source = "";
    private static int votes, voteHz;
    private static long firstMultiple;
    private static boolean twoMultiples;

    /** Every preview result (ParamController.onPreviewResult); cheap once latched. */
    public static void observe(CaptureResult result) {
        if (latched != 0 || result == null) return;
        Integer scene = null, aeMode = null;
        Long ns = null;
        try {
            scene = result.get(CaptureResult.STATISTICS_SCENE_FLICKER);
            ns = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            aeMode = result.get(CaptureResult.CONTROL_AE_MODE);
        } catch (RuntimeException ignored) {
        }
        observe(scene, ns, aeMode != null && aeMode != CaptureResult.CONTROL_AE_MODE_OFF);
    }

    static synchronized void observe(Integer scene, Long ns, boolean aeOn) {
        if (latched != 0) return;
        if (scene != null && scene == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) { latch(100, "camera"); return; }
        if (scene != null && scene == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) { latch(120, "camera"); return; }
        if (!aeOn || ns == null || ns < PERIOD_120HZ_NS) return; // a shutter shorter than a period says nothing
        final long k100 = multiple(ns, PERIOD_100HZ_NS), k120 = multiple(ns, PERIOD_120HZ_NS);
        if ((k100 > 0) == (k120 > 0)) return; // both (50 ms, 100 ms) or neither: no vote, no reset
        final int hz = k100 > 0 ? 100 : 120;
        final long k = Math.max(k100, k120);
        if (hz != voteHz) { voteHz = hz; votes = 0; firstMultiple = k; twoMultiples = false; }
        votes++;
        if (k != firstMultiple) twoMultiples = true;
        if (votes >= VOTES && twoMultiples) latch(hz, "AE shutters");
    }

    /** k when ns is k whole periods (0.5 %), else 0. */
    static long multiple(long ns, long period) {
        final long k = Math.round((double) ns / period);
        return k >= 1 && Math.abs(ns - k * period) <= period * 0.005 ? k : 0;
    }

    private static void latch(int hz, String why) {
        latched = hz;
        source = why;
        com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "mains flicker " + hz + " Hz (" + why + ")");
    }

    /** The flicker to snap to: the latched one, else the region's. */
    public static int hz() {
        final int h = latched;
        return h != 0 ? h : regionHz(Locale.getDefault().getCountry());
    }

    /** Where {@link #hz()} comes from (log lines). */
    public static String source() {
        return latched != 0 ? source : "region " + Locale.getDefault().getCountry();
    }

    static int regionHz(String country) {
        return country != null && SIXTY_HZ.contains(country.toUpperCase(Locale.ROOT)) ? 120 : 100;
    }

    static synchronized void resetForTest() {
        latched = 0;
        source = "";
        votes = voteHz = 0;
        firstMultiple = 0;
        twoMultiples = false;
    }
}
