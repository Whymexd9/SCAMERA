package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * P127: black and white by the stream's bit depth - 8 bit 16 / 255, 10 bit 64 / 1023, 12 bit 256 / 4095, 14 bit 1024 / 16383,
 * 16 bit 4096 / 65535 - from the declared levels, the frame's dynamic ones in their own units and the session's samples.
 */
public class RawLevelsTest {
    private static final float[] B64 = {64, 64, 64, 64};

    private static float[] all(float v) {
        return new float[]{v, v, v, v};
    }

    private static RawLevels.Levels resolve(float[] staticBlack, int staticWhite, float[] dyn, int dynWhite, boolean useDynBlack) {
        return RawLevels.resolve(staticBlack, staticWhite, dyn, dynWhite, 0, -1f, true, useDynBlack, true);
    }

    @Before public void session() {
        RawLevels.newSession(true);
    }

    @Test public void canonicalPairs() {
        assertEquals(16, RawLevels.pedestal(8));
        assertEquals(64, RawLevels.pedestal(10));
        assertEquals(256, RawLevels.pedestal(12));
        assertEquals(1024, RawLevels.pedestal(14));
        assertEquals(4096, RawLevels.pedestal(16));
        assertEquals(255, RawLevels.white(8));
        assertEquals(1023, RawLevels.white(10));
        assertEquals(4095, RawLevels.white(12));
        assertEquals(16383, RawLevels.white(14));
        assertEquals(65535, RawLevels.white(16));
    }

    @Test public void bitsOfWhite() {
        assertEquals(8, RawLevels.bitsOfWhite(255));
        assertEquals(10, RawLevels.bitsOfWhite(1023));
        assertEquals("a white under the full range is the same depth", 10, RawLevels.bitsOfWhite(1000));
        assertEquals(12, RawLevels.bitsOfWhite(1024));
        assertEquals(12, RawLevels.bitsOfWhite(4095));
        assertEquals(14, RawLevels.bitsOfWhite(16383));
        assertEquals(16, RawLevels.bitsOfWhite(65535));
        assertEquals(0, RawLevels.bitsOfWhite(0));
        assertEquals(0, RawLevels.bitsOfWhite(70000));
    }

    @Test public void scaleSteps() {
        assertEquals("a drift", 0, RawLevels.scaleSteps(64, 60, 0.25));
        assertEquals(4, RawLevels.scaleSteps(64, 1030.75, 0.25));
        assertEquals(4, RawLevels.scaleSteps(64, 1021.75, 0.25));
        assertEquals(2, RawLevels.scaleSteps(64, 256, 0.25));
        assertEquals(6, RawLevels.scaleSteps(64, 4096, 0.25));
        assertEquals("8-bit units", -2, RawLevels.scaleSteps(64, 16, 0.25));
        assertEquals("neither", RawLevels.NO_SCALE, RawLevels.scaleSteps(64, 150, 0.25));
        assertEquals("odd bits are no depth here", RawLevels.NO_SCALE, RawLevels.scaleSteps(64, 128, 0.25));
        assertEquals(RawLevels.NO_SCALE, RawLevels.scaleSteps(64, 0, 0.25));
        assertEquals(RawLevels.NO_SCALE, RawLevels.scaleSteps(0, 64, 0.25));
    }

    /** Every declared depth is taken as it is when nothing says otherwise. */
    @Test public void everyDeclaredDepth() {
        for (int bits = 8; bits <= 16; bits += 2) {
            RawLevels.Levels l = resolve(all(RawLevels.pedestal(bits)), RawLevels.white(bits), null, 0, false);
            assertEquals(bits, l.bits);
            assertEquals(RawLevels.white(bits), l.white);
            assertArrayEquals(all(RawLevels.pedestal(bits)), l.black, 0f);
        }
    }

    @Test public void plainTenBit() {
        RawLevels.Levels l = resolve(B64, 1023, all(64), 1023, false);
        assertEquals(10, l.bits);
        assertEquals(1023, l.white);
        assertArrayEquals(B64, l.black, 0f);
        assertFalse(l.dynamicBlack);
    }

    /** P126: vivo X300 Ultra DCG - 14-bit samples, white 1023 and black 64 declared, dynamic black 1030.75 / 1021.75. */
    @Test public void dynamicBlackInWiderUnitsNamesTheDepth() {
        float[] dyn = {1030.75f, 1021.75f, 1021.75f, 1030.75f};
        for (boolean useDyn : new boolean[]{false, true}) {
            RawLevels.Levels l = resolve(B64, 1023, dyn, 1023, useDyn);
            assertEquals(14, l.bits);
            assertEquals(16383, l.white);
            assertArrayEquals("the frame's own pedestal, not 64 x 16", dyn, l.black, 0f);
            assertTrue(l.dynamicBlack);
        }
        RawLevels.Levels twelve = resolve(B64, 1023, all(256), 0, false);
        assertEquals(12, twelve.bits);
        assertEquals(4095, twelve.white);
        RawLevels.Levels sixteen = resolve(B64, 1023, all(4096), 0, false);
        assertEquals(16, sixteen.bits);
        assertEquals(65535, sixteen.white);
    }

