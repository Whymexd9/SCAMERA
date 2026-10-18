package com.particlesdevs.photoncamera.processing;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/**
 * P235 (speed-ux B3): the ring frames of a FULLRES ZSL shot moved into the press arena while the stills are written into the
 * same arena. The merge input must be byte-identical to the burst ScamHybridBurst.moveIntoArena built after the series: every
 * frame's bytes, in the burst's order (the worker reads each frame at its own page, so only the slots may differ).
 */
public class RingArenaMoveTest {
    /** A heap stand-in for ShotArena + Allocator: slots taken in arrival order, frees recorded. */
    static final class HeapArena implements RingArenaMove.Target {
        final ByteBuffer memory;
        final int slotBytes, slots;
        final AtomicInteger next = new AtomicInteger();
        final Map<ByteBuffer, Integer> freed = Collections.synchronizedMap(new IdentityHashMap<>());
        volatile boolean released;

        HeapArena(int slots, int slotBytes) {
            this.slots = slots;
            this.slotBytes = slotBytes;
            memory = ByteBuffer.allocateDirect(slots * slotBytes);
        }

        @Override public ByteBuffer copy(ByteBuffer src, int bytes) {
            if (released || bytes > slotBytes) return null;
            final int slot = next.getAndIncrement();
            if (slot >= slots) return null;
            final ByteBuffer dst;
            synchronized (memory) {
                ByteBuffer d = memory.duplicate();
                d.position(slot * slotBytes).limit(slot * slotBytes + bytes);
                dst = d.slice();
            }
            final ByteBuffer s = src.duplicate();
            s.clear().limit(bytes);
            dst.put(s);
            dst.clear();
            return dst;
        }

        @Override public void free(ByteBuffer old) { freed.merge(old, 1, Integer::sum); }
    }

