package com.particlesdevs.photoncamera.processing.color;

import android.app.Application;
import android.media.MediaFormat;

import com.particlesdevs.photoncamera.processing.heif.HeifContainerWriterTestAccess;
import com.particlesdevs.photoncamera.processing.heif.P010;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.MessageDigest;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;

/**
 * P46: with «Цветовое пространство» sRGB and «HDR в HEIC / AVIF» off (the defaults) the encoders produce exactly what they
 * produced before the colour options existed. The SHA-256 values below were taken from the code at a3e94db (before P46):
 * the 10-bit HEIF container of a fixed grid, the RGBA_1010102 -> P010 conversion of a fixed random band, and the HEVC
 * MediaFormat of the 10-bit encoder (CQ and VBR).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DefaultOutputGoldenTest {
    static final String CONTAINER_SHA256 = "b37598a743f767e7c6886619e2940a78fbfe3d6406d256e670a16ca0539846b9";
    static final String P010_SHA256 = "98cef5f9c1a927459770c6e6d144333c19d680e705281d99f0f42f61d68d2e9c";
    static final String FORMAT_CQ = "{bitrate-mode=0, color-format=54, color-range=1, color-standard=1, color-transfer=3, frame-rate=30, "
            + "height=512, i-frame-interval=0, max-bframes=0, mime=video/hevc, profile=2, quality=77, width=512}";
    static final String FORMAT_VBR = "{bitrate=4000000, bitrate-mode=1, color-format=54, color-range=1, color-standard=1, color-transfer=3, "
            + "frame-rate=30, height=512, i-frame-interval=0, max-bframes=0, mime=video/hevc, profile=2, width=512}";

    static String sha256(byte[] data) throws Exception {
        final byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        final StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format("%02x", b & 0xFF));
        return s.toString();
    }

    @Test
    public void heifContainerOfTheDefaultColourIsUnchanged() throws Exception {
        final String got = sha256(HeifContainerWriterTestAccess.defaultSampleFile());
        assertEquals(CONTAINER_SHA256, got);
    }

    @Test
    public void p010OfTheDefaultMatrixIsUnchanged() throws Exception {
        final Random rnd = new Random(46);
        final int stride = 70, rows = 37;
        final int[] band = new int[stride * rows];
        for (int i = 0; i < band.length; i++) band[i] = P010.pack(rnd.nextInt(1024), rnd.nextInt(1024), rnd.nextInt(1024));
        final byte[] out = new byte[P010.tileBytes(64, 64)];
        P010.convertTile(band, stride, rows, 6, 60, 64, 64, out);
        final String got = sha256(out);
        assertEquals(P010_SHA256, got);
    }

    static String describe(MediaFormat f) {
        final TreeMap<String, String> m = new TreeMap<>();
        for (String k : f.getKeys()) {
            Object v;
            switch (f.getValueTypeForKey(k)) {
                case MediaFormat.TYPE_INTEGER: v = f.getInteger(k); break;
                case MediaFormat.TYPE_STRING: v = f.getString(k); break;
                case MediaFormat.TYPE_FLOAT: v = f.getFloat(k); break;
                case MediaFormat.TYPE_LONG: v = f.getLong(k); break;
                default: v = "?";
            }
            m.put(k, String.valueOf(v));
        }
        return m.toString();
    }

    @Test
    public void hevcFormatOfTheDefaultColourIsUnchanged() {
        final String cq = describe(HeifContainerWriterTestAccess.format(512, true, 77, 0));
        final String vbr = describe(HeifContainerWriterTestAccess.format(512, false, 0, 4_000_000));
        assertEquals(FORMAT_CQ, cq);
        assertEquals(FORMAT_VBR, vbr);
    }
}
