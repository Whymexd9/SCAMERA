package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.util.Arrays;
import java.util.Locale;

/**
 * P378 (owner, 2026-10-28: «исправить тонмапинг чтобы у нас небо было схожее с фото с Арк камеры. при этом чтобы в остальных
 * сценариях ничего не сломалось»; «Нет, только небо»; «наш тонмапинг не должен затемнять небо в контровых сценах»): whether a
 * photo has a sky for the WHY tone's sky term, and where - decided once per photo on per-cell statistics of whyLow (why/skycells.glsl
 * on the GPU, {@link Cells#of} the same on the CPU; the viewfinder: {@link Cells#ofQuads} on LiveWhyMeter's quads). Pure Java (no
 * GL): WhySky draws the cells and the fine mask, LiveWhyTone decides on the meter's frame, WhySkyMaskTest and tools/check_why_sky.py
 * run this decision as the photo does.
 * <ol>
 *   <li>A cell is sky-like when it is daylight (absolute EV from ISO / shutter / aperture: 10.5 .. 12 up - the review's ±1 EV of
 *       calibration per phone on top of 9.5 .. 11: a room, a daylit white ceiling (EV ~10.7) and a night sky stay below), 1.5 ..
 *       2.5 stops over the median cell of the half of the frame away from the top edge (P378 review: the whole frame's median was
 *       the sky itself once it filled half the frame - no term from 50 % up; a white wall over most of the frame is that half's
 *       median itself), smooth (the mean 1 px log2 step under 0.06 .. 0.12: branches, facades and the noise of the dark parts are
 *       above it; within-cell spread under 0.45 .. 0.9 stops: a cell across a horizon), and grey or blue ({@link #colourGate}: a
 *       face, sand, light plaster, a red roof, foliage and a sunset's glow are none).</li>
 *   <li>The sky is what of it connects to the photo's top edge (flood fill over 4-neighbours from the cells of the top edge with
 *       a weight of 0.5 up, through cells of 0.3 up; the top edge follows the shot's rotation, {@link #topEdge}). A thin line
 *       across the sky (a power line, a branch: its cells fail the texture and spread gates) does not cut it: a cell between
 *       sky-like cells on opposite sides within 2 cells takes their weight first ({@link #bridge}; the fine mask's pixel gate
 *       still keeps the wire's own pixels out). A light, flat surface joined below the sky along a straight step (a white facade,
 *       snow under overcast, a sea) is cut off along that step ({@link #joints}). Under 5 % of the frame it is none: no mask, no
 *       define, the tone as before bit for bit.</li>
 *   <li>Backlit ({@link Mask#backlit}: the sky term keeps the sky's level, only its colour and modelling change): a core touches
 *       the sky - clipped with its ceiling 2.5 stops or more over the sky's mean or at EV 17.5 and up (the sun with Bento or at the
 *       sensor's floor), a glow 3 stops over the sky, or (P378 review: a sun clipped without ultrashort frames at 1/500 - 1/2000 s
 *       sat within 2.5 stops of the sky and under EV 17.5, and the sky was lowered under it) a compact clipped blob with a radial
 *       fall-off ({@link #glowCores}: round, its centre fully clipped, the rings round it brighter than the sky and falling off on
 *       every side - broad clipped clouds are none); or the subject - the half of the frame away from the top edge - is
 *       indoor-dim (its median under EV 7.5) and 5 stops or more under the sky (a window from a room, the owner's burst).</li>
 *   <li>The clear sky ({@link Mask#cells} g): the sky away from everything else - its weight times the least weight of its 3 x 3
 *       neighbours (the frame's outside counts as sky), none within 2 cells of a core or its glow: the cloud modelling and the
 *       landing's glow act there only (Mertens' halo round a dark object, the sun's own landing).</li>
 * </ol>
 */
public final class WhySkyMask {
    private WhySkyMask() {}

    /** Cells along whyLow's longer side (64 x 48 at 4:3). */
    static final int CELLS_LONG = 64;
    /** At most this many samples along a cell's side: a larger cell (above 12 MP) is sampled with a stride. */
    static final int SAMPLES = 32;
    /**
     * EV100 of base white (a whyLow value of 1) over log2(N^2 / t) - log2(ISO / 100): ISO 12232's saturation-based speed, the
     * luminance at the sensor's clip L = 78 / (0.65 S) N^2 / t, EV100 = log2(8 L) - so log2(9.6).
     */
    static final double SAT_EV = Math.log(9.6) / Math.log(2.0);

    /** The photo's top edge in whyLow (sensor) coordinates: row 0, column 0, the last row, the last column. */
    static final int TOP_ROW0 = 0, TOP_COL0 = 1, TOP_ROWN = 2, TOP_COLN = 3;

