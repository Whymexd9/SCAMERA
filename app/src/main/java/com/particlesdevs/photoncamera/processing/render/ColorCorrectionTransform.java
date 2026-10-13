package com.particlesdevs.photoncamera.processing.render;

/**
 * The render's ProPhoto -> sRGB matrix (row-major 3 x 3): from the camera's colour data, the ISP / OPPO matrix of the shot
 * or a DCP profile (Parameters.FillDynamicParameters).
 */
public class ColorCorrectionTransform {
    public float[] matrix = new float[9];
}
