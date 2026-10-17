package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** P196: the EXIF Model our photos store: the phone's market name, else Build.MODEL. */
public class DeviceNameTest {
    @Test
    public void marketNameFirstThenBuildModel() {
        assertEquals("vivo X200 Ultra", DeviceName.model("V2446A", "vivo X200 Ultra", "", ""));
        assertEquals("Find X8 Ultra", DeviceName.model("PKJ110", "", "Find X8 Ultra", ""));
        assertEquals("Xiaomi 17 Ultra", DeviceName.model("25128PNA1C", "", "", "Xiaomi 17 Ultra"));
        assertEquals("V2446A", DeviceName.model("V2446A", "", "  ", null));
        assertEquals("V2446A", DeviceName.model("V2446A"));
        assertNull(DeviceName.model(null, ""));
    }

    @Test
    public void exifModelIsAscii() {
        assertEquals("vivo X300 Ultra", DeviceName.model("V2547A", "vivo X300 Ultra 卫星通信版"));
        assertEquals("25128PNA1C", DeviceName.model("25128PNA1C", "小米 17 Ultra")); // no Latin start: the build model
        assertEquals("vivo X200 Ultra", DeviceName.ascii("  vivo   X200 Ultra "));
        assertEquals("", DeviceName.ascii("123"));
    }

    @Test
    public void readsTheMarketNamePropertiesTheProjectKnows() {
        assertEquals("ro.vivo.market.name", DeviceName.MARKET_PROPERTIES[0]); // VivoQcomIsz.marketName
        assertEquals("ro.product.marketname", DeviceName.MARKET_PROPERTIES[2]); // XiaomiTeleZoom
    }
}