    // the gates of a sky-like cell (see the class comment)
    static final float EV_LO = 10.5f, EV_HI = 12f;
    static final float OVER_MEDIAN_LO = 1.5f, OVER_MEDIAN_HI = 2.5f;
    /** The subject's level: the median of the far half's cells unlike sky, when they are GROUND_MIN of it; else its percentile. */
    static final float GROUND_MIN = 0.1f, GROUND_PERCENTILE = 0.2f;
    /** The spread (stops, sd of the cells' log2 Y) of a sky the lower reference takes in: clouds, not a smooth wall. */
    static final float SKY_STRUCTURE = 0.15f;
    static final float TEX_LO = 0.06f, TEX_HI = 0.12f;
    static final float SPREAD_LO = 0.45f, SPREAD_HI = 0.9f;
    /** {@link #colourGate}: any hue under CHROMA_LO .. HI; a yellow-orange (OKLab b) or red-magenta (a) cast past WARM / RED none. */
    static final float CHROMA_LO = 0.04f, CHROMA_HI = 0.08f;
    static final float WARM_LO = 0.008f, WARM_HI = 0.02f, RED_LO = 0.015f, RED_HI = 0.03f;
    static final float SEED = 0.5f, GROW = 0.3f, MIN_COVERAGE = 0.05f;
    /** A cell is clipped from this share of its samples at 0.9 x the data ceiling (why/skycells.glsl clipU). */
    static final float CLIP_SHARE = 0.002f, CLIP_LEVEL = 0.9f;
    /** A core: a clipped ceiling this far over the sky's mean, or a glow this far over it (stops); its glow down to this over it. */
    static final float CORE_OVER = 2.5f, GLOW_OVER = 3f, GLOW_REACH = 1f;
    /**
     * A clipped ceiling this bright (EV100) is the sun or its reflection whatever the sky's level: sunlit cumulus tops reach ~EV 17
     * (the owner's sun shot at the sensor's floor, 1/8850 s f/1.8 ISO 100: the ceiling at EV ~20).
     */
    static final float SUN_EV = 17.5f;
    /**
     * {@link #glowCores}: a blob of clipped cells at most this share of the frame's cells, filling at least BLOB_FILL of its bounding
     * box with sides at most BLOB_ASPECT to 1, its most clipped cell at least BLOB_SOLID clipped (the sun is far over any ceiling;
     * a cloud's top that only brushes it clips in 0.5 - 0.75 of the cell, whose dome falls off as smoothly); the mean levels of the rings of
     * cells 1, 2, 3 cells from it falling by RING_FALL_12 and RING_FALL_23 stops, the second fall RING_SMOOTH .. RING_EASE of
     * the first (a glow's log falls off smoothly and ever slower, in the sky's light added to the glow's: a clipped cumulus top
     * drops 0.4 - 0.7 stop into the cloud's body next to it, then 0 - 0.17, 0.2 - 0.26 of it, or a cloud's bright top falls 0.2 then
     * 0.53 at its edge, 2.6 x; the suns of WhySkyMaskTest 0.5 - 0.56), the first RING_OVER over the sky's mean,
     * every side of the first ring (quadrants round the blob's centre; one may miss when all four have cells) RING_SIDE over the
     * third.
     */
    static final float BLOB_MAX_SHARE = 0.06f, BLOB_FILL = 0.35f, BLOB_ASPECT = 2.5f, BLOB_SOLID = 0.9f;
    static final float RING_FALL_12 = 0.12f, RING_FALL_23 = 0.12f, RING_SMOOTH = 0.35f, RING_EASE = 1.3f, RING_OVER = 0.4f, RING_SIDE = 0.1f;
    /**
     * {@link #glowCores}' disc: at least DISC_CELLS cells clipped fully (BLOB_FULL of their samples), filling DISC_FILL of their
     * box with sides within DISC_ASPECT, its first ring DISC_RING over the sky - a sun whose
     * glow is lost in the clouds' texture past the first ring (tools/check_why_sky.py's sun clipped at 1.2: 15 cells in 4 x 4);
     * clipped cumulus tops clip fully in at most 7 cells, along their sunlit side (9 x 3); a flat clipped patch has no brighter ring.
     */
    static final float BLOB_FULL = 0.9f, DISC_FILL = 0.55f, DISC_ASPECT = 1.7f, DISC_RING = 0.25f;
    static final int DISC_CELLS = 9;
    /**
     * P378 review ({@link #glowCores}: a backlit sun without ultrashort frames still darkened): the rings' and sides' level is the
     * mean of their cells down to RING_TRIM stops under the ring's median (a wire, a branch or a roofline in front of the glow darkens
     * some of a ring's cells, not the rest of it); a round blob may cover up to BLOB_ROUND_SHARE of the frame (a strong aureole's).
     */
    static final float RING_TRIM = 0.5f, BLOB_ROUND_SHARE = 0.15f;
    /** {@link #joints}: a flat grey surface (cells within JOINT_FLAT of each other) JOINT_STEP stops under the sky above it. */
    static final float JOINT_STEP = 0.25f, JOINT_FLAT = 0.05f, JOINT_GREY = 0.015f, JOINT_AREA = 0.02f;
    /** P378 review: a coloured joined region's colour against the sky's own (OKLab a, b); a side's wander across its rows (cells). */
    static final float JOINT_HUE = 0.012f, JOINT_SIDE_WANDER = 1f;
    static final int JOINT_RUN_MIN = 4;
    /**
     * The indoor-dim subject: its median EV under SHADE_EV and SHADE_STOPS under the sky. P378 review (a room 0.5 EV brighter took
     * the sky from x1.00 to x0.85; EV_LO had a 1 EV margin, this none): a ramp - the term's level share from 1 at SHADE_EV_HI down
     * to 0 at SHADE_EV, times SHADE_STOPS_LO .. SHADE_STOPS (a room at EV 7 - 9 under an overcast sky at EV 14 is backlit, at 9.5 in
     * part; the check's outdoor overcast ground at EV 10.4 all but not).
     */
    static final float SHADE_EV = 9f, SHADE_EV_HI = 10.5f, SHADE_STOPS = 4.5f, SHADE_STOPS_LO = 3.5f;
    /** The clouds' tops (why/combine.glsl L3): the sky's gain fades out from this to this many stops over the sky's mean. */
    static final float TOP_FROM = 0.2f, TOP_TO = 1.0f;

    /** The cell's side in whyLow px for a whyLow of w x h. */
    static int cellPx(int w, int h) {
        return Math.max(4, (Math.max(w, h) + CELLS_LONG - 1) / CELLS_LONG);
    }

    /** The sample stride inside a cell of {@code cell} px. */
    static int step(int cell) {
        return Math.max(1, cell / SAMPLES);
    }

    /**
     * The EV100 of a whyLow value of 1 (base white) for the shot's ISO, exposure time (s) and f-number, whyLow being the merge x
     * {@code inScale} (why_input_ev); NaN when any is unknown.
     */
    static float evOffset(int iso, double exposureSeconds, float aperture, float inScale) {
        if (iso <= 0 || !(exposureSeconds > 0) || !(aperture > 0f) || !(inScale > 0f)) return Float.NaN;
        final double n2 = (double) aperture * aperture;
        return (float) (log2(n2 / exposureSeconds) - log2(iso / 100.0) + SAT_EV - log2(inScale));
    }

    /**
     * The photo's top edge in whyLow for the shot's rotation (Parameters.cameraRotation) and mirror: the saved image is whyLow's
     * grid turned as PostPipeline's rotation pass turns it (rotate 0: row 0 on top, the mirror flips it; 90: column 0; 180: the
     * last row, mirrored row 0; 270: the last column).
     */
    static int topEdge(int rotation, boolean mirror) {
        switch (((rotation % 360) + 360) % 360) {
            case 90: return TOP_COL0;
            case 180: return mirror ? TOP_ROW0 : TOP_ROWN;
            case 270: return TOP_COLN;
            default: return mirror ? TOP_ROWN : TOP_ROW0;
        }
    }

    /** The shot's numbers the decision needs. */
    static final class Scene {
        /** {@link #evOffset}. */
        final float evOffset;
        /** {@link #topEdge}. */
        final int top;
        /** The data ceiling of whyLow (WhyAe's clip, or the Bento headroom's real ceiling when it is higher). */
        final float ceiling;

        Scene(float evOffset, int top, float ceiling) {
            this.evOffset = evOffset;
            this.top = top;
            this.ceiling = ceiling;
        }
    }

    /**
     * The cells' statistics: per cell (row-major from row 0) the mean R, G, B (linear, negatives and non-finite channels as 0), the
     * mean and the spread (sd) of log2 Y, the texture (the mean |step| of log2 Y to the sample a stride to the right and below,
     * averaged), the largest channel and the share of samples whose largest channel reaches the clip level.
     */
    static final class Cells {
        /** The statistics a cell carries, in the order of {@link #pack}. */
        static final int FIELDS = 8;
        final int nx, ny, cell, step;
        final float[] r, g, b, l, sd, tex, max, clip;

        Cells(int nx, int ny, int cell, int step) {
            this.nx = nx;
            this.ny = ny;
            this.cell = cell;
            this.step = step;
            final int n = nx * ny;
            r = new float[n];
            g = new float[n];
            b = new float[n];
            l = new float[n];
            sd = new float[n];
            tex = new float[n];
            max = new float[n];
            clip = new float[n];
        }

        /** The grid of a whyLow of w x h. */
        static Cells grid(int w, int h) {
            final int cell = cellPx(w, h);
            return new Cells((w + cell - 1) / cell, (h + cell - 1) / cell, cell, step(cell));
        }

