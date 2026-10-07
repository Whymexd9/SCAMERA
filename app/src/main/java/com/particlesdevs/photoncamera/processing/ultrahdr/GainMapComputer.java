package com.particlesdevs.photoncamera.processing.ultrahdr;

import android.graphics.Bitmap;

/**
 * Normalises the GPU-encoded gain map produced by PostPipeline's
 * scene-anchored pass (pre-local-tone-map scene luma anchored to the stored
 * base's top percentile) into the final Ultra HDR gain map and derives its
 * {@code GainMapMin}/{@code GainMapMax} metadata.
 *
 * <p>The comparison shader stores, per pixel, {@code v = log2(gain) / SCALE}
 * clamped to [0,1], where gain is the ratio of anchored scene luminance to
 * linear SDR luminance (both + the decode offset). This pass optionally
 * box-filters the map down ({@code down} pixels per axis), recovers the actual
 * log-gain range present in the image and requantizes it to fill [0,255]. The
 * derived {@code GainMapMin}/{@code GainMapMax} (in log2 units) go into the
 * hdrgm XMP metadata so any ISO 21496-1 decoder can reconstruct the HDR
 * rendition from the SDR base for any display headroom.
 */
public final class GainMapComputer {

    /**
     * Total log2 range covered by the encoding (6 stops = 64x boost). Must
     * equal uScale passed to ultrahdr/gainmap.glsl.
     */
    public static final float SCALE = 6.0f;
    /**
     * OffsetSDR/OffsetHDR of the hdrgm XMP metadata (1/64). The comparison
     * shader adds it to both luminance sides before taking the ratio so the
     * ISO 21496-1 decode reproduces the HDR rendition exactly.
     */
    public static final float DECODE_OFFSET = 1.0f / 64.0f;
    /** Downsample factor per axis (gain map is 1/SCALE_DOWN^2 the pixels). */
    public static final int SCALE_DOWN = 1;
    // Pad so extreme pixels don't sit exactly on the metadata endpoints.
    private static final float RANGE_PAD_FRACTION = 0.02f;
    private static final float MIN_RANGE = 1e-3f;

    private GainMapComputer() {}

    public static class Result {
        /** Single-channel gain map, packed as R=G=B=value, A=255 (ARGB_8888). */
        public final Bitmap gainMap;
        public final float gainMapMin;
        public final float gainMapMax;
        public final float hdrCapacityMax;
        public final int gainW;
        public final int gainH;

        Result(Bitmap gainMap, float gainMapMin, float gainMapMax) {
            this.gainMap = gainMap;
            this.gainMapMin = gainMapMin;
            this.gainMapMax = gainMapMax;
            // HDRCapacityMax must be greater than HDRCapacityMin (0); otherwise
            // the viewer's headroom weight divides by zero/negative and every
            // viewer clamps it differently.
            this.hdrCapacityMax = Math.max(gainMapMax, 1e-3f);
            this.gainW = gainMap.getWidth();
            this.gainH = gainMap.getHeight();
        }
    }

    /**
     * @param src   encoded gain map straight from the GPU (R=G=B in [0,1])
     * @param down  box-filter factor per axis; 1 keeps full resolution. Averaging
     *              happens in log domain, i.e. a geometric mean of gains.
     * @param scale total log2 range used at encode time (== {@link #SCALE})
     */
    public static Result compute(Bitmap src, int down, float scale) {
        final int sw = src.getWidth();
        final int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) {
            throw new IllegalArgumentException("Empty gain map: " + sw + "x" + sh);
        }
        down = Math.max(1, down);
        final int gw = Math.max(1, sw / down);
        final int gh = Math.max(1, sh / down);

        // Two passes over one band of rows at a time: the range first, then the requantised map. A whole-frame int[] and
        // float[] copy (the previous code) is ~400 MB of Java heap for a 50 MP map, which failed the encode with an
        // OutOfMemoryError and silently left the photo SDR.
        final int[] band = new int[sw * down];
        final float[] row = new float[gw];
        float maxBoost = Float.NEGATIVE_INFINITY;
        for (int gy = 0; gy < gh; gy++) {
            boostRow(src, band, row, gy, down, sw, sh, gw, scale);
            for (float lb : row) if (lb > maxBoost) maxBoost = lb;
        }

        // Gains are non-negative by construction (headroom >= 1), so the
        // metadata range is anchored at exactly 0: identity gain for everything
        // below white, matching the midtone-exact behaviour of the shader.
        if (!Float.isFinite(maxBoost) || maxBoost < 0f) maxBoost = 0f;
        if (maxBoost < MIN_RANGE) maxBoost = MIN_RANGE;

        final float gMin = 0f;
        final float gMax = maxBoost + Math.max(maxBoost * RANGE_PAD_FRACTION, MIN_RANGE);
        final float range = gMax - gMin;

        final Bitmap out = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
        final int[] pixels = new int[gw];
        for (int gy = 0; gy < gh; gy++) {
            boostRow(src, band, row, gy, down, sw, sh, gw, scale);
            for (int gx = 0; gx < gw; gx++) {
                float vNorm = (row[gx] - gMin) / range;
                if (vNorm < 0f) vNorm = 0f;
                else if (vNorm > 1f) vNorm = 1f;
                final int byteVal = Math.round(vNorm * 255.0f);
                pixels[gx] = (0xFF << 24) | (byteVal << 16) | (byteVal << 8) | byteVal;
            }
            out.setPixels(pixels, 0, gw, 0, gy, gw, 1);
        }

        return new Result(out, gMin, gMax);
    }

    /**
     * Log2 boost of gain-map row {@code gy}: decodes v -> logBoost over the fixed encode range, box-averaging
     * {@code down} x {@code down} source pixels in the log domain (geometric mean of the gains - robust to outliers).
     */
    private static void boostRow(Bitmap src, int[] band, float[] out, int gy, int down, int sw, int sh, int gw, float scale) {
        final int y0 = gy * down;
        final int rows = Math.min(down, sh - y0);
        src.getPixels(band, 0, sw, 0, y0, sw, rows);
        for (int gx = 0; gx < gw; gx++) {
            final int x0 = gx * down;
            final int x1 = Math.min(x0 + down, sw);
            float sum = 0f;
            int count = 0;
            for (int y = 0; y < rows; y++) {
                final int base = y * sw;
                for (int x = x0; x < x1; x++) {
                    sum += (band[base + x] >> 16) & 0xFF;
                    count++;
                }
            }
            out[gx] = (sum / (count * 255f)) * scale;
        }
    }
}
