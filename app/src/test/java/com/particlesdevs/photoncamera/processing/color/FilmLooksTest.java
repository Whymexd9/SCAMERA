package com.particlesdevs.photoncamera.processing.color;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P170: our five film looks as LUTs - in range, greys that never darken as they brighten, smooth between the nodes, each
 * with its own character; renders build/reports/film-luts/chart.png (and photo.png of a sample.jpg put next to it).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FilmLooksTest {
    private static float luma(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    private static float chroma(float[] c) {
        return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
    }

    @Test
    public void everyLookIsAWellBehavedLut() {
        String[] values = FilmLooks.values();
        assertEquals(5, values.length);
        for (String v : values) {
            assertTrue(v, FilmLooks.isBuiltIn(v));
            FilmLut lut = FilmLooks.lut(v);
            assertNotNull(v, lut);
            assertEquals(FilmLooks.SIZE, lut.size);
            assertEquals(FilmLooks.SIZE * FilmLooks.SIZE * FilmLooks.SIZE * 3, lut.rgb.length);
            for (float x : lut.rgb) assertTrue(v + " " + x, x >= 0f && x <= 1f);
            // the greys: brighter in, brighter out (no banding, no inversion)
            float last = -1f;
            for (int i = 0; i <= 256; i++) {
                float g = i / 256f;
                float y = luma(lut.sample(g, g, g));
                assertTrue(v + " grey " + g, y >= last - 1e-5f);
                last = y;
            }
            // smooth: neighbouring nodes close (a 33-point LUT of a smooth look)
            int n = lut.size;
            float worst = 0f;
            for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r + 1 < n; r++) {
                int at = ((b * n + g) * n + r) * 3;
                for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(lut.rgb[at + 3 + c] - lut.rgb[at + c]));
            }
            assertTrue(v + " jumps " + worst, worst < 0.12f);
            // a look, not the identity
            float diff = 0f;
            for (int i = 0; i < 64; i++) {
                float[] in = {(i % 4) / 3f, ((i / 4) % 4) / 3f, (i / 16) / 3f};
                float[] out = lut.sample(in[0], in[1], in[2]);
                for (int c = 0; c < 3; c++) diff += Math.abs(out[c] - in[c]);
            }
            assertTrue(v + " too close to the identity: " + diff / 192f, diff / 192f > 0.02f);
        }
        assertNull(FilmLooks.lut("Kodak 2383.cube"));
        assertNull(FilmLooks.lut("scam:none"));
    }

    @Test
    public void eachLookHasItsCharacter() {
        float[] mid = FilmLooks.apply("scam:warm400", 0.5f, 0.5f, 0.5f);
        assertTrue("warm: a warm grey", mid[0] > mid[2] + 0.01f);
        float[] black = FilmLooks.apply("scam:warm400", 0f, 0f, 0f);
        assertTrue("warm: lifted blacks", luma(black) > 0.02f);
        float[] shadow = FilmLooks.apply("scam:cool160", 0.15f, 0.15f, 0.15f);
        assertTrue("cool: cyan shadows", shadow[2] > shadow[0] && shadow[1] > shadow[0]);
        float[] sky = {0.25f, 0.45f, 0.85f};
        float[] slideSky = FilmLooks.apply("scam:slide100", sky[0], sky[1], sky[2]);
        assertTrue("slide: denser, more saturated blues", chroma(slideSky) > chroma(sky) && luma(slideSky) < luma(sky) + 0.02f);
        float lo = luma(FilmLooks.apply("scam:slide100", 0.25f, 0.25f, 0.25f)), hi = luma(FilmLooks.apply("scam:slide100", 0.75f, 0.75f, 0.75f));
        assertTrue("slide: more contrast", hi - lo > 0.6f);
        float[] cineShadow = FilmLooks.apply("scam:cine500", 0.12f, 0.12f, 0.12f), cineLight = FilmLooks.apply("scam:cine500", 0.85f, 0.85f, 0.85f);
        assertTrue("cine: teal shadows", cineShadow[2] > cineShadow[0]);
        assertTrue("cine: warm highlights", cineLight[0] > cineLight[2]);
        float[] leaf = {0.30f, 0.55f, 0.20f};
        float[] cineLeaf = FilmLooks.apply("scam:cine500", leaf[0], leaf[1], leaf[2]);
        assertTrue("cine: quieter greens", chroma(cineLeaf) < chroma(leaf));
        float[] mono = FilmLooks.apply("scam:mono400", 0.8f, 0.3f, 0.1f);
        assertEquals(mono[0], mono[1], 1e-6f);
        assertEquals(mono[1], mono[2], 1e-6f);
        // the cache hands the same LUT back; no file is read for a look
        FilmLut.forget();
        FilmLut a = FilmLut.chosen(null, "scam:cine500");
        assertNotNull(a);
        assertSame(a, FilmLut.chosen(null, "scam:cine500"));
        FilmLut.forget();
    }

    @Test
    public void rendersTheLooksForReview() throws Exception {
        File dir = new File("build/reports/film-luts");
        dir.mkdirs();
        // a chart: skin, sky, foliage, primaries and a grey ramp
        float[][] patches = {{0.85f, 0.62f, 0.50f}, {0.62f, 0.42f, 0.33f}, {0.25f, 0.45f, 0.85f}, {0.55f, 0.70f, 0.90f},
                {0.30f, 0.55f, 0.20f}, {0.55f, 0.65f, 0.30f}, {0.80f, 0.15f, 0.12f}, {0.95f, 0.75f, 0.10f},
                {0.10f, 0.35f, 0.60f}, {0.45f, 0.25f, 0.55f}};
        int cell = 48, ramp = 24, w = patches.length * cell;
        Bitmap chart = Bitmap.createBitmap(w, cell + ramp, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < w; x++) {
            float[] p = patches[x / cell];
            for (int y = 0; y < cell; y++) chart.setPixel(x, y, pack(p));
            float g = x / (float) (w - 1);
            for (int y = cell; y < cell + ramp; y++) chart.setPixel(x, y, pack(new float[]{g, g, g}));
        }
        save(sheet(chart), new File(dir, "chart.png"));
        File sample = new File(dir, "sample.jpg");   // a picture put there by hand (none in CI)
        if (sample.isFile()) {
            Bitmap photo = BitmapFactory.decodeFile(sample.getPath());
            int pw = 480, ph = Math.round(photo.getHeight() * pw / (float) photo.getWidth());
            save(sheet(Bitmap.createScaledBitmap(photo, pw, ph, true)), new File(dir, "photo.png"));
        }
    }

    private static void save(Bitmap b, File f) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** The original and the five looks, two columns, named. */
    private static Bitmap sheet(Bitmap src) {
        String[] values = FilmLooks.values();
        int w = src.getWidth(), h = src.getHeight(), label = 26, cols = 2, rows = (values.length + 2) / cols;
        Bitmap out = Bitmap.createBitmap(w * cols, (h + label) * rows, Bitmap.Config.ARGB_8888);
        out.eraseColor(0xFF1B1F23);
        Canvas canvas = new Canvas(out);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFE6E8EA);
        text.setTextSize(16);
        text.setFakeBoldText(true);
        int[] px = new int[w * h];
        for (int k = 0; k <= values.length; k++) {
            FilmLut lut = k == 0 ? null : FilmLooks.lut(values[k - 1]);
            src.getPixels(px, 0, w, 0, 0, w, h);
            if (lut != null) for (int i = 0; i < px.length; i++) {
                int p = px[i];
                px[i] = pack(lut.sample(((p >> 16) & 255) / 255f, ((p >> 8) & 255) / 255f, (p & 255) / 255f));
            }
            Bitmap tile = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            int ox = (k % cols) * w, oy = (k / cols) * (h + label);
            canvas.drawText(k == 0 ? "Original" : lut.title, ox + 8, oy + 19, text);
            canvas.drawBitmap(tile, ox, oy + label, null);
        }
        return out;
    }

    private static int pack(float[] c) {
        int r = Math.round(Math.max(0f, Math.min(1f, c[0])) * 255f), gg = Math.round(Math.max(0f, Math.min(1f, c[1])) * 255f),
                b = Math.round(Math.max(0f, Math.min(1f, c[2])) * 255f);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }
}
