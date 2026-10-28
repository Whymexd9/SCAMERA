package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

/**
 * P378 review (P301, owner, 2026-10-20: «Результат должен совпадать с итоговым фото»; the photo's sky term had no counterpart on the
 * RAW viewfinder - an overcast sky ~14 % brighter in the mid sky there, the clouds twice as chromatic): the sky term of the WHY tone
 * on the viewfinder, between LiveWhyTone's meter runs. Every meter run decides the sky on the meter's own frame
 * (WhySkyMask.Cells.ofQuads of LiveWhyMeter's quads, WhySkyMask.decide as the photo); every drawn frame eases what is shown towards
 * that decision (P383: the shown tone eases per frame in the scene's terms, no steps) - the cells' sky and clear-sky weights, the
 * local sky level and the clouds' tops in the scene's terms (log2 Y less the metered frame's log2 exposure: a move of the camera's
 * exposure is cancelled in its own frame, as the tone's), and the level share (0 in a backlit scene, 1 otherwise) - by ToneEase's
 * critically damped pair of lags. A cell coming into the sky takes its level at once (its weight eases from 0). {@link #texture}
 * puts the shown cells into the tone's orientation (rawdevelop_vs: the frame's crop position of a target pixel through the
 * rotation and the front mirror) with the drawn frame's level for why/combine SKY 1 POINT_FUSED 1 (WhySky: r the sky, g the clear
 * sky, b the local sky level). Pure Java, tested on the host.
 */
final class LiveSky {
    /** Below this the shown sky is gone (no SKY define: the tone without a sky term). */
    static final float SHOWN = 1e-3f;
    /** A shown change under this does not upload the cells again. */
    static final float SETTLED = 1e-4f;

    private int nx, ny;
    /** Per cell: the sky weight, the clear sky's, the local sky level in the scene's terms (x, lag, target each). */
    private float[] x, lag, target;
    /** The level share (0 backlit), the tops' from / to in the scene's terms. */
    private final float[] gx = new float[3], glag = new float[3], gtarget = new float[3];
    private boolean on, moved;

    /** A new context or session: nothing shown, the next decision shown at once. */
    void reset() {
        on = false;
        x = lag = target = null;
        nx = ny = 0;
        moved = true;
    }

    /**
     * A meter run's decision on a frame of log2 exposure {@code logP} (mask null: no sky - the shown sky eases out). P378 review: a
     * grid of another size (the live crop follows a pinch zoom: nx or ny changes with nearly every crop step) takes what is shown
     * resampled onto it - it was set back to no sky, a +15 % step of the sky in one frame and ~0.5 s to ease back.
     */
    void decided(WhySkyMask.Mask mask, float strength, double logP) {
        if (mask == null) {
            if (target != null) for (int i = 0; i < nx * ny; i++) {
                target[3 * i] = 0f;
                target[3 * i + 1] = 0f;
            }
            return;
        }
        final boolean fresh = x == null || !on;
        if (x == null || mask.nx != nx || mask.ny != ny) {
            final float[] oldX = x, oldLag = lag;
            final int ox = nx, oy = ny;
            nx = mask.nx;
            ny = mask.ny;
            x = new float[3 * nx * ny];
            lag = new float[3 * nx * ny];
            target = new float[3 * nx * ny];
            if (oldX != null) {
                resample(oldX, ox, oy, x, nx, ny);
                resample(oldLag, ox, oy, lag, nx, ny);
            }
        }
        final float lp = (float) logP;
        for (int i = 0; i < nx * ny; i++) {
            target[3 * i] = clamp01(mask.cells[4 * i]);
            target[3 * i + 1] = clamp01(mask.cells[4 * i + 1]);
            target[3 * i + 2] = mask.cells[4 * i + 2] - lp;
        }
        final float[] tops = mask.tops();
        gtarget[0] = strength * mask.keep;
        gtarget[1] = tops[0] - lp;
        gtarget[2] = tops[1] - lp;
        if (fresh) {
            // shown at once: the sky that comes in (from none) starts with its weights at 0, its levels its own
            for (int i = 0; i < nx * ny; i++) {
                x[3 * i] = lag[3 * i] = 0f;
                x[3 * i + 1] = lag[3 * i + 1] = 0f;
                x[3 * i + 2] = lag[3 * i + 2] = target[3 * i + 2];
            }
            System.arraycopy(gtarget, 0, gx, 0, 3);
            System.arraycopy(gtarget, 0, glag, 0, 3);
            on = true;
        }
        for (int i = 0; i < nx * ny; i++) {
            // a cell coming into the sky takes its level at once (its weight eases from 0)
            if (Math.max(x[3 * i], x[3 * i + 1]) < SHOWN && target[3 * i] > 0f) x[3 * i + 2] = lag[3 * i + 2] = target[3 * i + 2];
        }
        moved = true;
    }

