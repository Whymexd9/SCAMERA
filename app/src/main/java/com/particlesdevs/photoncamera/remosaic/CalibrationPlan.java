package com.particlesdevs.photoncamera.remosaic;

/** Original APK CAL schedule: five logarithmic ISO levels, two exposures, four RAWs each. */
public final class CalibrationPlan {
    public static final int PROFILES=10, FRAMES=4;
    public final int[] isos=new int[PROFILES];
    public final long[] exposures=new long[PROFILES];
    public CalibrationPlan(int minIso,int maxIso,long minExposure,long maxExposure) {
        if(minIso<1 || maxIso<minIso || minExposure<1 || maxExposure<minExposure)
            throw new IllegalArgumentException("Invalid sensor calibration range");
        for(int p=0;p<PROFILES;p++) {
            double f=(p/2)/4.0;
            isos[p]=(int)Math.round(Math.exp(Math.log(minIso)*(1-f)+Math.log(maxIso)*f));
            exposures[p]=Math.max(minExposure,Math.min(maxExposure,(p&1)==0?1_000_000L:66_666_667L));
        }
    }
    public static double distance(int iso,long exposure,int profileIso,long profileExposure) {
        double i=Math.log((double)Math.max(1,iso)/Math.max(1,profileIso));
        double e=Math.log((double)Math.max(1,exposure)/Math.max(1,profileExposure));
        return i*i+.35*e*e;
    }
    public static float scale(int iso,int profileIso) {
        return Math.max(.7f,Math.min(1.4f,(float)iso/Math.max(1,profileIso)));
    }
}
