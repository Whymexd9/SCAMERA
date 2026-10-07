package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Colour block of the current RAW stream (P13), shared by the capture thread (which measures it) and the viewfinder.
 *
 * <p>Per session the first frames are measured with {@link MosaicBlockDetector}; three confident results that agree fix the
 * block for the session. The result is remembered per module and its vendor requests (the key the capture controller gives),
 * so the next session of the same module starts with the right preview instead of a purple one for the first frames.
 * P35: the block is also persisted per key ({@link MosaicBlockStore}): a session starts from the stored block (the first
 * viewfinder frame is already right, also after an app restart), and the session's measurements are the background check of
 * the stored block (a confident disagreement replaces it after three agreeing answers, never an unconfident one).
 */
public final class MosaicStream {
    private MosaicStream() {}

    private static final ConcurrentHashMap<String, Integer> KNOWN = new ConcurrentHashMap<>();
    private static volatile int block;          // 0 = not measured yet
    private static volatile String key = "";
    private static int votes, voteBlock, measured;

    /** A new session of the module / request set {@code streamKey}: the remembered block, if any, applies at once. */
    public static synchronized void startSession(String streamKey) {
        key = streamKey == null ? "" : streamKey;
        int stored = MosaicBlockStore.get().stored(key);
        Integer known = stored != 0 ? Integer.valueOf(stored) : KNOWN.get(key);
        block = known == null ? 0 : known;
        votes = 0; voteBlock = 0; measured = 0;
    }

    /** P35: the key of the current session ("off" when the stream is not measured). */
    public static String key() { return key; }

    /** 0 until measured (or remembered), then 1 / 2 / 4. */
    public static int block() { return block; }

    /** True while this session still wants frames measured. */
    public static synchronized boolean wantsFrame() { return measured < 12 && votes < 3; }

    /** One measurement of this session's stream; returns the block once three agree (0 before). */
    public static synchronized int observe(MosaicBlockDetector.Result r) {
        measured++;
        if (r == null || !r.confident) return block;
        // P35: with a stored block every confident frame checks it (three agreeing disagreements in a row replace it).
        MosaicBlockStore store = MosaicBlockStore.get();
        if (store.stored(key) != 0) {
            int now = store.observe(key, true, r.block, "viewfinder");
            if (now != block && now != 0) {
                Log.i("MosaicStream", "RAW stream colour block " + block + " -> " + now + " for " + key + " (stored block replaced)");
                block = now;
                KNOWN.put(key, now);
            }
        }
        if (r.block == voteBlock) votes++; else { voteBlock = r.block; votes = 1; }
        if (votes >= 3) {
            Integer previous = KNOWN.put(key, voteBlock);
            if (previous == null || previous != voteBlock || block != voteBlock)
                Log.i("MosaicStream", "RAW stream colour block " + voteBlock + " for " + key + " (" + r + ")");
            block = voteBlock;
            if (store.stored(key) == 0) store.observe(key, true, voteBlock, "viewfinder, three agreeing frames");
        }
        return block;
    }

    /** Tests. */
    static synchronized void reset() { KNOWN.clear(); block = 0; key = ""; votes = voteBlock = measured = 0; }
}
