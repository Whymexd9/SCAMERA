package com.particlesdevs.photoncamera.processing.opengl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

/** P69: post-pipeline program binaries on disk, keyed by driver and source. */
public class GLProgramDiskCacheTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void roundTripOnlyForTheSameDriverAndSource() throws Exception {
        File dir = new File(tmp.getRoot(), "glprog");
        GLProgramDiskCache cache = new GLProgramDiskCache(dir);
        assertNull(cache.load("Mali-G57|OpenGL ES 3.2|fp", "C\nshader"));
        cache.store("Mali-G57|OpenGL ES 3.2|fp", "C\nshader", 0x8740, new byte[]{1, 2, 3, 4});
        cache.flush();
        Object[] b = new GLProgramDiskCache(dir).load("Mali-G57|OpenGL ES 3.2|fp", "C\nshader");
        assertNotNull(b);
        assertEquals(0x8740, (int) (Integer) b[0]);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) b[1]);
        assertNull(cache.load("Mali-G57|OpenGL ES 3.2|fp2", "C\nshader")); // driver update
        assertNull(cache.load("Mali-G57|OpenGL ES 3.2|fp", "C\nshader2"));
        cache.drop("Mali-G57|OpenGL ES 3.2|fp", "C\nshader");
        cache.flush();
        assertNull(cache.load("Mali-G57|OpenGL ES 3.2|fp", "C\nshader"));
    }

    @Test public void aDamagedFileIsDeleted() throws Exception {
        File dir = tmp.newFolder("glprog");
        GLProgramDiskCache cache = new GLProgramDiskCache(dir);
        File f = new File(dir, GLProgramDiskCache.name("d", "k"));
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(new byte[]{0x47, 0x4c, 0x50, 0x31, 0, 0, 0, 1, 0, 0, 1, 0, 9}); }
        assertNull(cache.load("d", "k")); // length field 256, one byte present
        assertFalse(f.exists());
    }

    @Test public void oldestFilesGoBeyondTheLimit() throws Exception {
        File dir = tmp.newFolder("glprog");
        GLProgramDiskCache cache = new GLProgramDiskCache(dir);
        for (int i = 0; i < GLProgramDiskCache.MAX_FILES + 5; i++) cache.store("d", "k" + i, 1, new byte[]{(byte) i});
        cache.flush();
        File[] files = dir.listFiles((d, n) -> n.startsWith("glp-") && n.endsWith(".bin"));
        assertNotNull(files);
        assertEquals(GLProgramDiskCache.MAX_FILES, files.length);
    }

    /** P237: hits, misses, damaged files, writes and evictions are counted for the per-shot log line. */
    @Test public void theCacheCountsHitsMissesDamagedWritesAndEvictions() throws Exception {
        File dir = tmp.newFolder("glprog");
        GLProgramDiskCache cache = new GLProgramDiskCache(dir);
        assertNull(cache.load("d", "k"));
        cache.store("d", "k", 1, new byte[]{1});
        cache.flush();
        assertNotNull(cache.load("d", "k"));
        File bad = new File(dir, GLProgramDiskCache.name("d", "bad"));
        try (FileOutputStream out = new FileOutputStream(bad)) { out.write(new byte[]{1, 2, 3}); }
        assertNull(cache.load("d", "bad"));
        assertEquals("disk 1 hit / 2 miss / 1 damaged / 1 written / 0 evicted", cache.takeStats());
        assertEquals("taken: back to zero", "disk 0 hit / 0 miss / 0 damaged / 0 written / 0 evicted", cache.takeStats());
        for (int i = 0; i < GLProgramDiskCache.MAX_FILES + 3; i++) cache.store("d", "e" + i, 1, new byte[]{(byte) i});
        cache.flush();
        final String stats = cache.takeStats();
        assertTrue(stats, stats.endsWith(" 163 written / 4 evicted")); // k and 163 more: 4 beyond the 160
    }

    /** P237: the sources are kept apart from the driver, so a new driver's missing binaries can be compiled in idle time. */
    @Test public void theSourcesOfMissingBinariesAreListedForTheWarmUp() throws Exception {
        File dir = tmp.newFolder("glprog");
        GLProgramDiskCache cache = new GLProgramDiskCache(dir);
        cache.store("old-driver", "C\ncompute source", 1, new byte[]{1});
        cache.store("old-driver", "F\nvertex\nfragment source", 1, new byte[]{2});
        cache.flush();
        assertTrue(new File(dir, GLProgramDiskCache.sourceName("C\ncompute source")).isFile());
        assertTrue("every binary of this driver is there", cache.missingSources("old-driver", 64).isEmpty());
        java.util.List<String> missing = cache.missingSources("new-driver", 64);
        assertEquals(2, missing.size());
        assertTrue(missing.contains("C\ncompute source") && missing.contains("F\nvertex\nfragment source"));
        assertEquals("the limit", 1, cache.missingSources("new-driver", 1).size());
        // the new driver's binary of one of them: only the other is left
        cache.store("new-driver", "C\ncompute source", 1, new byte[]{3});
        cache.flush();
        assertEquals(java.util.Collections.singletonList("F\nvertex\nfragment source"), cache.missingSources("new-driver", 64));
        // a source file whose name does not match its text is damaged: deleted, not compiled
        File odd = new File(dir, GLProgramDiskCache.sourceName("other"));
        try (FileOutputStream out = new FileOutputStream(odd)) { out.write("changed".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        assertFalse(cache.missingSources("new-driver", 64).contains("changed"));
        assertFalse(odd.exists());
        assertTrue("no driver yet (no GL context): nothing", cache.missingSources(null, 64).isEmpty());
    }

    /**
     * P312: the viewfinder's programs share the cache under keys of their own ("V\n" + attributes + "\n" + vertex + U+0001 +
     * fragment): the warm-up gets their whole sources back from a kept key, and they never collide with the post pipeline's.
     */
    @Test public void viewfinderKeysCarryBothShadersAndTheirAttributes() throws Exception {
        final String vertex = "#version 310 es\nin vec4 vPosition;\nvoid main() { gl_Position = vPosition; }\n";
        final String fragment = "#version 310 es\nprecision highp float;\nout vec4 o;\nvoid main() { o = vec4(1.0); }\n";
        final String key = GLProg.viewfinderKey(vertex, fragment, "vPosition", "vTexCoord");
        assertTrue(key.startsWith("V\n"));
        assertArrayEquals(new String[]{"vPosition,vTexCoord", vertex, fragment}, GLProg.parseViewfinderKey(key));
        assertArrayEquals(new String[]{"", vertex, fragment}, GLProg.parseViewfinderKey(GLProg.viewfinderKey(vertex, fragment)));
        assertNotEquals(key, GLProg.viewfinderKey(vertex, fragment, "vPosition"));
        assertNotEquals(key, GLProg.viewfinderKey(vertex, fragment + " ", "vPosition", "vTexCoord"));
        assertNull("a post-pipeline key", GLProg.parseViewfinderKey("F\n" + vertex + "\n" + fragment));
        assertNull("no separator", GLProg.parseViewfinderKey("V\nvPosition\n" + vertex));
        assertNull(GLProg.parseViewfinderKey(null));
        // kept like any other program: its source comes back for a new driver's warm-up
        File dir = tmp.newFolder("glprog");
        GLProgramDiskCache cache = new GLProgramDiskCache(dir);
        cache.store("driver", key, 1, new byte[]{7});
        cache.flush();
        assertEquals(java.util.Collections.singletonList(key), cache.missingSources("new-driver", 64));
        // ... but not counted in the shot's "programs:" line (P237's hits / misses / writes are the post pipeline's)
        cache.takeStats();
        assertNotNull(cache.load("driver", key));
        assertNull(cache.load("driver", key + " "));
        cache.store("driver", key + "  ", 1, new byte[]{8});
        cache.flush();
        assertEquals("disk 0 hit / 0 miss / 0 damaged / 0 written / 0 evicted", cache.takeStats());
        assertNull(cache.load("driver", "C\nother"));
        assertEquals("disk 0 hit / 1 miss / 0 damaged / 0 written / 0 evicted", cache.takeStats());
    }
}
