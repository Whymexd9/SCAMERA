package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.util.ShotArena;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.List;

/**
 * P162 (owner, 2026-10-16, a photo of the X200 Pro Mini with black stripes on its left and bottom: «сделай автоматическую
 * обрезку для него и для X200 Pro. Также добавь соответствующие настройки»): the MediaTek vivo X200 Pro / Pro Mini deliver
 * their 4096x3072 RAW_SENSOR with the image in 4000x3000 and the rest of the buffer exact zeros (96 columns on the right, 72
 * rows at the bottom of the sensor; on an upright photo the left and the bottom). The frames of a shot are cut to the image
 * before anything reads them, so the merge, the statistics, the tone and the DNG never see the padding.
 * <p>
 * Automatic: whole rows / columns of exact zeros at the frame's edges, only on a RAW with a black pedestal (a real sample sits
 * around its black level, never at 0; without a pedestal a dark frame can hold zeros). Manual: margins on each side of the
 * upright photo («Обрезка кадра»), turned to the sensor's sides by its mounting. Each side loses the larger of the two.
 * The kept area starts on whole colour periods ({@link #PERIOD}: Bayer, Quad and Tetra keep their order) and has even sides.
 */
public final class RawCrop {
    /** The kept area's origin is a multiple of this (the colour period of a Tetra mosaic; Bayer and Quad divide it). */
    public static final int PERIOD = 8;
    /** Largest manual margin of a side (sensor px). */
    public static final int MAX_MARGIN = 512;
    /** A zero band wider than this share of its side is no padding (a broken frame): that side is not cut. */
    static final int MAX_BAND_DIVISOR = 8;
    /** Least black level (DN) at which exact zeros can only be padding. */
    static final float MIN_PEDESTAL = 16f;

    private RawCrop() {}

    /** The kept part of a frame: origin and size in the frame's own pixels. */
    public static final class Area {
        public final int x, y, width, height;

        public Area(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }

        @Override public String toString() {
            return width + "x" + height + " at " + x + "," + y;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Area)) return false;
            Area a = (Area) o;
            return a.x == x && a.y == y && a.width == width && a.height == height;
        }

        @Override public int hashCode() {
            return ((x * 31 + y) * 31 + width) * 31 + height;
        }
    }

    /**
     * The zero padding at each side of a {@code w} x {@code h} uint16 frame (rows of {@code w} samples): {left, top, right,
     * bottom} in pixels. A side counts only whole rows / columns of exact zeros from the edge inwards, at most 1 / 8 of the
     * frame; nothing on a RAW whose black level is under {@link #MIN_PEDESTAL} (no pedestal: zeros can be signal).
     */
    public static int[] detectPadding(ByteBuffer raw, int w, int h, float black) {
        final int[] none = new int[4];
        if (raw == null || w < 64 || h < 64 || !(black >= MIN_PEDESTAL) || raw.capacity() < (long) w * h * 2) return none;
        final ShortBuffer s = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        final int maxRows = h / MAX_BAND_DIVISOR, maxCols = w / MAX_BAND_DIVISOR;
        int top = 0;
        while (top <= maxRows && zeroRow(s, w, top)) top++;
        int bottom = 0;
        while (bottom <= maxRows && zeroRow(s, w, h - 1 - bottom)) bottom++;
        if (top > maxRows || bottom > maxRows) return none;   // a frame of zeros, not padding
        int left = 0;
        while (left <= maxCols && zeroColumn(s, w, h, left)) left++;
        int right = 0;
        while (right <= maxCols && zeroColumn(s, w, h, w - 1 - right)) right++;
        if (left > maxCols || right > maxCols) return none;
        return new int[]{left, top, right, bottom};
    }

    private static boolean zeroRow(ShortBuffer s, int w, int y) {
        final int base = y * w;
        for (int x = 0; x < w; x++) if (s.get(base + x) != 0) return false;
        return true;
    }

    private static boolean zeroColumn(ShortBuffer s, int w, int h, int x) {
        for (int y = 0; y < h; y++) if (s.get(y * w + x) != 0) return false;
        return true;
    }

    /**
     * Margins of the upright photo ({left, top, right, bottom}) as the sensor's sides, for a sensor mounted at
     * {@code orientation} degrees (SENSOR_ORIENTATION: the clockwise turn that makes its image upright). A 90 degree turn takes
     * the sensor's left side to the photo's top, its top to the right, its right to the bottom and its bottom to the left.
     */
    public static int[] photoToSensor(int[] photo, int orientation) {
        final int k = quarterTurns(orientation);
        final int[] sensor = new int[4];
        for (int i = 0; i < 4; i++) sensor[i] = photo[(i + k) % 4];
        return sensor;
    }

    /** The sensor's sides ({left, top, right, bottom}) as the upright photo's: the inverse of {@link #photoToSensor}. */
    public static int[] sensorToPhoto(int[] sensor, int orientation) {
        final int k = quarterTurns(orientation);
        final int[] photo = new int[4];
        for (int j = 0; j < 4; j++) photo[j] = sensor[(j - k + 4) % 4];
        return photo;
    }

    /** Quarter turns (0..3) of a mounting of {@code orientation} degrees. */
    public static int quarterTurns(int orientation) {
        return ((Math.round(orientation / 90f) % 4) + 4) % 4;
    }

    /** The larger cut of each side. */
    public static int[] larger(int[] a, int[] b) {
        return new int[]{Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /**
     * The part of a {@code w} x {@code h} frame kept with {@code sides} ({left, top, right, bottom}, sensor px) cut off: the
     * origin rounded inwards to whole colour periods, the sides even. Null: nothing to cut, or less than half of a side would
     * stay.
     */
    public static Area keep(int w, int h, int[] sides) {
        if (w <= 0 || h <= 0 || sides == null) return null;
        final int left = Math.max(0, sides[0]), top = Math.max(0, sides[1]), right = Math.max(0, sides[2]), bottom = Math.max(0, sides[3]);
        if (left == 0 && top == 0 && right == 0 && bottom == 0) return null;
        final int x = roundUp(left, PERIOD), y = roundUp(top, PERIOD);
        final int width = (w - right - x) & ~1, height = (h - bottom - y) & ~1;
        if (width < w / 2 || height < h / 2) return null;
        if (x == 0 && y == 0 && width == w && height == h) return null;
        return new Area(x, y, width, height);
    }

    private static int roundUp(int v, int period) {
        return (v + period - 1) / period * period;
    }

    /**
     * Cuts every {@code w} x {@code h} frame of {@code frames} to {@code area}. The kept rows go into one new shot arena (the
     * merge reads its frames without a copy), each frame's old buffer freed right after (a ZSL ring slot is given back), else
     * into plain buffers. Frames of another size are left alone (the burst drops them).
     */
    public static void apply(List<ImageFrame> frames, int w, int h, Area area) throws IOException {
        if (area == null || frames.isEmpty()) return;
        final long bytes = (long) area.width * area.height * 2;
        if (bytes > Integer.MAX_VALUE) throw new IOException("RAW crop: " + area + " too large");
        final ShotArena arena = ShotArena.create(frames.size(), bytes);
        try {
            for (ImageFrame f : frames) {
                if (f == null || f.buffer == null || f.width != w || f.height != h || f.buffer.capacity() < (long) w * h * 2) continue;
                final ByteBuffer old = f.buffer;
                ByteBuffer out = arena == null ? null : arena.copy(old, 0, (int) bytes);   // its bytes are rewritten below
                if (out != null && out.isReadOnly()) out = null;
                if (out == null) out = Allocator.allocate((int) bytes);
                if (out == null) throw new IOException("RAW crop: out of memory");
                copyRows(old, w, area, out);
                out.order(old.order());
                out.position(0);
                f.buffer = out;
                f.width = area.width;
                f.height = area.height;
                f.zeroCopy = false;
                Allocator.free(old);
            }
        } finally {
            if (arena != null) arena.release();
        }
    }

    /** The rows of {@code area} out of a frame of rows of {@code w} samples into {@code out} (rows of area.width samples). */
    static void copyRows(ByteBuffer in, int w, Area area, ByteBuffer out) {
        final ByteBuffer src = in.duplicate(), dst = out.duplicate();
        final int rowBytes = area.width * 2;
        dst.clear();
        for (int r = 0; r < area.height; r++) {
            final int from = ((area.y + r) * w + area.x) * 2;
            src.limit(from + rowBytes).position(from);
            dst.put(src);
            src.limit(src.capacity());
        }
    }

    /**
     * The lens shading map of the whole {@code fullWidth} x {@code fullHeight} frame ({@code cols} x {@code rows} cells of 4
     * channels, corners on the frame's corner pixels) resampled for {@code area} of it: the same grid over the kept part.
     */
    public static float[] cropGainMap(float[] map, int cols, int rows, int fullWidth, int fullHeight, Area area) {
        if (map == null || area == null || cols < 2 || rows < 2 || map.length < cols * rows * 4 || fullWidth < 2 || fullHeight < 2)
            return map;
        final float[] out = new float[cols * rows * 4];
        for (int j = 0; j < rows; j++) {
            final double fy = (area.y + (double) j / (rows - 1) * (area.height - 1)) / (fullHeight - 1) * (rows - 1);
            final int y0 = clamp((int) Math.floor(fy), rows - 1), y1 = Math.min(rows - 1, y0 + 1);
            final float ty = (float) Math.max(0, Math.min(1, fy - y0));
            for (int i = 0; i < cols; i++) {
                final double fx = (area.x + (double) i / (cols - 1) * (area.width - 1)) / (fullWidth - 1) * (cols - 1);
                final int x0 = clamp((int) Math.floor(fx), cols - 1), x1 = Math.min(cols - 1, x0 + 1);
                final float tx = (float) Math.max(0, Math.min(1, fx - x0));
                for (int c = 0; c < 4; c++) {
                    final float a = map[(y0 * cols + x0) * 4 + c] * (1 - tx) + map[(y0 * cols + x1) * 4 + c] * tx;
                    final float b = map[(y1 * cols + x0) * 4 + c] * (1 - tx) + map[(y1 * cols + x1) * 4 + c] * tx;
                    out[(j * cols + i) * 4 + c] = a * (1 - ty) + b * ty;
                }
            }
        }
        return out;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    /** The detection of a shot for the settings' picture: "w,h,left,top,right,bottom,orientation" (sensor sides). */
    public static String describe(int w, int h, int[] padding, int orientation) {
        return w + "," + h + "," + padding[0] + "," + padding[1] + "," + padding[2] + "," + padding[3] + "," + orientation;
    }

    /** {@link #describe}'s text back: {w, h, left, top, right, bottom, orientation}, or null. */
    public static int[] parse(String text) {
        if (text == null) return null;
        final String[] p = text.split(",");
        if (p.length != 7) return null;
        final int[] v = new int[7];
        try {
            for (int i = 0; i < 7; i++) v[i] = Integer.parseInt(p[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (v[0] <= 0 || v[1] <= 0) return null;
        for (int i = 2; i < 6; i++) if (v[i] < 0) return null;
        return v;
    }

    /**
     * P230: a sensor mode's band at the bottom ({@code mode[3]}) leaves its window, centred on the sensor, at the buffer's top:
     * the buffer's rows are half the band lower on the sensor; a band at the top the other way round.
     */
    static int modeShiftY(int[] mode) {
        if (mode == null || mode.length < 4) return 0;
        if (mode[3] > 0 && mode[1] == 0) return mode[3] / 2;
        if (mode[1] > 0 && mode[3] == 0) return -mode[1] / 2;
        return 0;
    }

    /** What a shot's crop found and kept. */
    public static final class Shot {
        /** The zero padding found ({left, top, right, bottom}, sensor px; zeros with the automatic crop off). */
        public final int[] padding;
        /** The kept part of the frames, null when they stayed whole. */
        public final Area area;
        /** The frames' size as captured. */
        public final int fullWidth, fullHeight;
        /**
         * P230: rows the kept part sits lower on the sensor than in the buffer: a sensor mode's window (centred on the sensor)
         * written to the top of a taller buffer is half its empty band lower (Xiaomi 15 Ultra DCG: 2560 rows of 3072, +256); 0
         * without a mode band.
         */
        public final int sensorShiftY;

        Shot(int[] padding, Area area, int fullWidth, int fullHeight) {
            this(padding, area, fullWidth, fullHeight, 0);
        }

        Shot(int[] padding, Area area, int fullWidth, int fullHeight, int sensorShiftY) {
            this.padding = padding;
            this.area = area;
            this.fullWidth = fullWidth;
            this.fullHeight = fullHeight;
            this.sensorShiftY = sensorShiftY;
        }
    }

    /**
     * Cuts the frames of a shot (all of the first frame's size): the zero padding when {@code auto}, and the upright photo's
     * {@code manualPhoto} margins ({left, top, right, bottom}) turned by the sensor's {@code orientation}; each side the larger.
     */
    public static Shot cutShot(List<ImageFrame> frames, float black, boolean auto, int[] manualPhoto, int orientation) throws IOException {
        return cutShot(frames, black, auto, manualPhoto, orientation, null);
    }

    /**
     * As above, with {@code modeSensor}: the band a sensor mode leaves empty in a taller buffer ({left, top, right, bottom} sensor
     * px, null / zeros: none; P230 Xiaomi 15 Ultra DCG: 512 rows at the bottom of 4096 x 3072), cut whether or not the automatic
     * crop is on (those rows are no image), each side the larger of the three.
     */
    public static Shot cutShot(List<ImageFrame> frames, float black, boolean auto, int[] manualPhoto, int orientation,
                               int[] modeSensor) throws IOException {
        final ImageFrame first = frames.isEmpty() ? null : frames.get(0);
        if (first == null || first.buffer == null) return new Shot(new int[4], null, 0, 0);
        final long started = System.nanoTime();
        final int w = first.width, h = first.height;
        final int[] padding = auto ? detectPadding(first.buffer, w, h, black) : new int[4];
        final int[] manual = photoToSensor(manualPhoto, orientation);
        final int[] mode = modeSensor != null && modeSensor.length == 4 ? modeSensor : new int[4];
        final Area area = keep(w, h, larger(larger(padding, manual), mode));
        if (area != null) {
            apply(frames, w, h, area);
            Log.i("SCAM_HDR", "RAW crop " + w + "x" + h + " -> " + area + ": zero padding L/T/R/B " + padding[0] + "/" + padding[1]
                    + "/" + padding[2] + "/" + padding[3] + ", manual " + manual[0] + "/" + manual[1] + "/" + manual[2] + "/" + manual[3]
                    + ", sensor mode " + mode[0] + "/" + mode[1] + "/" + mode[2] + "/" + mode[3]
                    + " (sensor sides, orientation " + orientation + ") in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        }
        return new Shot(larger(padding, mode), area, w, h, area == null ? 0 : modeShiftY(mode));
    }
}
