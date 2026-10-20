package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** P302: the photos' DCIM comes from a DCIM grant only; the files of Download/SCAMERA/DCIM go back once, verified. */
public class DcimMoveBackTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void theDcimIsTakenFromADcimGrantOnly() {
        assertNull("Download/SCAMERA (P191) is no DCIM grant",
                SimpleStorageHelper.dcimOf(Collections.singletonList("/storage/emulated/0/Download/SCAMERA")));
        assertNull("nor a DCIM folder inside it",
                SimpleStorageHelper.dcimOf(Collections.singletonList("/storage/emulated/0/Download/SCAMERA/DCIM")));
        assertEquals("/storage/emulated/0/DCIM/", SimpleStorageHelper.dcimOf(Arrays.asList(
                "/storage/emulated/0/Download/SCAMERA", "/storage/emulated/0/DCIM")));
        assertEquals("a folder inside DCIM", "/storage/emulated/0/DCIM/",
                SimpleStorageHelper.dcimOf(Collections.singletonList("/storage/emulated/0/DCIM/SCAMERA/")));
        assertEquals("the storage root", "/storage/emulated/0/DCIM/",
                SimpleStorageHelper.dcimOf(Collections.singletonList("/storage/emulated/0")));
        assertEquals("the primary storage before a card", "/storage/emulated/0/DCIM/",
                SimpleStorageHelper.dcimOf(Arrays.asList("/storage/1A2B-3C4D/DCIM", "/storage/emulated/0/DCIM/Camera")));
        assertEquals("a card alone", "/storage/1A2B-3C4D/DCIM/",
                SimpleStorageHelper.dcimOf(Collections.singletonList("/storage/1A2B-3C4D/DCIM")));
        assertNull("no grant", SimpleStorageHelper.dcimOf(Collections.<String>emptyList()));
    }

    private File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), data);
        return f;
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + seed);
        return b;
    }

    @Test
    public void filesMoveVerifiedAndNothingIsOverwritten() throws IOException {
        File old = tmp.newFolder("Download", "SCAMERA", "DCIM", "Camera"), dcim = tmp.newFolder("DCIM", "Camera");
        byte[] a = bytes(200_000, 1), b = bytes(70_000, 2), c = bytes(5_000, 3), other = bytes(5_000, 9);
        write(old, "SCA_a.jpg", a);
        write(old, "SCA_b.dng", b);
        write(old, "SCA_c.jpg", c);
        write(dcim, "SCA_b.dng", b);          // the same bytes already in DCIM: the old one just goes
        write(dcim, "SCA_c.jpg", other);      // another file of the same name: the moved one is SCA_c_1.jpg
        File[] files = old.listFiles();
        Arrays.sort(files);
        List<DcimMoveBack.Source> sources = new java.util.ArrayList<>();
        for (File f : files) sources.add(DcimMoveBack.fileSource(null, f, null));
        DcimMoveBack.Result r = DcimMoveBack.move(sources, dcim);
        assertEquals(2, r.moved);
        assertEquals(1, r.same);
        assertEquals(0, r.kept);
        assertEquals("nothing left in Download", 0, old.list().length);
        assertArrayEquals(a, Files.readAllBytes(new File(dcim, "SCA_a.jpg").toPath()));
        assertArrayEquals(b, Files.readAllBytes(new File(dcim, "SCA_b.dng").toPath()));
        assertArrayEquals("the file there before is kept", other, Files.readAllBytes(new File(dcim, "SCA_c.jpg").toPath()));
        assertArrayEquals(c, Files.readAllBytes(new File(dcim, "SCA_c_1.jpg").toPath()));
    }

    @Test
    public void aCopyThatDiffersKeepsTheOldFile() throws IOException {
        File dcim = tmp.newFolder("DCIM2");
        final byte[] data = bytes(100_000, 4);
        final int[] opens = {0};
        final boolean[] deleted = {false};
        // a source whose second read differs from the first (a storage that does not give back what it holds)
        DcimMoveBack.Source flaky = new DcimMoveBack.Source() {
            @Override public String name() { return "SCA_x.jpg"; }
            @Override public InputStream open() {
                byte[] d = data.clone();
                if (opens[0]++ > 0) d[5000] ^= 1;
                return new ByteArrayInputStream(d);
            }
            @Override public boolean renameTo(File target) { return false; }
            @Override public boolean delete() { deleted[0] = true; return true; }
        };
        DcimMoveBack.Result r = DcimMoveBack.move(Collections.singletonList(flaky), dcim);
        assertEquals(1, r.kept);
        assertEquals(0, r.moved);
        assertFalse("the old file is not deleted", deleted[0]);
        assertFalse("the bad copy is removed", new File(dcim, "SCA_x.jpg").exists());
        assertTrue(r.problems.get(0).contains("differs"));
    }

    @Test
    public void anInterruptedCopyLeavesNothingUnderARealName() throws IOException {
        File dcim = tmp.newFolder("DCIM3");
        final byte[] data = bytes(300_000, 5);
        final boolean[] deleted = {false};
        // a stream that breaks after 100 kB (a full card, a grant that goes away)
        DcimMoveBack.Source breaking = new DcimMoveBack.Source() {
            @Override public String name() { return "SCA_y.jpg"; }
            @Override public InputStream open() {
                return new ByteArrayInputStream(data) {
                    @Override public synchronized int read(byte[] b, int off, int len) {
                        if (pos >= 100_000) throw new RuntimeException("I/O error");
                        return super.read(b, off, Math.min(len, 100_000 - pos));
                    }
                };
            }
            @Override public boolean renameTo(File target) { return false; }
            @Override public boolean delete() { deleted[0] = true; return true; }
        };
        DcimMoveBack.Result r = DcimMoveBack.move(Collections.singletonList(breaking), dcim);
        assertEquals(1, r.kept);
        assertFalse("the old file stays", deleted[0]);
        assertEquals("no file of any name left in DCIM", 0, dcim.list().length);
        // a leftover of a killed process goes on the next start
        write(dcim, DcimMoveBack.PART + "SCA_z.jpg", bytes(10, 1));
        DcimMoveBack.removeParts(dcim);
        assertEquals(0, dcim.list().length);
    }

    @Test
    public void anEarlierCopyUnderAnUnderscoreNameIsNotCopiedAgain() throws IOException {
        File old = tmp.newFolder("old4"), dcim = tmp.newFolder("DCIM4");
        byte[] mine = bytes(50_000, 6), other = bytes(50_000, 7);
        File src = write(old, "SCA_w.jpg", mine);
        src.setLastModified(1_600_000_000_000L);
        write(dcim, "SCA_w.jpg", other);       // another photo of the same name
        write(dcim, "SCA_w_1.jpg", mine);      // an earlier run copied it there and could not delete the old file
        final boolean[] deleted = {false};
        DcimMoveBack.Source s = new DcimMoveBack.Source() {
            final DcimMoveBack.Source f = DcimMoveBack.fileSource(null, src, null);
            @Override public String name() { return f.name(); }
            @Override public InputStream open() throws IOException { return f.open(); }
            @Override public boolean renameTo(File target) { return false; }
            @Override public boolean delete() { deleted[0] = true; return true; }
            @Override public long length() { return f.length(); }
            @Override public long lastModified() { return f.lastModified(); }
        };
        DcimMoveBack.Result r = DcimMoveBack.move(Collections.singletonList(s), dcim);
        assertEquals(1, r.same);
        assertTrue(deleted[0]);
        assertFalse("no SCA_w_2.jpg", new File(dcim, "SCA_w_2.jpg").exists());
        // a new copy keeps the old file's time (the gallery sorts by it)
        File old5 = tmp.newFolder("old5"), dcim5 = tmp.newFolder("DCIM5");
        File src5 = write(old5, "SCA_v.jpg", mine);
        src5.setLastModified(1_600_000_000_000L);
        DcimMoveBack.Source copyOnly = new DcimMoveBack.Source() {
            final DcimMoveBack.Source f = DcimMoveBack.fileSource(null, src5, null);
            @Override public String name() { return f.name(); }
            @Override public InputStream open() throws IOException { return f.open(); }
            @Override public boolean renameTo(File target) { return false; }
            @Override public boolean delete() { return f.delete(); }
            @Override public long length() { return f.length(); }
            @Override public long lastModified() { return f.lastModified(); }
        };
        DcimMoveBack.Result r5 = DcimMoveBack.move(Collections.singletonList(copyOnly), dcim5);
        assertEquals(1, r5.moved);
        File moved = new File(dcim5, "SCA_v.jpg");
        assertArrayEquals(mine, Files.readAllBytes(moved.toPath()));
        assertEquals(1_600_000_000_000L / 1000, moved.lastModified() / 1000);
        assertEquals("no temporary file left", 1, dcim5.list().length);
    }

    @Test
    public void freeNamesCountUp() throws IOException {
        File dir = tmp.newFolder("names");
        write(dir, "a.jpg", new byte[1]);
        write(dir, "a_1.jpg", new byte[1]);
        assertEquals("a_2.jpg", DcimMoveBack.free(dir, "a.jpg").getName());
        assertEquals("noext_1", DcimMoveBack.free(dir, "noext").getName());
    }
}
