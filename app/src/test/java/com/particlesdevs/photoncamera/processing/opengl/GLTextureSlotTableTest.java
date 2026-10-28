package com.particlesdevs.photoncamera.processing.opengl;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * processing-gltexture-slot: GLTexture.close() freed the slot its GL NAME indexed, not the slot it took. Once a raw glGenTextures
 * (P369 film grain's LUT, FilmGrain.runReference) took a name in the post context, names ran one ahead of slots: closing noise /
 * h / v freed the next texture's slot, so closeAll never deleted film grain's full-size output, and on the context kept for the
 * process (P353) the leak grew with every shot. A driver that never reuses names indexed the 256-entry table past its end.
 * Replayed here on {@link GLTextureSlots} with a model of the context's names.
 */
public class GLTextureSlotTableTest {
    /** The GL context's texture names: the lowest free one (Adreno), or always a new one (a driver without reuse). */
    static final class Names {
        final boolean monotonic;
        final TreeSet<Integer> live = new TreeSet<>();
        int next = 1;

        Names(boolean monotonic) { this.monotonic = monotonic; }

        int gen() {
            int n;
            if (monotonic) n = next++;
            else {
                n = 1;
                while (live.contains(n)) n++;
            }
            live.add(n);
            return n;
        }

        void delete(int... names) {
            for (int n : names) live.remove(n);
        }
    }

    /** A GLTexture's bookkeeping: its name and the slot it took. */
    static final class Tex {
        final int name, slot;

        Tex(Names gl, GLTextureSlots slots) {
            name = gl.gen();
            slot = slots.take(name);
        }

        void close(Names gl, GLTextureSlots slots) {
            gl.delete(name);
            slots.release(slot, name);
        }
    }

    /** One post run with the film grain's reference model; returns its output (no node closes it: closeAll must). */
    private static void filmGrainShot(Names gl, GLTextureSlots slots) {
        final List<Tex> pipeline = new ArrayList<>();
        pipeline.add(new Tex(gl, slots));   // the input
        pipeline.add(new Tex(gl, slots));   // main1
        pipeline.add(new Tex(gl, slots));   // main2
        final int lut = gl.gen();           // FilmLut.upload: a raw glGenTextures
        final Tex noise = new Tex(gl, slots), h = new Tex(gl, slots), v = new Tex(gl, slots);
        final Tex out = new Tex(gl, slots); // the grain's output, the next node's input
        gl.delete(lut);
        noise.close(gl, slots);
        h.close(gl, slots);
        v.close(gl, slots);
        // the pipeline closes its mains; out and the input stay for closeAll
        pipeline.get(1).close(gl, slots);
        pipeline.get(2).close(gl, slots);
        assertTrue("the grain's output is still open", gl.live.contains(out.name));
        gl.delete(slots.takeAll());          // GLTexture.closeAll
    }

    @Test
    public void foreignTextureNameDoesNotUntrackTextures() {
        final Names gl = new Names(false);
        final GLTextureSlots slots = new GLTextureSlots(256);
        for (int shot = 1; shot <= 5; shot++) {
            filmGrainShot(gl, slots);
            assertEquals("textures left alive after shot " + shot, "[]", gl.live.toString());
            assertEquals("", slots.inUse());
        }
        // a shot without film grain after them: nothing left either
        new Tex(gl, slots).close(gl, slots);
        final Tex open = new Tex(gl, slots);
        gl.delete(slots.takeAll());
        assertTrue(!gl.live.contains(open.name));
        assertEquals("[]", gl.live.toString());
    }

    @Test
    public void namesWithoutReuseNeverIndexPastTheTable() {
        final Names gl = new Names(true);
        final GLTextureSlots slots = new GLTextureSlots(256);
        while (gl.next < 1000) {
            filmGrainShot(gl, slots);
            assertEquals("[]", gl.live.toString());
        }
        assertTrue(gl.next >= 1000);
    }

    @Test
    public void aSlotClearedByCloseAllAndTakenAgainIsNotFreedByTheOldTexture() {
        final Names gl = new Names(false);
        final GLTextureSlots slots = new GLTextureSlots(256);
        final Tex old = new Tex(gl, slots);
        gl.delete(slots.takeAll());             // closeAll; the object itself is not closed
        final int raw = gl.gen();               // a raw name takes old's name back
        final Tex now = new Tex(gl, slots);     // the same slot, another name
        assertEquals(old.slot, now.slot);
        slots.release(old.slot, old.name);      // the old object's late close: not now's slot
        assertEquals(String.valueOf(now.slot) + " ", slots.inUse());
        gl.delete(raw);
        gl.delete(slots.takeAll());
        assertEquals("[]", gl.live.toString());
    }

    @Test
    public void aFullTableTracksNothingMoreAndReleasesNothing() {
        final GLTextureSlots slots = new GLTextureSlots(4);
        assertEquals(1, slots.take(10));
        assertEquals(2, slots.take(11));
        assertEquals(3, slots.take(12));
        assertEquals(0, slots.take(13));
        slots.release(0, 13);
        assertEquals("1 2 3 ", slots.inUse());
        slots.release(2, 11);
        assertEquals(2, slots.take(14));
        final int[] all = slots.takeAll();
        assertEquals(3, all.length);
        assertEquals(10, all[0]);
        assertEquals(14, all[1]);
        assertEquals(12, all[2]);
        assertEquals("", slots.inUse());
    }
}
