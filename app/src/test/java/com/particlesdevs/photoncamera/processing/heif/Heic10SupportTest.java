package com.particlesdevs.photoncamera.processing.heif;

import com.particlesdevs.photoncamera.processing.PhotoFormat;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Which encoder the 10-bit HEIC takes, when the option applies, and the CQ / VBR mapping of the HEIC quality. */
public class Heic10SupportTest {
    private static Heic10Support.Encoder enc(String name, boolean hw, boolean main10, boolean p010, boolean size, boolean cq) {
        return new Heic10Support.Encoder(name, hw, main10, p010, size, cq, cq ? 0 : 0, cq ? 100 : 0, 1000, 200_000_000);
    }

    @After
    public void tearDown() {
        Heic10Support.clearForTesting();
    }

    @Test
    public void hardwareMain10P010EncoderFirst() {
        final Heic10Support.Encoder sw = enc("c2.android.hevc.encoder", false, true, true, true, true);
        final Heic10Support.Encoder hw8 = enc("c2.qti.hevc.encoder.8bit", true, false, true, true, true);
        final Heic10Support.Encoder hwNoP010 = enc("c2.vendor.hevc.encoder", true, true, false, true, true);
        final Heic10Support.Encoder hw = enc("c2.qti.hevc.encoder", true, true, true, true, true);
        assertSame(hw, Heic10Support.choose(Arrays.asList(sw, hw8, hwNoP010, hw)));
        assertSame("a software Main10 encoder when the hardware one cannot", sw,
                Heic10Support.choose(Arrays.asList(hw8, sw, hwNoP010)));
        assertNull(Heic10Support.choose(Arrays.asList(hw8, hwNoP010)));
        assertNull(Heic10Support.choose(Collections.emptyList()));
        assertNull("no 512 x 512 frames", Heic10Support.choose(Collections.singletonList(enc("x", true, true, true, false, true))));
    }

    @Test
    public void reasons() {
        final Heic10Support.Encoder hw = enc("c2.qti.hevc.encoder", true, true, true, true, true);
        assertNotNull("Android 12", Heic10Support.reason(32, hw));
        assertNotNull("no encoder", Heic10Support.reason(35, null));
        assertNull(Heic10Support.reason(33, hw));
        assertNotEquals(Heic10Support.reason(32, hw), Heic10Support.reason(35, null));
    }

    @Test
    public void appliesOnlyToHeicWithTheSettingOnAndSupport() {
        assertTrue(Heic10Support.applies(PhotoFormat.HEIC, true, true));
        assertFalse(Heic10Support.applies(PhotoFormat.HEIC, false, true));
        assertFalse(Heic10Support.applies(PhotoFormat.HEIC, true, false));
        assertFalse(Heic10Support.applies(PhotoFormat.JPEG, true, true));
        assertFalse(Heic10Support.applies(PhotoFormat.WEBP, true, true));
    }

    @Test
    public void testingOverride() {
        Heic10Support.setForTesting(null);
        assertFalse(Heic10Support.available());
        assertNotNull(Heic10Support.unavailableReason());
        Heic10Support.setForTesting(enc("c2.qti.hevc.encoder", true, true, true, true, true));
        assertTrue(Heic10Support.available());
        assertNull(Heic10Support.unavailableReason());
    }

    @Test
    public void cqQualityFollowsTheHeicQuality() {
        assertEquals(0, Heic10Support.cqQuality(1, 0, 100));
        assertEquals(100, Heic10Support.cqQuality(100, 0, 100));
        assertEquals(90, Heic10Support.cqQuality(90, 0, 100), 1);
        assertEquals(1, Heic10Support.cqQuality(1, 1, 51));
        assertEquals(51, Heic10Support.cqQuality(250, 1, 51));
        assertEquals(5, Heic10Support.cqQuality(50, 5, 5));
        int last = -1;
        for (int q = 1; q <= 100; q++) {
            final int v = Heic10Support.cqQuality(q, 0, 100);
            assertTrue("monotonic", v >= last);
            last = v;
        }
    }

    @Test
    public void vbrBitrateIsGenerousAndBounded() {
        final int fps = 30; // Heic10Encoder.FPS
        // quality 90: about 3.7 bits per pixel of each 512 x 512 tile
        final int q90 = Heic10Support.vbrBitrate(90, 512, fps, 1000, 400_000_000);
        assertEquals(3.65, q90 / (512.0 * 512 * fps), 0.01);
        assertTrue(Heic10Support.vbrBitrate(1, 512, fps, 1000, 400_000_000) < q90);
        assertEquals("capped by the encoder", 20_000_000, Heic10Support.vbrBitrate(100, 512, fps, 1000, 20_000_000));
        assertEquals("at least the encoder's floor", 50_000_000, Heic10Support.vbrBitrate(1, 512, fps, 50_000_000, 400_000_000));
    }
}
