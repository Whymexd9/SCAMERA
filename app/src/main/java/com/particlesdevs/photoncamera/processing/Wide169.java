package com.particlesdevs.photoncamera.processing;

/**
 * «16:9» (P118): the one centred crop of a landscape RAW frame, for the ZSL ring (CaptureController) and the frames taken after
 * the press (SaverImplementation) alike, so every frame of a shot has one size (before, the frames after the press kept an odd
 * row count the merge dropped). Rows = width * 9 / 16 and the first row on a multiple of 8, so whole Bayer / Quad / Tetra colour
 * blocks stay in place. A portrait frame, or one already as wide, stays whole.
 */
public final class Wide169 {
    private Wide169() {}

    /** Rows of the crop of a width x height frame. */
    public static int rows(int width, int height) {
        if (width <= height) return height;
        final int rows = (width * 9 / 16) & ~7;
        return rows > 0 && rows < height ? rows : height;
    }

    /** First row of the crop. */
    public static int firstRow(int width, int height) {
        return ((height - rows(width, height)) / 2) & ~7;
    }

    /**
     * The lens shading map of the whole frame (cols x rows points, 4 gains each) cut to the crop's rows [firstRow, firstRow +
     * cropRows) of a frame fullHeight rows tall, at the same number of points (linear between the map's rows). The photo's
     * shaders and the DNG spread a map over the image they get: the full-frame map squeezed onto the crop brightened the top and
     * bottom of a 16:9 photo with the corrections of the sensor's edges.
     */
    public static float[] cropGainMap(float[] map, int cols, int rows, int fullHeight, int firstRow, int cropRows) {
        if (map == null || rows < 2 || cols < 1 || map.length < cols * rows * 4 || fullHeight <= 1 || cropRows >= fullHeight)
            return map;
        final float[] out = new float[cols * rows * 4];
        for (int j = 0; j < rows; j++) {
            // the crop's row j / (rows - 1), as a position on the full frame's map
            final double y = (firstRow + (double) j / (rows - 1) * (cropRows - 1)) / (fullHeight - 1) * (rows - 1);
            final int y0 = Math.max(0, Math.min(rows - 1, (int) Math.floor(y)));
            final int y1 = Math.min(rows - 1, y0 + 1);
            final float t = (float) Math.max(0, Math.min(1, y - y0));
            for (int i = 0; i < cols * 4; i++)
                out[j * cols * 4 + i] = map[y0 * cols * 4 + i] * (1 - t) + map[y1 * cols * 4 + i] * t;
        }
        return out;
    }
}
