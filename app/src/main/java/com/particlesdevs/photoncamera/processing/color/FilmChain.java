package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

/**
 * P281: the one 3D LUT the photo (WhyCombine, FILM_LUT) and the viewfinder apply - the film simulation (FilmSim) followed by
 * the chosen LUT (FilmLut, «Плёночный LUT»), each at its own strength, baked into one table; without a simulation it is the
 * chosen LUT as before (its strength then applied by the shader, unchanged).
 * P369: with a simulation the photo and the RAW viewfinder take {@link #scene} instead (scene light into the film, why/combine
 * FILM_SIM 1); this display-domain chain stays for the ISP viewfinder, the RAW viewfinder's own look and the settings' preview.
 */
public final class FilmChain {
    private FilmChain() {}

    /** What to apply: a key (the LUT's name, or the chain's) and the shader's strength. */
    public static final class State {
        public final String key;
        public final float strength;

        State(String key, float strength) {
            this.key = key;
            this.strength = strength;
        }
    }

    static final String PREFIX = "chain:";

    /** The chain in the settings now; key "" = nothing to apply. */
    public static State current() {
        final float lutStrength = com.particlesdevs.photoncamera.settings.PreferenceKeys.filmLutStrength();
        final String lut = lutStrength > 0f ? com.particlesdevs.photoncamera.settings.PreferenceKeys.filmLut() : "";
        return current(FilmSim.current(), lut, lutStrength);
    }

    /**
     * P369: the chain of a film snapshot {@code sim} (null: none) and the chosen LUT {@code lut} ("" = none) at {@code lutStrength} -
     * {@link #current()} of those settings, without reading them again (the photo's PostPipeline.film).
     */
    public static State current(@Nullable FilmSim.Settings sim, String lut, float lutStrength) {
        if (sim == null) return new State(lut, lutStrength);
        return new State(PREFIX + sim.lutKey() + "|" + String.format(java.util.Locale.ROOT, "%.2f", sim.strength) + "|" + lut
                + "|" + String.format(java.util.Locale.ROOT, "%.2f", lut.isEmpty() ? 0f : lutStrength), 1f);
    }

    private static String cachedKey;
    private static FilmLut cached;

    /**
     * The LUT of a key of {@link #current} (a chain is built once per key); null: none. P369 (viewfinder-filmchain-cache-key): a
     * chain is built from the settings its key stands for (FilmSim.Settings.forLut), never from the settings now - a key asked for
     * after the settings moved on was cached with the newer settings' table and kept it, and the viewfinder asks again only when the
     * key changes.
     */
    @Nullable
    public static synchronized FilmLut lut(android.content.Context context, String key) {
        if (key == null || key.isEmpty()) return null;
        if (!key.startsWith(PREFIX)) return FilmLut.chosen(context, key);
        if (key.equals(cachedKey)) return cached;
        final String body = key.substring(PREFIX.length());
        final String[] parts = body.split("\\|", -1);
        // lutKey's seven fields, the simulation's strength, the chosen LUT and its strength
        final FilmSim.Settings sim = parts.length >= 10 ? FilmSim.Settings.forLut(body, FilmSim.number(parts[7], 1f)) : null;
        FilmLut out = null;
        if (sim != null) {
            final FilmLut film = FilmSim.lut(sim, FilmLooks.SIZE);
            final String lutName = parts[parts.length - 2];
            final float lutStrength = lutName.isEmpty() ? 0f : FilmSim.number(parts[parts.length - 1], 0f);
            final FilmLut user = lutName.isEmpty() ? null : FilmLut.chosen(context, lutName);
            out = compose(film, sim.strength, user, lutStrength);
        }
        cachedKey = key;
        cached = out;
        return out;
    }

    // ───── P369: the scene-referred table of the photo and the RAW viewfinder (why/combine FILM_SIM 1)

    /** The scene table to apply: the table (FilmSim.lutScene, then the chosen LUT), the shader's film strength and its key. */
    public static final class Scene {
        public final FilmLut lut;
        public final float strength;
        public final String key;

        Scene(FilmLut lut, float strength, String key) {
            this.lut = lut;
            this.strength = strength;
            this.key = key;
        }
    }

    /**
     * What the scene table of {@code sim} with the chosen LUT {@code user} at {@code userStrength} depends on (not the simulation's
     * strength, the shader's mix; not the format, grain, halation or softness): FilmSim.Settings.lutKey, the LUT, its strength and the
     * log grid.
     */
    public static String sceneKey(FilmSim.Settings sim, String user, float userStrength) {
        return "scene:" + sim.lutKey() + "|" + user + "|" + String.format(java.util.Locale.ROOT, "%.2f", user.isEmpty() ? 0f : userStrength)
                + "|" + FilmSim.LOG_LO + "|" + FilmSim.LOG_SPAN;
    }

