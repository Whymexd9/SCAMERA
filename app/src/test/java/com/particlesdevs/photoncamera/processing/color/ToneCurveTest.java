package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P144: the own tone curve (points, monotone cubic) and ArkCam 1.42's ACES / Uchimura operators. */
public class ToneCurveTest {
    @Test public void defaultIsArkCamsSCurve() {
        ToneCurve c = ToneCurve.defaults();
        assertEquals(5, c.size());
        assertEquals(ToneCurve.DEFAULT_POINTS, c.serialize());
        assertEquals("middle grey stays", 0.5f, c.eval(0.5f), 1e-4f);
        assertEquals(0f, c.eval(0f), 1e-6f);
        assertEquals(1f, c.eval(1f), 1e-6f);
        assertTrue("an S: darker shadows", c.eval(0.25f) < 0.25f);
        assertTrue("brighter highlights", c.eval(0.75f) > 0.75f);
    }

    @Test public void parsingCleansUp() {
        ToneCurve c = ToneCurve.parse("0.9:0.95, 0.2:0.1,1.5:2,bad,0.3:-1, 0.5:0.5");
        // sorted, clamped, the ends pinned to x 0 and 1
        assertEquals(0f, c.get(0).x, 0f);
        assertEquals(1f, c.get(c.size() - 1).x, 0f);
        for (int i = 1; i < c.size(); i++) assertTrue("x grows", c.get(i).x > c.get(i - 1).x);
        for (ToneCurve.Point p : c.points()) assertTrue(p.y >= 0f && p.y <= 1f);
        assertEquals("nothing usable: the default", ToneCurve.DEFAULT_POINTS, ToneCurve.parse("").serialize());
        assertEquals(ToneCurve.DEFAULT_POINTS, ToneCurve.parse(null).serialize());
        assertEquals(ToneCurve.DEFAULT_POINTS, ToneCurve.parse("0.5:0.5").serialize());
        // a round trip keeps it
        assertEquals(c.serialize(), ToneCurve.parse(c.serialize()).serialize());
    }

    @Test public void monotoneWithoutOvershoot() {
        // steep steps that a plain cubic spline would overshoot
        ToneCurve c = ToneCurve.parse("0:0,0.3:0.05,0.35:0.9,1:1");
        float prev = -1f;
        for (int i = 0; i <= 1000; i++) {
            float y = c.eval(i / 1000f);
            assertTrue("never down at " + i, y >= prev - 1e-6f);
            assertTrue("within the points' range", y >= 0f && y <= 1f);
            prev = y;
        }
        assertEquals("through its points", 0.9f, c.eval(0.35f), 1e-4f);
        float[] s = c.samples(256);
        assertEquals(256, s.length);
        assertEquals(c.eval(0.5f), s[Math.round(0.5f * 255)], 0.01f);
        ToneCurve line = ToneCurve.identity();
        assertEquals(0.37f, line.eval(0.37f), 1e-5f);
    }

    @Test public void editingPoints() {
        ToneCurve c = ToneCurve.identity();
        int i = c.add(0.4f, 0.6f);
        assertEquals(1, i);
        assertEquals(3, c.size());
        assertEquals(0.6f, c.eval(0.4f), 1e-4f);
        assertEquals("too close to a point: no second one there", -1, c.add(0.405f, 0.2f));
        // as many points as wanted
        for (int k = 1; k <= 20; k++) c.add(0.02f + k * 0.045f, 0.5f);
        assertTrue(c.size() > 15);
        // a moved inner point stays between its neighbours, the ends move only up and down
        c.move(1, 5f, 0.9f);
        assertTrue(c.get(1).x < c.get(2).x);
        c.move(0, 0.5f, 0.2f);
        assertEquals(0f, c.get(0).x, 0f);
        assertEquals(0.2f, c.get(0).y, 0f);
        int n = c.size();
        assertFalse("the ends stay", c.remove(0));
        assertFalse(c.remove(n - 1));
        assertTrue(c.remove(1));
        assertEquals(n - 1, c.size());
    }

