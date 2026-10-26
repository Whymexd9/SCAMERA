package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** The lab's stage view: stages.bin as the worker writes it (scam-hybrid.h hybridWriteStages) and the maps' colours (P344). */
public class ScamLabStagesTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    private static void record(ByteArrayOutputStream out, String name, int frame, int role, int w, int h, int ch, int fill) {
        final byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        final ByteBuffer b = ByteBuffer.allocate(4 * 7 + n.length + w * h * ch).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x31475453).putInt(n.length).put(n).putInt(frame).putInt(role).putInt(w).putInt(h).putInt(ch);
        for (int i = 0; i < w * h * ch; i++) b.put((byte) fill);
        out.write(b.array(), 0, b.capacity());
    }

    private File file(boolean cutoff) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        record(out, "base", -1, 0, 4, 3, 3, 100);
        record(out, "merged", -1, 0, 4, 3, 3, 120);
        if (cutoff) record(out, "cutoff", -1, 0, 4, 3, 1, 0);
        record(out, "accepted", -1, 0, 4, 3, 1, 200);
        for (int f = 2; f <= 3; f++) {
            record(out, "raw", f, 1, 4, 3, 1, 250);
            record(out, "robust", f, 1, 4, 3, 1, 240);
        }
        final File f = tmp.newFile(cutoff ? "stages.bin" : "old.bin");
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(out.toByteArray()); }
        return f;
    }

    @Test public void theCutoffIsTheMergesOwnMapAfterTheFrameWeights() throws IOException {
        final ScamMergeStages s = ScamMergeStages.read(file(true));
        assertEquals(Arrays.asList("base", "raw", "robust", "cutoff", "accepted", "merged"), s.kinds());
        assertFalse(ScamMergeStages.perDonor(ScamMergeStages.CUTOFF));
        assertEquals(Collections.emptyList(), s.frames(ScamMergeStages.CUTOFF));
        final ScamMergeStages.Map m = s.find(ScamMergeStages.CUTOFF, 3);
        assertNotNull(m);
        assertEquals(-1, m.frame);
        assertEquals(1, m.channels);
        assertEquals(4, m.width);
        assertEquals(Arrays.asList(2, 3), s.frames(ScamMergeStages.RAW));
    }

    /** An older worker writes no cutoff: the stage list has none and nothing else changes. */
    @Test public void anOlderWorkersStagesListNoCutoff() throws IOException {
        final ScamMergeStages s = ScamMergeStages.read(file(false));
        assertEquals(Arrays.asList("base", "raw", "robust", "accepted", "merged"), s.kinds());
        assertNull(s.find(ScamMergeStages.CUTOFF, -1));
    }

    @Test public void theCutoffIsPlainGrey() {
        assertEquals(0xFF000000, ScamMergeStages.pixel(ScamMergeStages.CUTOFF, 0));
        assertEquals(0xFF808080, ScamMergeStages.pixel(ScamMergeStages.CUTOFF, 128));
        assertEquals(0xFFFFFFFF, ScamMergeStages.pixel(ScamMergeStages.CUTOFF, 255));
    }

    /** P344: a weight from 0.97 up is plain white (accepted); the noise-level band stays light, a rejection runs red to black. */
    @Test public void theWeightRampHasAnAcceptedPlateau() {
        for (String k : new String[]{ScamMergeStages.RAW, ScamMergeStages.ROBUST, ScamMergeStages.ACCEPTED}) {
            assertEquals(0xFFFFFFFF, ScamMergeStages.pixel(k, 255));
            assertEquals(0xFFFFFFFF, ScamMergeStages.pixel(k, ScamMergeStages.ACCEPT));
            assertEquals(0xFF000000, ScamMergeStages.pixel(k, 0));
        }
        assertEquals(247, ScamMergeStages.ACCEPT);
        assertEquals(0xFFFBEFC8, ScamMergeStages.weight(ScamMergeStages.ACCEPT - 1));
        assertEquals(0xFFF3B23A, ScamMergeStages.weight(200));
        // 0.85 (217): light, between amber and cream; every colour below the plateau is not white
        final int n = ScamMergeStages.weight(217);
        assertTrue(((n >> 16) & 255) > 240 && ((n >> 8) & 255) > 180);
        // darker as the weight falls: the sum of the channels never rises
        int last = Integer.MAX_VALUE;
        for (int v = ScamMergeStages.ACCEPT - 1; v >= 0; v--) {
            final int c = ScamMergeStages.weight(v), sum = ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255);
            assertNotEquals(0xFFFFFFFF, c);
            assertTrue("weight " + v, sum <= last);
            last = sum;
        }
    }

    /** The F6 offset and the Bento / blend masks keep the frame map's ramp. */
    @Test public void theOtherMapsKeepTheFrameMapRamp() {
        assertEquals(0xFFFDE725, ScamMergeStages.pixel(ScamMergeStages.FLOW, 255));
        assertEquals(0xFF1B0C41, ScamMergeStages.pixel(ScamMergeStages.BENTO, 0));
    }
}
