package com.particlesdevs.photoncamera.processing.parameters;

/** Exposure-ratio budget shared only by bracket members that are captured. */
public final class HdrBracketFactors {
    private HdrBracketFactors() {}

    public static double limit(double requested, double opposite, double ceiling) {
        requested = Math.max(1.0, requested);
        opposite = Math.max(1.0, opposite);
        if (ceiling <= 1.0 || requested * opposite <= ceiling) return requested;
        // Share the reduction in stops. If either side reaches the base
        // exposure, give the remaining budget to the other side.
        double stops = Math.log(requested);
        double budget = Math.log(ceiling);
        return Math.exp(Math.max(0.0, Math.min(stops,
                Math.min(budget, (budget + stops - Math.log(opposite)) / 2.0))));
    }
}
