package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * P212e (W1 = M2): what an OPPO camera's plain session reports and its native FULLRES mode does not - the lens shading map, the
 * ISP's settled neutral and its colour transform - stored per camera and native RAW size (SharedPreferences {@link #PREFS}, with
 * Build.FINGERPRINT: another firmware forgets them), so a native session opened straight from a cold start has them as well.
 * Before, they lived in memory only (OppoNativeFullRes' maps, filled by the plain session of the same process): 20 of 49 sessions
 * of the owner's unit opened straight into FULLRES, and 10 of 22 OPPO FULLRES photos were developed with 'lens shading map 1x1'
 * (log15:219 -> 913, log18:269 -> 1255, log21:250 -> 904, log22:249 -> 913, log23:250 -> 921, log24:238 -> 990, log25:243 -> 960,
 * log26:249 -> 839): the plain map's corner gains are 4.6-4.8, so their corners came out 1.2-2.2 EV dark and colour-shaded.
 * <p>
 * The map is kept as LensShadingMap.copyGainFactors gives it ({R, G even, G odd, B} per point, rows of {@code cols}) - the order
 * Parameters.gainMap, the DNG gain map, the viewfinder and the native meter read - so no hidden LensShadingMap constructor is
 * needed. The ISP interpolates its tables by the light, so up to {@link #BINS} maps are kept, each at the light (ln(r / b) of the
 * plain neutral) it was taken under, at least {@link #BIN_LN} apart; a lookup takes the one nearest to the light now (a daylight
 * map under tungsten is off by at most about 3.7 % at the corners on the PHY110 probe, far less than no map). Writes are throttled
 * to one every {@link #STORE_EVERY_MS} per camera, and only of what changed. scam_dev.txt "oppo_plain_store 0": none of it is
 * read or written (the P192 in-memory maps only).
 */
public final class OppoPlainState {
    private OppoPlainState() {}

    static final String PREFS = "oppo_plain_state";
    static final long STORE_EVERY_MS = 5_000L, NEUTRAL_REFRESH_MS = 60_000L;
    /** Maps kept per camera, the light (ln(r / b)) between them, the relative change of a gain that rewrites a bin. */
    static final int BINS = 3;
    static final float BIN_LN = 0.3f, MAP_CHANGE = 0.005f, NEUTRAL_CHANGE_LN = 0.01f;
    private static final int MAX_POINTS = 128;

    /** A stored shading map: {@code cols} x {@code rows} points of {R, G even, G odd, B} gains, the light it was taken under. */
    public static final class Shading {
        public final int cols, rows;
        /** LensShadingMap.copyGainFactors order; never modified (callers copy before they crop). */
        public final float[] gains;
        final float light;
        final long wallMs;

        Shading(int cols, int rows, float[] gains, float light, long wallMs) {
            this.cols = cols;
            this.rows = rows;
            this.gains = gains;
            this.light = light;
            this.wallMs = wallMs;
        }

        @Override public String toString() {
            return cols + "x" + rows;
        }
    }

    /** The stored plain neutral (r / g, b / g), when it was stored (wall clock), the ISP's transform (row-major, null: none). */
    static final class Neutral {
        final float r, b;
        final long wallMs;
        @Nullable final float[] transform;

        Neutral(float r, float b, long wallMs, @Nullable float[] transform) {
            this.r = r;
            this.b = b;
            this.wallMs = wallMs;
            this.transform = transform;
        }
    }

    private static final class Entry {
        Neutral neutral;
        final ArrayList<Shading> maps = new ArrayList<>();
        /** elapsedRealtime of the last write (0: none in this process). */
        long writtenMs;
    }

    private static final Map<String, Entry> CACHE = new HashMap<>(); // guarded by OppoPlainState.class
    private static volatile SharedPreferences store;
    private static volatile String fingerprint = android.os.Build.FINGERPRINT;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /** scam_dev.txt "oppo_plain_store" (default on). */
    public static boolean on() {
        return PreferenceKeys.scamDevSwitch("oppo_plain_store", true);
    }

    static String key(String camera, int width, int height) {
        return camera + '/' + width + 'x' + height;
    }

    /** True when a plain result of the camera may be stored now (the caller then copies its map; elapsedRealtime {@code nowMs}). */
    static synchronized boolean due(@Nullable String camera, int width, int height, long nowMs) {
        if (camera == null || width <= 0 || height <= 0 || !on()) return false;
        final Entry e = entry(key(camera, width, height));
        return e != null && (e.writtenMs == 0 || nowMs - e.writtenMs >= STORE_EVERY_MS || nowMs < e.writtenMs);
    }

    /**
     * A settled plain result of {@code camera} (whose native mode is {@code width} x {@code height}): its neutral (r / g, b / g),
     * transform (row-major 3x3, null: none or identity) and shading map (copyGainFactors order, null: none). What changed is
     * written; true when anything was.
     */
    static synchronized boolean remember(@Nullable String camera, int width, int height, float r, float b, @Nullable float[] transform,
                                         @Nullable float[] gains, int cols, int rows, long nowMs) {
        if (camera == null || !usable(r) || !usable(b)) return false;
        final String key = key(camera, width, height);
        final Entry e = entry(key);
        final SharedPreferences s = store();
        if (e == null || s == null) return false;
        e.writtenMs = nowMs;
        final long wall = clock.getAsLong();
        final SharedPreferences.Editor edit = s.edit();
        boolean wrote = false;
        final Neutral last = e.neutral;
        final float[] t = transform != null && transform.length == 9 && finite(transform) ? transform.clone() : null;
        if (last == null || Math.abs(Math.log(last.r / r)) > NEUTRAL_CHANGE_LN || Math.abs(Math.log(last.b / b)) > NEUTRAL_CHANGE_LN
                || wall - last.wallMs >= NEUTRAL_REFRESH_MS || (t != null) != (last.transform != null)
                || t != null && maxDiff(t, last.transform) > 1e-3f) {
            e.neutral = new Neutral(r, b, wall, t);
            edit.putString(key + "/n", fingerprint + ";" + String.format(Locale.ROOT, "%.5f;%.5f;%d;", r, b, wall) + floats(t));
            wrote = true;
        }
        if (gains != null && cols > 1 && rows > 1 && cols <= MAX_POINTS && rows <= MAX_POINTS && gains.length >= 4 * cols * rows
                && finite(gains)) {
            final float light = (float) Math.log(r / b);
            int bin = -1;
            float nearest = Float.MAX_VALUE;
            for (int i = 0; i < e.maps.size(); i++) {
                final float d = Math.abs(e.maps.get(i).light - light);
                if (d < nearest) { nearest = d; bin = i; }
            }
            final float[] copy = java.util.Arrays.copyOf(gains, 4 * cols * rows);
            int slot = -1;
            if (bin >= 0 && nearest < BIN_LN) {
                final Shading old = e.maps.get(bin);
                if (old.cols != cols || old.rows != rows || relDiff(copy, old.gains) > MAP_CHANGE) slot = bin;
            } else if (e.maps.size() < BINS) {
                slot = e.maps.size();
                e.maps.add(null);
            } else {
                slot = 0; // all bins taken: the oldest gives way
                for (int i = 1; i < e.maps.size(); i++) if (e.maps.get(i).wallMs < e.maps.get(slot).wallMs) slot = i;
            }
            if (slot >= 0) {
                e.maps.set(slot, new Shading(cols, rows, copy, light, wall));
                edit.putString(key + "/m" + slot, fingerprint + ";" + String.format(Locale.ROOT, "%.4f;%d;%d;%d;", light, cols, rows, wall)
                        + encode(copy));
                wrote = true;
            }
        }
        if (wrote) edit.apply();
        return wrote;
    }

    /** The stored map of the camera's native size taken under the light nearest to {@code neutral} (r, g, b; null: the newest). */
    @Nullable
    public static synchronized Shading shading(@Nullable String camera, int width, int height, @Nullable float[] neutral) {
        if (camera == null || !on()) return null;
        final Entry e = entry(key(camera, width, height));
        if (e == null || e.maps.isEmpty()) return null;
        final float light = neutral != null && neutral.length >= 3 && usable(neutral[0]) && usable(neutral[2])
                ? (float) Math.log(neutral[0] / neutral[2]) : Float.NaN;
        Shading best = null;
        for (Shading m : e.maps) {
            if (best == null) { best = m; continue; }
            if (Float.isNaN(light) ? m.wallMs > best.wallMs : Math.abs(m.light - light) < Math.abs(best.light - light)) best = m;
        }
        return best;
    }

    /** The stored plain neutral {r / g, 1, b / g} of the camera's native size; null: none. */
    @Nullable
    public static synchronized float[] neutral(@Nullable String camera, int width, int height) {
        if (camera == null || !on()) return null;
        final Entry e = entry(key(camera, width, height));
        return e == null || e.neutral == null ? null : new float[]{e.neutral.r, 1f, e.neutral.b};
    }

    /** The stored ISP transform (row-major 3x3) of the camera's native size; null: none. */
    @Nullable
    static synchronized float[] transform(@Nullable String camera, int width, int height) {
        if (camera == null || !on()) return null;
        final Entry e = entry(key(camera, width, height));
        return e == null || e.neutral == null || e.neutral.transform == null ? null : e.neutral.transform.clone();
    }

    /** The cached entry of a key, loaded once from the store (lines of another firmware or unreadable ones are dropped). */
    @Nullable
    private static Entry entry(String key) {
        Entry e = CACHE.get(key);
        if (e != null) return e;
        final SharedPreferences s = store();
        if (s == null) return null;
        e = new Entry();
        final SharedPreferences.Editor drop = s.edit();
        boolean dropped = false;
        try {
            final String n = s.getString(key + "/n", null);
            if (n != null) {
                final String[] f = n.split(";", -1);
                if (f.length == 5 && f[0].equals(String.valueOf(fingerprint))) {
                    final float r = Float.parseFloat(f[1]), b = Float.parseFloat(f[2]);
                    final float[] t = f[4].isEmpty() ? null : parseFloats(f[4], 9);
                    if (usable(r) && usable(b)) e.neutral = new Neutral(r, b, Long.parseLong(f[3]), t);
                } else { drop.remove(key + "/n"); dropped = true; }
            }
        } catch (RuntimeException bad) {
            drop.remove(key + "/n");
            dropped = true;
        }
        for (int i = 0; i < BINS; i++) {
            final String name = key + "/m" + i;
            try {
                final String m = s.getString(name, null);
                if (m == null) continue;
                final String[] f = m.split(";", -1);
                final int cols = f.length == 6 ? Integer.parseInt(f[2]) : 0, rows = f.length == 6 ? Integer.parseInt(f[3]) : 0;
                final float[] g = f.length == 6 && f[0].equals(String.valueOf(fingerprint)) && cols > 1 && rows > 1
                        && cols <= MAX_POINTS && rows <= MAX_POINTS ? decode(f[5]) : null;
                if (g == null || g.length != 4 * cols * rows || !finite(g)) { drop.remove(name); dropped = true; continue; }
                e.maps.add(new Shading(cols, rows, g, Float.parseFloat(f[1]), Long.parseLong(f[4])));
            } catch (RuntimeException bad) {
                drop.remove(name);
                dropped = true;
            }
        }
        if (dropped) {
            // the bins are renumbered in the order they were read: rewrite them so their slots stay those of the cache
            for (int i = 0; i < BINS; i++) drop.remove(key + "/m" + i);
            for (int i = 0; i < e.maps.size(); i++) {
                final Shading m = e.maps.get(i);
                drop.putString(key + "/m" + i, fingerprint + ";" + String.format(Locale.ROOT, "%.4f;%d;%d;%d;", m.light, m.cols, m.rows, m.wallMs)
                        + encode(m.gains));
            }
            drop.apply();
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "OPPO plain state " + key + ": stored lines of another firmware or unreadable ones dropped");
        }
        CACHE.put(key, e);
        return e;
    }

    private static boolean usable(float v) {
        return v > 0.05f && v < 20f;
    }

    private static boolean finite(float[] v) {
        for (float x : v) if (Float.isNaN(x) || Float.isInfinite(x)) return false;
        return true;
    }

    private static float maxDiff(float[] a, @Nullable float[] b) {
        if (b == null || a.length != b.length) return Float.MAX_VALUE;
        float d = 0f;
        for (int i = 0; i < a.length; i++) d = Math.max(d, Math.abs(a[i] - b[i]));
        return d;
    }

    private static float relDiff(float[] a, float[] b) {
        if (a.length != b.length) return Float.MAX_VALUE;
        float d = 0f;
        for (int i = 0; i < a.length; i++) d = Math.max(d, Math.abs(a[i] - b[i]) / Math.max(1e-3f, Math.abs(b[i])));
        return d;
    }

    private static String floats(@Nullable float[] v) {
        if (v == null) return "";
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) b.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "%.6f", v[i]));
        return b.toString();
    }

    @Nullable
    private static float[] parseFloats(String s, int count) {
        final String[] f = s.split(",");
        if (f.length != count) return null;
        final float[] v = new float[count];
        for (int i = 0; i < count; i++) v[i] = Float.parseFloat(f[i]);
        return finite(v) ? v : null;
    }

    /** float32 little endian, Base64 (884 floats of a 17 x 13 map: about 4.7 KB a bin). */
    static String encode(float[] v) {
        final ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) b.putFloat(x);
        return Base64.getEncoder().encodeToString(b.array());
    }

    @Nullable
    static float[] decode(String s) {
        final byte[] bytes = Base64.getDecoder().decode(s);
        if (bytes.length % 4 != 0) return null;
        final ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
        return v;
    }

    @Nullable
    private static SharedPreferences store() {
        SharedPreferences s = store;
        if (s != null) return s;
        final Context context = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
        if (context == null) return null;
        try {
            s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (RuntimeException e) {
            return null;
        }
        store = s;
        return s;
    }

    /** Tests: nothing cached (a new process), the app's store, firmware and clock. */
    static synchronized void resetForTest() {
        CACHE.clear();
        store = null;
        fingerprint = android.os.Build.FINGERPRINT;
        clock = System::currentTimeMillis;
    }

    /** Tests: a store, firmware and wall clock in place of the app's. */
    static synchronized void useStoreForTest(SharedPreferences prefs, String firmware, LongSupplier wallMs) {
        CACHE.clear();
        store = prefs;
        fingerprint = firmware;
        clock = wallMs;
    }
}
