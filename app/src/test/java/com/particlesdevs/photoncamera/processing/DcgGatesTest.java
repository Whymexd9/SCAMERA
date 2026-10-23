package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.particlesdevs.photoncamera.settings.SettingsAvailability;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/**
 * P320 DCG gates outside the merge: the ISO scale a DCG mode's ISO carries (from a measured clip only), the worker's two-zone
 * noise line, the clip plateau of a known-depth (14-bit) session, the shadow-frame rows under the DCG switch.
 */
public class DcgGatesTest {
    /** X300 Ultra DCG main: (8694 - 1030.75) / (1023 - 64) = 7.99 -> 8, the ISO 1336 given for 167 asked (HybridPlan P163). */
    @Test public void theIsoScaleIsTheRangeOverTheDeclaredRange() {
        assertEquals(8f, com.particlesdevs.photoncamera.processing.processor.ScamProcessor.isoScale(8694 - 1030.75, 1023 - 64), 0f);
        // P322: X200 Ultra DCG main (bursts of 2026-10-23): (16368 - 1028) / (1023 - 64) = 16.0, its ISO floor 1144 for 72
        assertEquals(16f, com.particlesdevs.photoncamera.processing.processor.ScamProcessor.isoScale(16368 - 1028, 1023 - 64), 0f);
        assertEquals("a plain stream", 1f, com.particlesdevs.photoncamera.processing.processor.ScamProcessor.isoScale(959, 959), 0f);
        assertEquals("no range", 1f, com.particlesdevs.photoncamera.processing.processor.ScamProcessor.isoScale(0, 959), 0f);
        assertEquals("not near a power of two", 3f, com.particlesdevs.photoncamera.processing.processor.ScamProcessor.isoScale(2877, 959), 0f);
    }

    @Test public void theWorkersDcgNoiseLineIsParsed() {
        String report = "HYBRID NOISE CHECK: green phase ... -> model x1\n"
                + "HYBRID NOISE CHECK DCG: slope=4.5e-05 hcg=1.2e-07 lcg=3.4e-06 knee=0.125 blocks=812/3072 zones=2\n";
        assertArrayEquals(new float[]{4.5e-5f, 1.2e-7f, 3.4e-6f, 0.125f},
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClientAccess.dcgNoise(report), 1e-12f);
        assertNull("not measured", com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClientAccess.dcgNoise(
                "HYBRID NOISE CHECK DCG: not measured (42 flat blocks)\n"));
        assertNull("no DCG line", com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClientAccess.dcgNoise(report.split("\n")[0]));
        assertNull("knee out of range", com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClientAccess.dcgNoise(
                "HYBRID NOISE CHECK DCG: slope=4.5e-05 hcg=1.2e-07 lcg=3.4e-06 knee=1.5 blocks=812/3072 zones=2\n"));
    }

    /** The worker's DCG tuning: Bento «Всегда» reads as «Авто», an explicit «Выкл» stays off; never Shasta or Mochi. */
    @Test public void theDcgTuningKeepsTheUsersBentoOff() {
        assertEquals("bento 1\nshastaEnable 0\nmochi 0\n",
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClientAccess.dcgTuning(2));
        assertEquals("shastaEnable 0\nmochi 0\n", com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClientAccess.dcgTuning(0));
        assertEquals("shastaEnable 0\nmochi 0\n", com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClientAccess.dcgTuning(1));
    }

    /** A 14-bit frame (known depth) that clips at 8694: the plateau is found although the frame is not checked. */
    @Test public void aKnownDepthFrameStillShowsItsClip() {
        final int w = 1024, h = 512;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        Random r = new Random(7);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double v = 1024 + 7000.0 * x / w + r.nextGaussian() * 20;
                if (x > w * 0.8) v = 8694;                                   // a blown window
                b.putShort((y * w + x) * 2, (short) Math.max(0, Math.min(8694, Math.round(v))));
            }
        assertEquals(8694, RawPayloadCheck.knownDepthClip(b, w, h, w * 2));
    }

    /** A DCG stream at its declared depth (Pixel 10 Pro main, RAW12 white 4095) takes its measured clip; a plain one never. */
    @Test public void aDeclaredDepthDcgStreamTakesItsClip() {
        final float[] black = {256, 256, 256, 256};
        try {
            RawLevels.newSession(true);
            RawLevels.noteCamera(black, 4095);
            RawLevels.noteSaturation(3600);
            assertEquals("plain: the declared white", 4095, RawLevels.forFrame(black, 4095, null, 0, false, false).white);
            RawLevels.noteDcgFrames(true);
            RawLevels.Levels dcg = RawLevels.forFrame(black, 4095, null, 0, false, false);
            assertEquals(dcg.toString(), 3600, dcg.white);
            assertEquals(true, dcg.source.contains("measured clip"));
            assertEquals("another camera's frame keeps its own white", 1023, RawLevels.forFrame(new float[]{64, 64, 64, 64}, 1023, null, 0, false, false).white);
        } finally {
            RawLevels.newSession(true);
        }
    }

    @Test public void theDcgSwitchExplainsTheShadowFrameRows() {
        Map<String, Object> values = new HashMap<>();
        assertNull(new SettingsAvailability(values).reason("pref_scam_hybrid_shasta_frames"));
        assertNotNull(new SettingsAvailability(values).dcg(true).reason("pref_scam_hybrid_shasta_frames"));
        assertNotNull(new SettingsAvailability(values).dcg(true).reason("pref_scam_hybrid_shasta"));
        assertNull("the highlight frames still act where the frame clips",
                new SettingsAvailability(values).dcg(true).reason("pref_scam_hybrid_bento_frames"));
    }

    /** The Pixel 10 Pro's main: the 6.9 mm lens on the full sensor; its 2x crop (4.9 mm wide) and the other lenses are not. */
    @Test public void thePixelMainIsTheDcgCamera() {
        assertEquals(true, com.particlesdevs.photoncamera.capture.PixelAccess.mainLens(6.9f, 9.8f));
        assertEquals(false, com.particlesdevs.photoncamera.capture.PixelAccess.mainLens(6.9f, 4.896f));
        assertEquals(false, com.particlesdevs.photoncamera.capture.PixelAccess.mainLens(2.02f, 5.6f));
        assertEquals(false, com.particlesdevs.photoncamera.capture.PixelAccess.mainLens(17.9f, 5.6f));
    }
}
