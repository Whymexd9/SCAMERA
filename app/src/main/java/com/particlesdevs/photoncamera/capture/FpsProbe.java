package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Range;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * P266 «Проверить частоты кадров» (owner, 2026-10-19, a per-lens setup to copy): the module's sensor page asks for it; the next
 * session of that module requests each rate as [f, f] for about a second and measures what the camera delivers (the median
 * interval of the frames' sensor timestamps) and the sensor mode index it ran; the camera's declared fixed rates and a few
 * common ones (a rate of its own may pick an undocumented mode). The result is shown and kept under the button.
 */
public final class FpsProbe {
    static final String PREFS = "scam_fps_probe";
    static final String REQUEST = "request";
    static final long SETTLE_NS = 400_000_000L, MEASURE_NS = 900_000_000L;
    static final int[] COMMON = {15, 24, 30, 60, 90, 120, 240};

    private final int[] rates;
    private final StringBuilder out = new StringBuilder();
    private int index = -1;
    private long startNs, lastTs = Long.MIN_VALUE;
    private final List<Long> deltas = new ArrayList<>();
    private int modeAtEnd = Integer.MIN_VALUE;

    FpsProbe(int[] rates) {
        this.rates = rates;
    }

    /** The rates to try: the camera's fixed ranges' rates and the common ones up to its fastest listed rate x 2. */
    static int[] candidates(@Nullable Range<Integer>[] available) {
        TreeSet<Integer> set = new TreeSet<>();
        int max = 30;
        if (available != null) for (Range<Integer> r : available) {
            if (r == null) continue;
            max = Math.max(max, r.getUpper());
            if (r.getLower().equals(r.getUpper())) set.add(r.getUpper());
        }
        for (int c : COMMON) if (c <= 2 * max) set.add(c);
        int[] out = new int[set.size()];
        int i = 0;
        for (int v : set) out[i++] = v;
        return out;
    }

    /** The rate of a run of frame intervals: the median's reciprocal, 0 without one. */
    static double fps(List<Long> deltasNs) {
        if (deltasNs.isEmpty()) return 0;
        List<Long> d = new ArrayList<>(deltasNs);
        java.util.Collections.sort(d);
        final long m = d.get(d.size() / 2);
        return m > 0 ? 1e9 / m : 0;
    }

    /** The rate to request now, or null when the probe is done (then {@link #result()}). */
    @Nullable
    Integer current() {
        return index >= 0 && index < rates.length ? rates[index] : null;
    }

    /** Starts the next rate at {@code nowNs}; false when every rate was tried. */
    boolean next(long nowNs) {
        index++;
        startNs = nowNs;
        lastTs = Long.MIN_VALUE;
        deltas.clear();
        modeAtEnd = Integer.MIN_VALUE;
        return index < rates.length;
    }

    /** One preview frame: true when the current rate's measurement is complete (then {@link #next}). */
    boolean frame(long nowNs, long sensorTs, int modeIndex) {
        if (current() == null) return false;
        if (nowNs - startNs < SETTLE_NS) return false;
        if (lastTs != Long.MIN_VALUE && sensorTs > lastTs) deltas.add(sensorTs - lastTs);
        lastTs = sensorTs;
        modeAtEnd = modeIndex;
        if (nowNs - startNs < SETTLE_NS + MEASURE_NS) return false;
        final double got = fps(deltas);
        if (out.length() > 0) out.append(", ");
        out.append(rates[index]).append(" → ").append(got > 0 ? String.valueOf(Math.round(got)) : "—");
        if (modeAtEnd != Integer.MIN_VALUE) out.append(" (").append(modeAtEnd).append(')');
        return true;
    }

    /** "24 → 24 (2), 60 → 60 (2), 120 → 60 (2)": asked → delivered fps (sensor mode index). */
    String result() {
        return out.toString();
    }

    // ---- the request and the kept result, per module slot

    @Nullable
    private static SharedPreferences prefs(@Nullable Context c) {
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void request(Context c, String slot) {
        SharedPreferences p = prefs(c);
        if (p != null) p.edit().putString(REQUEST, slot).apply();
    }

    /** Whether the probe was asked for this module (and takes the request). */
    static boolean take(@Nullable Context c, @Nullable String slot) {
        SharedPreferences p = prefs(c);
        if (p == null || slot == null || !slot.equals(p.getString(REQUEST, null))) return false;
        p.edit().remove(REQUEST).apply();
        return true;
    }

    static void keep(@Nullable Context c, String slot, String result) {
        SharedPreferences p = prefs(c);
        if (p != null) p.edit().putString("result_" + slot, result).apply();
    }

    @Nullable
    public static String lastResult(Context c, String slot) {
        SharedPreferences p = prefs(c);
        return p == null ? null : p.getString("result_" + slot, null);
    }

    public static boolean requested(Context c, String slot) {
        SharedPreferences p = prefs(c);
        return p != null && slot.equals(p.getString(REQUEST, null));
    }
}
