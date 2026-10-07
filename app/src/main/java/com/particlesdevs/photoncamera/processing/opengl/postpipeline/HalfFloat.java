package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

/**
 * IEEE half to float for CPU read-backs of RGBA16F textures (shot speed, W1.6): every one of the 65536 halves widened exactly
 * (subnormals, infinities; NaN stays NaN), as {@link LmcDenoiseTables#half} computes it.
 */
final class HalfFloat {
    private HalfFloat() {}

    /** TABLE[h & 0xffff] = the float value of half h. */
    static final float[] TABLE = new float[65536];
    static {
        for (int h = 0; h < 65536; h++) TABLE[h] = LmcDenoiseTables.half(h);
    }
}
