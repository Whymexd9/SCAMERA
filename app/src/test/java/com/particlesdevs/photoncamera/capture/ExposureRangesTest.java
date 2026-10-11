package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P87 (owner, 2026-10-11, stock pro mode vs ours on the vivo X200 Ultra): the rulers showed ISO 1144..3200 / 1144..5359 on the
 * main camera, the stock app 72..12800 and up to 32 s. Values from the phone's camera service dump of 2026-10-11.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ExposureRangesTest {
    @Test
    public void vivoMainTakesItsProModeIsoRange() {
        // android.sensor.info.sensitivityRange [1144 3200], com.vivo.pro_isoMin 72, com.vivo.pro_isoMax 12800
        assertEquals(new Range<>(72, 12800), ExposureRanges.iso(new Range<>(1144, 3200), 72, 12800, 0, 0));
        // the AE seen at 5359 changes nothing inside it
        assertEquals(new Range<>(72, 12800), ExposureRanges.iso(new Range<>(1144, 5359), 72, 12800, 1144, 5359));
    }

    @Test
    public void aModuleWithoutVendorKeysGrowsToWhatItsAeUses() {
        // the tele: [400 800] declared, no vendor key; its AE at ISO 50 by day and 3200 at night
        assertEquals(new Range<>(400, 800), ExposureRanges.iso(new Range<>(400, 800), null, null, 0, 0));
        assertEquals(new Range<>(50, 3200), ExposureRanges.iso(new Range<>(400, 800), null, null, 50, 3200));
        // a broken vendor pair is ignored
        assertEquals(new Range<>(100, 3200), ExposureRanges.iso(new Range<>(100, 3200), 12800, 72, 0, 0));
    }

    @Test
    public void x200UltraTeleTakesTheStockProRange() {
        assertEquals(50, ExposureRanges.knownProRange("PD2454", 22.48f)[0]);
        assertEquals(3200, ExposureRanges.knownProRange("PD2454", 22.48f)[1]);
        assertEquals(null, ExposureRanges.knownProRange("PD2454", 10.09f)); // main: its vendor keys
        assertEquals(null, ExposureRanges.knownProRange("PHY110", 22.48f));
        // the AE widening stays (owner): the tele's AE was seen up to 6400
        assertEquals(new Range<>(50, 6400), ExposureRanges.iso(new Range<>(400, 800), 50, 3200, 61, 6400));
    }

    @Test
    public void shutterReachesTheFrameDurationCeilingOnAStop() {
        // main: exposureTimeRange [43125 327675052], maxFrameDuration 33024381611 -> 32 s
        assertEquals(new Range<>(43_125L, 32_000_000_000L), ExposureRanges.shutter(new Range<>(43_125L, 327_675_052L), 33_024_381_611L));
        // tele: [83384 233455352], maxFrameDuration 29073607381 -> 25 s
        assertEquals(new Range<>(83_384L, 25_000_000_000L), ExposureRanges.shutter(new Range<>(83_384L, 233_455_352L), 29_073_607_381L));
        // OPPO Find X7 Ultra main: [102090 36313721080] declared, kept as it is
        assertEquals(new Range<>(102_090L, 36_313_721_080L), ExposureRanges.shutter(new Range<>(102_090L, 36_313_721_080L), 36_313_721_080L));
        // a frame-duration ceiling close to the declared one: unchanged
        assertEquals(new Range<>(100_000L, 1_000_000_000L), ExposureRanges.shutter(new Range<>(100_000L, 1_000_000_000L), 2_000_000_000L));
    }
}
