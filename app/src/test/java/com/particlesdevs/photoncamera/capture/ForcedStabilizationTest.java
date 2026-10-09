package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P54b: the forced stabilisation is for the vivo X300 Ultra only by default, and it skips the shot's HAL flush. */
public class ForcedStabilizationTest {
    @Test
    public void onlyTheX300UltraByDefault() {
        assertTrue(ForcedStabilization.phone("vivo", "V2562", "V2562"));
        assertTrue(ForcedStabilization.phone("VIVO", "v2562", null));
        assertTrue(ForcedStabilization.phone("vivo", "PD2562X", "V2562"));
        assertFalse(ForcedStabilization.phone("vivo", "PD2454", "V2502A"));
        assertFalse(ForcedStabilization.phone("OPPO", "V2562", "V2562"));
        assertFalse(ForcedStabilization.phone(null, null, null));
    }

    @Test
    public void forcedStabilisationSkipsTheFlush() {
        assertTrue(ForcedStabilization.flush(true, false));
        assertFalse(ForcedStabilization.flush(true, true));
        assertFalse(ForcedStabilization.flush(false, false));
        assertFalse(ForcedStabilization.flush(false, true));
    }
}
