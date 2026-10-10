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
}
