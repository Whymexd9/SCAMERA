package com.particlesdevs.photoncamera.processing.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.RggbChannelVector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P212f (W2 = M3): a mosaic shot keeps a hand-set white balance (P201) - the gains at the press first, else a result that says AWB
 * OFF - instead of its own estimate, for the photo and the DNG's AsShotNeutral (the white point).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersHandSetWbTest {
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;

    @Before public void setUp() {
        app = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
    }

    @After public void tearDown() {
        app.close();
    }

    /** 3200 K by hand on OPPO camera 2: ManualWhiteBalance's gains (R 2.0, G 1, B 1.5 - the inverse neutral 0.5 / 0.667). */
    private static final RggbChannelVector GAINS = new RggbChannelVector(2.0f, 1f, 1f, 1.5f);
    private static final float[] HAND = {0.5f, 1f, 1f / 1.5f};

    private static CaptureResult result(int awbMode, RggbChannelVector gains) {
        final CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(awbMode);
        when(r.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(gains);
        return r;
    }

    /** M3 VERIFY: an AWB-OFF result keeps the inverse of its gains as the shot's white point (ownNeutral false). */
    @Test public void anAwbOffResultKeepsItsGains() {
        final Parameters p = new Parameters();
        p.ownNeutral = true;
        assertTrue(p.keepHandSetWhiteBalance(result(CaptureRequest.CONTROL_AWB_MODE_OFF, GAINS)));
        assertArrayEquals(HAND, p.customNeutral, 1e-6f);
        assertFalse("the ISP transform stays usable", p.ownNeutral);
        assertTrue(p.handSetSource, p.handSetSource.contains("AWB off"));
    }

    /**
     * M3 VERIFY: the press-time gains give the same, and win over the result - an OPPO native result says AUTO (its request's
     * AWB is the mode's) or carries the gains complete() filled; a switch back to Auto after the press stays out of the shot.
     */
    @Test public void theGainsAtThePressWin() {
        final Parameters p = new Parameters();
        p.manualWbAtPress = GAINS;
        assertTrue(p.keepHandSetWhiteBalance(result(CaptureRequest.CONTROL_AWB_MODE_AUTO, null)));
        assertArrayEquals(HAND, p.customNeutral, 1e-6f);
        final Parameters q = new Parameters();
        q.manualWbAtPress = GAINS;
        assertTrue(q.keepHandSetWhiteBalance(result(CaptureRequest.CONTROL_AWB_MODE_OFF, new RggbChannelVector(2.6f, 1f, 1f, 1.2f))));
        assertArrayEquals(HAND, q.customNeutral, 1e-6f);
        assertEquals("the press", q.handSetSource);
    }

    /** Automatic white balance at the press and in the result: the mosaic estimate runs (nothing is kept). */
    @Test public void automaticWhiteBalanceIsNotKept() {
        final Parameters p = new Parameters();
        assertFalse(p.keepHandSetWhiteBalance(result(CaptureRequest.CONTROL_AWB_MODE_AUTO, GAINS)));
        assertNull(p.customNeutral);
        assertFalse(p.keepHandSetWhiteBalance(null));
        // AWB off without gains: the white point already read stays
        final Parameters q = new Parameters();
        q.whitePoint = new float[]{0.41f, 1f, 0.62f};
        assertTrue(q.keepHandSetWhiteBalance(result(CaptureRequest.CONTROL_AWB_MODE_OFF, null)));
        assertArrayEquals(new float[]{0.41f, 1f, 0.62f}, q.customNeutral, 0f);
        // a bad gain is no hand-set white point
        assertNull(Parameters.handSetNeutral(new RggbChannelVector(0f, 1f, 1f, 1.5f)));
        assertArrayEquals(HAND, Parameters.handSetNeutral(GAINS), 1e-6f);
    }
}
