package com.particlesdevs.photoncamera.processing.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.processing.color.PlainColourMemory;
import com.particlesdevs.photoncamera.processing.color.PlainColourMemoryAccess;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * P212g (W7 = M12 item 4): the X7 Ultra's 6500 K anchor of a sensor it has not measured (the 22.36 mm 6x tele, the 3.23 mm front)
 * from its plain-session neutrals labelled 6000-7000 K, moved to 6500 K by the SHIFT_MIRED rule; a measured anchor stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class OppoTunedAnchorTest {
    private SharedPreferences prefs;

    @Before public void setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("oppo_anchor_test", 0);
        prefs.edit().clear().commit();
        PlainColourMemoryAccess.useStore(prefs, "OPPO/PHY110/OP5D0DL1:16/202");
    }

    @After public void tearDown() {
        PlainColourMemoryAccess.reset();
    }

    private static float[] neutral(double lnRB) {
        return new float[]{(float) Math.exp(lnRB / 2), 1f, (float) Math.exp(-lnRB / 2)};
    }

    /** shiftAt reads SHIFT_MIRED the other way: a neutral at anchor + shiftAt(m) is estimated at 1e6 / m K. */
    @Test public void theShiftIsTheTableReadBackwards() {
        for (double mired = 115; mired <= 330; mired += 5) {
            final float cct = OppoTunedColor.estimateCct(neutral(-0.5 + OppoTunedColor.shiftAt(mired)), -0.5f);
            assertEquals("at " + mired + " mired", 1e6 / mired, cct, 1e6 / mired * 0.002);
        }
        assertEquals(0f, OppoTunedColor.shiftAt(154), 1e-6f);
    }

    /**
     * The rule's cross-check on the PHY110 probe's held AWB state of the main camera (gains 2.4490 / 1.6287, label 5546 K:
     * ln(R/B) -0.408): -0.685 against the measured -0.696 (outside the 6000-7000 K window, the rule alone).
     */
    @Test public void theMainCamerasHeldStateCrossChecks() {
        final double ln = Math.log(1.6287 / 2.4490);
        final float a = OppoTunedColor.learnedAnchor(Arrays.asList(new double[]{ln, 1e6 / 5546, 10}));
        assertEquals(-0.696f, a, 0.015f);
    }

    /** The count-weighted median of the bins' anchors; fewer than 5 labelled samples give none. */
    @Test public void theLearnedAnchorIsAWeightedMedian() {
        final List<double[]> bins = new ArrayList<>();
        bins.add(new double[]{-0.43 + OppoTunedColor.shiftAt(1e6 / 6500), 1e6 / 6500, 8});
        bins.add(new double[]{-0.45 + OppoTunedColor.shiftAt(1e6 / 6100), 1e6 / 6100, 3});
        bins.add(new double[]{-0.20 + OppoTunedColor.shiftAt(1e6 / 6900), 1e6 / 6900, 1}); // an outlier, one sample
        assertEquals(-0.43f, OppoTunedColor.learnedAnchor(bins), 1e-4f);
        assertTrue(Float.isNaN(OppoTunedColor.learnedAnchor(Arrays.asList(new double[]{-0.43, 154, 4}))));
        assertTrue(Float.isNaN(OppoTunedColor.learnedAnchor(null)));
    }

    /**
     * The 6x tele (22.36 mm) learns its anchor from its plain table's 6000-7000 K labels; without them it keeps -0.6; a measured
     * sensor keeps its measured anchor whatever its table says.
     */
    @Test public void unmeasuredSensorsLearnMeasuredOnesStay() {
        assertEquals(-0.6f, OppoTunedColor.anchor(22.36f, "5"), 0f);
        final float[] m = {1.51f, -0.19f, -0.32f, -0.14f, 1.52f, -0.38f, -0.01f, -0.59f, 1.60f};
        // the 6x's plain daylight under 6300 K labels: ln(R/B) -0.40 there (6500 K anchor -0.40 - shift(158.7 mired))
        final double ln = -0.40;
        for (int i = 0; i < 6; i++) PlainColourMemory.offer("5", (float) Math.exp(ln / 2), (float) Math.exp(-ln / 2), m, 6300, 1_000L * (i + 1));
        final float learned = OppoTunedColor.anchor(22.36f, "5");
        assertEquals(ln - OppoTunedColor.shiftAt(1e6 / 6300), learned, 1e-3f);
        assertTrue(OppoTunedColor.describeAnchor(22.36f, "5"), OppoTunedColor.describeAnchor(22.36f, "5").contains("learned"));
        // the CCT estimate then puts that daylight at 6300 K
        assertEquals(6300f, OppoTunedColor.estimateCct(neutral(ln), learned), 20f);
        // a measured sensor (the main 8.67 mm) keeps -0.696; its log line cross-checks the table
        for (int i = 0; i < 6; i++) PlainColourMemory.offer("2", (float) Math.exp(-0.60 / 2), (float) Math.exp(0.60 / 2), m, 6500, 1_000L * (i + 1));
        assertEquals(-0.696f, OppoTunedColor.anchor(8.67f, "2"), 0f);
        assertTrue(OppoTunedColor.describeAnchor(8.67f, "2"), OppoTunedColor.describeAnchor(8.67f, "2").contains("measured; the plain table"));
        // labels outside the window do not teach
        for (int i = 0; i < 6; i++) PlainColourMemory.offer("1", 0.6f, 0.6f, m, 4600, 1_000L * (i + 1));
        assertEquals(-0.6f, OppoTunedColor.anchor(3.23f, "1"), 0f);
    }
}