    /** Realme GT8 Pro: dynamic black 16 for the static 64 over 10-bit samples - the dynamic black is in 8-bit units. */
    @Test public void narrowerDynamicBlackNeverNarrowsTheStream() {
        RawLevels.Levels on = resolve(B64, 1023, all(16), 1023, true);
        assertEquals(10, on.bits);
        assertEquals(1023, on.white);
        assertArrayEquals("16 in 8-bit units is 64", B64, on.black, 0f);
        RawLevels.Levels off = resolve(B64, 1023, all(16), 1023, false);
        assertArrayEquals(B64, off.black, 0f);
    }

    /** OPPO Find X8 Ultra: the black drifts 64 -> 60 - taken with the dynamic-black setting only. */
    @Test public void driftInTheSameUnits() {
        float[] dyn = {60, 61, 60, 62};
        assertArrayEquals(dyn, resolve(B64, 1023, dyn, 1023, true).black, 0f);
        assertArrayEquals(B64, resolve(B64, 1023, dyn, 1023, false).black, 0f);
    }

    @Test public void junkDynamicBlackIsIgnored() {
        assertArrayEquals("150 for 64: neither a drift nor a depth", B64, resolve(B64, 1023, all(150), 1023, true).black, 0f);
        assertEquals(10, resolve(B64, 1023, all(150), 1023, true).bits);
        assertArrayEquals("the pedestal lost (P114): the data floor's job", B64, resolve(B64, 1023, all(0), 1023, true).black, 0f);
    }

    @Test public void dynamicWhite() {
        assertEquals("same units: the frame's white", 1000, resolve(B64, 1023, null, 1000, false).white);
        assertEquals("8-bit units never narrow the stream", 1023, resolve(B64, 1023, null, 255, false).white);
        RawLevels.Levels wide = resolve(B64, 1023, null, 16383, false);
        assertEquals(14, wide.bits);
        assertEquals(16383, wide.white);
        assertArrayEquals("the static black scaled to 14 bits", all(1024), wide.black, 0f);
    }

    /** P104: 14-bit samples under white 1023 and no metadata to tell (vivo X500 Pro Max DCG). */
    @Test public void samplesProveTheDepth() {
        RawLevels.Levels l = RawLevels.resolve(B64, 1023, all(64), 1023, 14, 1900f, true, false, true);
        assertEquals(14, l.bits);
        assertEquals(16383, l.white);
        assertArrayEquals(all(1024), l.black, 0f);
        RawLevels.Levels kept = RawLevels.resolve(B64, 1023, null, 0, 14, 70f, true, false, true);
        assertArrayEquals("the samples sit at the declared black: the pedestal did not move", B64, kept.black, 0f);
        assertEquals(16383, kept.white);
    }

    @Test public void samplesContradictingTheMetadataWin() {
        RawLevels.Levels l = RawLevels.resolve(B64, 1023, all(1024), 1023, 0, 60f, true, false, true);
        assertEquals("a floor of 60 cannot sit under a 1024 pedestal", 10, l.bits);
        assertEquals(1023, l.white);
        assertArrayEquals(B64, l.black, 0f);
    }

    @Test public void wideSwitchedOff() {
        RawLevels.Levels l = RawLevels.resolve(B64, 1023, all(1030.75f), 1023, 14, 1900f, false, false, true);
        assertEquals(10, l.bits);
        assertEquals(1023, l.white);
        assertArrayEquals(B64, l.black, 0f);
    }

    @Test public void missingMetadata() {
        RawLevels.Levels l = resolve(null, 0, null, 0, false);
        assertEquals(10, l.bits);
        assertEquals(1023, l.white);
        assertArrayEquals(B64, l.black, 0f);
        RawLevels.Levels zero = resolve(all(0), 1023, all(1024), 0, false);
        assertEquals("static black 0: the dynamic one against the declared pedestal", 14, zero.bits);
    }

    @Test public void sampledBitsOfOneFrame() {
        assertEquals(14, RawLevels.sampledBits(16000, 1023, 64, 2000));
        assertEquals("12-bit range, floor on the 14-bit pedestal (DCG at night)", 14, RawLevels.sampledBits(3000, 1023, 64, 1000));
        assertEquals("12-bit range, floor elsewhere", 12, RawLevels.sampledBits(3000, 1023, 64, 400));
        assertEquals("floor unknown", 12, RawLevels.sampledBits(3000, 1023, -1, 1000));
        assertEquals("within the declared range", 0, RawLevels.sampledBits(1023, 1023, 64, 64));
        assertEquals("beyond 14 bits is junk", 0, RawLevels.sampledBits(60000, 1023, 64, 2000));
        assertEquals("the floor never makes 16 bits", 14, RawLevels.sampledBits(16000, 4095, 256, 4096));
    }

