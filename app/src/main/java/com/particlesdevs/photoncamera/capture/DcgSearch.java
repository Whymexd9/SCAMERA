package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P107 The ordered tries that put the vivo X500 Pro Max's main IMX09C into its DCG 14-bit (or DCG + LOFIC) sensor mode for a
 * third-party app, and the test of a live result that says one of them worked.
 * <p>
 * Facts from the phone's SCAM TEST report (root files, 2026-10-11) and the owner's log of 2026-10-12:
 * <ul>
 *   <li>The selector /vendor/etc/camera/sensormode-selector/PD2602.json gives a non-vivo app CameraScenario 532 («PHOTO», 4:3) or
 *       552 («VIDEO», 16:9) with SensorScenario 1 (binned) and CustomHint 0; vivo's own video path is CameraScenario 501
 *       (VIDEO30) and its «seamless HDR 1 exposure» (DCGHDR1, session.seamlesscontrol 1) starts from SensorScenario 1 too.</li>
 *   <li>/odm/etc/camera/scenario_table_imx09cpd2602.xml: DCG_SENSOR_MERGE_14BIT_BAYER = sensor mode 18 (video default, 501 by
 *       the selector), _LOFIC = 19, the 2x crop of it = 20; 3RD_APP_HDR has the 14-bit DCG photo mode 33 («dcg 1:16»).
 *       CUSTOM_CAMERA_SCENARIO_FORCE_CAMERA_CHOOSE maps CustomHint 100 + n to sensor mode n (0..53): 118 = DCG 14-bit,
 *       119 = DCG + LOFIC, 133 = the 3RD_APP_HDR DCG mode; the numeric value of that camera scenario is not in the files.</li>
 *   <li>com.mediatek.seamlessfeature.cameraScenario / sensorScenario / sensorScenarioCustomHint are session keys (int[] pairs
 *       camera, value for the last two) and request keys; results report sensorScenario and the hint.</li>
 *   <li>Log 2026-10-12: hdrMode 3 + EnableDCGHDR 1 + session.seamlesscontrol 1 were accepted, yet sensorScenario stayed [2, 1]
 *       and the dynamic white 1023: these keys alone do not switch the mode.</li>
 * </ul>
 * The tries go from the likeliest to a sweep; the search remembers the next try before a session starts (a try that kills the
 * session is skipped after the restart) and stops at the first result with a 14-bit white level / wide RAW or a moved sensor
 * scenario.
 */
public final class DcgSearch {
    /** Sensor modes of the FORCE_CAMERA_CHOOSE hints (100 + mode). */
    static final int[] DCG_HINTS = {133, 118};
    static final int[] LOFIC_HINTS = {119, 124, 129};
    /** The camera scenario of vivo's own video path, whose table holds sensor modes 18 / 19 / 20. */
    static final int VIDEO_SCENARIO = 501;
    /** Unknown enum values swept in the request: the MediaTek seamless sensor-scenario types and the vivo camera scenarios. */
    static final int SWEEP_FROM = 2, SWEEP_TO = 40, SCENARIO_FROM = 500, SCENARIO_TO = 560;
    /** Results per session try / per swept request value. */
    static final int RESULTS_PER_TRY = 24, RESULTS_PER_SWEEP = 10;
    /** The binned scenario every camera reports without the mode. */
    static final int BINNED = 1;

    private DcgSearch() {}

    /** One try: session camera scenario and hint (-1 = not sent) and the request sensor-scenario values swept in it. */
    public static final class Step {
        public final int cameraScenario, hint;
        public final List<Integer> sweep;

        Step(int cameraScenario, int hint, List<Integer> sweep) {
            this.cameraScenario = cameraScenario;
            this.hint = hint;
            this.sweep = sweep;
        }

        @Override
        public String toString() {
            return "cameraScenario " + cameraScenario + ", hint " + hint
                    + (sweep.isEmpty() ? "" : ", request sensorScenario " + sweep.get(0) + ".." + sweep.get(sweep.size() - 1));
        }
    }

    /** The tries for DCG 14-bit (lofic false) or DCG + LOFIC (true), likeliest first. */
    public static List<Step> steps(boolean lofic) {
        int[] hints = lofic ? LOFIC_HINTS : DCG_HINTS;
        List<Integer> none = Collections.emptyList(), sweep = new ArrayList<>();
        for (int t = SWEEP_FROM; t <= SWEEP_TO; t++) if (t != BINNED) sweep.add(t);
        List<Step> out = new ArrayList<>();
        for (int h : hints) out.add(new Step(-1, h, none));
        out.add(new Step(-1, -1, sweep));
        for (int h : hints) out.add(new Step(VIDEO_SCENARIO, h, none));
        out.add(new Step(VIDEO_SCENARIO, -1, sweep));
        for (int s = SCENARIO_FROM; s <= SCENARIO_TO; s++)
            if (s != VIDEO_SCENARIO) out.add(new Step(s, hints[0], none));
        return out;
    }

    /** A 14-bit picture: the dynamic white or the RAW's own samples (RawPayloadCheck wide RAW) above the declared 10 bits. */
    public static boolean fourteenBit(@Nullable Integer dynamicWhite, int wideWhite, int declaredWhite) {
        int base = declaredWhite > 0 ? declaredWhite : 1023;
        return (dynamicWhite != null && dynamicWhite > base * 3 / 2) || wideWhite > base * 3 / 2;
    }

    /** The HAL reports a sensor scenario other than the binned one for this camera ([camera, scenario] pairs). */
    public static boolean scenarioMoved(@Nullable int[] sensorScenario, int camera) {
        if (sensorScenario == null) return false;
        for (int i = 0; i + 1 < sensorScenario.length; i += 2)
            if (sensorScenario[i] == camera && sensorScenario[i + 1] != BINNED && sensorScenario[i + 1] > 0) return true;
        return false;
    }
}