    private static String sceneCachedKey;
    private static FilmLut sceneCached;
    /** The film's own scene table (FilmSim.lutScene) under its key alone: a .cube or its strength changed only composes again. */
    private static String filmCachedKey;
    private static FilmLut filmCached;

    /** Both tables forgotten (the tests; the next ask builds them again). */
    static synchronized void forget() {
        cachedKey = null;
        cached = null;
        sceneCachedKey = null;
        sceneCached = null;
        filmCachedKey = null;
        filmCached = null;
    }

    /**
     * The scene table of a film snapshot {@code sim} (the shot's, or the viewfinder's settings now) followed by the chosen LUT
     * {@code user} ("" none) at {@code userStrength}: film strength 1 in the table - the film, then the .cube on its print - and
     * the film's own strength mixed by the shader (mix(ours, film, s)). Built only from {@code sim} (race-free), once per
     * {@link #sceneKey} in its own cache (apart from the display chain's: a phone on the ISP viewfinder would rebuild either on every
     * shot). Null when the film's data cannot be read.
     */
    @Nullable
    public static synchronized Scene scene(android.content.Context context, FilmSim.Settings sim, String user, float userStrength) {
        final String key = sceneKey(sim, user, userStrength);
        if (!key.equals(sceneCachedKey)) {
            final String filmKey = "scene:" + sim.lutKey() + "|" + FilmSim.LOG_LO + "|" + FilmSim.LOG_SPAN;
            if (!filmKey.equals(filmCachedKey)) {
                filmCached = FilmSim.lutScene(sim, FilmSim.SCENE_SIZE);
                filmCachedKey = filmKey;
            }
            final FilmLut film = filmCached;
            FilmLut table = null;
            if (film != null) {
                final FilmLut userLut = user.isEmpty() || !(userStrength > 0f) ? null : FilmLut.chosen(context, user);
                table = userLut == null ? film : compose(film, 1f, userLut, userStrength);
            }
            sceneCachedKey = key;
            sceneCached = table;
        }
        return sceneCached == null ? null : new Scene(sceneCached, sim.strength, key);
    }

    /**
     * x -> mix(x, film(x), filmStrength) -> mix(y, user(y), userStrength), sampled on the film LUT's grid (the user LUT's domain
     * and trilinear interpolation as the shader's).
     */
    static FilmLut compose(FilmLut film, float filmStrength, @Nullable FilmLut user, float userStrength) {
        final int n = film.size;
        final float[] v = new float[film.rgb.length];
        final float[] c = new float[3];
        int k = 0;
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            final float[] in = {r / (float) (n - 1), g / (float) (n - 1), b / (float) (n - 1)};
            for (int ch = 0; ch < 3; ch++) c[ch] = in[ch] + (film.rgb[k + ch] - in[ch]) * filmStrength;
            if (user != null && userStrength > 0f) {
                final float[] u = sample(user, c);
                for (int ch = 0; ch < 3; ch++) c[ch] += (u[ch] - c[ch]) * userStrength;
            }
            for (int ch = 0; ch < 3; ch++) v[k + ch] = Math.max(0f, Math.min(1f, c[ch]));
            k += 3;
        }
        return FilmLut.of(n, v, film.title + (user != null && userStrength > 0f ? " + " + user.title : ""));
    }

    /** A LUT at a colour (its domain, trilinear). */
    static float[] sample(FilmLut l, float[] rgb) {
        final int n = l.size;
        final float[] p = new float[3];
        final int[] i0 = new int[3];
        final float[] t = new float[3];
        for (int ch = 0; ch < 3; ch++) {
            final float range = l.domainMax[ch] - l.domainMin[ch];
            p[ch] = Math.max(0f, Math.min(1f, range > 0 ? (rgb[ch] - l.domainMin[ch]) / range : 0f)) * (n - 1);
            i0[ch] = Math.min(n - 2, (int) Math.floor(p[ch]));
            t[ch] = p[ch] - i0[ch];
        }
        final float[] out = new float[3];
        for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            final float w = (dx == 1 ? t[0] : 1 - t[0]) * (dy == 1 ? t[1] : 1 - t[1]) * (dz == 1 ? t[2] : 1 - t[2]);
            final int idx = (((i0[2] + dz) * n + (i0[1] + dy)) * n + (i0[0] + dx)) * 3;
            for (int ch = 0; ch < 3; ch++) out[ch] += w * l.rgb[idx + ch];
        }
        return out;
    }
}
