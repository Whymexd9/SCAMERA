package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P224: the viewfinder's automatic AE range never below 20 fps; fixed rates and the camera's upper bound stay. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class ViewfinderFpsTest {
    @Test
    public void theLowerBoundRisesToTheFloor() {
        assertEquals(new Range<>(20, 30), ViewfinderFps.raise(new Range<>(7, 30), 20));
        assertEquals(new Range<>(20, 30), ViewfinderFps.raise(new Range<>(5, 30), 20));   // vivo's stock profile
        Range<Integer> fixed = new Range<>(24, 24);
        assertSame("a fixed rate at or above the floor stays", fixed, ViewfinderFps.raise(fixed, 20));
        assertEquals("a top under the floor: one under the top, never a fixed rate", new Range<>(14, 15),
                ViewfinderFps.raise(new Range<>(10, 15), 20));
        assertEquals(new Range<>(9, 10), ViewfinderFps.raise(new Range<>(7, 10), 20));
        assertEquals("a top at the floor", new Range<>(19, 20), ViewfinderFps.raise(new Range<>(7, 20), 20));
        Range<Integer> high = new Range<>(9, 10);
        assertSame("already as high as it can be", high, ViewfinderFps.raise(high, 20));
        Range<Integer> auto = new Range<>(10, 30);
        assertSame("floor 0: the camera's own range", auto, ViewfinderFps.raise(auto, 0));
        assertNull(ViewfinderFps.raise(null, 20));
    }

    /** Owner: a sensor mode slower than the floor runs as fast as it can, as close to 20 fps as possible. */
    @Test
    public void aSensorModeSlowerThanTheFloorRunsAsFastAsItCan() {
        Range<Integer> auto = new Range<>(7, 30);
        assertEquals("a 15 fps mode", new Range<>(15, 30), ViewfinderFps.raise(auto, 20, 15.0));
        assertEquals("a 14.99 fps mode", new Range<>(14, 30), ViewfinderFps.raise(auto, 20, 14.99));
        assertEquals("a 30 fps mode", new Range<>(20, 30), ViewfinderFps.raise(auto, 20, 30.0));
        assertEquals("not known", new Range<>(20, 30), ViewfinderFps.raise(auto, 20, 0));
        assertSame("the 200 MP mode held (~2.7 fps): under the range's own floor, the range stays", auto,
                ViewfinderFps.raise(auto, 20, 2.7));
    }

    /** Owner, 2026-10-19: the 20 fps floor on vivo only (its night viewfinder fell to ~5 fps); other phones keep their range. */
    @org.junit.Test public void theFloorIsVivosOnly() {
        org.junit.Assert.assertEquals(ViewfinderFps.DEFAULT_FLOOR, ViewfinderFps.defaultFloor("vivo"));
        org.junit.Assert.assertEquals(ViewfinderFps.DEFAULT_FLOOR, ViewfinderFps.defaultFloor("VIVO "));
        org.junit.Assert.assertEquals(0, ViewfinderFps.defaultFloor("OPPO"));
        org.junit.Assert.assertEquals(0, ViewfinderFps.defaultFloor("Xiaomi"));
        org.junit.Assert.assertEquals(0, ViewfinderFps.defaultFloor(null));
    }
}
