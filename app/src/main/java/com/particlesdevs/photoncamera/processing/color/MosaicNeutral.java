package com.particlesdevs.photoncamera.processing.color;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * P121 white balance of an ISZ stream without remosaic (owner, 2026-10-13: «при использовании ISZ без ремозаика баланс белого не
 * работает нормально на всех смартфонах ... сделать его аналогичным тому как он работает без ISZ»).
 * <p>
 * The merge keeps the colour of a Quad / Tetra mosaic exactly, but the neutral point the camera reports for such a stream is not
 * the scene's: the ISP takes the mosaic for plain Bayer, where every 2x2 phase holds about the same mix of R, G and B, and its
 * white balance then sees a grey scene. Here the neutral of a mosaic shot is measured on the frame itself (each colour block
 * averaged, shades of grey p = 4 over the cells neither clipped nor dark) and then put on the camera's own illuminant curve: the
 * neutral points the same physical camera reported in plain-Bayer sessions, learned from the viewfinder (as without ISZ).
 * Without a learned curve the measured neutral is used as it is, within a sane range.
 */
public final class MosaicNeutral {
    private MosaicNeutral() {}

    static final int MAX_POINTS = 48;
    static final float POINT_SPACING = 0.03f; // ln units between learned points
    private static final String PREFS = "mosaic_awb_curve";
    private static Context context;
    private static final java.util.Map<String, ArrayList<float[]>> CURVES = new java.util.HashMap<>();

    public static synchronized void init(Context c) {
        if (context == null && c != null) context = c.getApplicationContext();
    }

    /** A plain-Bayer viewfinder neutral (r, 1, b) of camera {@code id}: a point of its illuminant curve. */
    public static synchronized void learn(String id, float r, float b) {
        if (id == null || id.isEmpty() || !(r > 0.05f && r < 20f && b > 0.05f && b < 20f)) return;
        final ArrayList<float[]> pts = curve(id);
        final float lr = (float) Math.log(r), lb = (float) Math.log(b);
        for (float[] p : pts) if (Math.hypot(p[0] - lr, p[1] - lb) < POINT_SPACING) return;
        pts.add(new float[]{lr, lb});
        if (pts.size() > MAX_POINTS) pts.remove(0);
        save(id, pts);
    }