    @Test public void sessionNeedsTwoFramesAndKeepsTheDepth() {
        RawLevels.noteCamera(B64, 1023);
        RawLevels.noteSamples(1023, 14, 1900f);
        assertEquals("one frame proves nothing yet", 0, RawLevels.sampledBits());
        RawLevels.noteSamples(1023, 12, 1100f);
        assertEquals("both frames prove 12 bits at least", 12, RawLevels.sampledBits());
        RawLevels.noteSamples(1023, 14, 1900f);
        assertEquals(14, RawLevels.sampledBits());
        RawLevels.noteSamples(1023, 0, -1f);
        assertEquals("a dark frame does not take it back", 14, RawLevels.sampledBits());
        assertEquals(14, RawLevels.forFrame(B64, 1023, null, 0, false, true).bits);
        assertEquals("another camera's frame does not get it", 12, RawLevels.forFrame(all(256), 4095, null, 0, false, true).bits);
        RawLevels.noteCamera(all(256), 4095);
        assertEquals("another camera starts over", 0, RawLevels.sampledBits());
        RawLevels.noteCamera(B64, 1023);
        RawLevels.noteSamples(1023, 14, 1900f);
        RawLevels.noteSamples(1023, 14, 1900f);
        RawLevels.newSession(true);
        assertEquals("a new session starts over", 0, RawLevels.sampledBits());
    }

    /**
     * vivo X300 Ultra DCG 14 bit (owner's log 2026-10-15): black 1022, the scaled white 16383, but the blown window clipped at
     * 8694 = 1022 + 8 x (1023 - 64) - every channel of the merge at 0.4995, the equal raw channels pink after the white balance.
     */
    @Test public void measuredClipIsTheWhiteOfAWiderStream() {
        RawLevels.Levels guess = RawLevels.resolve(B64, 1023, all(1022f), 1023, 14, 1045f, 0, true, false, true);
        assertEquals(14, guess.bits);
        assertEquals("without a clip seen: the scaled white", 16383, guess.white);
        RawLevels.Levels measured = RawLevels.resolve(B64, 1023, all(1022f), 1023, 14, 1045f, 8694, true, false, true);
        assertEquals(8694, measured.white);
        assertArrayEquals(all(1022f), measured.black, 0f);
        assertTrue(measured.source, measured.source.contains("measured clip 8694"));
        assertEquals("the declared depth keeps its white", 1023,
                RawLevels.resolve(B64, 1023, all(64f), 1023, 0, -1f, 900, true, false, true).white);
        assertEquals("a clip above the scaled white changes nothing", 16383,
                RawLevels.resolve(B64, 1023, all(1022f), 1023, 14, 1045f, 16383, true, false, true).white);
        assertEquals("a level at the black is no clip", 16383,
                RawLevels.resolve(B64, 1023, all(1022f), 1023, 14, 1045f, 1500, true, false, true).white);
        // the session: the highest plateau is kept, the shot's frame gets it, a new session forgets it
        RawLevels.noteCamera(B64, 1023);
        RawLevels.noteSamples(1023, 14, 1045f);
        RawLevels.noteSamples(1023, 14, 1045f);
        RawLevels.noteSaturation(8694);
        RawLevels.noteSaturation(7000);
        assertEquals(8694, RawLevels.sampledSaturation());
        assertEquals(8694, RawLevels.forFrame(B64, 1023, all(1022f), 1023, false, true).white);
        RawLevels.newSession(true);
        assertEquals(0, RawLevels.sampledSaturation());
        assertEquals(16383, RawLevels.resolve(B64, 1023, all(1022f), 1023, 14, 1045f, RawLevels.sampledSaturation(), true, false, true).white);
    }

    /** The clip plateau of a frame's samples: a blown window piles up on one value, bright content spreads with its noise. */
    @Test public void clipPlateauOfTheSamples() {
        java.util.Random r = new java.util.Random(7);
        int n = 8192;
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = 1022 + (int) (r.nextDouble() * 5000);    // the room
        for (int i = 0; i < 1200; i++) s[i * 6] = 8694;                               // the blown window (15 %)
        s[5] = 9400; s[11] = 9900;                                                     // two hot pixels above the clip
        assertEquals(8694, RawPayloadCheck.clipPlateau(s, n));
        int[] smooth = new int[n];
        for (int i = 0; i < n; i++) smooth[i] = 1022 + (int) (7600.0 * i / n + r.nextGaussian() * 40); // a bright gradient
        assertEquals("unclipped bright content", 0, RawPayloadCheck.clipPlateau(smooth, n));
        int[] few = s.clone();
        for (int i = 0; i < 1200; i++) few[i * 6] = 1022 + (int) (r.nextDouble() * 5000);
        for (int i = 0; i < 10; i++) few[i * 6] = 8694;
        assertEquals("ten samples are no plateau", 0, RawPayloadCheck.clipPlateau(few, n));
    }

    @Test public void sessionMetadataForFramesWithoutResult() {
        RawLevels.noteMetadata(B64, 1023, all(1030.75f), 1023);
        assertTrue(RawLevels.metadataWider());
        RawLevels.noteMetadata(B64, 1023, all(64), 1023);
        assertFalse(RawLevels.metadataWider());
        RawLevels.newSession(false);
        RawLevels.noteMetadata(B64, 1023, all(1030.75f), 1023);
        assertFalse("raw_wide 0", RawLevels.metadataWider());
    }
}
