package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * P35: the colour block of a module's RAW stream, remembered across shots and app restarts.
 *
 * <p>The key is MosaicStream's: the physical sensor, the RAW size, the module's vendor request set and its sensor mode, so a
 * change of mode or requests is a new key and an old answer never applies to another stream. The first confident detection
 * of a key is stored (block 1, 2 or 4); from then on the hybrid, SCAM HDR and the viewfinder start from it without running the
 * detector on the shot. The detector keeps running in the background on the viewfinder frames of each session: an
 * unconfident answer never changes the stored block, a confident disagreement replaces it only after {@link #REPLACE_VOTES}
 * agreeing confident answers in a row (the switch is logged).
 */
public final class MosaicBlockStore {
    /** Persistent map key -> block; 0 = nothing stored. */
    public interface Backing {
        int get(String key);
        void put(String key, int block);
    }

    static final int REPLACE_VOTES = 3;
    private static final String TAG = "MosaicBlockStore";
    private static final String PREFS = "mosaic_blocks";

    private final Backing backing;
    /** key -> {block that disagrees with the stored one, agreeing confident answers in a row}. */
    private final Map<String, int[]> disagreement = new HashMap<>();

    MosaicBlockStore(Backing backing) {
        this.backing = backing;
    }

    /** A key that may be stored: measured streams only (MosaicStream uses "off" when the measurement is disabled). */
    static boolean storable(String key) {
        return key != null && !key.isEmpty() && !"off".equals(key);
    }

    /** The block stored for {@code key}: 1, 2 or 4, or 0 when nothing is known. */
    public synchronized int stored(String key) {
        if (!storable(key)) return 0;
        int b = backing.get(key);
        return b == 1 || b == 2 || b == 4 ? b : 0;
    }

    /**
     * One detection of the stream {@code key}. Returns the block stored afterwards (0 = none). Unconfident or invalid answers
     * change nothing.
     */
    public synchronized int observe(String key, boolean confident, int block, String source) {
        if (!storable(key)) return 0;
        int current = stored(key);
        if (!confident || (block != 1 && block != 2 && block != 4)) return current;
        if (current == 0) {
            backing.put(key, block);
            disagreement.remove(key);
            Log.i(TAG, "block " + block + " stored for " + key + " (first confident answer, " + source + ")");
            return block;
        }
        if (current == block) {
            disagreement.remove(key);
            return current;
        }
        int[] d = disagreement.get(key);
        if (d == null || d[0] != block) d = new int[]{block, 0};
        d[1]++;
        disagreement.put(key, d);
        if (d[1] < REPLACE_VOTES) {
            Log.i(TAG, "block " + block + " disagrees with the stored " + current + " for " + key + " (" + d[1] + "/" + REPLACE_VOTES
                    + ", " + source + ")");
            return current;
        }
        backing.put(key, block);
        disagreement.remove(key);
        Log.i(TAG, "block of " + key + " replaced: " + current + " -> " + block + " after " + REPLACE_VOTES
                + " agreeing confident answers (" + source + ")");
        return block;
    }

    // ---- the app's instance -------------------------------------------------------------------------------------------

    private static volatile MosaicBlockStore instance;
    private static volatile String shotKey = "";

    /** The app's store (SharedPreferences "mosaic_blocks"; in memory when no context is available). */
    public static MosaicBlockStore get() {
        MosaicBlockStore s = instance;
        if (s != null) return s;
        synchronized (MosaicBlockStore.class) {
            if (instance == null) instance = new MosaicBlockStore(createBacking());
            return instance;
        }
    }

    private static Backing createBacking() {
        android.content.Context context = null;
        try {
            context = com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext();
        } catch (RuntimeException ignored) { }
        if (context == null) return memoryBacking();
        final android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        return new Backing() {
            @Override public int get(String key) { return prefs.getInt(key, 0); }
            @Override public void put(String key, int block) { prefs.edit().putInt(key, block).apply(); }
        };
    }

    static Backing memoryBacking() {
        final Map<String, Integer> map = new HashMap<>();
        return new Backing() {
            @Override public int get(String key) { Integer v = map.get(key); return v == null ? 0 : v; }
            @Override public void put(String key, int block) { map.put(key, block); }
        };
    }

    /** Tests: replace the app's store. */
    static void setInstance(MosaicBlockStore store) { instance = store; }

    /** The stream key of the shot being processed (set on the processing thread before the shot's processing starts). */
    public static void setShotKey(String key) { shotKey = key == null ? "" : key; }
    public static String shotKey() { return shotKey; }

    /**
     * The block for the shot being processed without the detector: the stored block of its stream, else the block of a
     * declared mosaic sensor mode ({@code declared} 2 / 4), else 0 (measure it).
     */
    public static int blockForShot(int declared) {
        int s = get().stored(shotKey);
        if (s != 0) return s;
        return declared == 2 || declared == 4 ? declared : 0;
    }

    /** A detection made on the shot (no stored block): it seeds the store. */
    public static void observeShot(MosaicBlockDetector.Result r, String source) {
        if (r != null) get().observe(shotKey, r.confident, r.block, source);
    }
}
