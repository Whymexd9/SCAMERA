package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P123: the vivo MediaTek ISZ modules are removed on vivo MediaTek phones only (not the Snapdragon X200 / X300 Ultra). */
public class IszModuleTest {
    @Test public void vivoMediatekOnly() {
        assertTrue(IszModule.vivoMediatek("vivo", "mt6993", null));
        assertTrue(IszModule.vivoMediatek("vivo", "qcom", "Mediatek"));
        assertFalse("X200 Ultra / X300 Ultra: Snapdragon", IszModule.vivoMediatek("vivo", "qcom", "QTI"));
        assertFalse(IszModule.vivoMediatek("OPPO", "mt6993", "Mediatek"));
        assertFalse(IszModule.vivoMediatek(null, null, null));
    }
}
