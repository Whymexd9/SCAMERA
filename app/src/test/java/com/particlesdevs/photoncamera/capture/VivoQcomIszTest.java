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
        assertEquals(Dcg14Mode.DCG, Dcg14Mode.next(Dcg14Mode.OFF));
        assertEquals("on / off, no LOFIC step any more", Dcg14Mode.OFF, Dcg14Mode.next(Dcg14Mode.DCG));
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

    /** P126 (X300 Ultra 2026-10-14): two «4.8×» and two «9.6×» on the tele, a plain «2×» crop next to the ISZ «2×». */
    @Test public void extraModulesGo() {
        java.util.List<VivoQcomIsz.Mod> mods = java.util.Arrays.asList(
                new VivoQcomIsz.Mod("back0", "4", 0.4f, 0, false),
                new VivoQcomIsz.Mod("back1", "3", 1f, 0, false),
                new VivoQcomIsz.Mod("back2", "5", 2.4f, 0, false),
                new VivoQcomIsz.Mod("back3", "3", 2f, 0, true),   // IszModule's CROPPED_RAW «2×»
                new VivoQcomIsz.Mod("back4", "5", 4.8f, 5, true),
                new VivoQcomIsz.Mod("back5", "5", 4.8f, 5, true),  // the second «4.8×»
                new VivoQcomIsz.Mod("back6", "3", 2f, 10, true),
                new VivoQcomIsz.Mod("back7", "5", 9.6f, 7, true));
        assertEquals(java.util.Arrays.asList("back5", "back3"), VivoQcomIsz.extras(mods, true));
        assertEquals("the plain twin only once", java.util.Collections.singletonList("back5"), VivoQcomIsz.extras(mods, false));
    }
}