    /** Points learned for camera {@code id} (ln r, ln b), oldest first. */
    static synchronized ArrayList<float[]> curve(String id) {
        ArrayList<float[]> pts = CURVES.get(id);
        if (pts != null) return pts;
        pts = new ArrayList<>();
        if (context != null) {
            final String s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, "");
            for (String p : s.split(";")) {
                final String[] v = p.split(",");
                if (v.length != 2) continue;
                try { pts.add(new float[]{Float.parseFloat(v[0]), Float.parseFloat(v[1])}); } catch (NumberFormatException ignored) {}
            }
        }
        CURVES.put(id, pts);
        return pts;
    }

    private static void save(String id, ArrayList<float[]> pts) {
        if (context == null) return;
        final StringBuilder b = new StringBuilder();
        for (float[] p : pts) b.append(b.length() == 0 ? "" : ";").append(String.format(Locale.ROOT, "%.4f,%.4f", p[0], p[1]));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, b.toString()).apply();
    }

    /** The result: the neutral (r, 1, b), how it was found (log line) and (P212e) the cells it was measured on. */
    public static final class Result {
        public final float[] neutral;
        public final String how;
        public final int cells;
        Result(float[] neutral, String how, int cells) { this.neutral = neutral; this.how = how; this.cells = cells; }
    }

    /**
     * P212e (W3): the middle of camera {@code id}'s learned curve {r, 1, b} (its points ordered by colour temperature, ln b - ln r):
     * the OPPO native white balance's last prior; null with fewer than 2 points (or before {@link #init}: the curve is not read
     * then, so an empty one is never cached for the process).
     */
    public static synchronized float[] middle(String id) {
        if (id == null || id.isEmpty() || context == null && !CURVES.containsKey(id)) return null;
        final ArrayList<float[]> s = new ArrayList<>(curve(id));
        if (s.size() < 2) return null;
        s.sort((a, c) -> Float.compare(a[1] - a[0], c[1] - c[0]));
        final float[] m = s.get(s.size() / 2);
        return new float[]{(float) Math.exp(m[0]), 1f, (float) Math.exp(m[1])};
    }

    /**
     * The neutral of a mosaic frame: 16-bit samples (little endian, {@code stride} bytes a row), colour blocks of {@code block}
     * sites in the 2x2 order {@code cfa} (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR), black per phase, white; null when too few cells count.
     */
    public static Result estimate(String id, ByteBuffer raw, int width, int height, int stride, int cfa, float[] black, float white,
                                  int block) {
        final float[] m = measure(raw, width, height, stride, cfa, black, white, block);
        if (m == null) return null;
        final float lr = (float) Math.log(m[0]), lb = (float) Math.log(m[1]);
        final ArrayList<float[]> pts;
        synchronized (MosaicNeutral.class) { pts = new ArrayList<>(curve(id)); }
        if (pts.size() < 2) {
            final float r = clamp(m[0]), b = clamp(m[1]);
            return new Result(new float[]{r, 1f, b}, String.format(Locale.ROOT, "measured %.3f / %.3f (no curve of camera %s yet)", r, b, id),
                    (int) m[2]);
        }
        final float[] q = project(pts, lr, lb);
        return new Result(new float[]{(float) Math.exp(q[0]), 1f, (float) Math.exp(q[1])},
                String.format(Locale.ROOT, "measured %.3f / %.3f, on the curve of camera %s (%d points, %.3f away) %.3f / %.3f",
                        m[0], m[1], id, pts.size(), q[2], Math.exp(q[0]), Math.exp(q[1])), (int) m[2]);
    }

    private static float clamp(float v) { return Math.max(0.15f, Math.min(6f, v)); }

    /**
     * The nearest point of the curve: the learned points ordered by colour temperature (ln b - ln r), joined into a polyline;
     * {lr, lb, distance}.
     */
    static float[] project(ArrayList<float[]> pts, float lr, float lb) {
        final ArrayList<float[]> s = new ArrayList<>(pts);
        s.sort((a, c) -> Float.compare(a[1] - a[0], c[1] - c[0]));
        float bestD = Float.MAX_VALUE, br = s.get(0)[0], bb = s.get(0)[1];
        for (int i = 0; i + 1 < s.size(); i++) {
            final float[] a = s.get(i), c = s.get(i + 1);
            final float dx = c[0] - a[0], dy = c[1] - a[1], len2 = dx * dx + dy * dy;
            float t = len2 > 0 ? ((lr - a[0]) * dx + (lb - a[1]) * dy) / len2 : 0f;
            t = Math.max(0f, Math.min(1f, t));
            final float px = a[0] + t * dx, py = a[1] + t * dy;
            final float d = (float) Math.hypot(lr - px, lb - py);
            if (d < bestD) { bestD = d; br = px; bb = py; }
        }
        return new float[]{br, bb, bestD};
    }

    /** Shades of grey (p = 4) over the colour cells of the frame: {R / G, B / G, cells} (P212e: the cells too), null with fewer than 64. */
    static float[] measure(ByteBuffer raw, int width, int height, int stride, int cfa, float[] black, float white, int block) {
        if (raw == null || block < 1 || width < 4 * block || height < 4 * block || cfa < 0 || cfa > 3) return null;
        final ShortBuffer px = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        final int rowShorts = stride / 2, cell = 2 * block;
        final int cellsX = width / cell, cellsY = height / cell;
        final int step = Math.max(1, Math.max(cellsX, cellsY) / 256);
        // canonical position of red / blue in the 2x2 colour order
        final int redPhase = cfa == 0 ? 0 : cfa == 1 ? 1 : cfa == 2 ? 2 : 3, bluePhase = 3 - redPhase;
        final float range = Math.max(1f, white - (black[0] + black[1] + black[2] + black[3]) / 4f);
        double sr = 0, sg = 0, sb = 0;
        int n = 0;
        final float[] c = new float[4];
        for (int cy = 0; cy < cellsY; cy += step) {
            for (int cx = 0; cx < cellsX; cx += step) {
                boolean bad = false;
                for (int ph = 0; ph < 4; ph++) {
                    final int x0 = cx * cell + (ph & 1) * block, y0 = cy * cell + (ph >> 1) * block;
                    double sum = 0;
                    for (int y = 0; y < block; y++) {
                        final int row = (y0 + y) * rowShorts;
                        for (int x = 0; x < block; x++) {
                            final int i = row + x0 + x;
                            if (i >= px.limit()) { bad = true; break; }
                            sum += px.get(i) & 0xFFFF;
                        }
                    }
                    final float v = (float) (sum / (block * block) - black[ph]) / range;
                    if (v > 0.9f) bad = true;
                    c[ph] = v;
                }
                if (bad) continue;
                final float r = c[redPhase], b = c[bluePhase], g = 0.5f * (c[(redPhase ^ 1)] + c[(redPhase ^ 2)]);
                if (g < 0.02f || r <= 0f || b <= 0f) continue;
                sr += Math.pow(r, 4); sg += Math.pow(g, 4); sb += Math.pow(b, 4);
                n++;
            }
        }
        if (n < 64 || sg <= 0) return null;
        final double g = Math.pow(sg / n, 0.25);
        return new float[]{(float) (Math.pow(sr / n, 0.25) / g), (float) (Math.pow(sb / n, 0.25) / g), n};
    }
}