        /**
         * From why/skycells.glsl's two read-backs (RGBA, row-major from row 0): MODE 0 = mean R, G, B, mean log2 Y; MODE 1 =
         * mean (log2 Y)^2, texture, largest channel, clipped share.
         */
        static Cells fromGpu(int w, int h, float[] mode0, float[] mode1) {
            final Cells c = grid(w, h);
            for (int i = 0; i < c.nx * c.ny; i++) {
                c.r[i] = mode0[4 * i];
                c.g[i] = mode0[4 * i + 1];
                c.b[i] = mode0[4 * i + 2];
                c.l[i] = mode0[4 * i + 3];
                c.sd[i] = (float) Math.sqrt(Math.max(0.0, (double) mode1[4 * i] - (double) c.l[i] * c.l[i]));
                c.tex[i] = mode1[4 * i + 1];
                c.max[i] = mode1[4 * i + 2];
                c.clip[i] = mode1[4 * i + 3];
            }
            return c;
        }

        /** why/skycells.glsl on the CPU: whyLow as {@code channels} floats a pixel (RGB first), rows from row 0. */
        static Cells of(float[] px, int w, int h, int channels, float clipLevel) {
            final Cells c = grid(w, h);
            for (int cy = 0; cy < c.ny; cy++) {
                for (int cx = 0; cx < c.nx; cx++) {
                    final int x0 = cx * c.cell, y0 = cy * c.cell, x1 = Math.min(x0 + c.cell, w), y1 = Math.min(y0 + c.cell, h);
                    double sr = 0, sg = 0, sb = 0, sl = 0, sl2 = 0, st = 0, n = 0, clipped = 0;
                    float mx = 0f;
                    for (int y = y0; y < y1; y += c.step) {
                        for (int x = x0; x < x1; x += c.step) {
                            final float[] v = tap(px, w, channels, x, y);
                            final float lv = lum(v);
                            sr += v[0];
                            sg += v[1];
                            sb += v[2];
                            sl += lv;
                            sl2 += (double) lv * lv;
                            final float right = lum(tap(px, w, channels, Math.min(x + c.step, w - 1), y));
                            final float below = lum(tap(px, w, channels, x, Math.min(y + c.step, h - 1)));
                            st += 0.5 * (Math.abs(lv - right) + Math.abs(lv - below));
                            final float m = Math.max(v[0], Math.max(v[1], v[2]));
                            mx = Math.max(mx, m);
                            if (m >= clipLevel) clipped++;
                            n++;
                        }
                    }
                    final int i = cy * c.nx + cx;
                    c.r[i] = (float) (sr / n);
                    c.g[i] = (float) (sg / n);
                    c.b[i] = (float) (sb / n);
                    c.l[i] = (float) (sl / n);
                    c.sd[i] = (float) Math.sqrt(Math.max(0.0, sl2 / n - (sl / n) * (sl / n)));
                    c.tex[i] = (float) (st / n);
                    c.max[i] = mx;
                    c.clip[i] = (float) (clipped / n);
                }
            }
            return c;
        }

        /**
         * P378 review (the viewfinder: «Результат должен совпадать с итоговым фото», P301): the cells of LiveWhyMeter's frame -
         * {@code mx} x {@code my} meter cells, each the 2 x 2 colour quads at its centre (adjacent quads: one whyLow px apart, the
         * photo's 1 px texture), as {@code quads}: per meter cell its 4 quads (top left, top right, bottom left, bottom right) x
         * R, G, B (linear x inScale, the values the viewfinder draws). A sky cell is 2 x 2 meter cells (64 x 48 of the meter's
         * 128 x 96): the mean colour and log2 Y of its 16 quads, their spread, the texture of the quads' 4 neighbour pairs in each
         * group, the largest channel and the share of quads at the clip level.
         */
        static Cells ofQuads(float[] quads, int mx, int my, float clipLevel) {
            final int nx = Math.max(1, mx / 2), ny = Math.max(1, my / 2);
            final Cells c = new Cells(nx, ny, 2, 1);
            final float[] lq = new float[4];
            for (int cy = 0; cy < ny; cy++) {
                for (int cx = 0; cx < nx; cx++) {
                    double sr = 0, sg = 0, sb = 0, sl = 0, sl2 = 0, st = 0, pairs = 0, n = 0, clipped = 0;
                    float top = 0f;
                    for (int gy = 2 * cy; gy < Math.min(2 * cy + 2, my); gy++) {
                        for (int gx = 2 * cx; gx < Math.min(2 * cx + 2, mx); gx++) {
                            final int o = (gy * mx + gx) * 12;
                            for (int q = 0; q < 4; q++) {
                                final float qr = finite(quads[o + 3 * q]), qg = finite(quads[o + 3 * q + 1]), qb = finite(quads[o + 3 * q + 2]);
                                lq[q] = lum(qr, qg, qb);
                                sr += qr;
                                sg += qg;
                                sb += qb;
                                sl += lq[q];
                                sl2 += (double) lq[q] * lq[q];
                                final float m = Math.max(qr, Math.max(qg, qb));
                                top = Math.max(top, m);
                                if (m >= clipLevel) clipped++;
                                n++;
                            }
                            // the group's neighbour pairs: across (0-1, 2-3) and down (0-2, 1-3)
                            st += Math.abs(lq[0] - lq[1]) + Math.abs(lq[2] - lq[3]) + Math.abs(lq[0] - lq[2]) + Math.abs(lq[1] - lq[3]);
                            pairs += 4;
                        }
                    }
                    final int i = cy * nx + cx;
                    if (n == 0) {
                        c.l[i] = Float.NaN;
                        continue;
                    }
                    c.r[i] = (float) (sr / n);
                    c.g[i] = (float) (sg / n);
                    c.b[i] = (float) (sb / n);
                    c.l[i] = (float) (sl / n);
                    c.sd[i] = (float) Math.sqrt(Math.max(0.0, sl2 / n - (sl / n) * (sl / n)));
                    c.tex[i] = (float) (st / Math.max(pairs, 1));
                    c.max[i] = top;
                    c.clip[i] = (float) (clipped / n);
                }
            }
            return c;
        }

        /** The statistics as one array (FIELDS a cell: R, G, B, log2 Y, its sd, texture, largest channel, clipped share). */
        float[] pack() {
            final int n = nx * ny;
            final float[] v = new float[FIELDS * n];
            final float[][] f = {r, g, b, l, sd, tex, max, clip};
            for (int k = 0; k < FIELDS; k++) System.arraycopy(f[k], 0, v, k * n, n);
            return v;
        }

        /** {@link #pack}'s array back (null when it does not fit the grid). */
        static Cells unpack(int nx, int ny, int cell, int step, float[] v) {
            if (nx <= 0 || ny <= 0 || v == null || v.length != FIELDS * nx * ny) return null;
            final Cells c = new Cells(nx, ny, cell, step);
            final int n = nx * ny;
            final float[][] f = {c.r, c.g, c.b, c.l, c.sd, c.tex, c.max, c.clip};
            for (int k = 0; k < FIELDS; k++) System.arraycopy(v, k * n, f[k], 0, n);
            return c;
        }

        /**
         * The same cells of whyLow x {@code k} (P277: a lab side with another «Масштаб входа, EV»; whyLow is linear in it): the
         * colour and the largest channel x k, log2 Y + log2 k; the spread, the texture and the clipped share (at the ceiling, which
         * scales alike) unchanged.
         */
        Cells scaled(float k) {
            final Cells c = new Cells(nx, ny, cell, step);
            final float lk = k > 0f && Float.isFinite(k) ? (float) log2(k) : 0f;
            final float m = k > 0f && Float.isFinite(k) ? k : 1f;
            for (int i = 0; i < nx * ny; i++) {
                c.r[i] = r[i] * m;
                c.g[i] = g[i] * m;
                c.b[i] = b[i] * m;
                c.l[i] = l[i] + lk;
                c.sd[i] = sd[i];
                c.tex[i] = tex[i];
                c.max[i] = max[i] * m;
                c.clip[i] = clip[i];
            }
            return c;
        }

