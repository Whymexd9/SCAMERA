package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ArkAe;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P314: the RAW viewfinder's CPU AE (LiveArkMeter) against the path it replaces - rawdevelop_fs's linear frame (u_linear_out)
 * drawn at the viewport, ark/low at factor 8 of it read back, ArkAe.smartHdr - on synthetic frames: a 2x ISZ Quad frame's cell
 * means (block 1, cfa_mode 0), the same Quad unbinned (block 2), a plain Bayer frame (cfa_mode 1: the range-filtered Bayer), each
 * with white balance, highlight clip, a lens-shading map, a colour matrix and the look's white matrix.
 */
public class LiveArkMeterTest {
    private static final int[][] ORDERS = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 3, 0, 1}, {3, 2, 1, 0}};
    /** A camera matrix with the look's white matrix after it, column-major as LiveRawRenderer uploads it. */
    private static final float[] TRANSFORM = {1.72f, -0.21f, 0.02f, -0.55f, 1.45f, -0.48f, -0.17f, -0.24f, 1.46f};

    /** The scene's linear camera RGB (before WB: a grey has R and B below G) at frame position (x, y) of w x h. */
    private static double[] scene(double x, double y, int w, int h, boolean window) {
        double u = x / w, v = y / h;
        double level;
        double[] tint = {1, 1, 1};
        if (v < 0.3) { level = 0.55 + 0.3 * u; tint = new double[]{0.75, 1, 1.25}; }                  // sky
        else if (u < 0.35) level = 0.04 + 0.02 * Math.sin(40 * u) * Math.sin(30 * v);               // shadow
        else if (u < 0.7) { level = 0.18 + 0.08 * Math.sin(25 * u + 9 * v); tint = new double[]{1.3, 1, 0.7}; }  // a warm wall
        else level = 0.35 + 0.25 * v;                                                                 // a grey floor
        double dx = u - 0.82, dy = v - 0.2;
        if (dx * dx + dy * dy < 0.0012) level = 100.0;                                                // a lamp, clipped
        if (window && u > 0.86 && v > 0.32 && v < 0.5) level = 2.0 + 3 * u;                            // a window, ~2.5 % of the frame
        return new double[]{level * tint[0] * 0.5, level * tint[1], level * tint[2] * 0.62};
    }

    /**
     * A w x h RGGB mosaic of block x block colour cells (1: plain Bayer) of the scene times {@code exposure}, black 64, white
     * 1023, shot noise of {@code noise} x sqrt(DN).
     */
    private static LiveRawFrame.Frame frame(int w, int h, int block, boolean cellMean, double noise, long seed, double exposure) {
        return frame(w, h, block, cellMean, noise, seed, exposure, false);
    }

    private static LiveRawFrame.Frame frame(int w, int h, int block, boolean cellMean, double noise, long seed, double exposure, boolean window) {
        Random random = new Random(seed);
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int site = ((y / block) & 1) * 2 + ((x / block) & 1);   // 0 R, 1 Gr, 2 Gb, 3 B
                double[] c = scene(x + 0.5, y + 0.5, w, h, window);
                double s = (site == 0 ? c[0] : site == 3 ? c[2] : c[1]) * exposure;
                // vignetting the lens-shading map takes back
                double r2 = Math.pow((x + 0.5) / w - 0.5, 2) + Math.pow((y + 0.5) / h - 0.5, 2);
                double dn = 64 + 959 * s / (1 + 2.2 * r2);
                if (noise > 0) dn += random.nextGaussian() * noise * Math.sqrt(Math.max(dn - 64, 0) + 4);
                b.putShort((y * w + x) * 2, (short) Math.max(0, Math.min(1023, Math.round(dn))));
            }
        }
        LiveRawFrame.Frame f = new LiveRawFrame.Frame();
        f.buffer = b;
        f.width = w;
        f.height = h;
        f.rowStride = w * 2;
        f.cfaPattern = 0;
        f.cfaBlock = block;
        f.cellMean = cellMean;
        f.whiteLevel = 1023;
        f.blackLevel = new float[]{64, 64, 64, 64};
        f.wbGains = new float[]{2.0f, 1f, 1.6f};
        f.crop = new float[]{0, 0, 1, 1};
        f.shadingWidth = 17;
        f.shadingHeight = 13;
        f.shading = new float[17 * 13 * 3];
        for (int y = 0; y < 13; y++) for (int x = 0; x < 17; x++) {
            double r2 = Math.pow(x / 16.0 - 0.5, 2) + Math.pow(y / 12.0 - 0.5, 2);
            for (int c = 0; c < 3; c++) f.shading[(y * 17 + x) * 3 + c] = (float) ((1 + 2.2 * r2) * (c == 1 ? 1.0 : 1.03));
        }
        f.iso = 200;
        f.maxAnalogIso = 6400;
        return f;
    }

    // ---- the replaced path: rawdevelop_fs u_linear_out at the viewport, then ark/low factor 8

    private static float raw(LiveRawFrame.Frame f, int x, int y, int block) {
        int mask = block * 2 - 1;   // getUnpackedPixel's CFA-phase clamp
        int px = x & mask, py = y & mask;
        x = Math.max(px, Math.min(x, f.width - 1 - ((f.width - 1 - px) & mask)));
        y = Math.max(py, Math.min(y, f.height - 1 - ((f.height - 1 - py) & mask)));
        return f.buffer.getShort((y * (f.rowStride / 2) + x) * 2) & 0xFFFF;
    }

    private static float norm(LiveRawFrame.Frame f, float v, float black) {
        return Math.max(0f, (v - black) / Math.max(1f, f.whiteLevel - black));
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /** apply_wb_and_hl_clip of the R, Gr, Gb, B site values (normalised). */
    private static float[] wbClip(LiveRawFrame.Frame f, float r, float gr, float gb, float bl) {
        float g = (gr + gb) * 0.5f;
        float[] c = {r * f.wbGains[0], g * f.wbGains[1], bl * f.wbGains[2]};
        float rawMax = Math.max(r, Math.max(g, bl));
        if (rawMax > 0.85f) {
            float fade = smoothstep(0.85f, 0.98f, rawMax), m = Math.max(c[0], Math.max(c[1], c[2]));
            for (int i = 0; i < 3; i++) c[i] += (m - c[i]) * fade;
        }
        return c;
    }

    /** getFastColor (cfa_mode 0): the 2 x 2 macro pixels at (mx & ~1, my & ~1). */
    private static float[] fastColor(LiveRawFrame.Frame f, int mx, int my) {
        int b = f.cfaBlock, bx = mx & ~1, by = my & ~1;
        float[] m = new float[4];
        for (int p = 0; p < 4; p++) {
            float sum = 0;
            for (int y = 0; y < b; y++) for (int x = 0; x < b; x++) sum += raw(f, (bx + (p & 1)) * b + x, (by + (p >> 1)) * b + y, b);
            m[p] = norm(f, sum / (b * b), f.blackLevel[p]);
        }
        int[] o = ORDERS[f.cfaPattern];
        return wbClip(f, m[o[0]], m[o[1]], m[o[2]], m[o[3]]);
    }

    /** getFilteredBayerPixel: the site and its four +-2 neighbours of the same colour, range-weighted. */
    private static float filtered(LiveRawFrame.Frame f, int x, int y, float black) {
        float c = norm(f, raw(f, x, y, 1), black);
        float[] n = {norm(f, raw(f, x - 2, y, 1), black), norm(f, raw(f, x + 2, y, 1), black), norm(f, raw(f, x, y - 2, 1), black),
                norm(f, raw(f, x, y + 2, 1), black)};
        float sig = 0.04f + 0.06f * c, inv2 = 0.5f / (sig * sig), sum = c, wsum = 1;
        for (float v : n) {
            float w = (float) Math.exp(-(v - c) * (v - c) * inv2);
            sum += v * w;
            wsum += w;
        }
        return sum / wsum;
    }

    /** getStandardBayerColor (cfa_mode 1). */
    private static float[] bayerColor(LiveRawFrame.Frame f, int px, int py) {
        int bx = (px / 2) * 2, by = (py / 2) * 2;
        float[] m = new float[4];
        for (int p = 0; p < 4; p++) m[p] = filtered(f, bx + (p & 1), by + (p >> 1), f.blackLevel[p]);
        int[] o = ORDERS[f.cfaPattern];
        return wbClip(f, m[o[0]], m[o[1]], m[o[2]], m[o[3]]);
    }

    /** ark/low factor 8 (RGBA) of rawdevelop_fs's linear frame of the crop drawn at w x h (frame orientation), x inScale. */
    private static FloatBuffer arkLow8(LiveRawFrame.Frame f, int[] crop, int w, int h, float inScale, int[] size) {
        float[] lin = new float[w * h * 3];
        int shift = f.cfaBlock == 4 ? 2 : f.cfaBlock == 2 ? 1 : 0;
        boolean bayer = f.cfaBlock == 1 && !f.cellMean;
        float[] gain = new float[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float u = (x + 0.5f) / w, v = (y + 0.5f) / h;
                float[] c = bayer ? bayerColor(f, (int) (u * crop[2]) + crop[0], (int) (v * crop[3]) + crop[1])
                        : fastColor(f, (int) (u * (crop[2] >> shift)) + (crop[0] >> shift), (int) (v * (crop[3] >> shift)) + (crop[1] >> shift));
                LiveArkMeter.shading(f, (crop[0] + u * crop[2]) / f.width, (crop[1] + v * crop[3]) / f.height, gain);
                for (int i = 0; i < 3; i++) c[i] *= gain[i];
                float[] t = TRANSFORM;
                lin[(y * w + x) * 3] = Math.max(0f, t[0] * c[0] + t[3] * c[1] + t[6] * c[2]) * inScale;
                lin[(y * w + x) * 3 + 1] = Math.max(0f, t[1] * c[0] + t[4] * c[1] + t[7] * c[2]) * inScale;
                lin[(y * w + x) * 3 + 2] = Math.max(0f, t[2] * c[0] + t[5] * c[1] + t[8] * c[2]) * inScale;
            }
        }
        int aw = (w + 7) / 8, ah = (h + 7) / 8;
        FloatBuffer out = FloatBuffer.allocate(aw * ah * 4);
        for (int ay = 0; ay < ah; ay++) {
            for (int ax = 0; ax < aw; ax++) {
                float[] s = new float[3];
                for (int j = 0; j < 8; j++) for (int i = 0; i < 8; i++) {
                    int x = Math.min(ax * 8 + i, w - 1), y = Math.min(ay * 8 + j, h - 1);
                    for (int c = 0; c < 3; c++) s[c] += lin[(y * w + x) * 3 + c];
                }
                out.put(s[0] / 64f).put(s[1] / 64f).put(s[2] / 64f).put(1f);
            }
        }
        out.flip();
        size[0] = aw;
        size[1] = ah;
        return out;
    }

    private static ArkAe.Settings settings() {
        ArkAe.Settings s = new ArkAe.Settings();
        s.noiseS = 2e-4;
        s.noiseO = 1e-6;
        return s;
    }

    private static String line(String what, ArkAe.Result r) {
        return String.format(Locale.ROOT, "%s ae %.4f clip %.3f p50 %.5f p98 %.4f p995 %.4f geo %.5f effS %.3f effH %.3f", what, r.ae, r.clip,
                r.p50, r.p98, r.p995, r.geo, r.effS, r.effH);
    }

    /**
     * The photo's own AE input at this frame's colour resolution: arkLow is the box mean of 2 x 2 pixels of the 1x merge (one
     * Bayer quad), 4 x 4 of the 2x grid (one Quad colour quad) - one sample per colour quad of the whole crop.
     */
    private static FloatBuffer photoGrid(LiveRawFrame.Frame f, int[] crop, float inScale, int[] size) {
        int b = f.cfaBlock, qw = crop[2] / (2 * b), qh = crop[3] / (2 * b);
        FloatBuffer out = FloatBuffer.allocate(qw * qh * 3);
        float[] gain = new float[3];
        for (int qy = 0; qy < qh; qy++) {
            for (int qx = 0; qx < qw; qx++) {
                float[] c = fastColor(f, (crop[0] / b) + 2 * qx, (crop[1] / b) + 2 * qy);
                LiveArkMeter.shading(f, (crop[0] + (2 * qx + 1) * b) / (float) f.width, (crop[1] + (2 * qy + 1) * b) / (float) f.height, gain);
                for (int i = 0; i < 3; i++) c[i] *= gain[i];
                float[] t = TRANSFORM;
                out.put(Math.max(0f, t[0] * c[0] + t[3] * c[1] + t[6] * c[2]) * inScale);
                out.put(Math.max(0f, t[1] * c[0] + t[4] * c[1] + t[7] * c[2]) * inScale);
                out.put(Math.max(0f, t[2] * c[0] + t[5] * c[1] + t[8] * c[2]) * inScale);
            }
        }
        out.flip();
        size[0] = qw;
        size[1] = qh;
        return out;
    }

    /**
     * The CPU cells against the photo's quad grid (what they sample: the exposure, the percentiles, the fusion strengths) and
     * against the read-back they replace (the exposure; the read-back's box means of 8 x 8 viewport pixels smeared small lights
     * over their surroundings, so its highlight percentiles sat above the photo's - the cells are at least as close to the photo).
     */
    private static void compare(String label, LiveRawFrame.Frame f, int vw, int vh, double aeTol, double strengthTol) {
        compare(label, f, f, vw, vh, aeTol, 3 * aeTol, strengthTol);
    }

    /**
     * {@code merged}: the frame the photo's statistics come from (a noisy viewfinder frame: the same scene without the noise);
     * {@code highTol}: the relative tolerance of p98 / p995.
     */
    private static void compare(String label, LiveRawFrame.Frame f, LiveRawFrame.Frame merged, int vw, int vh, double aeTol, double highTol,
                                double strengthTol) {
        int[] crop = {0, 0, f.width / (2 * f.cfaBlock) * 2 * f.cfaBlock, f.height / (2 * f.cfaBlock) * 2 * f.cfaBlock};
        LiveArkMeter.View view = new LiveArkMeter.View(f, crop[0], crop[1], crop[2], crop[3], TRANSFORM);
        float inScale = 1.19f;
        int[] size = new int[2];
        ArkAe.Result before = ArkAe.smartHdr(arkLow8(f, crop, vw, vh, inScale, size), 4, size[0], size[1], settings(), f.iso, f.maxAnalogIso);
        ArkAe.Result photo = ArkAe.smartHdr(photoGrid(merged, crop, inScale, size), 3, size[0], size[1], settings(), f.iso, f.maxAnalogIso);
        ArkAe.Result now = new LiveArkMeter().measure(view, inScale, settings(), f.iso, f.maxAnalogIso);
        assertNotNull(now);
        String msg = label + "\n  " + line("arkLow f8 :", before) + "\n  " + line("quad grid :", photo) + "\n  " + line("CPU cells :", now);
        System.out.println(msg);
        // what the photo's AE sees
        assertEquals(msg, 1.0, now.ae / photo.ae, aeTol);
        assertEquals(msg, 1.0, now.p50 / photo.p50, aeTol);
        // the log mean: a frame's noise lifts it at night - never farther from the photo than the read-back was
        assertTrue(msg, Math.abs(now.geo / photo.geo - 1) <= Math.max(aeTol, Math.abs(before.geo / photo.geo - 1) + aeTol / 2));
        assertEquals(msg, 1.0, now.p98 / photo.p98, highTol);
        assertEquals(msg, 1.0, now.p995 / photo.p995, highTol);
        // the ceiling (the sparse cells may miss a small light's brightest quads: only the histograms' scale; headroom is off)
        assertEquals(msg, photo.bento, now.bento);
        assertEquals(msg, photo.clip, now.clip, 0.25 * photo.clip);
        assertEquals(msg, photo.effS, now.effS, strengthTol);
        assertEquals(msg, photo.effH, now.effH, strengthTol);
        assertEquals(msg, photo.slope, now.slope, 0.1);
        float[] em0 = ArkAe.exposures(photo), em1 = ArkAe.exposures(now);
        for (int i = 0; i < 4; i++) assertEquals(msg, 1.0, em1[i] / em0[i], 0.15);
        // the read-back's exposure (its percentiles of the highlights: at least as far from the photo's)
        assertEquals(msg, 1.0, now.ae / before.ae, 2 * aeTol);
        assertEquals(msg, before.effS, now.effS, 2 * strengthTol);
        assertTrue(msg, Math.abs(now.effH - photo.effH) <= Math.abs(before.effH - photo.effH) + strengthTol);
        assertTrue(msg, Math.abs(now.p995 / photo.p995 - 1) <= Math.abs(before.p995 / photo.p995 - 1) + highTol / 3);
    }

    /** No noise: the cells and the factor-8 box means see the same scene; the AE agrees within a few percent. */
    @Test
    public void theCellsGiveTheReadBacksExposureOnACleanFrame() {
        // the viewport (frame orientation) 960 x 720: the colour grid drawn larger, as the 1440 x 1920 screen drew it
        compare("2x ISZ cell means (block 1, cfa_mode 0)", frame(1024, 768, 1, true, 0, 1, 0.25), 960, 720, 0.04, 0.08);
        compare("Quad unbinned (block 2)", frame(1024, 768, 2, false, 0, 2, 0.25), 960, 720, 0.04, 0.08);
        compare("plain Bayer (cfa_mode 1)", frame(1024, 768, 1, false, 0, 3, 0.25), 960, 720, 0.04, 0.08);
        compare("a bright scene (ae below 1)", frame(1024, 768, 1, true, 0, 4, 1.0), 960, 720, 0.04, 0.08);
        compare("a window beside the lamp", frame(1024, 768, 1, true, 0, 8, 0.25, true), 960, 720, 0.04, 0.08);
        compare("a window, plain Bayer", frame(1024, 768, 1, false, 0, 9, 0.25, true), 960, 720, 0.04, 0.08);
    }

    /**
     * Shot noise (sigma ~ 1.2 - 1.5 sqrt(DN)) against the photo's statistics of the same scene without it (the merge's noise is
     * far below a frame's): the cells average 4 quads, the box means ~16-64 - the percentiles of the highlights move a little
     * (the risk the design names: the viewfinder's exposure a little off the photo's at night), the exposure stays close.
     */
    @Test
    public void theCellsStayCloseOnANoisyFrame() {
        compare("2x ISZ cell means, noisy", frame(1024, 768, 1, true, 1.2, 5, 0.25), frame(1024, 768, 1, true, 0, 5, 0.25), 960, 720, 0.08, 0.24, 0.15);
        compare("plain Bayer, noisy", frame(1024, 768, 1, false, 1.2, 6, 0.25), frame(1024, 768, 1, false, 0, 6, 0.25), 960, 720, 0.08, 0.24, 0.15);
        compare("a window, noisy", frame(1024, 768, 1, true, 1.2, 7, 0.25, true), frame(1024, 768, 1, true, 0, 7, 0.25, true), 960, 720, 0.08, 0.24, 0.15);
        // at night a frame's noise (sigma ~40 % of a site 15 DN above black) lifts the cells' p98 / p995 well above the merged
        // photo's (here +37 % / +74 %; the exposure is at its noise clamp either way): the risk the design names
        compare("2x ISZ cell means, night", frame(1024, 768, 1, true, 1.5, 8, 0.03), frame(1024, 768, 1, true, 0, 8, 0.03), 960, 720, 0.15, 1.0, 0.15);
    }

    @Test
    public void theGridCoversTheCropWithSquareCells() {
        LiveRawFrame.Frame f = frame(64, 48, 1, true, 0, 6, 0.25);
        assertArrayEquals4(new int[]{16, 12, 2, 2}, LiveArkMeter.grid(new LiveArkMeter.View(f, 0, 0, 64, 48, TRANSFORM)));
        f.cfaBlock = 2;
        assertArrayEquals4(new int[]{8, 6, 2, 2}, LiveArkMeter.grid(new LiveArkMeter.View(f, 0, 0, 64, 48, TRANSFORM)));
        // 2x ISZ: 2048 x 1536 cell means, 1024 x 768 quads -> 128 x 96 cells, 8 quads apart
        LiveRawFrame.Frame big = new LiveRawFrame.Frame();
        big.cfaBlock = 1;
        big.width = 4096;
        big.height = 3072;
        assertArrayEquals4(new int[]{128, 96, 8, 2}, LiveArkMeter.grid(new LiveArkMeter.View(big, 0, 0, 2048, 1536, TRANSFORM)));
        assertArrayEquals4(new int[]{128, 96, 16, 2}, LiveArkMeter.grid(new LiveArkMeter.View(big, 0, 0, 4096, 3072, TRANSFORM)));
        assertNull("no colour quad", LiveArkMeter.grid(new LiveArkMeter.View(big, 0, 0, 1, 1, TRANSFORM)));
        // a cell is the mean of its quads in the frame's linear values: a flat grey frame gives one value everywhere
        LiveRawFrame.Frame flat = frame(64, 48, 1, true, 0, 7, 0.25);
        for (int i = 0; i < 64 * 48; i++) flat.buffer.putShort(i * 2, (short) (64 + 400));
        flat.shadingWidth = flat.shadingHeight = 1;
        flat.shading = new float[]{1, 1, 1};
        float[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        int[] n = new int[2];
        float[] c = LiveArkMeter.cells(new LiveArkMeter.View(flat, 0, 0, 64, 48, id), 2f, n);
        float want = 400f / 959f * 2f;
        for (int i = 0; i < n[0] * n[1]; i++) {
            assertEquals(want * 2.0f, c[i * 3], 1e-5);
            assertEquals(want * 1.0f, c[i * 3 + 1], 1e-5);
            assertEquals(want * 1.6f, c[i * 3 + 2], 1e-5);
        }
        assertTrue(n[0] == 16 && n[1] == 12);
    }

    private static void assertArrayEquals4(int[] want, int[] got) {
        assertNotNull(got);
        org.junit.Assert.assertArrayEquals(want, got);
    }
}