    /** {@code from} (fw x fh cells of 3) onto {@code to} (tw x th) bilinear in the crop's normalised coordinates (cell centres). */
    static void resample(float[] from, int fw, int fh, float[] to, int tw, int th) {
        for (int ty = 0; ty < th; ty++) {
            final float fy = Math.max(0f, Math.min(fh - 1, (ty + 0.5f) / th * fh - 0.5f));
            final int y0 = (int) Math.floor(fy), y1 = Math.min(fh - 1, y0 + 1);
            final float ay = fy - y0;
            for (int tx = 0; tx < tw; tx++) {
                final float fx = Math.max(0f, Math.min(fw - 1, (tx + 0.5f) / tw * fw - 0.5f));
                final int x0 = (int) Math.floor(fx), x1 = Math.min(fw - 1, x0 + 1);
                final float ax = fx - x0;
                for (int c = 0; c < 3; c++) {
                    final float a = from[3 * (y0 * fw + x0) + c], b = from[3 * (y0 * fw + x1) + c];
                    final float d = from[3 * (y1 * fw + x0) + c], e = from[3 * (y1 * fw + x1) + c];
                    final float top = a + (b - a) * ax, bottom = d + (e - d) * ax;
                    to[3 * (ty * tw + tx) + c] = top + (bottom - top) * ay;
                }
            }
        }
    }

    /** One drawn frame {@code dtNs} after the last towards the decision (ToneEase's lags; dt capped as there). */
    void ease(long dtNs) {
        if (!on || target == null) return;
        final float[] f = ToneEase.factors(dtNs);
        boolean any = false;
        float change = 0f;
        for (int k = 0; k < x.length; k++) {
            final float before = x[k];
            step(x, lag, k, target[k], f);
            change = Math.max(change, Math.abs(x[k] - before));
            if (k % 3 != 2 && x[k] >= SHOWN) any = true;
        }
        for (int k = 0; k < 3; k++) {
            final float before = gx[k];
            step(gx, glag, k, gtarget[k], f);
            change = Math.max(change, Math.abs(gx[k] - before));
        }
        if (change > SETTLED) moved = true;
        if (!any) {
            // gone: the weights and their targets are all 0
            boolean targets = false;
            for (int i = 0; i < nx * ny; i++) if (target[3 * i] > 0f || target[3 * i + 1] > 0f) targets = true;
            if (!targets) on = false;
        }
    }

    /** Whether a sky is shown (the SKY define and the uniforms below). */
    boolean shown() {
        return on;
    }

    /** The level share shown (why/combine skyU.x: L2 / L3; 0 in a backlit scene). */
    float level() {
        return gx[0];
    }

    /** The clouds' tops in the log2 Y of a frame of log2 exposure {@code logP} (why/combine skyTopU). */
    float[] tops(double logP) {
        return new float[]{(float) (gx[1] + logP), (float) (gx[2] + logP)};
    }