        private static float[] tap(float[] px, int w, int channels, int x, int y) {
            final int o = (y * w + x) * channels;
            return new float[]{finite(px[o]), finite(px[o + 1]), finite(px[o + 2])};
        }

        /** why/skycells.glsl tap(): a non-finite or negative channel counts as 0. */
        private static float finite(float v) {
            return v > 0f && v < 1.0e30f ? v : 0f;
        }

        private static float lum(float[] v) {
            return lum(v[0], v[1], v[2]);
        }

        private static float lum(float r, float g, float b) {
            return (float) log2(Math.max(0.2126f * r + 0.7152f * g + 0.0722f * b, 0.000001f));
        }
    }

    /** The sky of a photo: per cell r = the sky's weight, g = the clear sky's, b = the local sky level (log2 Y), a = 0. */
    static final class Mask {
        final int nx, ny;
        final float[] cells;
        /** P378 review: the share of the sky term's level (1 none of it backlit; 0 a core touches the sky or a dim room under it). */
        final float keep;
        /** Mostly backlit (keep under a half; the log, the Ultra HDR ratio). */
        final boolean backlit;
        final float coverage, skyEv, subjectEv;
        /** The sky's mean log2 Y of whyLow (weighted by the cells' sky weights). */
        final float skyLog2;
        /** Why it is backlit ("" when it is not). */
        final String backlitWhy;

        Mask(int nx, int ny, float[] cells, float keep, float coverage, float skyLog2, float evOffset, float subjectEv, String backlitWhy) {
            this.nx = nx;
            this.ny = ny;
            this.cells = cells;
            this.keep = keep;
            this.backlit = keep < 0.5f;
            this.coverage = coverage;
            this.skyLog2 = skyLog2;
            this.skyEv = skyLog2 + evOffset;
            this.subjectEv = subjectEv;
            this.backlitWhy = backlitWhy;
        }

        /**
         * why/combine.glsl skyTopU (L3): the clouds' tops in log2 Y of whyLow, where the sky's gain fades out - from TOP_FROM to
         * TOP_TO stops over the sky's mean.
         */
        float[] tops() {
            return new float[]{skyLog2 + TOP_FROM, skyLog2 + TOP_TO};
        }
    }

    /** {@link #decide}'s answer: the mask (null: no sky) and a line for the log. */
    static final class Decision {
        final Mask mask;
        final String log;

        Decision(Mask mask, String log) {
            this.mask = mask;
            this.log = log;
        }
    }

    /** The grid in the photo's terms: u along the top edge, v away from it ({@link #at}). */
    private static final class Frame {
        final int top, nx, ny, us, vs;

        Frame(Cells c, int top) {
            this.top = top;
            nx = c.nx;
            ny = c.ny;
            final boolean rows = top == TOP_ROW0 || top == TOP_ROWN;
            us = rows ? nx : ny;
            vs = rows ? ny : nx;
        }

        /** The cell at u along the top edge, v from it; -1 outside. */
        int at(int u, int v) {
            if (u < 0 || v < 0 || u >= us || v >= vs) return -1;
            switch (top) {
                case TOP_COL0: return u * nx + v;
                case TOP_COLN: return u * nx + (nx - 1 - v);
                case TOP_ROWN: return (ny - 1 - v) * nx + u;
                default: return v * nx + u;
            }
        }

        /** Whether cell i lies in the half of the frame away from the top edge. */
        boolean far(int i) {
            final int x = i % nx, y = i / nx;
            switch (top) {
                case TOP_COL0: return 2 * x >= nx;
                case TOP_COLN: return 2 * x < nx;
                case TOP_ROWN: return 2 * y < ny;
                default: return 2 * y >= ny;
            }
        }
    }

