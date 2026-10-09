package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** P44: lead preview frames after the flush and the P38 re-arm default that follows from them. */
public class PreviewContinuityTest {
    @Test
    public void leadFramesOnlyAfterAFlushAndBounded() {
        assertEquals(0, PreviewContinuity.leadFrames(0f, true));          // scam_dev "preview_lead 0": off
        assertEquals(1, PreviewContinuity.leadFrames(PreviewContinuity.DEFAULT_LEAD, true)); // default: one lead frame
        assertEquals(1, PreviewContinuity.leadFrames(1f, true));
        assertEquals(2, PreviewContinuity.leadFrames(2.4f, true));
        assertEquals(PreviewContinuity.MAX_LEAD, PreviewContinuity.leadFrames(9f, true));
        assertEquals(0, PreviewContinuity.leadFrames(-1f, true));
        assertEquals(0, PreviewContinuity.leadFrames(Float.NaN, true));
        // without a flush the repeating preview runs into the series: a lead frame would only delay it
        assertEquals(0, PreviewContinuity.leadFrames(2f, false));
    }

    @Test
    public void theRearmDefaultIsUnchangedWithoutLeadFrames() {
        // as before P44: vivo 3 after a flushed shot, 1 otherwise; other HALs 0
        assertEquals(3, PreviewContinuity.defaultRearm(true, true, 0));
        assertEquals(1, PreviewContinuity.defaultRearm(true, false, 0));
        assertEquals(0, PreviewContinuity.defaultRearm(false, true, 0));
        assertEquals(0, PreviewContinuity.defaultRearm(false, false, 0));
    }

    @Test
    public void leadFramesReplaceTheSecondFlushOfTheRearm() {
        // the pipeline already restarted on a preview request: re-send the repeating request, drop nothing
        assertEquals(1, PreviewContinuity.defaultRearm(true, true, 1));
        assertEquals(1, PreviewContinuity.defaultRearm(true, true, 3));
        assertEquals(0, PreviewContinuity.defaultRearm(false, true, 2));
    }
}