    /** Whether the shown cells moved since {@link #texture} was last taken (or the frame's exposure did: {@code logP}). */
    boolean dirty() {
        return moved;
    }

    /**
     * The shown cells in the tone's orientation as RGBA floats (rows from the target's bottom, as the texture's), {@code size}
     * gets {width, height}: a target pixel at normalised (sx, sy) is the frame's crop position (u, v) that rawdevelop_vs draws
     * there - NDC = rotation x vertex, texCoord = ((1 + vertex.y) / 2, (1 + vertex.x) / 2), the front mirror flips v - sampled
     * bilinear from the cells, which cover {@code cover} of the crop (LiveWhyMeter.cover); b at the frame's own level ({@code logP}).
     * Null without cells. {@code into}: the array to fill when it is large enough (else a new one).
     */
    float[] texture(float[] rotation, boolean mirror, float[] cover, double logP, int[] size) {
        return texture(rotation, mirror, cover, logP, size, null);
    }

    float[] texture(float[] rotation, boolean mirror, float[] cover, double logP, int[] size, float[] into) {
        moved = false;
        if (x == null) return null;
        final float m0 = rotation[0], m1 = rotation[1], m4 = rotation[4], m5 = rotation[5], m12 = rotation[12], m13 = rotation[13];
        final float det = m0 * m5 - m4 * m1;
        if (!(Math.abs(det) > 1e-6f)) return null;
        final boolean quarter = Math.abs(m1) > Math.abs(m0);
        final int tw = quarter ? nx : ny, th = quarter ? ny : nx;
        final float cu = cover != null && cover[0] > 0f ? cover[0] : 1f, cv = cover != null && cover[1] > 0f ? cover[1] : 1f;
        final float[] out = into != null && into.length >= 4 * tw * th ? into : new float[4 * tw * th];
        final float lp = (float) logP;
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                final float px = 2f * (tx + 0.5f) / tw - 1f - m12, py = 2f * (ty + 0.5f) / th - 1f - m13;
                final float vx = (m5 * px - m4 * py) / det, vy = (-m1 * px + m0 * py) / det;
                final float u = 0.5f * (1f + vy);
                float v = 0.5f * (1f + vx);
                if (mirror) v = 1f - v;
                // cell coordinates (centres at (i + 0.5) / n of the covered part)
                final float fx = u / cu * nx - 0.5f, fy = v / cv * ny - 0.5f;
                final int o = 4 * (ty * tw + tx);
                for (int c = 0; c < 3; c++) out[o + c] = bilinear(fx, fy, c);
                out[o + 2] += lp;
                out[o + 3] = 1f;
            }
        }
        size[0] = tw;
        size[1] = th;
        return out;
    }

    /** The shown cells' channel {@code c} at cell coordinates (fx, fy), clamped at the grid's edges. */
    private float bilinear(float fx, float fy, int c) {
        fx = Math.max(0f, Math.min(nx - 1, fx));
        fy = Math.max(0f, Math.min(ny - 1, fy));
        final int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        final int x1 = Math.min(nx - 1, x0 + 1), y1 = Math.min(ny - 1, y0 + 1);
        final float ax = fx - x0, ay = fy - y0;
        final float a = x[3 * (y0 * nx + x0) + c], b = x[3 * (y0 * nx + x1) + c];
        final float d = x[3 * (y1 * nx + x0) + c], e = x[3 * (y1 * nx + x1) + c];
        return (a + (b - a) * ax) + ((d + (e - d) * ax) - (a + (b - a) * ax)) * ay;
    }

    /** ToneEase's exact step of the pair of lags over a dt with the target constant. */
    private static void step(float[] x, float[] lag, int i, float u, float[] f) {
        final float d = x[i] - u, dl = lag[i] - u;
        x[i] = u + d * f[0] + dl * f[1];
        lag[i] = u + dl * f[0];
    }

    private static float clamp01(float v) {
        return v > 0f ? Math.min(1f, v) : 0f;
    }
}