    private static ByteBuffer pattern(int bytes, int seed) {
        ByteBuffer b = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bytes; i++) b.put((byte) (i * 31 + seed * 7 + (i >> 8)));
        b.clear();
        return b;
    }

    private static ImageFrame frame(ByteBuffer buffer) {
        ImageFrame f = mock(ImageFrame.class);
        f.buffer = buffer;
        f.zeroCopy = true;
        return f;
    }

    /** The bytes the worker reads: each frame of the burst in its order. */
    private static byte[] workerInput(List<ImageFrame> burst) {
        int total = 0;
        for (ImageFrame f : burst) total += f.buffer.capacity();
        byte[] out = new byte[total];
        int at = 0;
        for (ImageFrame f : burst) {
            ByteBuffer b = f.buffer.duplicate();
            b.clear();
            b.get(out, at, b.capacity());
            at += b.capacity();
        }
        return out;
    }

    /** moveIntoArena after the series: every frame copied into a new arena in burst order. */
    private static void moveAfterSeries(List<ImageFrame> burst, HeapArena late, int bytes) {
        for (ImageFrame f : burst) {
            ByteBuffer old = f.buffer;
            ByteBuffer view = late.copy(old, bytes);
            view.order(old.order());
            view.position(old.position());
            f.buffer = view;
            late.free(old);
        }
    }

    @Test public void theMergeInputIsByteIdenticalToTheMoveAfterTheSeries() throws Exception {
        final int bytes = 64 * 1024, ring = 5, stills = 9;
        // the burst as the series leaves it: 5 ring views, then 9 stills (in the order the saver buffers them)
        List<ByteBuffer> ringSource = new ArrayList<>(), stillSource = new ArrayList<>();
        for (int i = 0; i < ring; i++) ringSource.add(pattern(bytes, i));
        for (int i = 0; i < stills; i++) stillSource.add(pattern(bytes, 100 + i));

        // before P235: the stills beyond the ring's slots in native buffers, the whole burst moved after the series
        List<ImageFrame> before = new ArrayList<>();
        for (ByteBuffer b : ringSource) before.add(frame(b));
        for (ByteBuffer b : stillSource) before.add(frame(b));
        moveAfterSeries(before, new HeapArena(ring + stills, bytes), bytes);
        final byte[] reference = workerInput(before);

        // P235: the arena at the press; the stills arrive on their own thread while the ring frames are moved
        final HeapArena press = new HeapArena(ring + stills, bytes);
        List<ImageFrame> ringFrames = new ArrayList<>();
        for (ByteBuffer b : ringSource) ringFrames.add(frame(b));
        final List<ImageFrame> stillFrames = Collections.synchronizedList(new ArrayList<>());
        RingArenaMove move = RingArenaMove.start(ringFrames, press, bytes);
        assertNotNull(move);
        Thread camera = new Thread(() -> {
            for (ByteBuffer b : stillSource) {
                ImageFrame f = frame(press.copy(b, bytes)); // RAW16Saver: the still straight into the arena
                f.zeroCopy = false;
                stillFrames.add(f);
            }
        });
        camera.start();
        assertEquals(ring, move.await());
        camera.join();
        List<ImageFrame> after = new ArrayList<>(ringFrames);
        after.addAll(stillFrames);
        assertArrayEquals("the merge input is byte-identical", reference, workerInput(after));
        for (int i = 0; i < ring; i++) {
            assertFalse(ringFrames.get(i).zeroCopy);
            assertNotSame("the ring frame now lives in the arena", ringSource.get(i), ringFrames.get(i).buffer);
        }
        for (ByteBuffer b : ringSource) assertEquals("each ring view freed once", Integer.valueOf(1), press.freed.get(b));
        assertEquals("one slot per frame", ring + stills, press.next.get());
        assertTrue(move.elapsedMs() >= 0);
    }

    @Test public void aFullOrReleasedArenaLeavesTheRestWhereTheyAre() {
        final int bytes = 4096;
        HeapArena small = new HeapArena(2, bytes);
        List<ImageFrame> ring = new ArrayList<>();
        List<ByteBuffer> source = new ArrayList<>();
        for (int i = 0; i < 4; i++) { source.add(pattern(bytes, i)); ring.add(frame(source.get(i))); }
        RingArenaMove move = RingArenaMove.start(ring, small, bytes);
        assertEquals(2, move.await());
        assertNotSame(source.get(0), ring.get(0).buffer);
        assertSame("no slot left: the ring view stays (moveIntoArena moves it later)", source.get(2), ring.get(2).buffer);
        assertTrue(ring.get(2).zeroCopy);
        assertNull(small.freed.get(source.get(2)));

        HeapArena released = new HeapArena(4, bytes);
        released.released = true;
        List<ImageFrame> other = new ArrayList<>();
        other.add(frame(pattern(bytes, 9)));
        assertEquals(0, RingArenaMove.start(other, released, bytes).await());
    }

    @Test public void closedFramesAndOtherSizesAreSkippedAndCancelStops() {
        final int bytes = 4096;
        HeapArena arena = new HeapArena(8, bytes);
        List<ImageFrame> ring = new ArrayList<>();
        ImageFrame closed = frame(null);
        ImageFrame odd = frame(pattern(bytes / 2, 1));
        ImageFrame good = frame(pattern(bytes, 2));
        ring.add(closed); ring.add(odd); ring.add(good);
        assertEquals(1, RingArenaMove.start(ring, arena, bytes).await());
        assertNull(closed.buffer);
        assertEquals(bytes / 2, odd.buffer.capacity());

        List<ImageFrame> many = new ArrayList<>();
        for (int i = 0; i < 64; i++) many.add(frame(pattern(bytes, i)));
        RingArenaMove move = RingArenaMove.start(many, new HeapArena(64, bytes), bytes);
        move.cancel();
        assertTrue("cancel ends the move", move.elapsedMs() >= 0);
        for (ImageFrame f : many) assertEquals(bytes, f.buffer.capacity()); // moved or not, every frame whole

        assertNull("nothing to move", RingArenaMove.start(new ArrayList<>(), arena, bytes));
        assertNull(RingArenaMove.start(ring, (com.particlesdevs.photoncamera.util.ShotArena) null, bytes));
    }
}
