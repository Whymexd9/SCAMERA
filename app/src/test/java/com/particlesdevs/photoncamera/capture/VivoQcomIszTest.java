package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.api.VendorTagUtils;

import org.junit.Test;

/** P122: the vivo X300 Ultra's sensor-mode modules (the owner's table of working modes; session keys only). */
public class VivoQcomIszTest {
    @Test public void bothX300UltraVariantsOnly() {
        assertTrue(VivoQcomIsz.x300Ultra("vivo", "PD2547", "V2547DA", null));
        assertTrue(VivoQcomIsz.x300Ultra("vivo", "PD2547", "V2547A", ""));
        assertTrue(VivoQcomIsz.x300Ultra("vivo", "V2562", "V2562", null));
        assertTrue("another region, by its market name", VivoQcomIsz.x300Ultra("vivo", "PD2599", "V2599", "vivo X300 Ultra"));
        assertFalse(VivoQcomIsz.x300Ultra("vivo", "PD2599", "V2599", "vivo X300 Pro"));
        assertFalse("the X200 Ultra is VivoQcomModes'", VivoQcomIsz.x300Ultra("vivo", "PD2454", "V2454A", "vivo X200 Ultra"));
        assertFalse(VivoQcomIsz.x300Ultra("OPPO", "PD2547", "V2547DA", null));
        assertFalse(VivoQcomIsz.x300Ultra(null, null, null, null));
    }

    @Test public void cameraRolesFromTheVivoCameraType() {
        assertEquals("main", VivoQcomIsz.role("Master"));
        assertEquals("wide", VivoQcomIsz.role("Wide"));
        assertEquals("tele", VivoQcomIsz.role("Tele3P5x"));
        assertNull("the extender profile", VivoQcomIsz.role("Tele3P5xExternal"));
        assertNull(VivoQcomIsz.role("MultiSpectrum"));
        assertNull(VivoQcomIsz.role(null));
    }

    @Test public void dcgOnAllThreeCameras() {
        assertEquals(17, VivoQcomIsz.dcgMode("main"));
        assertEquals(11, VivoQcomIsz.dcgMode("wide"));
        assertEquals(20, VivoQcomIsz.dcgMode("tele"));
        assertEquals(0, VivoQcomIsz.dcgMode(null));
    }

    @Test public void modulesCarryTheModeAsASessionKey() {
        VendorTagUtils.TunableKey k = VivoQcomIsz.sessionKey(VivoQcomIsz.TELE_4X);
        assertEquals(VendorTagUtils.TunableKey.TYPE_SESSION, k.type);
        assertEquals("vivo.control.forceSensorMode", k.name);
        assertEquals("7", k.value);
        assertEquals("7.4×", VivoQcomIsz.label(3.7f * 2));
        assertEquals("15×", VivoQcomIsz.label(3.7f * 4));
        assertEquals("2×", VivoQcomIsz.label(2f));
        assertEquals("1.2×", VivoQcomIsz.label(0.6f * 2));
    }
}