    @Test public void acesAndUchimuraAsArkCam() {
        // eval_acescg with toe 0.05: b = 0.0375, e = 0.132; pre-gain 1, a 2.61, d 0.59
        float x = 0.18f;
        float want = x * (2.61f * x + 0.0375f) / (x * (2.43f * x + 0.59f) + 0.132f);
        assertEquals(want, ToneCurve.aces(x, 0.05f, 1f, 2.61f, 0.59f), 1e-6f);
        assertEquals(0f, ToneCurve.aces(0f, 0.05f, 1f, 2.61f, 0.59f), 0f);
        assertTrue("rises", ToneCurve.aces(1f, 0.05f, 1f, 2.61f, 0.59f) > ToneCurve.aces(0.5f, 0.05f, 1f, 2.61f, 0.59f));
        assertTrue("toward a / 2.43", ToneCurve.aces(100f, 0.05f, 1f, 2.61f, 0.59f) < 2.61f / 2.43f + 1e-3f);
        // Uchimura defaults: linear in the mid-tones (slope a), the shoulder to P
        float P = 1f, a = 1f, m = 0.22f, l = 0.4f, c = 1.33f, b = 0f;
        assertEquals("linear section: m + a (x - m)", 0.4f, ToneCurve.uchimura(0.4f, P, a, m, l, c, b), 1e-4f);
        assertTrue("shoulder below P", ToneCurve.uchimura(5f, P, a, m, l, c, b) < P);
        assertTrue(ToneCurve.uchimura(5f, P, a, m, l, c, b) > 0.95f);
        assertTrue("toe under the line", ToneCurve.uchimura(0.05f, P, a, m, l, c, b) < 0.05f);
        float prev = -1f;
        for (int i = 0; i <= 400; i++) {
            float y = ToneCurve.uchimura(i / 100f, P, a, m, l, c, b);
            assertTrue("monotone at " + i, y >= prev - 1e-5f);
            prev = y;
        }
    }

    /**
     * P177: AgX, ACES and Uchimura in the own curve's domain (x = 0.5 + EV / 10, y the display value before the 2.4 of the
     * outset): AgX passes its pivot 0.5 where EV = -ev, ACES and Uchimura are their linear value ^ (1 / 2.4) at 0.18 x 2^EV;
     * all rise within 0..1, and an operator loaded as an own curve goes through it at its nine points.
     */
    @Test public void operatorsInTheOwnCurvesDomain() {
        float slope = 2.7f, sp = 1.35f, tp = 1.6f, minEv = -8.5f, maxEv = 3.5f, ev = 0.3f;
        assertEquals("AgX at its pivot", 0.5f, ToneCurve.agxAt(0.5f - ev / 10f, slope, sp, tp, minEv, maxEv, ev), 1e-4f);
        float[] aces = {1f, 2.61f, 0.59f, 0.05f};
        float grey = (float) Math.pow(ToneCurve.aces(0.18f, 0.05f, 1f, 2.61f, 0.59f), 1 / 2.4);
        assertEquals("ACES at the grey", grey, ToneCurve.acesAt(0.5f, aces), 1e-5f);
        float[] ua = {1f, 0.9f, 1f, 1f}, ub = {0.22f, 0.4f, 1.33f, 0f};
        float uGrey = (float) Math.pow(ToneCurve.uchimura(0.18f, 1f, 1f, 0.22f, 0.4f, 1.33f, 0f) * 0.9f, 1 / 2.4);
        assertEquals("Uchimura at the grey", uGrey, ToneCurve.uchimuraAt(0.5f, ua, ub), 1e-5f);
        float pa = -1, pc = -1, pu = -1;
        for (int i = 0; i <= 100; i++) {
            float x = i / 100f;
            float a = ToneCurve.agxAt(x, slope, sp, tp, minEv, maxEv, ev), c = ToneCurve.acesAt(x, aces), u = ToneCurve.uchimuraAt(x, ua, ub);
            for (float v : new float[]{a, c, u}) assertTrue("in 0..1 at " + x, v >= 0f && v <= 1f);
            assertTrue("AgX rises at " + x, a >= pa - 1e-5f);
            assertTrue("ACES rises at " + x, c >= pc - 1e-5f);
            assertTrue("Uchimura rises at " + x, u >= pu - 1e-5f);
            pa = a; pc = c; pu = u;
        }
        ToneCurve loaded = ToneCurve.following(x -> ToneCurve.acesAt((float) x, aces), 9);
        assertEquals(9, loaded.size());
        for (int i = 0; i < 9; i++) {
            float x = i / 8f;
            assertEquals(x, loaded.get(i).x, 1e-4f);
            assertEquals("through ACES at " + x, ToneCurve.acesAt(x, aces), loaded.get(i).y, 1e-4f);
        }
    }

