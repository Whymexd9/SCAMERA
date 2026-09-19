package com.particlesdevs.photoncamera.control;

public class GyroBurst {
    public float shakiness;
    public int samples;
    public float[][] movementss;
    public long[] timestampss;
    public float[] integrated;
    private final int maxSamples;
    public GyroBurst(int maxSamples){
        this.maxSamples = maxSamples;
        movementss = new float[3][maxSamples];
        timestampss = new long[maxSamples];
        integrated = new float[3];
        samples = 0;
    }

    /** Squared angular path, consistent across ZSL and post-shutter captures. */
    public void recalculateShakiness() {
        double motion = 0;
        for (int i=0;i<samples;i++) for (int axis=0;axis<3;axis++) {
            float v = movementss[axis][i];
            if (!Float.isFinite(v)) { samples=0; shakiness=0; return; }
            motion += Math.abs(v);
        }
        shakiness = (float)Math.min(motion*motion, Float.MAX_VALUE);
    }

    @Override
    public GyroBurst clone() {
        GyroBurst out = new GyroBurst(maxSamples);
        for (int axis = 0; axis < 3; axis++) out.movementss[axis] = movementss[axis].clone();
        out.timestampss = timestampss.clone();
        out.integrated = integrated.clone();
        out.shakiness = shakiness;
        out.samples = samples;
        return out;
    }
}
