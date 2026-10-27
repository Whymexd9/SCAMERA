package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** P359: kept RAW series in the lab - their sidecars, a folder naming one, its header, white point and noise. */
public class ScamLabSeriesTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sidecarsSitBesideTheSeries() {
        final File b = new File("/x/Download/SCAMERA/scam_burst-20261026-232432.bin");
        assertEquals("scam_burst-20261026-232432.json", ScamLab.sidecar(b, ".json").getName());
        assertEquals("scam_burst-20261026-232432.frame.json", ScamLab.sidecar(b, ".frame.json").getName());
        assertEquals(b.getParentFile(), ScamLab.sidecar(b, ".jpg").getParentFile());
    }

    @Test
    public void anImportedFolderNamesItsSeries() throws Exception {
        final File series = new ScamLabBurstTest() {{ tmp = ScamLabSeriesTest.this.tmp; }}.burst(12);
        final File dir = tmp.newFolder("2026-10-26_23-24-32_imp");
        assertFalse(ScamLab.burstOf(dir).isFile());
        Files.write(new File(dir, ScamLab.SOURCE).toPath(), series.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        assertEquals(series.getAbsoluteFile(), ScamLab.burstOf(dir).getAbsoluteFile());
        assertArrayEquals(new int[]{ScamLabBurstTest.W, ScamLabBurstTest.H, 3, ScamLabBurstTest.N, 2, 2}, ScamLab.header(series));
        // the base frame's table entry: ISO 100, slope 1e-4, offset 1e-6
        final float[] noise = ScamLab.baseNoise(series);
        assertNotNull(noise);
        assertEquals(100f, noise[0], 0f);
        assertEquals(1e-4f, noise[1], 1e-9f);
        assertFalse(ScamLab.settingsNow(dir));
        ScamLab.settingsNow(dir, true);
        assertTrue(ScamLab.settingsNow(dir));
        ScamLab.settingsNow(dir, false);
        assertFalse(ScamLab.settingsNow(dir));
        assertNull(ScamLab.header(new File(dir, ScamLab.SOURCE)));
    }

    @Test
    public void greyWorldOfABayerPlane() {
        // RGGB (cfa 0), black 64, white 1023: R 0.2, G 0.4, B 0.3 of the range -> neutral 0.5, 1, 0.75
        final int w = 64, h = 64;
        final ByteBuffer p = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        final float[] level = {0.2f, 0.4f, 0.4f, 0.3f};
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                p.putShort((y * w + x) * 2, (short) Math.round(64 + level[(y & 1) * 2 + (x & 1)] * (1023 - 64)));
        final float[] n = ScamLab.greyWorld(p, w, h, 0, 1, new float[]{64, 64, 64, 64}, 1023f);
        assertNotNull(n);
        assertEquals(0.5f, n[0], 0.01f);
        assertEquals(1f, n[1], 0f);
        assertEquals(0.75f, n[2], 0.01f);
        // BGGR (cfa 3): the same samples read as blue at (0, 0)
        final float[] m = ScamLab.greyWorld(p, w, h, 3, 1, new float[]{64, 64, 64, 64}, 1023f);
        assertNotNull(m);
        assertEquals(0.75f, m[0], 0.01f);
        assertEquals(0.5f, m[2], 0.01f);
        // a clipped frame has no grey
        final ByteBuffer clipped = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < w * h; i++) clipped.putShort(i * 2, (short) 1023);
        assertNull(ScamLab.greyWorld(clipped, w, h, 0, 1, new float[]{64, 64, 64, 64}, 1023f));
    }
}
