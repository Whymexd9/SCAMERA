package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

import static org.junit.Assert.*;

public class ScamLabBurstTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final int W = 128, H = 96, N = 3;

    /** Pixel (x, y) of frame i. */
    static short px(int i, int x, int y) {
        return (short) (i * 10000 + y * W + x);
    }

    /** An NCH v11 (planes after the table) or v12 (planes at their pages) burst, flags 1 | 2 | 4, grid 2, block 2. */
    File burst(int version) throws Exception {
        final File f = tmp.newFile("burst-v" + version + ".nch");
        final long plane = (long) W * H * 2;
        final ByteBuffer h = ByteBuffer.allocate(128 + 32 * N).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(ScamLabBurst.MAGIC).putInt(version).putInt(W).putInt(H).putInt(3).putInt(N).putFloat(1023f);
        for (int k = 0; k < 4; k++) h.putFloat(64f);
        h.putInt(1 | 2 | 4).putInt(0).putInt(2).putInt(2).putInt(0).putInt(0);
        h.position(128);
        final long[] pages = new long[N];
        for (int i = 0; i < N; i++) {
            pages[i] = 1 + i * ((plane + 4095) / 4096);
            h.putInt(1).putFloat(1f).putInt(100 + i).putFloat(1e-4f).putFloat(1e-6f).putFloat(i * 33f).putInt(0).putInt(version == 12 ? (int) pages[i] : 0);
        }
        h.flip();
        try (FileChannel out = new FileOutputStream(f).getChannel()) {
            out.write(h, 0);
            for (int i = 0; i < N; i++) {
                final ByteBuffer p = ByteBuffer.allocate((int) plane).order(ByteOrder.LITTLE_ENDIAN);
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) p.putShort(px(i, x, y));
                p.flip();
                out.write(p, version == 12 ? pages[i] * 4096 : 128 + 32L * N + i * plane);
            }
        }
        return f;
    }

    ByteBuffer transport(ScamLabBurst b) throws Exception {
        final File out = tmp.newFile();
        b.write(out);
        final byte[] bytes = java.nio.file.Files.readAllBytes(out.toPath());
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test public void aFragmentIsAV11TransportOfThatWindowOfEveryFrame() throws Exception {
        for (int version : new int[]{11, 12}) {
            final ScamLabBurst b = new ScamLabBurst(burst(version), new int[]{40, 33, 64, 50}, "grid 2\n");
            assertArrayEquals(new int[]{32, 32, 64, 32}, b.crop);
            assertEquals(64, b.width());
            assertEquals(128, b.outputWidth());
            assertEquals(64, b.outputHeight());
            assertFalse(b.mergedDng());
            assertTrue(b.clipFlags());
            assertEquals("grid 2\n", b.burstTuning());
            assertFalse(b.keepable());
            assertNull(b.sharedBurst());
            final ByteBuffer t = transport(b);
            assertEquals(11, t.getInt(4));
            assertEquals(64, t.getInt(8));
            assertEquals(32, t.getInt(12));
            assertEquals(0, t.getInt(44) & 3);
            assertEquals(4, t.getInt(44) & 4);
            for (int i = 0; i < N; i++) {
                assertEquals(100 + i, t.getInt(128 + 32 * i + 8));
                assertEquals(0, t.getInt(128 + 32 * i + 28));
            }
            assertEquals(128 + 32 * N + N * 64 * 32 * 2, t.capacity());
            final int base = 128 + 32 * N;
            for (int i = 0; i < N; i++) for (int y : new int[]{0, 31}) for (int x : new int[]{0, 63})
                assertEquals("v" + version + " frame " + i, px(i, 32 + x, 32 + y), t.getShort(base + ((i * 32 + y) * 64 + x) * 2));
        }
    }

    @Test public void theWholeFrameOfAV12BurstIsWrittenAsV11() throws Exception {
        final ScamLabBurst b = new ScamLabBurst(burst(12), null, "");
        final ByteBuffer t = transport(b);
        assertEquals(11, t.getInt(4));
        assertEquals(128 + 32 * N + N * W * H * 2, t.capacity());
        final int base = 128 + 32 * N;
        assertEquals(px(2, 127, 95), t.getShort(base + ((2 * H + 95) * W + 127) * 2));
    }

    @Test public void theKeptBurstLosesOnlyItsDngAndDiagnosticsFlags() throws Exception {
        final File f = burst(11);
        assertTrue(ScamLab.clearReplayFlags(f));
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.seek(44);
            assertEquals(4, Integer.reverseBytes(r.readInt()));
        }
        assertEquals(4, new ScamLabBurst(f, null, "").flags);
    }

    @Test public void fragmentsStayInsideTheFrameOnWholeColourPeriods() {
        assertArrayEquals(new int[]{64, 0, 64, 64}, ScamLabBurst.aligned(new int[]{200, -5, 70, 70}, 128, 96));
        assertArrayEquals(new int[]{0, 0, 128, 96}, ScamLabBurst.aligned(new int[]{0, 0, 999, 999}, 128, 96));
    }

    @Test public void labFoldersAreNamedByDateAndTime() throws Exception {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, 9, 19, 15, 30, 12);
        assertEquals("2026-10-19_15-30-12", ScamLab.folderName(c.getTime()));
        final File root = tmp.newFolder("Lab");
        assertTrue(new File(root, "2026-10-19_15-30-12").mkdir());
        assertEquals("2026-10-19_15-30-12_2", ScamLab.newFolder(root, c.getTime()).getName());
    }
}
