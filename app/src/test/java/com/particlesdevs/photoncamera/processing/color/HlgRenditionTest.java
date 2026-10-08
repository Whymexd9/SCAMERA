package com.particlesdevs.photoncamera.processing.color;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.particlesdevs.photoncamera.processing.heif.P010;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

/**
 * P46 «HDR в HEIC / AVIF»: the HLG transfer (ARIB STD-B67 / BT.2100), SDR white at 75 % HLG (BT.2408, 203 cd/m²), the
 * gain map applied for the HLG headroom, the BT.2020 conversion and the RGBA_1010102 picture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HlgRenditionTest {
    @Test
    public void oetfKnownPoints() {
        assertEquals(0.0, HlgRendition.oetf(0.0), 0);
        assertEquals(0.5, HlgRendition.oetf(1.0 / 12.0), 1e-9);
        assertEquals(1.0, HlgRendition.oetf(1.0), 1e-6);
        // continuity at 1/12 (both branches)
        assertEquals(HlgRendition.oetf(1.0 / 12.0 - 1e-9), HlgRendition.oetf(1.0 / 12.0 + 1e-9), 1e-6);
        for (int i = 0; i <= 1000; i++) {
            final double e = i / 1000.0;
            assertEquals(e, HlgRendition.inverseOetf(HlgRendition.oetf(e)), 1e-9);
        }
    }

    @Test
    public void sdrWhiteIsSeventyFivePercent() {
        final double[] out = new double[3];
        HlgRendition.encode(1, 1, 1, out);
        for (double v : out) assertEquals("BT.2408: 203 cd/m² = 75 % HLG", 0.75, v, 0.001);
        HlgRendition.encode(0, 0, 0, out);
        for (double v : out) assertEquals(0.0, v, 0);
        // 18 % grey: 38 % HLG in BT.2408's table (22 cd/m² ... 26 cd/m² region): check the order of magnitude and monotony
        HlgRendition.encode(0.18, 0.18, 0.18, out);
        assertTrue(out[0] > 0.3 && out[0] < 0.45);
        // the nominal peak (1000 cd/m² = headroom 4.93) is 100 % HLG, reached through the knee only asymptotically
        HlgRendition.encode(100, 100, 100, out);
        assertTrue(out[0] > 0.99 && out[0] <= 1.0);
    }

    @Test
    public void decodeInvertsEncodeBelowTheKnee() {
        final double[] hlg = new double[3], back = new double[3];
        final double[][] colours = {{1, 1, 1}, {0.5, 0.2, 0.1}, {0.05, 0.3, 0.9}, {2.5, 2.0, 1.0}, {0.001, 0.002, 0.003}};
        for (double[] c : colours) {
            HlgRendition.encode(c[0], c[1], c[2], hlg);
            HlgRendition.decode(hlg[0], hlg[1], hlg[2], back);
            for (int i = 0; i < 3; i++) assertEquals(c[i], back[i], 1e-6 + 1e-6 * c[i]);
        }
    }

    @Test
    public void kneeKeepsTheHeadroom() {
        final double p = HlgRendition.headroom(), k = HlgRendition.KNEE * p;
        assertEquals(1000.0 / 203.0, p, 1e-12);
        assertEquals(k * 0.9, HlgRendition.knee(k * 0.9), 0);
        double last = 0;
        for (double m = k; m < 100; m += 0.01) {
            final double v = HlgRendition.knee(m);
            assertTrue(v >= last && v <= p);
            last = v;
        }
        // slope 1 at the knee
        assertEquals(1.0, (HlgRendition.knee(k + 1e-6) - HlgRendition.knee(k)) / 1e-6, 1e-4);
    }

    @Test
    public void rendererWithoutBoostIsTheSdrPicture() {
        // A flat map at GainMapMin = 0: every pixel is the SDR picture in HLG (white at 75 %).
        final HlgRendition.Renderer tenBit = new HlgRendition.Renderer(true, OutputColour.Space.SRGB, 0f, 3f, 3f);
        final HlgRendition.Renderer eightBit = new HlgRendition.Renderer(false, OutputColour.Space.SRGB, 0f, 3f, 3f);
        final int white = tenBit.pixel(P010.pack(1023, 1023, 1023), 0f);
        assertEquals(767, white & 0x3FF, 1);
        assertEquals(767, (white >>> 10) & 0x3FF, 1);
        assertEquals(767, (white >>> 20) & 0x3FF, 1);
        assertEquals(3, white >>> 30);
        assertEquals(white, eightBit.pixel(0xFFFFFFFF, 0f));
        assertEquals(P010.pack(0, 0, 0), tenBit.pixel(P010.pack(0, 0, 0), 0f));
        // monotone in the base value
        int last = -1;
        for (int v = 0; v < 1024; v += 7) {
            final int g = (tenBit.pixel(P010.pack(v, v, v), 0f) >>> 10) & 0x3FF;
            assertTrue(g >= last);
            last = g;
        }
    }

    @Test
    public void boostGoesIntoTheHeadroom() {
        final HlgRendition.Renderer r = new HlgRendition.Renderer(true, OutputColour.Space.SRGB, 0f, 2f, 2f);
        // weight: min(1, log2(1000 / 203) / 2) = 1: the full boost of 2 stops at map value 255
        assertEquals(1.0, HlgRendition.Renderer.weight(2f), 1e-12);
        assertEquals(Math.log(1000.0 / 203.0) / Math.log(2) / 6.0, HlgRendition.Renderer.weight(6f), 1e-12);
        assertEquals(4.0, r.gain(255f), 1e-5);
        assertEquals(1.0, r.gain(0f), 1e-6);
        final int grey = P010.pack(512, 512, 512);
        final int plain = (r.pixel(grey, 0f) >>> 10) & 0x3FF, boosted = (r.pixel(grey, 255f) >>> 10) & 0x3FF;
        assertTrue("boosted " + boosted + " > plain " + plain, boosted > plain + 100);
        final int white = (r.pixel(P010.pack(1023, 1023, 1023), 255f) >>> 10) & 0x3FF;
        assertTrue("white x4 above 75 %: " + white, white > 900 && white <= 1023);
    }

    @Test
    public void primariesGoToBt2020() {
        final HlgRendition.Renderer srgb = new HlgRendition.Renderer(true, OutputColour.Space.SRGB, 0f, 1f, 1f);
        final HlgRendition.Renderer p3 = new HlgRendition.Renderer(true, OutputColour.Space.DISPLAY_P3, 0f, 1f, 1f);
        final int red = P010.pack(1023, 0, 0);
        final int a = srgb.pixel(red, 0f), b = p3.pixel(red, 0f);
        // sRGB red in BT.2020: (0.627, 0.069, 0.016) - every channel lit; P3 red is more saturated: less green in BT.2020
        assertTrue((a >>> 10 & 0x3FF) > 0 && (a >>> 20 & 0x3FF) > 0);
        assertTrue("P3 red has less green in BT.2020", (b >>> 10 & 0x3FF) < (a >>> 10 & 0x3FF));
        assertTrue((b & 0x3FF) > (a & 0x3FF));
    }

    private static Bitmap tenBit(int w, int h, int[] px) {
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_1010102);
        final ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        buf.asIntBuffer().put(px);
        b.copyPixelsFromBuffer(buf);
        return b;
    }

    private static int[] pixels(Bitmap b) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(b.getByteCount()).order(ByteOrder.nativeOrder());
        b.copyPixelsToBuffer(buf);
        buf.rewind();
        final int[] out = new int[b.getWidth() * b.getHeight()];
        buf.asIntBuffer().get(out);
        return out;
    }

    /** A gain-map result with the map values {@code v(x, y)} (R = G = B). */
    private static GainMapComputer.Result map(int w, int h, float max, java.util.function.IntBinaryOperator v) throws Exception {
        final Bitmap m = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int g = v.applyAsInt(x, y);
                m.setPixel(x, y, Color.rgb(g, g, g));
            }
        final java.lang.reflect.Constructor<GainMapComputer.Result> c =
                GainMapComputer.Result.class.getDeclaredConstructor(Bitmap.class, float.class, float.class);
        c.setAccessible(true);
        return c.newInstance(m, 0f, max);
    }

    @Test
    public void bitmapIsTheRendererPixelByPixel() throws Exception {
        final int w = 70, h = 150; // more rows than one band, odd width
        final int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[y * w + x] = P010.pack(x * 1023 / (w - 1), y * 1023 / (h - 1), (x * 7 + y * 3) & 1023);
        final Bitmap base = tenBit(w, h, px);
        final GainMapComputer.Result gain = map(w, h, 2.5f, (x, y) -> (x * 3 + y) & 255);
        final Bitmap out = HlgRendition.render(base, gain, OutputColour.Space.DISPLAY_P3);
        assertEquals(Bitmap.Config.RGBA_1010102, out.getConfig());
        assertEquals(w, out.getWidth());
        assertEquals(h, out.getHeight());
        assertFalse("the base is kept", base.isRecycled());
        final HlgRendition.Renderer r = new HlgRendition.Renderer(true, OutputColour.Space.DISPLAY_P3, 0f, 2.5f, gain.hdrCapacityMax);
        final int[] got = pixels(out);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                assertEquals("pixel " + x + "," + y, r.pixel(px[y * w + x], (x * 3 + y) & 255), got[y * w + x]);
    }

    @Test
    public void eightBitBaseAndASmallerMap() throws Exception {
        final int w = 40, h = 20;
        final Bitmap base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        base.eraseColor(Color.rgb(200, 200, 200));
        // a 4 x 2 map: left half 0, right half 255 -> the HLG brightness rises left to right, monotone
        final GainMapComputer.Result gain = map(4, 2, 2f, (x, y) -> x < 2 ? 0 : 255);
        final int[] got = pixels(HlgRendition.render(base, gain, OutputColour.Space.SRGB));
        int last = -1;
        for (int x = 0; x < w; x++) {
            final int g = (got[10 * w + x] >>> 10) & 0x3FF;
            assertTrue(g >= last);
            last = g;
        }
        final HlgRendition.Renderer r = new HlgRendition.Renderer(false, OutputColour.Space.SRGB, 0f, 2f, gain.hdrCapacityMax);
        assertEquals(r.pixel(0xFFC8C8C8, 0f), got[10 * w]);
        assertEquals(r.pixel(0xFFC8C8C8, 255f), got[10 * w + w - 1]);
    }
}
