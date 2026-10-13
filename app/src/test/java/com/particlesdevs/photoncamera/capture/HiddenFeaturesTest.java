package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** P103 / P104: the HAL keys are only those the camera lists, and the DCG switch only on the X500 Pro Max. */
public class HiddenFeaturesTest {
    private static final List<String> MTK_VIVO = Arrays.asList("com.mediatek.3afeature.aishutterMode", "vivo.control.is_motion_autofocus");
    private static final List<String> QCOM = Arrays.asList("org.codeaurora.qcamera3.sessionParameters.EnableIdealRAW",
            "com.oplus.fastmotion.mode.enable");

    @Test
    public void motionKeysWhereListed() {
        List<HiddenFeatures.VendorKey> k = HiddenFeatures.select(MTK_VIVO, true, false);
        assertEquals(2, k.size());
        assertEquals("com.mediatek.3afeature.aishutterMode", k.get(0).name);
        assertEquals((byte) 1, k.get(0).value);
        assertEquals(1, k.get(1).value);
        assertEquals("com.oplus.fastmotion.mode.enable", HiddenFeatures.select(QCOM, true, false).get(0).name);
    }

    @Test
    public void nothingWhenOffOrUnlisted() {
        assertTrue(HiddenFeatures.select(MTK_VIVO, false, false).isEmpty());
        assertTrue(HiddenFeatures.select(Collections.emptyList(), true, true).isEmpty());
        assertTrue(HiddenFeatures.select(MTK_VIVO, false, true).isEmpty()); // no Ideal RAW key on MediaTek
    }

    @Test
    public void idealRawOnlyWithTheSwitch() {
        assertTrue(HiddenFeatures.select(QCOM, false, false).isEmpty());
        assertEquals("org.codeaurora.qcamera3.sessionParameters.EnableIdealRAW", HiddenFeatures.select(QCOM, false, true).get(0).name);
    }

    @Test
    public void contains() {
        assertTrue(HiddenFeatures.contains(new int[]{0, 1, 6}, 6));
        assertFalse(HiddenFeatures.contains(null, 6));
        assertFalse(HiddenFeatures.contains(new int[]{0, 1}, 6));
    }
}
