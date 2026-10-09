package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P54: the stock preview profile recorded on the X200 Ultra is not sent to other vivo phones (X300 Ultra EIS). */
public class VivoNicePreviewTest {
    @Test
    public void stockProfileOnlyOnThePhoneItWasRecordedOn() {
        assertTrue(VivoNicePreview.stockProfile("PD2454"));
        assertFalse(VivoNicePreview.stockProfile("v2562"));
        assertFalse(VivoNicePreview.stockProfile("PD2505"));
        assertFalse(VivoNicePreview.stockProfile(null));
    }
}
