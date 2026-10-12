package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VivoMtkIszTest {
    @Test
    public void labelsOfTheIszModules() {
        assertEquals("2×", VivoMtkIsz.label(2f));
        assertEquals("1.3×", VivoMtkIsz.label(0.63f * 2f));
        assertEquals("7×", VivoMtkIsz.label(3.51f * 2f));
        assertEquals("14×", VivoMtkIsz.label(3.51f * 4f));
    }

    @Test
    public void vivoMediatekPhonesAndTheFourXTele() {
        org.junit.Assert.assertTrue(VivoMtkIsz.vivoMediatek("vivo", "mt6993", null));
        org.junit.Assert.assertTrue(VivoMtkIsz.vivoMediatek("vivo", "qcom", "Mediatek"));
        org.junit.Assert.assertFalse(VivoMtkIsz.vivoMediatek("vivo", "qcom", "QTI")); // X200 Ultra / X300 Ultra: Snapdragon
        org.junit.Assert.assertFalse(VivoMtkIsz.vivoMediatek("OPPO", "mt6993", "Mediatek"));
        org.junit.Assert.assertTrue(VivoMtkIsz.fourXSensor(9.1392f)); // X500 Pro Max / X200 Pro 200 MP tele
        org.junit.Assert.assertFalse(VivoMtkIsz.fourXSensor(6.5536f)); // V2515 teles
    }

    @Test
    public void onlyPhysicalCamerasOfTheCurrentListAreSources() {
        org.junit.Assert.assertTrue(VivoMtkIsz.source("2", true));
        org.junit.Assert.assertFalse(VivoMtkIsz.source("0-2", true)); // V2515 log_4: the logical main labelled «17×»
        org.junit.Assert.assertFalse(VivoMtkIsz.source("0", false)); // a slot of the unspoofed camera list
        org.junit.Assert.assertFalse(VivoMtkIsz.source(null, true));
    }

    @Test
    public void onlyTheBusyHalErrorReopensTheSameCamera() {
        org.junit.Assert.assertTrue(VivoMtkIsz.busyHalError(
                "CAMERA_ERROR (3): endConfigure:879: Camera 3: Error configuring streams: Function not implemented (-38)"));
        org.junit.Assert.assertFalse(VivoMtkIsz.busyHalError("Error configuring streams: Broken pipe (-32)"));
        org.junit.Assert.assertFalse(VivoMtkIsz.busyHalError(null));
    }
}
