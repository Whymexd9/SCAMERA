package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

/** P289 (owner: the film sliders «не работают нормально»): every film slider changes the settings' preview. */
public class FilmEffectsTest {
    @BeforeClass
    public static void assets() {
        FilmSpectralTest.assets();
    }

    static FilmSim.Settings set(float grain, float halation, float softness, float ev) {
        return new FilmSim.Settings(FilmSim.stock("kodak_portra_400"), FilmSim.print("kodak_portra_endura"), FilmSim.format("135"),
                grain, halation, softness, ev, 0, 0, 0, 0, 1);
    }

    /** A grey scene with a bright lamp. */
    static int[] scene(int w, int h) {
        final int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final boolean lamp = Math.abs(x - w / 2) < 3 && Math.abs(y - h / 2) < 3;
            final int v = lamp ? 255 : 110 + (x * 7 + y * 3) % 20;
            px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return px;
    }

    static double diff(int[] a, int[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++)
            for (int sh = 0; sh <= 16; sh += 8) s += Math.abs(((a[i] >> sh) & 255) - ((b[i] >> sh) & 255));
        return s / (a.length * 3.0);
    }

    @Test
    public void grainHalationAndSoftnessShowInThePreview() {
        final int w = 160, h = 120;
        final int[] base = scene(w, h);
        final int[] grain = FilmEffects.grain(base, w, h, set(1, 0, 0, 0));
        assertTrue("grain " + diff(grain, base), diff(grain, base) > 0.5);
        assertTrue("more grain, more change", diff(FilmEffects.grain(base, w, h, set(2, 0, 0, 0)), base) > diff(grain, base));
        // a weak antihalation layer (Gold 200), a dark street around the lamp
        final int[] dark = new int[w * h];
        for (int i = 0; i < dark.length; i++) dark[i] = 0xFF282828;
        for (int y = h / 2 - 4; y <= h / 2 + 4; y++) for (int x = w / 2 - 4; x <= w / 2 + 4; x++) dark[y * w + x] = 0xFFFFFFFF;
        final FilmSim.Settings gold = new FilmSim.Settings(FilmSim.stock("kodak_gold_200"), FilmSim.print("kodak_portra_endura"),
                FilmSim.format("135"), 0, 4, 0, 0, 0, 0, 0, 0, 1);
        final int[] halo = FilmEffects.light(dark, w, h, gold);
        final int ring = (h / 2) * w + w / 2 + 9;
        assertTrue("halation reddens the lamp's surroundings: " + Integer.toHexString(halo[ring]),
                ((halo[ring] >> 16) & 255) > ((dark[ring] >> 16) & 255) + 3 && ((halo[ring] >> 16) & 255) > (halo[ring] & 255) + 2);
        final int[] soft = FilmEffects.light(base, w, h, set(0, 0, 2, 0));
        assertTrue("softness " + diff(soft, base), diff(soft, base) > 0.2);
    }

    @Test
    public void filmExposureBrightensThePrint() {
        final float[] lo = FilmSim.apply(set(0, 0, 0, -1), .46f, .46f, .46f), mid = FilmSim.apply(set(0, 0, 0, 0), .46f, .46f, .46f),
                hi = FilmSim.apply(set(0, 0, 0, 1), .46f, .46f, .46f);
        assertTrue("-1 " + lo[1] + " 0 " + mid[1] + " +1 " + hi[1], lo[1] < mid[1] - .05f && hi[1] > mid[1] + .05f);
    }
}
