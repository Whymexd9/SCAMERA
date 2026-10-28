package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.Log;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P386: the black of a RAW stream proven to have come without its pedestal, per stream - MosaicStream's key (the physical camera,
 * the RAW size, the module's vendor requests and sensor mode, MosaicBlockStore's key style) - for the app's lifetime only.
 *
 * <p>Only a frame with deep shade proves a lost pedestal (Parameters.refineBlackLevel, PedestalFloor: non-zero samples far under
 * the black). A frame of the same stream without deep shade - a sunlit facade, the sky - proves nothing by itself: its darkest
 * blocks still hold light, so its floor is only an upper bound of the black (the reviewer's probes: 18.8 / 39.6 DN for a true 0),
 * and it lost that much of every channel - the teal of P386 again. Such a frame takes the stream's remembered floor, as long as its
 * own darkest block means stay below {@link #BACK} x the declared black (a frame with its pedestal never sits there: the read noise
 * of a 64-sample mean, a 64 -> 60 drift and zero padding do not reach it). A frame whose darkest block means come back to that
 * clears the stream's floor (P114: the X8U's pedestal came back after a restart of the phone).
 *
 * <p>Not persisted: the loss is a state of the HAL (P114: gone after a restart), and a stale floor must not outlive the process.
 * The RAW viewfinder's PedestalFloor proves the loss on the session's own frames and seeds the floor of the session's stream.
 */
public final class PedestalMemory {
    private PedestalMemory() {}

    private static final String TAG = "PedestalMemory";
    /** A floor at or below this many DN of a 10-bit range (scaled by white / 1023) is the black of a RAW without its pedestal. */
    public static final float NEAR_ZERO_10BIT = 3f;
    /** The darkest block means at or above this share of the declared black: the pedestal is there. */
    public static final float BACK = 0.75f;

    private static final class Entry {
        final float floor, white;
        final String source;
        Entry(float floor, float white, String source) { this.floor = floor; this.white = white; this.source = source; }
        float at(float w) { return floor * (w + 1f) / (white + 1f); }
    }

    private static final ConcurrentHashMap<String, Entry> STREAMS = new ConcurrentHashMap<>();

    /** The level (DN at {@code white}) at or below which a floor is the black itself, not an upper bound of it. */
    public static float nearZero(float white) {
        return NEAR_ZERO_10BIT * Math.max(1f, white / 1023f);
    }

    /** A key that may be remembered (MosaicBlockStore.storable: not empty, not "off"). */
    public static boolean storable(String key) {
        return MosaicBlockStore.storable(key);
    }

    /** The remembered floor of stream {@code key} in DN at the white level {@code white}; NaN when none. */
    public static float floor(String key, float white) {
        if (!storable(key)) return Float.NaN;
        final Entry e = STREAMS.get(key);
        return e == null ? Float.NaN : e.at(white);
    }

    /** Where the remembered floor of {@code key} came from ("" when none). */
    public static String source(String key) {
        final Entry e = storable(key) ? STREAMS.get(key) : null;
        return e == null ? "" : e.source;
    }

    /**
     * A frame of stream {@code key} proved its pedestal lost, its floor {@code floor} DN at white {@code white}. A floor near zero
     * ({@link #nearZero}) is the black itself and replaces the remembered one; a higher one is only an upper bound of it and lowers
     * the remembered floor at most.
     */
    public static void remember(String key, float floor, float white, String source) {
        if (!storable(key) || !(floor >= 0f) || !(white > 0f)) return;
        final boolean exact = floor <= nearZero(white);
        final Entry[] before = new Entry[1];
        final Entry now = STREAMS.compute(key, (k, e) -> {
            before[0] = e;
            if (e == null || exact || floor < e.at(white)) return new Entry(floor, white, source);
            return e;
        });
        final Entry was = before[0];
        if (was == null || Math.abs(was.at(white) - now.at(white)) > nearZero(white))
            Log.i(TAG, String.format(Locale.ROOT, "stream %s came without its black pedestal: floor %.2f DN of white %.0f (%s)%s",
                    key, now.floor, now.white, now.source, was == null ? "" : String.format(Locale.ROOT, ", was %.2f (%s)",
                            was.at(white), was.source)));
    }

    /** A frame of stream {@code key} showed its pedestal: the remembered floor, if any, is dropped. */
    public static void clear(String key, String why) {
        if (!storable(key)) return;
        final Entry e = STREAMS.remove(key);
        if (e != null) Log.i(TAG, "stream " + key + ": the black pedestal is back (" + why + "), floor " + e.floor
                + " (" + e.source + ") dropped");
    }

    /** Tests. */
    static void reset() { STREAMS.clear(); }
}
