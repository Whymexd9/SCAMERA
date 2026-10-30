package com.particlesdevs.photoncamera.capture;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MotionPolicyTest {
    @Test public void aTwoFrameShotCanDropItsBlurredDonorButNeverItsBase() {
        assertEquals(Arrays.asList(1), MotionPolicy.blurredDonors(new float[]{1f, 0.1f}, 0, 0.85f));
        assertEquals(Arrays.asList(1), MotionPolicy.blurredDonors(new float[]{1f, 0f}, 0, 0.85f));
        assertTrue(MotionPolicy.blurredDonors(new float[]{0.1f, 1f}, 0, 0.85f).isEmpty());
    }

    @Test public void fourAndEightFramesDoNotImposeThePhotoMinimum() {
        assertEquals(Arrays.asList(1, 3), MotionPolicy.blurredDonors(new float[]{1, .1f, 1, .2f}, 0, .85f));
        assertEquals(Arrays.asList(1, 3, 5, 7), MotionPolicy.blurredDonors(new float[]{1, .1f, 1, .2f, 1, .3f, 1, .4f}, 0, .85f));
    }

    @Test public void UnknownOrNoiseOnlyScoresAreNotEvidenceOfBlur() {
        assertTrue(MotionPolicy.blurredDonors(new float[]{0, 0, 0, 0}, 0, .85f).isEmpty());
        assertTrue(MotionPolicy.blurredDonors(new float[]{1, Float.NaN}, 0, .85f).isEmpty());
        assertTrue(MotionPolicy.blurredDonors(new float[]{1, 1, 1, 1}, 0, .85f).isEmpty());
    }

    @Test public void donorWindowIsTimeBasedAndShortensWithMotion() {
        assertEquals(250_000_000L, MotionPolicy.windowNs(0));
        assertEquals(64_000_000L, MotionPolicy.windowNs(1000));
        assertEquals(16_000_000L, MotionPolicy.windowNs(10_000));
        assertEquals(250_000_000L, MotionPolicy.windowNs(Double.NaN));
    }

    @Test public void inputAgeDoesNotMixTheSensorAndHostClockOrigins() {
        assertEquals(900_000_000L, MotionPolicy.observedAtNs(1_000_000_000L, 50_000_000_000L, 50_100_000_000L));
        assertEquals(1_000_000_000L, MotionPolicy.observedAtNs(1_000_000_000L, 50_000_000_000L, 0));
    }
}
