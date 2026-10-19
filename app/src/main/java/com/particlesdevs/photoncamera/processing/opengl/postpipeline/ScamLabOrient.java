package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

/**
 * P277: how a lab shot's photo lies over its sensor frame - {@code turns} quarter turns clockwise, then a left-right mirror -
 * found from the pictures themselves (the worker RGB before the post pipeline and the pipeline's output), so the lab's
 * fragment picked on the photo and the merge's frame map land where they are on the photo whatever the camera's rotation.
 * Code = turns + 4 * mirror (0..7). Coordinates are normalised (0..1, x right, y down).
 */
public final class ScamLabOrient {
    private ScamLabOrient() {}

    /** Photo (x, y) of the sensor's (u, v). */
    public static float[] toPhoto(int code, float u, float v) {
        float x, y;
        switch (code & 3) {
            case 1: x = 1 - v; y = u; break;
            case 2: x = 1 - u; y = 1 - v; break;
            case 3: x = v; y = 1 - u; break;
            default: x = u; y = v;
        }
        if ((code & 4) != 0) x = 1 - x;
        return new float[]{x, y};
    }

    /** Sensor (u, v) of the photo's (x, y). */
    public static float[] toSensor(int code, float x, float y) {
        if ((code & 4) != 0) x = 1 - x;
        switch (code & 3) {
            case 1: return new float[]{y, 1 - x};
            case 2: return new float[]{1 - x, 1 - y};
            case 3: return new float[]{1 - y, x};
            default: return new float[]{x, y};
        }
    }

    /** Whether the photo's sides are the sensor's swapped. */
    public static boolean swaps(int code) {
        return (code & 1) != 0;
    }

    /**
     * The code under which {@code photo} (pw x ph, row-major, any monotone tone of the scene) is {@code sensor} (sw x sh) turned:
     * the best Pearson correlation over the codes whose aspect fits; 0 when nothing correlates.
     */
    public static int find(float[] sensor, int sw, int sh, float[] photo, int pw, int ph) {
        int best = 0;
        double bestScore = 0.2;
        final float sensorAspect = (float) sw / sh, photoAspect = (float) pw / ph;
        for (int code = 0; code < 8; code++) {
            final float aspect = swaps(code) ? 1f / sensorAspect : sensorAspect;
            if (Math.abs(Math.log(aspect / photoAspect)) > 0.25) continue;
            double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
            final int n = pw * ph;
            for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
                final float[] uv = toSensor(code, (x + .5f) / pw, (y + .5f) / ph);
                final int su = Math.min(sw - 1, (int) (uv[0] * sw)), sv = Math.min(sh - 1, (int) (uv[1] * sh));
                final double a = sensor[sv * sw + su], b = photo[y * pw + x];
                sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b;
            }
            final double va = saa - sa * sa / n, vb = sbb - sb * sb / n;
            if (va <= 0 || vb <= 0) continue;
            final double r = (sab - sa * sb / n) / Math.sqrt(va * vb);
            if (r > bestScore) { bestScore = r; best = code; }
        }
        return best;
    }

    /** The sensor rectangle {x, y, w, h} (pixels of a w x h frame) under the photo's normalised rectangle {x0, y0, x1, y1}. */
    public static int[] sensorRect(int code, float[] photoRect, int w, int h) {
        float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
        for (int i = 0; i < 4; i++) {
            final float[] uv = toSensor(code, i % 2 == 0 ? photoRect[0] : photoRect[2], i < 2 ? photoRect[1] : photoRect[3]);
            u0 = Math.min(u0, uv[0]); u1 = Math.max(u1, uv[0]);
            v0 = Math.min(v0, uv[1]); v1 = Math.max(v1, uv[1]);
        }
        return new int[]{Math.round(u0 * w), Math.round(v0 * h), Math.round((u1 - u0) * w), Math.round((v1 - v0) * h)};
    }

    /** The photo's normalised rectangle {x0, y0, x1, y1} over the sensor rectangle {x, y, w, h} of a w x h frame. */
    public static float[] photoRect(int code, int[] r, int w, int h) {
        float x0 = 1, y0 = 1, x1 = 0, y1 = 0;
        for (int i = 0; i < 4; i++) {
            final float[] xy = toPhoto(code, (r[0] + (i % 2 == 0 ? 0 : r[2])) / (float) w, (r[1] + (i < 2 ? 0 : r[3])) / (float) h);
            x0 = Math.min(x0, xy[0]); x1 = Math.max(x1, xy[0]);
            y0 = Math.min(y0, xy[1]); y1 = Math.max(y1, xy[1]);
        }
        return new float[]{x0, y0, x1, y1};
    }
}