    /** P144: a saved curve file - the header line and the points; reading takes «x:y» pairs from any text. */
    @Test public void curveFiles() {
        ToneCurve c = ToneCurve.parse("0:0,0.2:0.1,0.5:0.55,1:1");
        String text = c.toFileText();
        assertTrue(text.startsWith(ToneCurve.FILE_HEADER + "\n"));
        assertEquals(c.serialize(), ToneCurve.fromFileText(text).serialize());
        // ArkCam's string, new lines, semicolons, comments
        assertEquals(c.serialize(), ToneCurve.fromFileText("# my curve 1:1\r\n0:0; 0.2 : 0.1\n0.5:0.55  1:1").serialize());
        // an 8-bit curve (0..255) is scaled
        ToneCurve eight = ToneCurve.fromFileText("0:0,64:32,128:140,255:255");
        assertEquals(4, eight.size());
        assertEquals(128 / 255f, eight.get(2).x, 1e-4f);
        assertEquals(140 / 255f, eight.get(2).y, 1e-4f);
        // nothing usable
        assertNull(ToneCurve.fromFileText(null));
        assertNull(ToneCurve.fromFileText(""));
        assertNull(ToneCurve.fromFileText("<xml>not a curve</xml>"));
        assertNull("one point", ToneCurve.fromFileText("0.5:0.5"));
        assertNull("values past 8 bit", ToneCurve.fromFileText("0:0,1000:1000"));
        assertNull("only comments", ToneCurve.fromFileText("# 0:0,1:1"));
    }

    /** P231: ArkCam's curve file (ToneCurveFileManager): {"name", "points": [{"x", "y"}]}, or [x, y] pairs, or a bare array. */
    /**
     * Owner, 2026-10-18 (ToneCurve_imx06A.txt): a camera's DNG ProfileToneCurve - a bare list x0, y0, x1, y1 ... in linear gamma,
     * 128 points - comes in as our curve (x the exposure around middle grey, y the display value): middle grey through the curve
     * and sRGB, white at +2.47 EV, monotone.
     */
    @Test public void aCamerasLinearToneCurveFile() throws Exception {
        String text;
        try (java.io.InputStream in = getClass().getResourceAsStream("/tonecurve/ToneCurve_imx06A.txt")) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        float[] pairs = ToneCurve.linearPairs(text);
        assertEquals("128 points", 256, pairs.length);
        ToneCurve c = ToneCurve.fromFileText(text);
        assertTrue(c.size() > 20);
        // middle grey: linear 0.18 -> 0.2856 on the camera's curve -> sRGB 0.571
        assertEquals(0.571f, c.eval(0.5f), 0.01f);
        assertEquals(ToneCurve.display(ToneCurve.linearAt(pairs, 0.18f)), c.eval(0.5f), 0.01f);
        assertEquals("white from +2.47 EV", 1f, c.eval(ToneCurve.xOfLinear(1f) + 0.01f), 1e-3f);
        assertEquals(1f, c.eval(1f), 1e-4f);
        for (int i = 1; i < c.size(); i++) assertTrue("monotone", c.get(i).y >= c.get(i - 1).y - 1e-6f);
        // the same numbers with spaces / new lines only; anything else is not such a curve
        assertNotNull(ToneCurve.fromFileText("0 0\n0.5 0.7\n1 1"));
        assertNull("x not rising", ToneCurve.linearPairs("0, 0, 0.5, 0.7, 0.4, 0.8, 1, 1"));
        assertNull("odd count", ToneCurve.linearPairs("0, 0, 0.5, 0.7, 1"));
        assertNull("above 1", ToneCurve.linearPairs("0, 0, 128, 200, 255, 255"));
        assertNull("words", ToneCurve.linearPairs("curve 0, 0, 1, 1"));
        assertNull("our x:y pairs keep their own reading", ToneCurve.linearPairs("0:0, 1:1"));
    }

