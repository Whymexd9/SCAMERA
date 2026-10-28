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

/**
 * P286: the port against spektrafilm's own output (research/filmsim/refs.py; spatial and stochastic effects off). P380: refs.txt's
 * cases with an 8th header field set spektrafilm's DIR couplers amount (0 / 0.5 / 2; the 13 cases before it at the default 1).
 */
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
            assertEquals("the 13 cases at the default amount and P380's 7 with the couplers' amount", 20, cases);
            final double[] out = new double[3];
            int withAmount = 0;
            for (int k = 0; k < cases; k++) {
                final String[] h = r.readLine().trim().split(" ");
                final boolean scan = h[2].equals("1");
                final double couplers = h.length > 7 ? Double.parseDouble(h[7]) : 1.0;
                if (h.length > 7) withAmount++;
                final FilmSpectral.Engine e = h.length > 7
                        ? FilmSpectral.engine(h[0], scan ? null : h[1], Double.parseDouble(h[3]),
                        Math.log(Double.parseDouble(h[4])) / Math.log(2), Double.parseDouble(h[5]), Double.parseDouble(h[6]), 1.0, couplers)
                        : FilmSpectral.engine(h[0], scan ? null : h[1], Double.parseDouble(h[3]),
                        Math.log(Double.parseDouble(h[4])) / Math.log(2), Double.parseDouble(h[5]), Double.parseDouble(h[6]), 1.0);
                double worst = 0;
                for (int i = 0; i < n; i++) {
                    final double[] y = nums(r.readLine());
                    e.render(x[i][0], x[i][1], x[i][2], out);
                    for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(out[c] - y[c]));
                }
                System.out.println(String.format(java.util.Locale.ROOT, "%s / %s couplers %.2f: max error %.5f", h[0], scan ? "scan" : h[1],
                        couplers, worst));
                assertTrue(h[0] + " / " + h[1] + " couplers " + couplers + ": max error " + worst, worst < 0.004);
            }
            assertEquals(7, withAmount);
        }
    }

    /**
     * P380: the couplers' amount 1 is the engine without it, bit for bit (today's colours); 0 is the curves as measured (a grey keeps
     * its print, colours lose chroma); 2 separates the colours more.
     */
    @Test
    public void theCouplersAmount() throws Exception {
        final java.util.Random rnd = new java.util.Random(380);
        final double[] a = new double[3], b = new double[3];
        for (String[] fp : new String[][]{{"kodak_portra_400", "kodak_portra_endura"}, {"kodak_vision3_250d", "kodak_2383"},
                {"fujifilm_velvia_100", null}, {"kodak_ektar_100", "kodak_endura_premier"}}) {
            final FilmSpectral.Engine old = FilmSpectral.engine(fp[0], fp[1], 0.3, -0.2, 3, -2, 1.1);
            final FilmSpectral.Engine one = FilmSpectral.engine(fp[0], fp[1], 0.3, -0.2, 3, -2, 1.1, 1.0);
            for (int i = 0; i < 500; i++) {
                final double r = 0.003 * Math.pow(2000, rnd.nextDouble()), g = 0.003 * Math.pow(2000, rnd.nextDouble()),
                        bl = 0.003 * Math.pow(2000, rnd.nextDouble());
                old.render(r, g, bl, a);
                one.render(r, g, bl, b);
                for (int c = 0; c < 3; c++) assertEquals(fp[0], Double.doubleToLongBits(a[c]), Double.doubleToLongBits(b[c]));
            }
            // the chroma of a red, a green and a blue patch at 0 / 1 / 2
            double[] chroma = new double[3];
            final double[][] patches = {{0.40, 0.10, 0.08}, {0.10, 0.30, 0.08}, {0.08, 0.12, 0.40}};
            for (int k = 0; k < 3; k++) {
                final FilmSpectral.Engine e = FilmSpectral.engine(fp[0], fp[1], 0, 0, 0, 0, 1, k);
                for (double[] p : patches) {
                    e.render(p[0], p[1], p[2], a);
                    chroma[k] += Math.max(a[0], Math.max(a[1], a[2])) - Math.min(a[0], Math.min(a[1], a[2]));
                }
            }
            assertTrue(fp[0] + " couplers 0 / 1 / 2: " + java.util.Arrays.toString(chroma), chroma[0] < chroma[1] && chroma[1] < chroma[2]);
            // a grey stays about the grey it was (the curves before the couplers give the measured grey scale back at every amount;
            // the print's balance moves a little) and as neutral: ParamHelp's «на серые тона почти не действует»
            double shift = 0, spread0 = 0, spread1 = 0;
            for (double v : new double[]{0.02, 0.05, 0.1, 0.184, 0.4, 0.9, 2.0}) {
                FilmSpectral.engine(fp[0], fp[1], 0, 0, 0, 0, 1, 0).render(v, v, v, a);
                FilmSpectral.engine(fp[0], fp[1], 0, 0, 0, 0, 1, 1).render(v, v, v, b);
                for (int c = 0; c < 3; c++) shift = Math.max(shift, Math.abs(a[c] - b[c]));
                spread0 = Math.max(spread0, Math.max(a[0], Math.max(a[1], a[2])) - Math.min(a[0], Math.min(a[1], a[2])));
                spread1 = Math.max(spread1, Math.max(b[0], Math.max(b[1], b[2])) - Math.min(b[0], Math.min(b[1], b[2])));
            }
            System.out.println(String.format(java.util.Locale.ROOT, "%s couplers 0 vs 1: grey moves %.4f, spread %.4f vs %.4f, chroma %s",
                    fp[0], shift, spread0, spread1, java.util.Arrays.toString(chroma)));
            assertTrue(fp[0] + " grey moves " + shift, shift < 0.03);
            assertTrue(fp[0] + " grey spread " + spread0 + " vs " + spread1, spread0 < spread1 + 0.02);
        }
    }

    /**
     * P380 review: over the whole range the UI offers (0 .. 100 %), on every colour stock with its print, a grey ramp stays a grey
     * near its colour at 100 % and keeps rising - above 100 % 14 of the 20 stocks broke (blue / orange greys, inverted tones).
     */
    @Test
    public void theCouplersRangeKeepsGreysOnEveryColourStock() throws Exception {
        final double[] a = new double[3], ref = new double[3];
        final double[] ramp = {0.005, 0.01, 0.02, 0.046, 0.1, 0.184, 0.4, 0.74, 1.4, 2.0};
        int stocks = 0;
        for (FilmSim.Stock st : FilmSim.STOCKS) {
            if (st.spectral == null) continue;
            stocks++;
            final String print = FilmSim.print("", st).id;
            final FilmSpectral.Engine full = FilmSpectral.engine(st.spectral, print, 0, 0, 0, 0, 1, 1.0);
            for (int k = 0; k <= 10; k++) {
                final double amount = k / 10.0;
                final FilmSpectral.Engine e = FilmSpectral.engine(st.spectral, print, 0, 0, 0, 0, 1, amount);
                double last = -1, worst = 0;
                for (double v : ramp) {
                    e.render(v, v, v, a);
                    full.render(v, v, v, ref);
                    for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(a[c] - ref[c]));
                    final double y = 0.2126 * a[0] + 0.7152 * a[1] + 0.0722 * a[2];
                    assertTrue(st.id + " at " + amount + ": the grey ramp falls at " + v + " (" + y + " after " + last + ")", y >= last - 1e-4);
                    last = y;
                }
                assertTrue(st.id + " at " + amount + ": a grey moves " + worst + " from 100 %", worst < 0.06);
            }
        }
        assertTrue("the colour stocks " + stocks, stocks >= 15);
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
