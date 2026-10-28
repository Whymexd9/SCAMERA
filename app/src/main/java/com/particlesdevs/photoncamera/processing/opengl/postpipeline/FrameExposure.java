package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.LiveRawFrame;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * P383 (owner, 2026-10-28: «экспозиция меняется не плавно, а ступенчато», ETTR off): the RAW viewfinder's frame drawn against its
 * own exposure. The developed RAW is the scene times the frame's exposure, so every move of the camera's AE (or of our own AE)
 * landed on the screen in full in its frame and the viewfinder's tone eased it back over the next ~0.3 s - and when the scene
 * changed, the camera's AE and ours corrected together and overshot (host model of a +2 EV window: up to 0.6 EV a frame and
 * 0.4 - 1.2 EV past the end). LiveWhyTone eases the exposure in the scene's terms instead (ToneEase: log2 ae + log2 of the
 * metered frame's exposure) and draws each frame at that minus its own exposure: a move of the camera's exposure is cancelled in
 * its own frame, only the scene's change eases.
 * <p>
 * The exposure is the result's SENSOR_EXPOSURE_TIME x SENSOR_SENSITIVITY up to the analog ceiling (a gain past it is applied after
 * the RAW). {@link Track} follows it from frame to frame and takes a move only where the frame agrees: a small one (at most
 * {@link #TRUSTED_EV}: the AE's own steps), or a large one the RAW's level follows (the vivo V2502A's log reported ISO 8019 ->
 * 22698 -> 8328 for frames whose RAW did not change). A move it does not take is drawn as before (the tone's meter takes it up).
 * A hand-set exposure is followed too (review: the photo's WhyAe meters a hand-set change away as well, and the software priority
 * loop's own steps are cancelled the same way); scam_dev vf_ae_scene 0 follows nothing (as before P383, for an A/B).
 */
public final class FrameExposure {
    /** A move up to this is taken as reported (the AE's own steps; the viewfinder's flash of a wrong one stays this small). */
    static final double TRUSTED_EV = 0.5;
    /** A larger one, up to MAX_EV, when the RAW's level follows it within max(LEVEL_EV, LEVEL_SHARE x the move). */
    static final double MAX_EV = 3, LEVEL_EV = 0.25, LEVEL_SHARE = 0.5;
    /** A taken move over this that the level did not follow is counted as suspect (the 5 s AE line). */
    static final double SUSPECT_EV = 0.15;
    /** The level's grid (quads across and down) and the darkest quad it counts (a fraction of white). */
    static final int GRID = 24;
    static final float FLOOR = 1f / 4096f;

    private FrameExposure() {}

    /** log2 of the exposure (s x ISO, the ISO up to the analog ceiling); NaN when the time or the ISO is unknown. */
    public static double log2Product(long exposureNs, int iso, int maxAnalogIso) {
        if (exposureNs <= 0 || iso <= 0) return Double.NaN;
        final int analog = maxAnalogIso > 0 ? Math.min(iso, maxAnalogIso) : iso;
        return Math.log(exposureNs / 1e9 * analog) / Math.log(2);
    }

    public static double log2Product(LiveRawFrame.Frame f) {
        return f == null ? Double.NaN : log2Product(f.exposureNs, f.iso, f.maxAnalogIso);
    }

    /**
     * The frame's level: the median of log2 of its quads' mean (the four sites, black to white) on a GRID x GRID grid of its crop -
     * cheap (GRID² quads), and robust to a clipped lamp or window under half of the frame. NaN without pixels.
     */
    public static double log2Level(LiveRawFrame.Frame f) {
        if (f == null || f.buffer == null || f.width <= 0 || f.height <= 0) return Double.NaN;
        final ShortBuffer raw = f.buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        final int block = Math.max(1, f.cfaBlock), period = 2 * block, rowPx = f.rowStride / 2;
        final float[] crop = f.crop != null && f.crop.length >= 4 ? f.crop : new float[]{0, 0, 1, 1};
        final int left = Math.max(0, (int) (crop[0] * f.width) / period * period);
        final int top = Math.max(0, (int) (crop[1] * f.height) / period * period);
        final int right = Math.min(f.width, (int) ((crop[0] + crop[2]) * f.width));
        final int bottom = Math.min(f.height, (int) ((crop[1] + crop[3]) * f.height));
        final int dx = Math.max(period, (right - left) / GRID / period * period), dy = Math.max(period, (bottom - top) / GRID / period * period);
        final float white = f.whiteLevel;
        final float[] black = f.blackLevel != null && f.blackLevel.length >= 4 ? f.blackLevel : new float[4];
        final float[] logs = new float[(GRID + 1) * (GRID + 1)];
        int n = 0;
        for (int y = top; y + period <= bottom && n < logs.length; y += dy) {
            for (int x = left; x + period <= right && n < logs.length; x += dx) {
                float sum = 0f;
                for (int site = 0; site < 4; site++) {
                    final int sx = x + (site & 1) * block, sy = y + (site >> 1) * block;
                    int s = 0;
                    for (int yy = 0; yy < block; yy++) {
                        final int row = (sy + yy) * rowPx + sx;
                        if (row + block > raw.limit()) return Double.NaN;
                        for (int xx = 0; xx < block; xx++) s += raw.get(row + xx) & 0xFFFF;
                    }
                    sum += Math.max(0f, (s / (float) (block * block) - black[site]) / Math.max(1f, white - black[site]));
                }
                logs[n++] = (float) (Math.log(Math.max(FLOOR, sum * 0.25f)) / Math.log(2));
            }
        }
        if (n == 0) return Double.NaN;
        Arrays.sort(logs, 0, n);
        return logs[n / 2];
    }

    /**
     * One session's exposure as the viewfinder draws it: {@link #logP()} moves with the frames' reported exposure where it is
     * taken (see the class), and holds where it is not (unknown, refused, vf_ae_scene 0).
     */
    public static final class Track {
        private boolean on;
        private double logP, lastLogP = Double.NaN, lastLevel = Double.NaN;
        private int refused, suspect;

        public void reset() {
            on = false;
            lastLogP = lastLevel = Double.NaN;
        }

        /** The exposure the frame last given is drawn against (log2; 0 before a known one). */
        public double logP() {
            return logP;
        }

        /** Moves refused since the last call (for the log). */
        public int takeRefused() {
            final int r = refused;
            refused = 0;
            return r;
        }

        /**
         * Moves taken although the frame's level did not follow them (over SUSPECT_EV, off by more than the level check allows) since
         * the last call: a wrong report the guard trusted flashes on the screen; the log counts them (a phone's numbers decide
         * TRUSTED_EV).
         */
        public int takeSuspect() {
            final int s = suspect;
            suspect = 0;
            return s;
        }

        /** A new frame: {@code reported} its log2 exposure (NaN unknown), {@code level} its log2 level; {@code follow} false: held. */
        public double next(double reported, double level, boolean follow) {
            if (!on) {
                on = true;
                logP = Double.isNaN(reported) ? 0 : reported;
            } else if (follow && !Double.isNaN(reported) && !Double.isNaN(lastLogP)) {
                final double move = reported - lastLogP, levelMove = level - lastLevel;
                if (takes(move, levelMove)) {
                    logP += move;
                    if (Math.abs(move) > SUSPECT_EV && !agrees(move, levelMove)) suspect++;
                } else refused++;
            }
            lastLogP = reported;
            lastLevel = level;
            return logP;
        }

        /** Whether a reported move is taken: a small one at once, a large one when the level follows. */
        static boolean takes(double move, double levelMove) {
            if (Math.abs(move) <= TRUSTED_EV) return true;
            return Math.abs(move) <= MAX_EV && agrees(move, levelMove);
        }

        static boolean agrees(double move, double levelMove) {
            return !Double.isNaN(levelMove) && Math.abs(levelMove - move) <= Math.max(LEVEL_EV, LEVEL_SHARE * Math.abs(move));
        }
    }
}
