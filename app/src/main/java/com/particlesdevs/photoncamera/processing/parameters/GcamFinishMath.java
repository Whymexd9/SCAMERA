package com.particlesdevs.photoncamera.processing.parameters;

/** Recovered arithmetic, with explicit guards outside the donor's tested domain. */
public final class GcamFinishMath {
    private GcamFinishMath() {}
    public static double[] splitGain(double gain) {
        if(!Double.isFinite(gain) || gain<=0) return new double[]{1,1};
        if(gain<=1) return new double[]{1,gain};
        if(gain<1.25) return new double[]{gain,1};
        double u=Math.max(0,Math.min(1,2*(gain-1.25)));
        double rolloff=1.25+.5*(u-.5*u*u);
        return new double[]{rolloff,gain/rolloff};
    }
    public static double effectiveSamples(double weight,double squareWeight) {
        if(!Double.isFinite(weight+squareWeight) || weight<=0 || squareWeight<=0) return 1;
        return Math.max(1,weight*weight/squareWeight);
    }
    public static double varianceScale(double effectiveSamples,double independentSpatialSamples) {
        if(!Double.isFinite(effectiveSamples) || effectiveSamples<1) effectiveSamples=1;
        if(!Double.isFinite(independentSpatialSamples) || independentSpatialSamples<1) independentSpatialSamples=1;
        return 1/(effectiveSamples*independentSpatialSamples);
    }
}
