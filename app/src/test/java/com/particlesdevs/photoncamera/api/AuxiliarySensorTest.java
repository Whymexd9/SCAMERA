package com.particlesdevs.photoncamera.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Sensors without a lens, gain or exposure are not photo lenses (SCAM TEST reports, 2026-10-11). */
public class AuxiliarySensorTest {
    @Test
    public void sensorsWithoutLensGainOrExposureAreAuxiliary() {
        assertTrue(AuxiliarySensors.noLensGainOrExposure(0f, 0, 400_000_000L));        // X200 Pro camera 5
        assertTrue(AuxiliarySensors.noLensGainOrExposure(2.59f, 1070, 0L));            // X200 Ultra camera 7
    }

    @Test
    public void photoLensesAndMissingKeysStay() {
        assertFalse(AuxiliarySensors.noLensGainOrExposure(6.54f, 6400, 16_000_000_000L)); // X200 Pro main
        assertFalse(AuxiliarySensors.noLensGainOrExposure(3.09f, 1536, 430_464_311L));    // X200 Pro front
        assertFalse(AuxiliarySensors.noLensGainOrExposure(Float.NaN, -1, -1L));           // keys missing
    }

    @Test
    public void vivoTeleconverterAndDummyProfilesAreNoLenses() {
        org.junit.Assert.assertTrue(AuxiliarySensors.vivoProfileOnly("Tele3xLongExternal"));   // X300 camera 5
        org.junit.Assert.assertTrue(AuxiliarySensors.vivoProfileOnly("Tele3P5xExternal400"));  // X500 Pro Max camera 6
        org.junit.Assert.assertTrue(AuxiliarySensors.vivoProfileOnly("DUMMY"));
        org.junit.Assert.assertFalse(AuxiliarySensors.vivoProfileOnly("Tele3xLong"));
        org.junit.Assert.assertFalse(AuxiliarySensors.vivoProfileOnly("Master"));
        org.junit.Assert.assertFalse(AuxiliarySensors.vivoProfileOnly("MultiCamera"));
        org.junit.Assert.assertFalse(AuxiliarySensors.vivoProfileOnly(null));
        // P120: the X300 Ultra's multispectral camera 7 and its pre-ISP chip (camera 6)
        org.junit.Assert.assertTrue(AuxiliarySensors.vivoProfileOnly("MultiSpectrum"));
        org.junit.Assert.assertTrue(AuxiliarySensors.vivoProfileOnly("PreISP"));
        org.junit.Assert.assertTrue(AuxiliarySensors.notAPhotoCamera(new int[]{8}));      // DEPTH_OUTPUT only
        org.junit.Assert.assertFalse(AuxiliarySensors.notAPhotoCamera(new int[]{0, 1, 3})); // BACKWARD_COMPATIBLE, MANUAL, RAW
        org.junit.Assert.assertFalse(AuxiliarySensors.notAPhotoCamera(null));
    }
}
