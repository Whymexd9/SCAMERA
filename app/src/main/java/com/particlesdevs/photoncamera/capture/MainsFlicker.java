package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureResult;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P118 «Частота мерцания» = «Авто»: the lamp flicker of the place (twice the mains frequency), for the long Shasta frames of the
 * hybrid (HybridPlan.snapAntibanding) and the OPPO native AE (NativeRawExposure.flicker). Latched for the app's life from
 * {@link #REPORTS} preview results in a row whose STATISTICS_SCENE_FLICKER says the same 50 / 60 Hz. Without it the AE's
 * anti-banding decides: {@link #VOTES} AE shutters in a row that are whole multiples of one flicker period and not of the other,
 * at two different multiples at least (a shutter held at one value proves nothing). With no evidence at all the region's mains
 * (60 Hz in most of the Americas, Taiwan, South Korea, the Philippines, Saudi Arabia; 50 Hz elsewhere, Japan included) - and
 * {@link #known()} false, so the OPPO native AE bands least at both frequencies.
 * <p>
 * P212d (M4, owner's X7 Ultra 2026-10-17: stripes at 23:00 after a 120 Hz latch; over 31 starts in one place the latch said 100 Hz
 * 20 times and 120 Hz 11 times): the PHY110's report is a placeholder, not a detection - 2 (60 Hz) on frame 0, 1 (50 Hz) after,
 * org.quic.camera.afdData.IsFlickerDetected [0] on every result of every mode, the plain bin12 one included - and the latch fired on
 * the first result of a native session (18 of 31 latches). A report now counts only from an AE-on result of a plain session
 * after its first {@link #SKIP_RESULTS} results, on an OPPO phone only with IsFlickerDetected 1 on the same result, and only
 * {@link #REPORTS} agreeing ones in a row latch. The strongest evidence is the merge's own: the row profile of an ultrashort frame
 * against the base (RowFlicker, the worker's P197 row check) - {@link #SHOT_VOTES} shots in a row voting alike latch and override
 * a latch from the report or the AE.
 */
public final class MainsFlicker {
    private MainsFlicker() {}

    static final long PERIOD_100HZ_NS = 10_000_000L, PERIOD_120HZ_NS = 8_333_333L;
    static final int VOTES = 6;
    /** P212d: agreeing scene-flicker reports in a row that latch; results of a session whose report is never read; shot votes. */
    static final int REPORTS = 8, SKIP_RESULTS = 5, SHOT_VOTES = 2;
    /** P212d: the Qualcomm auto flicker detection's own verdict (int or int[]; looked up by name, absent on most phones). */
    static final String AFD_KEY = "org.quic.camera.afdData.IsFlickerDetected";
    private static final Set<String> SIXTY_HZ = new HashSet<>(Arrays.asList(
            "US", "CA", "MX", "GT", "BZ", "SV", "HN", "NI", "CR", "PA", "CU", "DO", "PR", "BS", "KN", "LC", "VC", "AG", "TT",
            "BM", "KY", "TC", "VG", "VI", "BR", "CO", "VE", "EC", "PE", "SR", "GY", "TW", "KR", "PH", "SA", "LR",
            "GU", "AS", "MP", "FM", "PW", "MH"));

    private static volatile int latched; // flicker in Hz (100 / 120), 0 = not known yet
    private static String source = "";
    private static int votes, voteHz;
    private static long firstMultiple;
    private static boolean twoMultiples;
    /** P212d: the report run (Hz, length); results of the current session; a native session; the session's report note logged. */
    private static int reportHz, reports, sessionResults;
    private static volatile boolean nativeSession; // read unlocked by observe(CaptureResult)
    private static boolean noted;
    /** P212d: the shot votes of the row profile (Hz, run length); the latch came from them. */
    private static int shotHz, shotVotes;
    private static boolean evidence;
    /** P212d: the AFD key of this camera HAL once found (the same Key object reads every later result); scans that missed it. */
    private static volatile CaptureResult.Key<?> afdKey;
    private static volatile int afdMisses; // the camera thread only (observe, newSession)

    /**
     * P212d: a capture session was configured (CaptureController.onConfigured): its first {@link #SKIP_RESULTS} results are not read,
     * the report run starts over; a native session (the OPPO native mode, a vivo native route with its own RAW viewfinder) is never
     * read - its HAL has no statistics there.
     */
    public static synchronized void newSession(boolean nativeRoute) {
        sessionResults = 0;
        nativeSession = nativeRoute;
        reportHz = reports = 0;
        noted = false;
        afdMisses = 0;
    }

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
        final boolean oppo = OppoNativeFullRes.brand(), oppoNative = OppoNativeFullRes.running();
        // the AFD verdict is read only where it decides: an OPPO phone's 50 / 60 Hz report in a plain session
        final Integer afd = oppo && !oppoNative && !nativeSession && scene != null
                && (scene == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ || scene == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ)
                ? afd(result) : null;
        observe(scene, ns, aeMode != null && aeMode != CaptureResult.CONTROL_AE_MODE_OFF, afd, oppo, oppoNative);
    }

    /** A result of a plain session of a phone without the AFD rule (the tests of the P118 rules). */
    static void observe(Integer scene, Long ns, boolean aeOn) {
        observe(scene, ns, aeOn, null, false, false);
    }

    /**
     * P212d: one preview result: its scene-flicker report, its exposure, its AE mode, the AFD verdict on it (null: none read), an
     * OPPO phone, an OPPO native session running.
     */
    static synchronized void observe(Integer scene, Long ns, boolean aeOn, Integer afd, boolean oppo, boolean oppoNative) {
        if (latched != 0) return;
        sessionResults++;
        report(scene, aeOn, afd, oppo, oppoNative);
        if (latched != 0) return;
        if (!aeOn || ns == null || ns < PERIOD_120HZ_NS) return; // a shutter shorter than a period says nothing
        final long k100 = multiple(ns, PERIOD_100HZ_NS), k120 = multiple(ns, PERIOD_120HZ_NS);
        if ((k100 > 0) == (k120 > 0)) return; // both (50 ms, 100 ms) or neither: no vote, no reset
        final int hz = k100 > 0 ? 100 : 120;
        final long k = Math.max(k100, k120);
        if (hz != voteHz) { voteHz = hz; votes = 0; firstMultiple = k; twoMultiples = false; }
        votes++;
        if (k != firstMultiple) twoMultiples = true;
        if (votes >= VOTES && twoMultiples) latch(hz, "AE shutters", false);
    }

    /** P212d: the scene-flicker report of one result, where it counts (see the class comment); REPORTS in a row latch. */
    private static void report(Integer scene, boolean aeOn, Integer afd, boolean oppo, boolean oppoNative) {
        final int hz = scene == null ? 0 : scene == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ ? 100
                : scene == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ ? 120 : 0;
        if (hz == 0) {
            if (scene != null) reportHz = reports = 0; // NONE (or a value outside the enum) breaks the run
            return;
        }
        // not read at all (no run broken): the user's exposure, a native mode's HAL without statistics, a session's first results
        // (the PHY110's frame 0 says 60 Hz, the frames after it 50 Hz, in every mode)
        final String skipped = !aeOn ? "AE off" : nativeSession || oppoNative ? "native session" : null;
        if (skipped == null && sessionResults <= SKIP_RESULTS) return;
        // an OPPO phone's report counts only with its detector's own verdict on the same result; a 0 is a verdict: no flicker
        final String refused = skipped != null || !oppo || (afd != null && afd == 1) ? null
                : "IsFlickerDetected " + (afd == null ? "absent" : String.valueOf(afd));
        if (skipped != null || refused != null) {
            if (refused != null) reportHz = reports = 0;
            if (!noted) {
                noted = true;
                com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "mains flicker: the camera's " + (hz / 2)
                        + " Hz report not taken (" + (skipped != null ? skipped : refused) + ")");
            }
            return;
        }
        if (hz != reportHz) { reportHz = hz; reports = 0; }
        if (++reports >= REPORTS) latch(hz, "camera, " + reports + " reports", false);
    }

    /**
     * P212d: the AFD verdict on a result (the key looked up by name in result.getKeys() once, then read directly; int, int[] or a
     * byte of either), null when the HAL has none: a phone without it is never assumed to have it.
     */
    @SuppressWarnings("unchecked")
    static Integer afd(CaptureResult result) {
        CaptureResult.Key<?> key = afdKey;
        try {
            if (key == null) {
                if (afdMisses >= 3) return null; // three scans of this session found none: not on this HAL
                final List<CaptureResult.Key<?>> keys = result.getKeys();
                if (keys != null) for (CaptureResult.Key<?> k : keys) {
                    if (k != null && AFD_KEY.equals(k.getName())) { key = k; break; }
                }
                if (key == null) {
                    afdMisses++;
                    return null;
                }
                afdKey = key;
            }
            return afdValue(result.get((CaptureResult.Key<Object>) key));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** P212d: the first value of an int / int[] / byte / byte[] / long vendor tag (null: none or another type). */
    static Integer afdValue(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof int[]) return ((int[]) v).length > 0 ? ((int[]) v)[0] : null;
        if (v instanceof Byte) return (int) (Byte) v;
        if (v instanceof byte[]) return ((byte[]) v).length > 0 ? (int) ((byte[]) v)[0] : null;
        if (v instanceof Long) return (int) (long) (Long) v;
        if (v instanceof long[]) return ((long[]) v).length > 0 ? (int) ((long[]) v)[0] : null;
        return null;
    }

    /**
     * P212d: one shot's vote of the merge's row profile (RowFlicker; 100 / 120 Hz, anything else is no vote and changes nothing):
     * {@link #SHOT_VOTES} shots in a row voting alike latch, over a latch from the report, the AE or an older vote. True when the
     * flicker changed.
     */
    public static synchronized boolean shotVote(int hz) {
        if (hz != 100 && hz != 120) return false;
        if (hz != shotHz) { shotHz = hz; shotVotes = 0; }
        shotVotes++;
        if (shotVotes < SHOT_VOTES || (evidence && latched == hz)) return false;
        final boolean changed = latched != hz;
        latch(hz, "row profile, " + shotVotes + " shots", true);
        return changed;
    }

    /** k when ns is k whole periods (0.5 %), else 0. */
    static long multiple(long ns, long period) {
        final long k = Math.round((double) ns / period);
        return k >= 1 && Math.abs(ns - k * period) <= period * 0.005 ? k : 0;
    }

    private static void latch(int hz, String why, boolean fromEvidence) {
        final int before = latched;
        latched = hz;
        source = why;
        evidence = fromEvidence;
        com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "mains flicker " + hz + " Hz (" + why + ")"
                + (before != 0 && before != hz ? ", was " + before + " Hz" : ""));
    }

    /** The flicker to snap to: the latched one, else the region's. */
    public static int hz() {
        final int h = latched;
        return h != 0 ? h : regionHz(Locale.getDefault().getCountry());
    }

    /** P212d: true once the flicker is latched from evidence (reports, AE shutters, row profiles); false while {@link #hz()} is a guess. */
    public static boolean known() {
        return latched != 0;
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
        reportHz = reports = sessionResults = 0;
        nativeSession = false;
        noted = false;
        shotHz = shotVotes = 0;
        evidence = false;
        afdKey = null;
        afdMisses = 0;
    }
}
