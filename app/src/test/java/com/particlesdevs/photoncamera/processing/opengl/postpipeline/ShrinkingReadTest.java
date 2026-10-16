package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLBasePipeline;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * P179 (200 MP: LOW_MEMORY): a large worker result read from its end with the memfd cut behind each slice - the same bytes as
 * one read, the source shorter after every slice; and main2 of the pipeline made on its first use only.
 */
public class ShrinkingReadTest {
    @Test
    public void readsTheSameBytesFromTheEndAndCutsBehindEachSlice() throws Exception {
        final int head = 7, n = 1_000_003;
        final byte[] file = new byte[head + n + 50];
        for (int i = 0; i < file.length; i++) file[i] = (byte) (i * 31 + (i >> 9));
        final long[] size = {file.length};
        final List<Long> cuts = new ArrayList<>();
        ScamNeuralClient.PositionalReader reader = (dst, at) -> {
            if (at >= size[0]) return -1;
            int k = (int) Math.min(dst.remaining(), Math.min(4096, size[0] - at));
            dst.put(file, (int) at, k);
            return k;
        };
        ScamNeuralClient.Truncatable source = s -> {
            assertTrue("only shorter", s <= size[0]);
            size[0] = s;
            cuts.add(s);
        };
        ByteBuffer dst = ByteBuffer.allocate(n);
        ScamNeuralClient.readShrinking(reader, source, dst, head, 100_000);
        assertEquals(n, dst.position());
        byte[] want = new byte[n];
        System.arraycopy(file, head, want, 0, n);
        assertArrayEquals(want, dst.array());
        assertEquals("one cut per slice", 11, cuts.size());
        for (int i = 1; i < cuts.size(); i++) assertTrue("from the end back", cuts.get(i) < cuts.get(i - 1));
        assertEquals("down to the result's start", head, (long) cuts.get(cuts.size() - 1));
    }

    @Test
    public void main2IsMadeOnItsFirstUseOnly() {
        GLBasePipeline p = new GLBasePipeline("test");
        GLTexture one = mock(GLTexture.class), two = mock(GLTexture.class);
        int[] made = {0};
        p.main1 = one;
        p.lazyMain2 = () -> { made[0]++; return two; };
        assertSame(one, p.getMain());
        assertEquals("not before it is asked for", 0, made[0]);
        assertSame(two, p.getMain());
        assertSame(one, p.getMain());
        assertSame(two, p.getMain());
        assertEquals("once", 1, made[0]);
        assertNull(p.lazyMain2);
    }
}
