package com.particlesdevs.photoncamera.processing.color;

import android.opengl.GLES30;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * P134 «Плёночный LUT»: a 3D look LUT from an Adobe / Resolve .cube file (LUT_3D_SIZE N, N^3 rows "r g b", red fastest,
 * optional TITLE, DOMAIN_MIN / DOMAIN_MAX, LUT_3D_INPUT_RANGE). WhyCombine looks the display-encoded output up in it
 * (trilinear, a 3D texture) and mixes the result by the strength. A 1D LUT, a size outside 2..65, a wrong row count or a
 * value that is not a number makes {@link #parse} return null.
 */
public final class FilmLut {
    /** The chosen file in Download/SCAMERA/LUT ("" = none) and the strength in percent. */
    public static final String KEY = "pref_film_lut", STRENGTH_KEY = "pref_film_lut_strength";
    public static final int MIN_SIZE = 2, MAX_SIZE = 65;
    public static final long MAX_BYTES = 24L << 20;

    public final int size;
    /** N^3 x 3 output values, red fastest (the 3D texture's x). */
    public final float[] rgb;
    public final float[] domainMin, domainMax;
    public final String title;

    private FilmLut(int size, float[] rgb, float[] domainMin, float[] domainMax, String title) {
        this.size = size;
        this.rgb = rgb;
        this.domainMin = domainMin;
        this.domainMax = domainMax;
        this.title = title;
    }

    /** P170: a LUT built here (FilmLooks): n^3 x 3 values, red fastest, over 0..1. */
    static FilmLut of(int n, float[] rgb, String title) {
        return new FilmLut(n, rgb, new float[]{0, 0, 0}, new float[]{1, 1, 1}, title);
    }

    /** The identity LUT of size n (tests, and the strength check). */
    public static FilmLut identity(int n) {
        float[] v = new float[n * n * n * 3];
        int k = 0;
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            v[k++] = r / (float) (n - 1);
            v[k++] = g / (float) (n - 1);
            v[k++] = b / (float) (n - 1);
        }
        return new FilmLut(n, v, new float[]{0, 0, 0}, new float[]{1, 1, 1}, "identity");
    }

    @Nullable
    public static FilmLut parse(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        int size = 0, k = 0;
        float[] values = null, min = {0, 0, 0}, max = {1, 1, 1};
        String title = "";
        long read = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            read += line.length() + 1;
            if (read > MAX_BYTES) return null;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            String head = t[0].toUpperCase(Locale.ROOT);
            try {
                switch (head) {
                    case "TITLE":
                        title = line.substring(5).trim().replace("\"", "");
                        continue;
                    case "LUT_1D_SIZE":
                        return null;
                    case "LUT_3D_SIZE":
                        size = Integer.parseInt(t[1]);
                        if (size < MIN_SIZE || size > MAX_SIZE) return null;
                        values = new float[size * size * size * 3];
                        continue;
                    case "DOMAIN_MIN":
                        for (int c = 0; c < 3; c++) min[c] = Float.parseFloat(t[1 + c]);
                        continue;
                    case "DOMAIN_MAX":
                        for (int c = 0; c < 3; c++) max[c] = Float.parseFloat(t[1 + c]);
                        continue;
                    case "LUT_3D_INPUT_RANGE":
                        float lo = Float.parseFloat(t[1]), hi = Float.parseFloat(t[2]);
                        min = new float[]{lo, lo, lo};
                        max = new float[]{hi, hi, hi};
                        continue;
                    default:
                        break;
                }
                if (!Character.isDigit(head.charAt(0)) && head.charAt(0) != '-' && head.charAt(0) != '.' && head.charAt(0) != '+')
                    continue; // another keyword (LUT_1D_INPUT_RANGE, comments some tools write without '#')
                if (values == null || t.length < 3 || k + 3 > values.length) return null;
                for (int c = 0; c < 3; c++) {
                    float v = Float.parseFloat(t[c]);
                    if (!Float.isFinite(v)) return null;
                    values[k++] = v;
                }
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                return null;
            }
        }
        if (values == null || k != values.length) return null;
        for (int c = 0; c < 3; c++) if (!(max[c] > min[c])) return null;
        return new FilmLut(size, values, min, max, title);
    }

    /** A 3D RGB16F texture of the LUT (linear filtering, clamped); the caller deletes it (glDeleteTextures). */
    public int upload() {
        int[] id = new int[1];
        GLES30.glGenTextures(1, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, id[0]);
        FloatBuffer data = ByteBuffer.allocateDirect(rgb.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        data.put(rgb).position(0);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, data);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0);
        return id[0];
    }

    /** The LUT's output at a display value (trilinear, as the shader samples it): the host checks' reference. */
    public float[] sample(float r, float g, float b) {
        float[] in = {r, g, b}, out = new float[3];
        float[] p = new float[3];
        int[] i0 = new int[3];
        for (int c = 0; c < 3; c++) {
            float v = Math.max(0f, Math.min(1f, (Math.max(0f, Math.min(1f, in[c])) - domainMin[c]) / (domainMax[c] - domainMin[c])));
            float x = v * (size - 1);
            i0[c] = Math.min(size - 2, (int) Math.floor(x));
            p[c] = x - i0[c];
        }
        for (int corner = 0; corner < 8; corner++) {
            int dr = corner & 1, dg = (corner >> 1) & 1, db = corner >> 2;
            float w = (dr == 1 ? p[0] : 1 - p[0]) * (dg == 1 ? p[1] : 1 - p[1]) * (db == 1 ? p[2] : 1 - p[2]);
            int idx = (((i0[2] + db) * size + (i0[1] + dg)) * size + (i0[0] + dr)) * 3;
            for (int c = 0; c < 3; c++) out[c] += w * rgb[idx + c];
        }
        return out;
    }

    /** {@link #sample} without allocations (the settings' previews run it per pixel). */
    public void sampleInto(float r, float g, float b, float[] out) {
        float pr, pg, pb;
        int ir, ig, ib;
        float x = Math.max(0f, Math.min(1f, (Math.max(0f, Math.min(1f, r)) - domainMin[0]) / (domainMax[0] - domainMin[0]))) * (size - 1);
        ir = Math.min(size - 2, (int) x);
        pr = x - ir;
        x = Math.max(0f, Math.min(1f, (Math.max(0f, Math.min(1f, g)) - domainMin[1]) / (domainMax[1] - domainMin[1]))) * (size - 1);
        ig = Math.min(size - 2, (int) x);
        pg = x - ig;
        x = Math.max(0f, Math.min(1f, (Math.max(0f, Math.min(1f, b)) - domainMin[2]) / (domainMax[2] - domainMin[2]))) * (size - 1);
        ib = Math.min(size - 2, (int) x);
        pb = x - ib;
        out[0] = out[1] = out[2] = 0f;
        for (int corner = 0; corner < 8; corner++) {
            int dr = corner & 1, dg = (corner >> 1) & 1, db = corner >> 2;
            float w = (dr == 1 ? pr : 1 - pr) * (dg == 1 ? pg : 1 - pg) * (db == 1 ? pb : 1 - pb);
            int idx = (((ib + db) * size + (ig + dg)) * size + (ir + dr)) * 3;
            out[0] += w * rgb[idx];
            out[1] += w * rgb[idx + 1];
            out[2] += w * rgb[idx + 2];
        }
    }

    /** A LUT by its stored value (one of our looks or a file of Download/SCAMERA/LUT), outside the pipeline's cache; null: none. */
    @Nullable
    public static FilmLut load(android.content.Context context, String name) {
        if (name == null || name.isEmpty()) return null;
        if (FilmLooks.isBuiltIn(name)) return FilmLooks.lut(name);
        try (InputStream in = com.particlesdevs.photoncamera.util.ScameraFolder.LUTS.open(context, name)) {
            return in != null ? parse(in) : null;
        } catch (IOException | RuntimeException e) {
            com.particlesdevs.photoncamera.util.Log.w("FilmLut", "cannot read " + name + ": " + e);
            return null;
        }
    }

    // ---- the chosen LUT, parsed once per file (the pipeline asks for it per shot)
    private static String cachedName;
    private static FilmLut cached;

    /** The LUT chosen in the settings, or null (none, missing, unreadable); one of our looks (FilmLooks) is built here. */
    @Nullable
    public static synchronized FilmLut chosen(android.content.Context context, String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.equals(cachedName)) return cached;
        FilmLut lut = null;
        if (FilmLooks.isBuiltIn(name)) {
            lut = FilmLooks.lut(name);
        } else try (InputStream in = com.particlesdevs.photoncamera.util.ScameraFolder.LUTS.open(context, name)) {
            if (in != null) lut = parse(in);
        } catch (IOException | RuntimeException e) {
            com.particlesdevs.photoncamera.util.Log.w("FilmLut", "cannot read " + name + ": " + e);
        }
        cachedName = name;
        cached = lut;
        return lut;
    }

    /** Forget the parsed file (a new import of the same name). */
    public static synchronized void forget() {
        cachedName = null;
        cached = null;
    }
}