    static Decision decide(Cells c, Scene s) {
        if (!Float.isFinite(s.evOffset)) return new Decision(null, "none (exposure unknown)");
        final int n = c.nx * c.ny;
        final Frame fr = new Frame(c, s.top);
        final float[] w0 = new float[n];
        // P378 review: over the median of the half away from the top edge (the subject's), not the whole frame's; from a sky over ~75 %
        // of the frame's height that median was the sky itself (a low horizon got no term, a 2 % tilt switched it): then over the
        // median of its cells that do not look like sky (the gates but this one; few such cells: the half's 20th percentile) - when
        // what that takes in has clouds' structure (SKY_STRUCTURE): a white wall over most of the frame is smooth
        final boolean[] farHalf = new boolean[n], ground = new boolean[n];
        final float[] look = new float[n];
        int farN = 0, groundN = 0;
        for (int i = 0; i < n; i++) {
            farHalf[i] = fr.far(i);
            if (!Float.isFinite(c.l[i]) || !Float.isFinite(c.tex[i]) || !Float.isFinite(c.sd[i])) continue;
            look[i] = smooth(EV_LO, EV_HI, c.l[i] + s.evOffset) * (1f - smooth(TEX_LO, TEX_HI, c.tex[i]))
                    * (1f - smooth(SPREAD_LO, SPREAD_HI, c.sd[i])) * colourGate(c, i);
            if (!farHalf[i]) continue;
            farN++;
            ground[i] = look[i] < 0.5f;
            if (ground[i]) groundN++;
        }
        float median = median(c.l, farHalf);
        if (!Float.isFinite(median)) median = median(c.l, null);
        final float below = groundN >= GROUND_MIN * farN ? median(c.l, ground) : percentile(c.l, farHalf, GROUND_PERCENTILE);
        if (Float.isFinite(below) && below < median) {
            // the spread of the levels it would take in (sky-like, over the lower reference, not over the half's median)
            double sl = 0, sl2 = 0;
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (!(look[i] >= 0.5f) || !(c.l[i] - below >= OVER_MEDIAN_LO)) continue;
                sl += c.l[i];
                sl2 += (double) c.l[i] * c.l[i];
                k++;
            }
            final double spread = k > 1 ? Math.sqrt(Math.max(0, sl2 / k - (sl / k) * (sl / k))) : 0;
            if (spread >= SKY_STRUCTURE) median = below;
        }
        for (int i = 0; i < n; i++) {
            if (!Float.isFinite(c.l[i]) || !Float.isFinite(c.tex[i]) || !Float.isFinite(c.sd[i])) continue;
            w0[i] = look[i] * smooth(OVER_MEDIAN_LO, OVER_MEDIAN_HI, c.l[i] - median);
        }
        // the cells kept out of the flood: a joint's step (P378 review) and the cores below
        final boolean[] joint = new boolean[n], core = new boolean[n], veto = new boolean[n];
        float[] w = flood(c, bridge(c, w0, veto), s.top);
        float sum = sum(w);
        if (sum / n < MIN_COVERAGE) {
            return new Decision(null, String.format(Locale.ROOT, "none (sky %.1f %% of the frame, under %.0f %%; median EV %.1f)",
                    100f * sum / n, 100f * MIN_COVERAGE, median + s.evOffset));
        }
        // a light, flat surface joined below the sky along a straight step: cut off there
        final int joined = joints(c, fr, w, joint);
        if (joined > 0) {
            for (int i = 0; i < n; i++) veto[i] |= joint[i];
            w = flood(c, bridge(c, w0, veto), s.top);
            sum = sum(w);
            if (sum / n < MIN_COVERAGE) {
                return new Decision(null, String.format(Locale.ROOT, "none (sky %.1f %% of the frame above a joined surface)", 100f * sum / n));
            }
        }
        float skyL = weightedMean(c.l, w);
        // cores: a clipped ceiling far over the sky (the sun, a lamp), a glow far over it, a compact clipped blob with a radial
        // fall-off (the sun without ultrashort frames); never sky
        final float ceilingL = (float) log2(Math.max(CLIP_LEVEL * s.ceiling, 1e-6f));
        boolean anyCore = false;
        for (int i = 0; i < n; i++) {
            core[i] = (c.clip[i] >= CLIP_SHARE && (ceilingL - skyL >= CORE_OVER || ceilingL + s.evOffset >= SUN_EV))
                    || c.l[i] >= skyL + GLOW_OVER;
        }
        final int glow = glowCores(c, skyL, core);
        for (int i = 0; i < n; i++) {
            if (core[i]) {
                anyCore = true;
                veto[i] = true;
            }
        }
        if (anyCore) {
            w = flood(c, bridge(c, w0, veto), s.top);
            sum = sum(w);
            if (sum / n < MIN_COVERAGE) {
                return new Decision(null, String.format(Locale.ROOT, "none (sky %.1f %% of the frame beside a clipped core)", 100f * sum / n));
            }
            skyL = weightedMean(c.l, w);
        }
        // a core with its own glow: grown over 8-neighbours a stop and more over the sky's mean (a sun's halo falls off over
        // several cells, too steep to be sky-like itself)
        if (anyCore) grow(c, core, skyL + GLOW_REACH);
        // backlit (a): a core (or its glow) touching the sky-like cells
        boolean coreTouches = false;
        for (int y = 0; y < c.ny && !coreTouches; y++) {
            for (int x = 0; x < c.nx && !coreTouches; x++) {
                if (!core[y * c.nx + x]) continue;
                for (int dy = -1; dy <= 1 && !coreTouches; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < c.nx && yy < c.ny && Math.max(w0[yy * c.nx + xx], w[yy * c.nx + xx]) >= GROW) {
                            coreTouches = true;
                            break;
                        }
                    }
                }
            }
        }
        // backlit (b): the subject (the half of the frame away from the top edge, not sky, not a core) indoor-dim and far under the sky
        final boolean[] subject = new boolean[n];
        for (int i = 0; i < n; i++) subject[i] = farHalf[i] && w[i] < 0.05f && !core[i] && Float.isFinite(c.l[i]);
        final float subjectL = median(c.l, subject);
        final float shade = Float.isFinite(subjectL)
                ? (1f - smooth(SHADE_EV, SHADE_EV_HI, subjectL + s.evOffset)) * smooth(SHADE_STOPS_LO, SHADE_STOPS, skyL - subjectL) : 0f;
        final float keep = coreTouches ? 0f : 1f - shade;
        final boolean backlit = keep < 1f;
        final String why = coreTouches ? (glow > 0 ? "a clipped core with a radial glow touches the sky" : "a clipped core touches the sky")
                : shade > 0f ? String.format(Locale.ROOT, "the subject is indoor-dim under the sky: %.0f %%", 100f * shade) : "";
        // the clear sky: the least weight of the 3 x 3 neighbourhood (outside: sky), none within 2 cells of a core
        final float[] out = new float[4 * n];
        for (int y = 0; y < c.ny; y++) {
            for (int x = 0; x < c.nx; x++) {
                final int i = y * c.nx + x;
                float least = 1f;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < c.nx && yy < c.ny) least = Math.min(least, w[yy * c.nx + xx]);
                    }
                }
                boolean nearCore = false;
                for (int dy = -2; dy <= 2 && !nearCore; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        final int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < c.nx && yy < c.ny && core[yy * c.nx + xx]) {
                            nearCore = true;
                            break;
                        }
                    }
                }
                // the local sky level: the weighted mean of the sky cells within 2 cells, else the sky's
                double sw = 0, sl = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        final int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= c.nx || yy >= c.ny) continue;
                        final float wv = w[yy * c.nx + xx];
                        if (wv > 0f && Float.isFinite(c.l[yy * c.nx + xx])) {
                            sw += wv;
                            sl += wv * c.l[yy * c.nx + xx];
                        }
                    }
                }
                out[4 * i] = w[i];
                out[4 * i + 1] = nearCore ? 0f : w[i] * least;
                out[4 * i + 2] = sw > 0.05 ? (float) (sl / sw) : skyL;
            }
        }
        final Mask m = new Mask(c.nx, c.ny, out, keep, sum(w) / n, skyL, s.evOffset, subjectL + s.evOffset, why);
        return new Decision(m, String.format(Locale.ROOT, "%.1f %% of the frame, sky EV %.1f, subject EV %.1f, median EV %.1f%s%s",
                100f * m.coverage, m.skyEv, m.subjectEv, median + s.evOffset, joined > 0 ? ", a joined surface cut off (" + joined + " cells)" : "",
                backlit ? ", backlit (" + why + "): " + (keep == 0f ? "the sky's level kept" : String.format(Locale.ROOT,
                        "%.0f %% of the sky's lowering", 100f * keep)) : ""));
    }

    /**
     * Grey or blue: the OKLab chroma of the cell's mean colour (at a fixed lightness) under CHROMA_LO .. HI or a blue hue, and no
     * yellow-orange (b over WARM_LO .. HI) or red-magenta (a over RED_LO .. HI) cast - P378 review: a front-lit face (OKLab a +0.03,
     * b +0.04), sand and light plaster passed the chroma limit; the clouds' own cast (a, b within +0.002 .. 0.01) stays in.
     */
    static float colourGate(Cells c, int i) {
        return colourGate(c.r[i], c.g[i], c.b[i]);
    }

    /** The OKLab chroma of the cell's mean colour at a fixed lightness (as {@link #colourGate}); NaN without light. */
    static float chroma(Cells c, int i) {
        final float y = 0.2126f * c.r[i] + 0.7152f * c.g[i] + 0.0722f * c.b[i];
        if (!(y > 0f) || !Float.isFinite(y)) return Float.NaN;
        final float k = 0.5f / y;
        final float[] lab = oklab(c.r[i] * k, c.g[i] * k, c.b[i] * k);
        return (float) Math.hypot(lab[1], lab[2]);
    }

    /** {@link #colourGate(Cells, int)} of a linear colour (why/skymask.glsl skyHue: the same per pixel). */
    static float colourGate(float r, float g, float b) {
        final float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        if (!(y > 0f) || !Float.isFinite(y)) return 0f;
        final float k = 0.5f / y;
        final float[] lab = oklab(r * k, g * k, b * k);
        final float chroma = (float) Math.hypot(lab[1], lab[2]);
        final float blue = Math.max(0f, Math.min(1f, -lab[2] / Math.max(chroma, 1e-6f)));
        return (1f - smooth(CHROMA_LO, CHROMA_HI, chroma) * (1f - smooth(0.5f, 0.8f, blue)))
                * (1f - smooth(WARM_LO, WARM_HI, lab[2])) * (1f - smooth(RED_LO, RED_HI, lab[1]));
    }

    /**
     * P378 review (the owner's flats: two power lines across the whole width cut the sky below them off - x0.84 above, x1.00 and
     * cream clouds below, a seam along the wire): w0 with every cell between sky-like cells on opposite sides (across, down or a
     * diagonal, each side within 2 cells) raised to the weaker side's weight - a line up to 2 cells thick; a cell at the sky's
     * edge has sky on one side only and keeps its own. {@code veto} cells (cores, joints) stay 0.
     */
    static float[] bridge(Cells c, float[] w0, boolean[] veto) {
        final int n = c.nx * c.ny;
        final float[] out = w0.clone();
        final int[][] axes = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int y = 0; y < c.ny; y++) {
            for (int x = 0; x < c.nx; x++) {
                final int i = y * c.nx + x;
                if (veto[i]) {
                    out[i] = 0f;
                    continue;
                }
                for (int[] a : axes) {
                    float plus = 0f, minus = 0f;
                    for (int k = 1; k <= 2; k++) {
                        plus = Math.max(plus, weight(c, w0, veto, x + k * a[0], y + k * a[1]));
                        minus = Math.max(minus, weight(c, w0, veto, x - k * a[0], y - k * a[1]));
                    }
                    out[i] = Math.max(out[i], Math.min(plus, minus));
                }
            }
        }
        return out;
    }

    private static float weight(Cells c, float[] w, boolean[] veto, int x, int y) {
        if (x < 0 || y < 0 || x >= c.nx || y >= c.ny) return 0f;
        final int i = y * c.nx + x;
        return veto[i] ? 0f : w[i];
    }

    /**
     * P378 review (a light, smooth surface joined below the sky - a white facade, snow under overcast - passed every gate and the
     * flood took it in): the flooded cells of a flat surface below a straight step down from the sky, marked in {@code veto}
     * - a region of flooded cells joined over 4-neighbours within JOINT_FLAT stops of each other, grey (OKLab chroma under
     * JOINT_GREY) or of a colour the sky's other coloured cells do not have (skyHasColour: the clear blue sky below a bank of
     * clouds is flat too; bluish snow in shade is not the sky's blue), at least JOINT_AREA of the frame (a flat grey patch of
     * cloud, 21 cells under brighter ones in tools/check_why_sky.py's sky over 60 % of the frame, stays) and along at least
     * max(JOINT_RUN_MIN, 1/8 of the top edge) cells of its top boundary (on the top edge itself: straight sides with the sky
     * brighter beside them, sideStep - a tower), whose top boundary
     * (its cells with the cell towards the top edge outside it) lies JOINT_STEP stops and more under the brightest of the 3
     * cells above, in the median along that boundary. The clouds' own flat grey parts have no straight step above them; a
     * stratus sky below a brighter band of clouds is cut off as well (the sky term then leaves it as before). Returns the cells
     * marked.
     */
    static int joints(Cells c, Frame fr, float[] w, boolean[] veto) {
        final int n = c.nx * c.ny;
        final boolean[] flat = new boolean[n];
        // OKLab a, b of each cell's colour at a fixed lightness (chroma's)
        final float[] ca = new float[n], cb = new float[n], ch = new float[n];
        for (int i = 0; i < n; i++) {
            flat[i] = w[i] >= GROW && Float.isFinite(c.l[i]);
            final float y = 0.2126f * c.r[i] + 0.7152f * c.g[i] + 0.0722f * c.b[i];
            if (!(y > 0f) || !Float.isFinite(y)) {
                ch[i] = Float.NaN;
                flat[i] = false;
                continue;
            }
            final float[] lab = oklab(c.r[i] * 0.5f / y, c.g[i] * 0.5f / y, c.b[i] * 0.5f / y);
            ca[i] = lab[1];
            cb[i] = lab[2];
            ch[i] = (float) Math.hypot(lab[1], lab[2]);
        }
        final int[] label = new int[n];
        Arrays.fill(label, -1);
        final int[] queue = new int[n];
        final int run = Math.max(JOINT_RUN_MIN, fr.us / 8), tall = Math.max(JOINT_RUN_MIN, fr.vs / 8);
        int marked = 0, comp = 0;
        // u, v of each cell (the photo's terms)
        final int[] cu = new int[n], cv = new int[n];
        for (int u = 0; u < fr.us; u++)
            for (int v = 0; v < fr.vs; v++) {
                final int i = fr.at(u, v);
                cu[i] = u;
                cv[i] = v;
            }
        for (int s0 = 0; s0 < n; s0++) {
            if (!flat[s0] || label[s0] >= 0) continue;
            int head = 0, tail = 0;
            queue[tail++] = s0;
            label[s0] = comp;
            boolean onTop = false;
            while (head < tail) {
                final int i = queue[head++];
                if (cv[i] == 0) onTop = true;
                final int[] next = {fr.at(cu[i] + 1, cv[i]), fr.at(cu[i] - 1, cv[i]), fr.at(cu[i], cv[i] + 1), fr.at(cu[i], cv[i] - 1)};
                for (int k : next) {
                    if (k < 0 || label[k] >= 0 || !flat[k] || !(Math.abs(c.l[k] - c.l[i]) <= JOINT_FLAT)) continue;
                    label[k] = comp;
                    queue[tail++] = k;
                }
            }
            final int me = comp++;
            final boolean step = onTop ? sideStep(c, fr, label, me, queue, tail, cu, cv, tall)
                    : tail >= Math.max(run, JOINT_AREA * n) && topStep(c, fr, label, me, queue, tail, cu, cv, run);
            if (!step) continue;
            // a coloured region (not grey) stays sky when the sky's own coloured cells elsewhere have its colour: the clear blue sky
            // below a bank of clouds is flat too; bluish snow in shade or a grey-blue sea has no blue sky of its colour
            double sa = 0, sb = 0;
            for (int k = 0; k < tail; k++) {
                sa += ca[queue[k]];
                sb += cb[queue[k]];
            }
            final float ra = (float) (sa / tail), rb = (float) (sb / tail);
            if (Math.hypot(ra, rb) > JOINT_GREY && skyHasColour(w, label, me, ca, cb, ch, ra, rb)) continue;
            for (int k = 0; k < tail; k++) veto[queue[k]] = true;
            marked += tail;
        }
        return marked;
    }

    /** The steps along a region's top boundary: the brightest of the 3 cells above against the boundary cell, in the median. */
    private static boolean topStep(Cells c, Frame fr, int[] label, int me, int[] queue, int tail, int[] cu, int[] cv, int run) {
        final float[] steps = new float[tail];
        int ns = 0;
        for (int k = 0; k < tail; k++) {
            final int i = queue[k];
            final int up = fr.at(cu[i], cv[i] - 1);
            if (up < 0 || label[up] == me) continue;
            float above = Float.NEGATIVE_INFINITY;
            for (int d = 1; d <= 3; d++) {
                final int j = fr.at(cu[i], cv[i] - d);
                if (j >= 0 && label[j] != me && Float.isFinite(c.l[j])) above = Math.max(above, c.l[j]);
            }
            if (above > Float.NEGATIVE_INFINITY) steps[ns++] = above - c.l[i];
        }
        return ns >= run && median(Arrays.copyOf(steps, ns), null) >= JOINT_STEP;
    }

    /**
     * P378 review (a light tower beside the sky rising to the top edge was taken in: it seeds itself from the top edge): a region on the
     * top edge whose sides run straight (each side's boundary within JOINT_SIDE_WANDER cells across at least {@code tall} rows) with
     * the sky JOINT_STEP brighter beside them (the brightest of the 3 cells beside each boundary cell, in the median). A sky on the
     * top edge has the frame's edges or the clouds' irregular edges beside it.
     */
    private static boolean sideStep(Cells c, Frame fr, int[] label, int me, int[] queue, int tail, int[] cu, int[] cv, int tall) {
        final int[] lo = new int[fr.vs], hi = new int[fr.vs];
        Arrays.fill(lo, Integer.MAX_VALUE);
        Arrays.fill(hi, Integer.MIN_VALUE);
        for (int k = 0; k < tail; k++) {
            final int i = queue[k];
            lo[cv[i]] = Math.min(lo[cv[i]], cu[i]);
            hi[cv[i]] = Math.max(hi[cv[i]], cu[i]);
        }
        final float[] steps = new float[2 * fr.vs];
        int ns = 0, rows = 0;
        double sl = 0, sl2 = 0, sh = 0, sh2 = 0;
        for (int v = 0; v < fr.vs; v++) {
            if (lo[v] == Integer.MAX_VALUE) continue;
            rows++;
            sl += lo[v];
            sl2 += (double) lo[v] * lo[v];
            sh += hi[v];
            sh2 += (double) hi[v] * hi[v];
            for (int side = -1; side <= 1; side += 2) {
                final int edge = side < 0 ? lo[v] : hi[v];
                final int own = fr.at(edge, v);
                float beside = Float.NEGATIVE_INFINITY;
                for (int d = 1; d <= 3; d++) {
                    final int j = fr.at(edge + side * d, v);
                    if (j >= 0 && label[j] != me && Float.isFinite(c.l[j])) beside = Math.max(beside, c.l[j]);
                }
                if (beside > Float.NEGATIVE_INFINITY && own >= 0) steps[ns++] = beside - c.l[own];
            }
        }
        if (rows < tall || ns < tall) return false;
        final double wanderLo = Math.sqrt(Math.max(0, sl2 / rows - (sl / rows) * (sl / rows)));
        final double wanderHi = Math.sqrt(Math.max(0, sh2 / rows - (sh / rows) * (sh / rows)));
        return wanderLo <= JOINT_SIDE_WANDER && wanderHi <= JOINT_SIDE_WANDER && median(Arrays.copyOf(steps, ns), null) >= JOINT_STEP;
    }

    /** Whether the flooded coloured cells (over JOINT_GREY) outside region {@code me} have its colour (a, b within JOINT_HUE). */
    private static boolean skyHasColour(float[] w, int[] label, int me, float[] ca, float[] cb, float[] ch, float ra, float rb) {
        int n = 0;
        final float[] a = new float[w.length], b = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            if (label[i] == me || !(w[i] >= GROW) || !(ch[i] > JOINT_GREY)) continue;
            a[n] = ca[i];
            b[n] = cb[i];
            n++;
        }
        if (n < JOINT_RUN_MIN) return false;
        return Math.abs(median(Arrays.copyOf(a, n), null) - ra) <= JOINT_HUE && Math.abs(median(Arrays.copyOf(b, n), null) - rb) <= JOINT_HUE;
    }

    /**
     * P378 review: the cores the ceiling rule misses - a sun clipped without ultrashort frames (the motion mode, «ES ✗ align») at
     * 1/500 - 1/2000 s sits within 2.5 stops of the sky and under EV 17.5, as clipped clouds do. Told apart by its shape: a compact
     * blob of clipped cells (8-connected, at most BLOB_MAX_SHARE of the frame, filling BLOB_FILL of its box, sides within
     * BLOB_ASPECT, its centre at least BLOB_SOLID clipped: fully; one round a disc up to BLOB_ROUND_SHARE) with a radial fall-off round it -
     * the level (the mean down to RING_TRIM under the median) of the rings 1, 2 and 3 cells out (cells within 2 stops under the sky: a dark roof beside it does not
     * count) falling smoothly (RING_FALL_12, RING_FALL_23, RING_SMOOTH), the first RING_OVER over the sky's mean and every side of
     * it (quadrants round the blob's centre, one may miss of four) RING_SIDE over the third ring; or a disc (DISC_CELLS: a large
     * round fully clipped core with a brighter first ring). Broad clipped clouds are too large or irregular, a clipped cloud top's surroundings are the cloud's body at a
     * cliff, a flat clipped patch's the sky's own level. The blobs found go into {@code core}; returns how many.
     */
    static int glowCores(Cells c, float skyL, boolean[] core) {
        final int n = c.nx * c.ny;
        if (!Float.isFinite(skyL)) return 0;
        final int[] label = new int[n];
        Arrays.fill(label, -1);
        final int[] queue = new int[n];
        final int[] dist = new int[n], q2 = new int[n];
        int found = 0, comp = 0;
        for (int s0 = 0; s0 < n; s0++) {
            if (label[s0] >= 0 || !(c.clip[s0] >= CLIP_SHARE)) continue;
            int head = 0, tail = 0;
            queue[tail++] = s0;
            label[s0] = comp;
            while (head < tail) {
                final int i = queue[head++];
                final int x = i % c.nx, y = i / c.nx;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= c.nx || yy >= c.ny) continue;
                        final int k = yy * c.nx + xx;
                        if (label[k] < 0 && c.clip[k] >= CLIP_SHARE) {
                            label[k] = comp;
                            queue[tail++] = k;
                        }
                    }
                }
            }
            final int area = tail;
            int x0 = c.nx, y0 = c.ny, x1 = -1, y1 = -1;
            float solid = 0f;
            double cx = 0, cy = 0;
            for (int k = 0; k < area; k++) {
                final int i = queue[k], x = i % c.nx, y = i / c.nx;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
                solid = Math.max(solid, c.clip[i]);
                cx += x + 0.5;
                cy += y + 0.5;
            }
            cx /= area;
            cy /= area;
            final int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            // its fully clipped cells and their box: a round fully clipped core (a disc) decides the blob's shape - the sun's blob takes
            // in the clouds that clip beside it (P378 review: an aureole's blob 13 x 20 cells, its core 9 x 12)
            int full = 0, fx0 = c.nx, fy0 = c.ny, fx1 = -1, fy1 = -1;
            for (int k = 0; k < area; k++) {
                final int i = queue[k];
                if (c.clip[i] < BLOB_FULL) continue;
                full++;
                fx0 = Math.min(fx0, i % c.nx);
                fy0 = Math.min(fy0, i / c.nx);
                fx1 = Math.max(fx1, i % c.nx);
                fy1 = Math.max(fy1, i / c.nx);
            }
            final int fw = fx1 - fx0 + 1, fh = fy1 - fy0 + 1;
            final boolean round = full >= DISC_CELLS && full >= DISC_FILL * fw * fh && Math.max(fw, fh) <= DISC_ASPECT * Math.min(fw, fh);
            final boolean compact = round ? area <= BLOB_ROUND_SHARE * n
                    : area <= Math.max(1f, BLOB_MAX_SHARE * n) && area >= BLOB_FILL * bw * bh
                    && Math.max(bw, bh) <= BLOB_ASPECT * Math.min(bw, bh) && solid >= BLOB_SOLID;
            comp++;
            if (!compact) continue;
            // the rings: chessboard distance from the blob, up to 3
            Arrays.fill(dist, Integer.MAX_VALUE);
            head = 0;
            int t2 = 0;
            for (int k = 0; k < area; k++) {
                dist[queue[k]] = 0;
                q2[t2++] = queue[k];
            }
            final float[][] ringL = new float[4][q2.length], sideL = new float[4][q2.length];
            final int[] ringN = new int[4], sideN = new int[4];
            while (head < t2) {
                final int i = q2[head++];
                if (dist[i] >= 3) continue;
                final int x = i % c.nx, y = i / c.nx;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= c.nx || yy >= c.ny) continue;
                        final int k = yy * c.nx + xx;
                        if (dist[k] != Integer.MAX_VALUE) continue;
                        dist[k] = dist[i] + 1;
                        q2[t2++] = k;
                        if (!Float.isFinite(c.l[k]) || c.l[k] < skyL - 2f) continue;
                        ringL[dist[k]][ringN[dist[k]]++] = c.l[k];
                        if (dist[k] == 1) {
                            final double ax = xx + 0.5 - cx, ay = yy + 0.5 - cy;
                            final int side = Math.abs(ax) >= Math.abs(ay) ? (ax >= 0 ? 0 : 1) : (ay >= 0 ? 2 : 3);
                            sideL[side][sideN[side]++] = c.l[k];
                        }
                    }
                }
            }
            if (ringN[1] == 0 || ringN[2] == 0 || ringN[3] == 0) continue;
            final double m1 = ringLevel(ringL[1], ringN[1]), m2 = ringLevel(ringL[2], ringN[2]), m3 = ringLevel(ringL[3], ringN[3]);
            // a disc: a large round fully clipped core with a brighter ring round it (its glow lost in the clouds' texture farther out;
            // P378 review: the clipped blob takes in the glow's steep part, the rings past it may fall by 0.02 stop - a flat clipped
            // patch's first ring is the sky's own level)
            final boolean disc = round && m1 - skyL >= DISC_RING;
            if (!disc && !(m1 - m2 >= RING_FALL_12 && m2 - m3 >= RING_FALL_23 && m2 - m3 >= RING_SMOOTH * (m1 - m2)
                    && m2 - m3 <= RING_EASE * (m1 - m2) && m1 - skyL >= RING_OVER)) continue;
            int sides = 0, misses = 0;
            for (int k = 0; k < 4; k++) {
                if (sideN[k] == 0) continue;
                sides++;
                if (ringLevel(sideL[k], sideN[k]) - m3 < RING_SIDE) misses++;
            }
            if (!disc && (sides == 0 || misses > (sides == 4 ? 1 : 0))) continue;
            for (int k = 0; k < area; k++) core[queue[k]] = true;
            found++;
        }
        return found;
    }

    /** The mean of the first {@code n} values of {@code v} down to RING_TRIM under their median (sorted in place). */
    static float ringLevel(float[] v, int n) {
        Arrays.sort(v, 0, n);
        final float floor = (n % 2 == 1 ? v[n / 2] : 0.5f * (v[n / 2 - 1] + v[n / 2])) - RING_TRIM;
        double sum = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (v[i] < floor) continue;
            sum += v[i];
            k++;
        }
        return (float) (sum / k);
    }

    /** {@code region} grown over 8-neighbours whose mean log2 Y is {@code floor} and up. */
    static void grow(Cells c, boolean[] region, float floor) {
        final int n = c.nx * c.ny;
        final int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) if (region[i]) queue[tail++] = i;
        while (head < tail) {
            final int i = queue[head++];
            final int x = i % c.nx, y = i / c.nx;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    final int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= c.nx || yy >= c.ny) continue;
                    final int j = yy * c.nx + xx;
                    if (!region[j] && c.l[j] >= floor) {
                        region[j] = true;
                        queue[tail++] = j;
                    }
                }
            }
        }
    }

    /** The weights connected to the top edge: seeds of SEED up on its cells, grown over 4-neighbours of GROW up; 0 elsewhere. */
    static float[] flood(Cells c, float[] w0, int top) {
        final int n = c.nx * c.ny;
        final boolean[] seen = new boolean[n];
        final int[] queue = new int[n];
        int head = 0, tail = 0;
        final int edge = top == TOP_COL0 || top == TOP_COLN ? c.ny : c.nx;
        for (int k = 0; k < edge; k++) {
            final int i;
            switch (top) {
                case TOP_COL0: i = k * c.nx; break;
                case TOP_COLN: i = k * c.nx + c.nx - 1; break;
                case TOP_ROWN: i = (c.ny - 1) * c.nx + k; break;
                default: i = k; break;
            }
            if (w0[i] >= SEED && !seen[i]) {
                seen[i] = true;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            final int i = queue[head++];
            final int x = i % c.nx, y = i / c.nx;
            final int[][] next = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
            for (int[] p : next) {
                if (p[0] < 0 || p[1] < 0 || p[0] >= c.nx || p[1] >= c.ny) continue;
                final int j = p[1] * c.nx + p[0];
                if (!seen[j] && w0[j] >= GROW) {
                    seen[j] = true;
                    queue[tail++] = j;
                }
            }
        }
        final float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = seen[i] ? w0[i] : 0f;
        return w;
    }

    private static float sum(float[] v) {
        float s = 0f;
        for (float x : v) s += x;
        return s;
    }

    private static float weightedMean(float[] v, float[] w) {
        double s = 0, sw = 0;
        for (int i = 0; i < v.length; i++) {
            if (w[i] > 0f && Float.isFinite(v[i])) {
                s += w[i] * v[i];
                sw += w[i];
            }
        }
        return sw > 0 ? (float) (s / sw) : Float.NaN;
    }

    /** The median of {@code v} (where {@code take} is true; null: all finite values), NaN when none. */
    /** The {@code q} quantile of the finite values of {@code v} where {@code take} (all when null); NaN without any. */
    static float percentile(float[] v, boolean[] take, float q) {
        final float[] t = new float[v.length];
        int k = 0;
        for (int i = 0; i < v.length; i++) if ((take == null || take[i]) && Float.isFinite(v[i])) t[k++] = v[i];
        if (k == 0) return Float.NaN;
        Arrays.sort(t, 0, k);
        return t[Math.min(k - 1, (int) Math.floor(q * (k - 1) + 0.5f))];
    }

    static float median(float[] v, boolean[] take) {
        final float[] a = new float[v.length];
        int k = 0;
        for (int i = 0; i < v.length; i++) if ((take == null || take[i]) && Float.isFinite(v[i])) a[k++] = v[i];
        if (k == 0) return Float.NaN;
        final float[] s = Arrays.copyOf(a, k);
        Arrays.sort(s);
        return (k & 1) == 1 ? s[k / 2] : 0.5f * (s[k / 2 - 1] + s[k / 2]);
    }

    static float smooth(float e0, float e1, float x) {
        final float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /** Linear sRGB -> OKLab (why/combine.glsl toOklab). */
    static float[] oklab(float r, float g, float b) {
        final double l = Math.cbrt(Math.max(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b, 0));
        final double m = Math.cbrt(Math.max(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b, 0));
        final double s = Math.cbrt(Math.max(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b, 0));
        return new float[]{(float) (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s),
                (float) (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s),
                (float) (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)};
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }
}
