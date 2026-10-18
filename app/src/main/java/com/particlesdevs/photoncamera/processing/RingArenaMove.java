package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * P235 (speed-ux B3): the ring frames of a FULLRES ZSL shot moved into the shot's own arena while its stills arrive. A 50 MP
 * shot whose stills did not all fit the free slots of the ring's memfd had its burst in two places (ring views, stills beside
 * them), and ScamHybridBurst.moveIntoArena copied the whole burst into one arena before the worker could start: «HEX CLIENT PREP
 * raw_write=606» on the owner's X7 Ultra, 903 ms on the test phone, on the critical path. Now the arena is made at the press
 * (CaptureController), the stills are written straight into it, and this mover copies the ring frames in on its own thread
 * meanwhile, one at a time, each ring view freed right after its copy (the ring is released at the press, so its pages go back
 * at once: the peak is one frame more, as moveIntoArena's). Same bytes, same frame order; only the frames' slots in the memfd
 * differ, and the worker reads each frame at its own page (NCH v12 header): the merge input is byte-identical.
 * <p>
 * A frame that could not be moved (closed, another size, the arena full or released) keeps its ring view; the merge's
 * moveIntoArena then moves the burst as before.
 */
public final class RingArenaMove {
    /** Where the frames go: a copy of {@code bytes} of {@code src} into the next slot (null: none), and the old buffer's release. */
    public interface Target {
        ByteBuffer copy(ByteBuffer src, int bytes);
        void free(ByteBuffer old);
    }

    private final List<ImageFrame> frames;
    private final Target target;
    private final long frameBytes;
    private final Thread thread;
    private volatile boolean cancelled;
    private volatile int moved;
    private volatile long elapsedMs = -1;

    private RingArenaMove(List<ImageFrame> frames, Target target, long frameBytes) {
        this.frames = new ArrayList<>(frames);
        this.target = target;
        this.frameBytes = frameBytes;
        this.thread = new Thread(this::run, "scam-ring-arena");
    }

    /** Starts moving {@code frames} (frames of {@code frameBytes} each) into {@code arena}; null when there is nothing to move. */
    public static RingArenaMove start(List<ImageFrame> frames, com.particlesdevs.photoncamera.util.ShotArena arena, long frameBytes) {
        if (arena == null) return null;
        return start(frames, new Target() {
            @Override public ByteBuffer copy(ByteBuffer src, int bytes) { return arena.copy(src, 0, bytes); }
            @Override public void free(ByteBuffer old) { com.particlesdevs.photoncamera.util.Allocator.free(old); }
        }, frameBytes);
    }

    /** As {@link #start(List, com.particlesdevs.photoncamera.util.ShotArena, long)} with any target (the unit test's heap arena). */
    public static RingArenaMove start(List<ImageFrame> frames, Target target, long frameBytes) {
        if (frames == null || frames.isEmpty() || target == null || frameBytes <= 0 || frameBytes > Integer.MAX_VALUE) return null;
        final RingArenaMove move = new RingArenaMove(frames, target, frameBytes);
        move.thread.start();
        return move;
    }

    private void run() {
        final long start = System.nanoTime();
        int n = 0;
        try {
            for (ImageFrame f : frames) {
                if (cancelled) break;
                // the frame's own lock: ImageFrame.close (a failed series discarding the burst) waits for a copy under way
                synchronized (f) {
                    if (cancelled) break;
                    final ByteBuffer old = f.buffer;
                    if (old == null || old.capacity() != frameBytes) continue;
                    final ByteBuffer view = target.copy(old, (int) frameBytes);
                    if (view == null) break; // the arena is full or released: the rest stay where they are
                    view.order(old.order());
                    view.position(old.position());
                    f.buffer = view;
                    f.zeroCopy = false;
                    target.free(old);
                    n++;
                    moved = n;
                }
            }
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "ring frames into the shot arena: " + e);
        } finally {
            elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        }
    }

    /** Waits for the move to end; the frames moved. */
    public int await() {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return moved;
    }

    /** Stops after the frame under way and waits (the series failed: its frames are discarded next). */
    public void cancel() {
        cancelled = true;
        await();
    }

    public int moved() { return moved; }
    public int frames() { return frames.size(); }
    /** Time the move took (-1 while it runs). */
    public long elapsedMs() { return elapsedMs; }

    @Override public String toString() {
        return moved + "/" + frames.size() + " ring frames moved in " + elapsedMs + " ms";
    }
}
