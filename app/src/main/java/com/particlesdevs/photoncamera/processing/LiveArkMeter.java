package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ArkAe;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * P314 (owner, 2026-10-21: «Исправь живой видоискатель», the reference viewfinder «обработку он никак не замедляет»): the auto
 * exposure of the photo's ARK tone on the RAW viewfinder, on the CPU straight from the RAW frame the GL thread holds, as the
 * reference viewfinder meters its RAW. Before, every 4th frame drew ark/low at factor 8 of the viewfinder's linear frame and read
 * it back with a synchronous glReadPixels (15-21 ms the GL thread stood still, the GPU drained under the merge).
 *
 * <p>The cells are the viewfinder's own linear values (rawdevelop_fs u_linear_out: black and white level, white balance with its
 * highlight fade, the lens-shading map, the colour transform with the look's white matrix) of ~CELLS x 3/4 CELLS cells over the
 * visible crop, each the mean of QUADS x QUADS colour quads at its centre (the shader's one colour per 2 x 2 block of macro pixels),
 * times inScale as ark/low; ArkAe.smartHdr reads them as it read the factor-8 arkLow (the same Settings and noise). Pure Java, no
 * GL: unit-tested against that arkLow path on synthetic frames. The meter reads a 4096 x 3072 frame's ~12 k cells (~200 k samples)
 * in about a millisecond.
 */
public final class LiveArkMeter {
    /** The last cells, reused (~150 KB at every AE run otherwise). */
    private float[] buffer;

    /** Cells along the visible crop's longer side. */
    public static final int CELLS = 128;
    /** Colour quads per cell side: a cell is the mean of QUADS x QUADS quads at its centre. */
    public static final int QUADS = 2;

    /**
     * The frame the RAW viewfinder drew: its visible crop {@code left, top, width, height} (frame pixels, whole CFA periods,
     * fitted to the viewport's aspect) and the colour transform it drew with (column-major, rawdevelop_fs color_transform).
     */
    public static final class View {
        public final LiveRawFrame.Frame frame;
        public final int left, top, width, height;
        public final float[] transform;

        public View(LiveRawFrame.Frame frame, int left, int top, int width, int height, float[] transform) {
            this.frame = frame;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.transform = transform.clone();
        }
    }

    /** {cells across, cells down, quads per cell pitch, quads per cell side}; null when the crop holds no colour quad. */
    static int[] grid(View v) {
        final int q = 2 * Math.max(1, v.frame.cfaBlock);
        final int qw = quads(v.width, v.left, v.frame.width, q), qh = quads(v.height, v.top, v.frame.height, q);
        if (qw < 1 || qh < 1) return null;
        final int pitch = Math.max(QUADS, (Math.max(qw, qh) + CELLS - 1) / CELLS);
        final int k = Math.min(QUADS, Math.min(qw, qh));
        return new int[]{Math.max(1, qw / pitch), Math.max(1, qh / pitch), pitch, k};
    }

    /** Whole colour quads (q frame pixels) of a crop side of {@code size} from {@code from}, within the frame's {@code total}. */
    private static int quads(int size, int from, int total, int q) {
        return Math.max(0, Math.min(size, total - from)) / q;
    }

    private static float smoothstep(float e0, float e1, float x) {
        final float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /**
     * The cells as nx * ny RGB floats, rows in order (linear Rec.709 x {@code inScale}); {@code size} gets {nx, ny}. Null when
     * the crop holds no colour quad.
     */
    public static float[] cells(View v, float inScale, int[] size) {
        return cells(v, inScale, size, null);
    }

    /** {@link #cells(View, float, int[])} into {@code into} when it is large enough (else a new array). */
    public static float[] cells(View v, float inScale, int[] size, float[] into) {
        final int[] g = grid(v);
        if (g == null) return null;
        final LiveRawFrame.Frame f = v.frame;
        final int nx = g[0], ny = g[1], pitch = g[2], k = g[3];
        final int b = Math.max(1, f.cfaBlock), q = 2 * b, rowPx = f.rowStride / 2;
        final int qw = quads(v.width, v.left, f.width, q), qh = quads(v.height, v.top, f.height, q);
        final ShortBuffer raw = f.buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        final int[][] orders = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 3, 0, 1}, {3, 2, 1, 0}};
        final int[] o = orders[Math.max(0, Math.min(3, f.cfaPattern))];
        final float[] black = f.blackLevel, gains = f.wbGains, t = v.transform;
        final float white = f.whiteLevel;
        final boolean lsc = f.shadingWidth > 1 && f.shading != null && f.shading.length >= f.shadingWidth * f.shadingHeight * 3;
        final float scale = inScale > 0f ? inScale : 1f;
        final float[] out = into != null && into.length >= nx * ny * 3 ? into : new float[nx * ny * 3];
        final float[] site = new float[4], gain = new float[3];
        for (int cy = 0; cy < ny; cy++) {
            final int qy0 = Math.min(cy * pitch + (pitch - k) / 2, qh - k);
            for (int cx = 0; cx < nx; cx++) {
                final int qx0 = Math.min(cx * pitch + (pitch - k) / 2, qw - k);
                float sr = 0f, sg = 0f, sb = 0f;
                for (int j = 0; j < k; j++) {
                    for (int i = 0; i < k; i++) {
                        final int x0 = v.left + (qx0 + i) * q, y0 = v.top + (qy0 + j) * q;
                        // the quad's four macro pixels (b x b sites each), normalised by their own black level
                        for (int p = 0; p < 4; p++) {
                            final int mx = x0 + (p & 1) * b, my = y0 + (p >> 1) * b;
                            int sum = 0;
                            for (int yy = 0; yy < b; yy++) {
                                final int row = (my + yy) * rowPx + mx;
                                for (int xx = 0; xx < b; xx++) sum += raw.get(row + xx) & 0xFFFF;
                            }
                            site[p] = Math.max(0f, (sum / (float) (b * b) - black[p]) / Math.max(1f, white - black[p]));
                        }
                        final float rr = site[o[0]], gg = (site[o[1]] + site[o[2]]) * 0.5f, bb = site[o[3]];
                        // apply_wb_and_hl_clip
                        float cr = rr * gains[0], cg = gg * gains[1], cb = bb * gains[2];
                        final float rawMax = Math.max(rr, Math.max(gg, bb));
                        if (rawMax > 0.85f) {
                            final float fade = smoothstep(0.85f, 0.98f, rawMax), m = Math.max(cr, Math.max(cg, cb));
                            cr += (m - cr) * fade;
                            cg += (m - cg) * fade;
                            cb += (m - cb) * fade;
                        }
                        if (lsc) {
                            shading(f, (x0 + b) / (float) f.width, (y0 + b) / (float) f.height, gain);
                            cr *= gain[0];
                            cg *= gain[1];
                            cb *= gain[2];
                        }
                        // color_transform (column-major), then max(0) as the linear output
                        sr += Math.max(0f, t[0] * cr + t[3] * cg + t[6] * cb);
                        sg += Math.max(0f, t[1] * cr + t[4] * cg + t[7] * cb);
                        sb += Math.max(0f, t[2] * cr + t[5] * cg + t[8] * cb);
                    }
                }
                final int at = (cy * nx + cx) * 3;
                final float norm = scale / (k * k);
                out[at] = sr * norm;
                out[at + 1] = sg * norm;
                out[at + 2] = sb * norm;
            }
        }
        if (size != null && size.length >= 2) {
            size[0] = nx;
            size[1] = ny;
        }
        return out;
    }

    /** The lens-shading map at (u, v) of the frame as GL_LINEAR samples it (texel centres, clamped at the edges). */
    static void shading(LiveRawFrame.Frame f, float u, float v, float[] out) {
        final int sw = f.shadingWidth, sh = f.shadingHeight;
        final float tx = u * sw - 0.5f, ty = v * sh - 0.5f;
        final int x0 = (int) Math.floor(tx), y0 = (int) Math.floor(ty);
        final float fx = tx - x0, fy = ty - y0;
        final int xa = Math.max(0, Math.min(sw - 1, x0)), xb = Math.max(0, Math.min(sw - 1, x0 + 1));
        final int ya = Math.max(0, Math.min(sh - 1, y0)), yb = Math.max(0, Math.min(sh - 1, y0 + 1));
        final float[] s = f.shading;
        for (int c = 0; c < 3; c++) {
            final float top = s[(ya * sw + xa) * 3 + c] + (s[(ya * sw + xb) * 3 + c] - s[(ya * sw + xa) * 3 + c]) * fx;
            final float bottom = s[(yb * sw + xa) * 3 + c] + (s[(yb * sw + xb) * 3 + c] - s[(yb * sw + xa) * 3 + c]) * fx;
            out[c] = top + (bottom - top) * fy;
        }
    }

    /** ArkAe.smartHdr on the cells of the view; null when the crop holds no colour quad. One thread at a time (the GL thread). */
    public ArkAe.Result measure(View v, float inScale, ArkAe.Settings s, int iso, int maxIso) {
        final int[] n = new int[2];
        final float[] c = cells(v, inScale, n, buffer);
        if (c == null) return null;
        buffer = c;
        final int rowLen = n[0] * 3;
        return ArkAe.smartHdr((y, row) -> System.arraycopy(c, y * rowLen, row, 0, rowLen), 3, n[0], n[1], s, iso, maxIso);
    }
}
