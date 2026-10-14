package com.particlesdevs.photoncamera.processing.ultrahdr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;

import org.junit.Test;

/** P125: the ISO 21496-1 metadata of the HEIC / AVIF gain map, byte for byte as libavif writes it. */
public class IsoGainMapTest {
    @Test public void toneMapPayloadLayout() {
        final IsoGainMap m = new IsoGainMap(0, 2.5, 2.5);
        final byte[] p = m.toneMapPayload();
        assertEquals("version + versions + flags + 2 headrooms + 1 channel", 62, p.length);
        final ByteBuffer b = ByteBuffer.wrap(p);
        assertEquals(0, b.get());          // ToneMapImage version
        assertEquals(0, b.getShort());     // minimum_version
        assertEquals(0, b.getShort());     // writer_version
        assertEquals("one channel, the base colour space", 0x40, b.get() & 0xFF);
        assertEquals(0, b.getInt());       // base headroom 0 (SDR base)
        assertEquals(IsoGainMap.DENOMINATOR, b.getInt());
        assertEquals(2_500_000, b.getInt()); // alternate headroom 2.5 stops
        assertEquals(IsoGainMap.DENOMINATOR, b.getInt());
        assertEquals(0, b.getInt());       // gain map min
        b.getInt();
        assertEquals(2_500_000, b.getInt()); // gain map max
        b.getInt();
        assertEquals(1_000_000, b.getInt()); // gamma 1
        b.getInt();
        assertEquals("1/64 offsets", 15_625, b.getInt());
        b.getInt();
        assertEquals(15_625, b.getInt());
        assertEquals(IsoGainMap.DENOMINATOR, b.getInt());
    }

    @Test public void valuesInTheNativeOrder() {
        assertArrayEquals(new double[]{0, 3, 1, 1.0 / 64, 1.0 / 64, 0, 3}, new IsoGainMap(0, 3, 3).values(), 1e-9);
    }

    @Test public void fractionsClamp() {
        assertEquals(0, IsoGainMap.unsigned(-1));
        assertEquals(-2_000_000, IsoGainMap.signed(-2));
        assertEquals("no int overflow", 2_147_000_000, IsoGainMap.signed(5000));
    }
}
