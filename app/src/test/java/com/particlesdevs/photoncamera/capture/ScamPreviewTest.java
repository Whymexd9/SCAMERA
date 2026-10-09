package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P54: the stock preview profile recorded on the X200 Ultra is not sent to other vivo phones (X300 Ultra EIS). */
public class ScamPreviewTest {
    @Test
    public void stockProfileOnlyOnThePhoneItWasRecordedOn() {
        assertTrue(ScamPreview.stockProfile("PD2454"));
        assertFalse(ScamPreview.stockProfile("v2562"));
        assertFalse(ScamPreview.stockProfile("PD2505"));
        assertFalse(ScamPreview.stockProfile(null));
    }
}
