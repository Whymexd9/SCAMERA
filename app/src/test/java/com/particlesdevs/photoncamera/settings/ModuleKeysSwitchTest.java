package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.particlesdevs.photoncamera.api.VendorTagUtils;

import org.junit.Test;

import java.util.Arrays;

/** P122: a module's vendor keys can be switched off one by one; a switched-off sensor mode is no sensor mode. */
public class ModuleKeysSwitchTest {
    private static VendorTagUtils.TunableKey key(String type, String name, int value, boolean enabled) {
        VendorTagUtils.TunableKey k = new VendorTagUtils.TunableKey(type, name, Integer.class, value);
        k.enabled = enabled;
        return k;
    }

    @Test public void theFirstSwitchedOnModeCounts() {
        Gson g = new Gson();
        String on = g.toJson(Arrays.asList(key("CaptureRequest", "vivo.control.EnableDCGHDR", 1, true),
                key(VendorTagUtils.TunableKey.TYPE_SESSION, "vivo.control.forceSensorMode", 7, true)));
        assertEquals(7, ModuleRegistry.forcedMode(on));
        String off = g.toJson(Arrays.asList(key(VendorTagUtils.TunableKey.TYPE_SESSION, "vivo.control.forceSensorMode", 7, false),
                key(VendorTagUtils.TunableKey.TYPE_SESSION, "vivo.control.forceSensorMode", 5, true)));
        assertEquals(5, ModuleRegistry.forcedMode(off));
        assertEquals(0, ModuleRegistry.forcedMode(g.toJson(Arrays.asList(
                key("CaptureRequest", "vivo.control.forceSensorMode", 20, false)))));
        assertEquals(0, ModuleRegistry.forcedMode(""));
        assertEquals(0, ModuleRegistry.forcedMode(null));
    }

    @Test public void olderListsWithoutTheFieldAreOn() {
        VendorTagUtils.TunableKey k = new Gson().fromJson(
                "{\"type\":\"CaptureRequest\",\"name\":\"vivo.control.forceSensorMode\",\"valueType\":\"Integer\",\"value\":\"5\"}",
                VendorTagUtils.TunableKey.class);
        assertTrue(k.enabled);
        assertEquals(5, ModuleRegistry.forcedMode("[{\"type\":\"CaptureRequest\",\"name\":\"vivo.control.forceSensorMode\","
                + "\"valueType\":\"Integer\",\"value\":\"5\",\"supported\":true,\"tested\":true}]"));
    }
}