    @Test public void arkCamJsonCurveFiles() {
        String file = "{\n  \"name\": \"ark_art_drama_faces\",\n  \"points\": [\n"
                + "    {\"x\": 0.0, \"y\": 0.0}, {\"x\": 0.0961, \"y\": 0.011}, {\"x\": 0.15, \"y\": 0.0245}, {\"x\": 0.2, \"y\": 0.0493},\n"
                + "    {\"x\": 0.26, \"y\": 0.0956}, {\"x\": 0.3, \"y\": 0.1365}, {\"x\": 0.34, \"y\": 0.1814}, {\"x\": 0.38, \"y\": 0.2345},\n"
                + "    {\"x\": 0.42, \"y\": 0.2998}, {\"x\": 0.47, \"y\": 0.4048}, {\"x\": 0.54, \"y\": 0.562}, {\"x\": 0.6, \"y\": 0.6961},\n"
                + "    {\"x\": 0.66, \"y\": 0.81}, {\"x\": 0.72, \"y\": 0.89}, {\"x\": 0.78, \"y\": 0.938}, {\"x\": 0.84, \"y\": 0.966},\n"
                + "    {\"x\": 0.9, \"y\": 0.984}, {\"x\": 0.95, \"y\": 0.992}, {\"x\": 1.0, \"y\": 1.0}\n  ]\n}";
        ToneCurve c = ToneCurve.fromFileText(file);
        assertEquals(19, c.size());
        assertEquals(0f, c.get(0).x, 0f);
        assertEquals(0f, c.get(0).y, 0f);
        assertEquals(1f, c.get(18).x, 0f);
        assertEquals(1f, c.get(18).y, 0f);
        assertEquals(0.0961f, c.get(1).x, 1e-6f);
        assertEquals(0.011f, c.get(1).y, 1e-6f);
        for (int i = 1; i < c.size(); i++) assertTrue("monotone", c.get(i).y >= c.get(i - 1).y);
        assertEquals("ark_art_drama_faces", ToneCurve.jsonName(file));
        // «y» before «x», [x, y] pairs, a bare array; values are clamped to 0..1 (no 8-bit scaling in JSON, as ArkCam)
        assertEquals(0.3f, ToneCurve.fromFileText("{\"points\":[{\"y\":0,\"x\":0},{\"y\":0.2,\"x\":0.3},{\"y\":1,\"x\":1}]}").get(1).x, 1e-6f);
        assertEquals(3, ToneCurve.fromFileText("{\"points\": [[0, 0], [0.5, 0.6], [1, 1]]}").size());
        assertEquals(0.6f, ToneCurve.fromFileText("[[0, 0], [0.5, 0.6], [1, 1]]").get(1).y, 1e-6f);
        assertEquals(1f, ToneCurve.fromFileText("{\"points\": [[0, 0], [0.5, 7], [1, 1]]}").get(1).y, 0f);
        assertNull("one point", ToneCurve.fromFileText("{\"points\": [{\"x\": 0.5, \"y\": 0.5}]}"));
        assertNull(ToneCurve.jsonName("0:0,1:1"));
        // a JSON text without points falls back to the "x:y" scan, which finds none in it
        assertNull(ToneCurve.fromFileText("{\"name\": \"empty\"}"));
    }
}
