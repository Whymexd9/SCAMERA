package com.particlesdevs.photoncamera.manual;

/**
 * P82 Shutter / ISO priority of the manual panel (owner, 2026-10-11): one of ISO and shutter set by hand, the other follows
 * the scene, the EV compensation still applies. Two ways:
 * <ul>
 *   <li>the camera's own priority AE (Android 16 CONTROL_AE_PRIORITY_MODE, where the module lists it, e.g. the Xiaomi
 *       17 Ultra's [0 1 2]);</li>
 *   <li>otherwise a loop over the viewfinder's brightness: AE off, the hand-set parameter fixed, the other one moved so the
 *       frame's linear brightness returns to what the camera's AE gave before the hand-set value (× 2^EV).</li>
 * </ul>
 * Pure arithmetic here, tested on the host; {@link ParamController} drives it.
 */
public final class ExposurePriority {
    public static final int NONE = 0, SHUTTER = 1, ISO = 2;

    /** Display-encoded luma to linear light (the viewfinder stream is close to sRGB, gamma 2.2). */
    static final double GAMMA = 2.2;
    /** No correction below a sixth of a stop: the meter's noise and the HAL's ISO steps. */
    static final double DEADBAND_STOPS = 1.0 / 6;
    /** At most two stops a step, and two thirds of the error (no overshoot through the ISP's tone curve). */
    static final double MAX_STEP_STOPS = 2, DAMPING = 2.0 / 3;
    /** The middle grey the loop aims at without an auto measurement of its own (≈ 18 % reflectance in sRGB). */
    static final double DEFAULT_TARGET = 0.46;

    private ExposurePriority() {}

    /** Which priority the panel's state asks for: exactly one of ISO and shutter by hand. */
    public static int mode(boolean isoManual, boolean shutterManual) {
        if (shutterManual && !isoManual) return SHUTTER;
        if (isoManual && !shutterManual) return ISO;
        return NONE;
    }

    /** The CONTROL_AE_PRIORITY_MODE value of a priority (SENSOR_SENSITIVITY_PRIORITY 1, SENSOR_EXPOSURE_TIME_PRIORITY 2). */
    public static int halMode(int mode) {
        return mode == ISO ? 1 : mode == SHUTTER ? 2 : 0;
    }

    /** Whether the module's CONTROL_AE_AVAILABLE_PRIORITY_MODES has the priority. */
    public static boolean halSupports(int[] available, int mode) {
        if (available == null || mode == NONE) return false;
        for (int m : available) if (m == halMode(mode)) return true;
        return false;
    }

    static double linear(double luma) {
        return Math.pow(Math.max(1e-4, Math.min(1, luma)), GAMMA);
    }

    /**
     * The exposure (ISO × seconds) after one viewfinder measurement: {@code product} gave {@code luma}, the target is
     * {@code targetLuma} (the auto exposure's) brightened by {@code evStops}. Unchanged inside the dead band.
     */
    static double nextProduct(double product, double luma, double targetLuma, double evStops) {
        if (!(product > 0) || !(luma >= 0)) return product;
        double stops = Math.log(linear(targetLuma) * Math.pow(2, evStops) / linear(luma)) / Math.log(2);
        // a clipped frame says only "too bright": one stop down at least
        if (luma >= 0.985) stops = Math.min(stops, -1);
        if (Math.abs(stops) < DEADBAND_STOPS) return product;
        stops = Math.max(-MAX_STEP_STOPS, Math.min(MAX_STEP_STOPS, stops * DAMPING));
        return product * Math.pow(2, stops);
    }

    /** The ISO that gives {@code product} at {@code shutterNs}, inside [isoMin, isoMax]. */
    static int isoFor(double product, long shutterNs, int isoMin, int isoMax) {
        double iso = product / (shutterNs / 1e9);
        return (int) Math.max(isoMin, Math.min(isoMax, Math.round(iso)));
    }

    /** The shutter that gives {@code product} at {@code iso}, inside [minNs, maxNs]. */
    static long shutterFor(double product, int iso, long minNs, long maxNs) {
        double ns = product / Math.max(1, iso) * 1e9;
        return Math.max(minNs, Math.min(maxNs, Math.round(ns)));
    }

    /**
     * The software loop of one priority session. It starts from the camera's auto exposure (the free parameter takes the
     * hand-set one's difference, so the frame keeps the AE's brightness) and learns that brightness from the first
     * measurement: the target is what the camera's AE chose, at the EV compensation of that moment.
     */
    public static final class Loop {
        public final int mode;
        private double targetLuma = Double.NaN, targetEv;
        private int iso;
        private long shutterNs;

        public Loop(int mode, int iso, long shutterNs) {
            this.mode = mode;
            this.iso = iso;
            this.shutterNs = shutterNs;
        }

        /** A new priority from this one's state (ISO priority after shutter priority): the learned target stays. */
        public Loop(int mode, Loop from) {
            this(mode, from.iso, from.shutterNs);
            targetLuma = from.targetLuma;
            targetEv = from.targetEv;
        }

        public int iso() { return iso; }
        public long shutterNs() { return shutterNs; }
        public double targetLuma() { return targetLuma; }

        /** The hand-set value changed: the exposure stays, the free parameter takes the difference. */
        public void setFixed(int newIso, long newShutterNs, int isoMin, int isoMax, long minNs, long maxNs) {
            final double product = iso * (shutterNs / 1e9);
            if (mode == SHUTTER) {
                shutterNs = newShutterNs;
                iso = isoFor(product, shutterNs, isoMin, isoMax);
            } else {
                iso = newIso;
                shutterNs = shutterFor(product, iso, minNs, maxNs);
            }
        }

        /**
         * One viewfinder measurement taken with the current values. True when the free parameter changed (the request
         * needs the new values). The first one only learns the target.
         */
        public boolean measured(double luma, double evStops, int isoMin, int isoMax, long minNs, long maxNs) {
            if (!(luma >= 0 && luma <= 1)) return false;
            if (Double.isNaN(targetLuma)) {
                targetLuma = luma > 0.02 && luma < 0.98 ? luma : DEFAULT_TARGET;
                targetEv = evStops;
                return false;
            }
            final double next = nextProduct(iso * (shutterNs / 1e9), luma, targetLuma, evStops - targetEv);
            final int oldIso = iso;
            final long oldShutter = shutterNs;
            if (mode == SHUTTER) iso = isoFor(next, shutterNs, isoMin, isoMax);
            else shutterNs = shutterFor(next, iso, minNs, maxNs);
            return iso != oldIso || shutterNs != oldShutter;
        }
    }
}
