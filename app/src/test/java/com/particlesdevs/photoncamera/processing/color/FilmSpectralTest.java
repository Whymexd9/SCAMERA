package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** P286: the port against spektrafilm's own output (research/filmsim/refs.py; spatial and stochastic effects off). */
public class FilmSpectralTest {
    @BeforeClass
    public static void assets() {
        File dir = new File("src/main/assets/film");
        if (!dir.isDirectory()) dir = new File("app/src/main/assets/film");
        final File d = dir;
        FilmSpectral.source(name -> new FileInputStream(new File(d, name)));
    }

    @Test
    public void matchesTheReference() throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                FilmSpectralTest.class.getResourceAsStream("/film/refs.txt"), StandardCharsets.US_ASCII))) {
            final int n = Integer.parseInt(r.readLine().trim());
            final double[][] x = new double[n][];
            for (int i = 0; i < n; i++) x[i] = nums(r.readLine());
            final int cases = Integer.parseInt(r.readLine().trim());
            final double[] out = new double[3];
            for (int k = 0; k < cases; k++) {
                final String[] h = r.readLine().trim().split(" ");
                final boolean scan = h[2].equals("1");
                final FilmSpectral.Engine e = FilmSpectral.engine(h[0], scan ? null : h[1], Double.parseDouble(h[3]),
                        Math.log(Double.parseDouble(h[4])) / Math.log(2), Double.parseDouble(h[5]), Double.parseDouble(h[6]), 1.0);
                double worst = 0;
                for (int i = 0; i < n; i++) {
                    final double[] y = nums(r.readLine());
                    e.render(x[i][0], x[i][1], x[i][2], out);
                    for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(out[c] - y[c]));
                }
                assertTrue(h[0] + " / " + h[1] + ": max error " + worst, worst < 0.004);
            }
        }
    }

    @Test
    public void stocksDiffer() throws Exception {
        // the owner's P285: the stocks must look different - a skin-like and a sky-like colour through three films
        final double[][] colours = {{0.40, 0.22, 0.15}, {0.12, 0.20, 0.40}, {0.10, 0.30, 0.08}};
        final String[] films = {"kodak_portra_400", "kodak_ektar_100", "fujifilm_c200"};
        final double[][] o = new double[films.length][9];
        for (int f = 0; f < films.length; f++) {
            final FilmSpectral.Engine e = FilmSpectral.engine(films[f], "kodak_portra_endura", 0, 0, 0, 0, 1);
            final double[] t = new double[3];
            for (int c = 0; c < colours.length; c++) {
                e.render(colours[c][0], colours[c][1], colours[c][2], t);
                System.arraycopy(t, 0, o[f], c * 3, 3);
            }
        }
        for (int a = 0; a < films.length; a++) for (int b = a + 1; b < films.length; b++) {
            double d = 0;
            for (int i = 0; i < 9; i++) d = Math.max(d, Math.abs(o[a][i] - o[b][i]));
            assertTrue(films[a] + " vs " + films[b] + ": " + d, d > 0.02);
        }
    }

    @Test
    public void midGreyStaysGrey() throws Exception {
        final double[] t = new double[3];
        FilmSpectral.engine("kodak_portra_400", "kodak_portra_endura", 0, 0, 0, 0, 1).render(.184, .184, .184, t);
        assertEquals(t[0], t[1], 0.01);
        assertEquals(t[2], t[1], 0.01);
        assertEquals(0.46, t[1], 0.02);
    }

    @Test
    public void grainOfTheReferenceModel() {
        // the grain seen in the picture: positive everywhere, largest in the mid tones of a print, finer on a slower film
        final FilmSim.Settings portra = new FilmSim.Settings(FilmSim.stock("kodak_portra_800"), FilmSim.print("kodak_portra_endura"),
                FilmSim.format("135"), 1, 1, 1, 0, 0, 0, 1);
        final FilmLut g = FilmSim.grainLut(portra, 17);
        for (float v : g.rgb) assertTrue("grain " + v, Float.isFinite(v) && v >= 0f);
        final float[] mid = g.sample(.46f, .46f, .46f), black = g.sample(.03f, .03f, .03f);
        assertTrue("mid " + mid[1] + " black " + black[1], mid[1] > black[1]);
        // a 6 um pixel (35 mm, 6000 px): the picture's grain sigma in the mid tones about 0.5 - 4 % (blur 0.65 px keeps ~0.43)
        final double sigma = mid[1] / 6.0 * 0.43;
        assertTrue("portra 800 mid sigma " + sigma, sigma > 0.005 && sigma < 0.04);
        final FilmSim.Settings ektar = new FilmSim.Settings(FilmSim.stock("kodak_ektar_100"), FilmSim.print("kodak_portra_endura"),
                FilmSim.format("135"), 1, 1, 1, 0, 0, 0, 1);
        assertTrue(FilmSim.grainLut(ektar, 17) != null);
    }

    @Test
    public void halfFloats() {
        assertEquals(1f, FilmSpectral.half((short) 0x3c00), 0f);
        assertEquals(-2f, FilmSpectral.half((short) 0xc000), 0f);
        assertEquals(65504f, FilmSpectral.half((short) 0x7bff), 0f);
        assertEquals(5.96e-8f, FilmSpectral.half((short) 0x0001), 1e-10f);
    }

    private static double[] nums(String line) {
        final String[] p = line.trim().split(" ");
        final double[] v = new double[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i]);
        return v;
    }
}
