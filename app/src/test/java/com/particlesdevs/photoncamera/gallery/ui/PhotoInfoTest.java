package com.particlesdevs.photoncamera.gallery.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** P196: the resolution and phone text of the photo info (compare panes, details sheet). */
public class PhotoInfoTest {
    private static final String RU = " Мп", EN = " MP"; // Lang.t(" Мп", " MP")

    @Test
    public void megapixelsAreWholeFrom20AndOneDecimalBelow() {
        assertEquals("50 Мп", PhotoInfo.megapixels(8192, 6144, true, RU)); // 50.33
        assertEquals("12,6 Мп", PhotoInfo.megapixels(4096, 3072, true, RU)); // 12.58
        assertEquals("12.6 MP", PhotoInfo.megapixels(4096, 3072, false, EN));
        assertEquals("12 Мп", PhotoInfo.megapixels(4000, 3000, true, RU)); // no «12,0»
        assertEquals("200 Мп", PhotoInfo.megapixels(16320, 12240, true, RU)); // 199.76
        assertEquals("20 Мп", PhotoInfo.megapixels(5160, 3870, true, RU)); // 19.97: «20», never «20,0»
        assertEquals("2,1 Мп", PhotoInfo.megapixels(1920, 1080, true, RU));
        assertEquals("0,31 Мп", PhotoInfo.megapixels(640, 480, true, RU));
        assertEquals("15.9 MP", PhotoInfo.megapixels(4608, 3456, false, EN)); // 15.93
    }

    @Test
    public void noSizeNoText() {
        assertNull(PhotoInfo.megapixels(0, 3000, true, RU));
        assertNull(PhotoInfo.resolution(4000, 0, true, RU));
        assertNull(PhotoInfo.resolution(-1, -1, false, EN));
    }

    @Test
    public void resolutionIsPixelsThenMegapixels() {
        assertEquals("8192×6144 · 50 Мп", PhotoInfo.resolution(8192, 6144, true, RU));
        assertEquals("4096×3072 · 12.6 MP", PhotoInfo.resolution(4096, 3072, false, EN));
        assertEquals("6144×8192 · 50 Мп", PhotoInfo.resolution(6144, 8192, true, RU));
    }

    @Test
    public void deviceFromMakeAndModelWithoutTheBrandTwice() {
        assertEquals("vivo X200 Ultra", PhotoInfo.device("vivo", "vivo X200 Ultra")); // vivo's own Model names the brand
        assertEquals("vivo X200 Ultra", PhotoInfo.device("vivo", "X200 Ultra"));
        assertEquals("vivo V2446A", PhotoInfo.device("vivo", "V2446A"));
        assertEquals("Xiaomi 25010PN30G", PhotoInfo.device("Xiaomi", "25010PN30G"));
        assertEquals("OPPO Find X8 Ultra", PhotoInfo.device("OPPO", "Find X8 Ultra"));
        assertEquals("oneplus", PhotoInfo.device("OnePlus", "oneplus")); // the same word only once, as the model writes it
        assertEquals("vivo X200 Ultra", PhotoInfo.device(" vivo\0", "vivo  X200 Ultra\0\0")); // EXIF padding
        assertEquals("vivo vivox", PhotoInfo.device("vivo", "vivox")); // the brand, not a word prefix of the model
    }

    @Test
    public void deviceInventsNothing() {
        assertNull(PhotoInfo.device(null, null));
        assertNull(PhotoInfo.device("", "  "));
        assertEquals("vivo", PhotoInfo.device("vivo", null));
        assertEquals("Pixel 9 Pro", PhotoInfo.device(null, "Pixel 9 Pro"));
    }

    @Test
    public void orientationSwapsTheSidesAsShown() {
        assertArrayEquals(new int[]{8192, 6144}, PhotoInfo.oriented(8192, 6144, 0));
        assertArrayEquals(new int[]{6144, 8192}, PhotoInfo.oriented(8192, 6144, 90));
        assertArrayEquals(new int[]{8192, 6144}, PhotoInfo.oriented(8192, 6144, 180));
        assertArrayEquals(new int[]{6144, 8192}, PhotoInfo.oriented(8192, 6144, 270));
        assertArrayEquals(new int[]{6144, 8192}, PhotoInfo.oriented(8192, 6144, -90));
    }
}
