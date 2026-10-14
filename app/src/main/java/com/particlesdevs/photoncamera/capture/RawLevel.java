package com.particlesdevs.photoncamera.capture;

import android.graphics.ImageFormat;
import android.media.Image;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * P110 The mean level of the ring's RAW frames (0..1 above black, linear), for the «Движение» shutter ceiling
 * (manual/MotionShutter): the same signal in every viewfinder mode (the viewfinder's own meter runs only on the ISP
 * viewfinder). A sparse grid of 2x2 sites; only plain 16-bit RAW_SENSOR, only while the mode is on. Each level carries its
 * frame's sensor timestamp, so the controller pairs it with that frame's own exposure.
 */
public final class RawLevel {
    /** One frame's level and its sensor timestamp (Image.getTimestamp = CaptureResult.SENSOR_TIMESTAMP). */
    public static final class Sample {
        public final double level;
        public final long timestampNs;

        Sample(double level, long timestampNs) {
            this.level = level;
            this.timestampNs = timestampNs;
        }
    }

    private static volatile Sample latest;

    private RawLevel() {}

    /** The newest level, null when none. */
    public static Sample sample() { return latest; }

    public static void clear() {
        latest = null;
        MotionMeter.clear();
    }

    /** One ring frame (camera's RAW reader thread). {@code plain}: the payload check found a plain 16-bit frame. */
    static void observe(Image img, boolean plain, float black, float white) {
        if (img == null || !plain || img.getFormat() != ImageFormat.RAW_SENSOR) return;
        try {
            Image.Plane p = img.getPlanes()[0];
            if (p.getPixelStride() != 2) return;
            ShortBuffer s = p.getBuffer().duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
            double m = mean(s, img.getWidth(), img.getHeight(), p.getRowStride() / 2, black, white);
            if (!Double.isNaN(m)) latest = new Sample(m, img.getTimestamp());
            MotionMeter.observe(s, img.getWidth(), img.getHeight(), p.getRowStride() / 2, black, img.getTimestamp()); // P111
        } catch (RuntimeException ignored) {
            // a frame closed under us or an odd layout: no level from it
        }
    }

    /** Mean of a 40 x 30 grid of 2x2 sites, each value clipped at white, as (v - black) / (white - black). */
    static double mean(ShortBuffer s, int width, int height, int rowPixels, float black, float white) {
        if (width < 4 || height < 4 || rowPixels < width || !(white > black)) return Double.NaN;
        final int gx = 40, gy = 30;
        final double range = white - black;
        double sum = 0;
        int n = 0;
        for (int j = 0; j < gy; j++) {
            int y = ((height - 2) * (2 * j + 1) / (2 * gy)) & ~1;
            for (int i = 0; i < gx; i++) {
                int x = ((width - 2) * (2 * i + 1) / (2 * gx)) & ~1;
                for (int dy = 0; dy < 2; dy++) {
                    int row = (y + dy) * rowPixels;
                    for (int dx = 0; dx < 2; dx++) {
                        int idx = row + x + dx;
                        if (idx >= s.limit()) return Double.NaN;
                        double v = Math.min(white, s.get(idx) & 0xFFFF);
                        sum += Math.max(0, v - black) / range;
                        n++;
                    }
                }
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
