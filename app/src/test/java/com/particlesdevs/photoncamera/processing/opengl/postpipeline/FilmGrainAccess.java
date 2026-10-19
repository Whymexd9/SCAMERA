package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

/** Tests: the package-private sizes of the film effects. */
public final class FilmGrainAccess {
    private FilmGrainAccess() {}

    public static float sigma(float rms, float grainUm, float pixelUm) {
        return FilmGrain.sigma(rms, grainUm, pixelUm);
    }

    public static int level(float sigmaPx, int levels) {
        return FilmHalation.level(sigmaPx, levels);
    }
}
