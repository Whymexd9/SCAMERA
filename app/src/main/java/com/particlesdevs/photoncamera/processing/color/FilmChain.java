package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

/**
 * P281: the one 3D LUT the photo (ArkCombine, FILM_LUT) and the viewfinder apply - the film simulation (FilmSim) followed by
 * the chosen LUT (FilmLut, «Плёночный LUT»), each at its own strength, baked into one table; without a simulation it is the
 * chosen LUT as before (its strength then applied by the shader, unchanged).
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
        final FilmSim.Settings sim = FilmSim.current();
        if (sim == null) return new State(lut, lutStrength);
        return new State(PREFIX + sim.lutKey() + "|" + String.format(java.util.Locale.ROOT, "%.2f", sim.strength) + "|" + lut
                + "|" + String.format(java.util.Locale.ROOT, "%.2f", lut.isEmpty() ? 0f : lutStrength), 1f);
    }

    private static String cachedKey;
    private static FilmLut cached;

    /** The LUT of a key of {@link #current} (a chain is built once per key); null: none. */
    @Nullable
    public static synchronized FilmLut lut(android.content.Context context, String key) {
        if (key == null || key.isEmpty()) return null;
        if (!key.startsWith(PREFIX)) return FilmLut.chosen(context, key);
        if (key.equals(cachedKey)) return cached;
        final FilmSim.Settings sim = FilmSim.current();
        FilmLut out = null;
        if (sim != null) {
            final FilmLut film = FilmSim.lut(sim, FilmLooks.SIZE);
            final State st = current();
            final String[] parts = key.split("\\|", -1);
            final String lutName = parts.length >= 2 ? parts[parts.length - 2] : "";
            final float lutStrength = lutName.isEmpty() ? 0f : FilmSim.number(parts[parts.length - 1], 0f);
            final FilmLut user = lutName.isEmpty() ? null : FilmLut.chosen(context, lutName);
            out = compose(film, sim.strength, user, lutStrength);
            if (!st.key.equals(key)) com.particlesdevs.photoncamera.util.Log.d("FilmChain", "settings moved while building " + key);
        }
        cachedKey = key;
        cached = out;
        return out;
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
