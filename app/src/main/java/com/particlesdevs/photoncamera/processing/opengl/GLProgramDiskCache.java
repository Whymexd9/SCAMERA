package com.particlesdevs.photoncamera.processing.opengl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P69: driver binaries of the post-pipeline programs on disk, so the first shot after the app starts does not compile them
 * again (Redmi Note 11 Pro, Mali-G57 MC2: 32 programs compiled in 2954 ms of a 5.7 s post pipeline; the in-process cache of
 * {@link GLProg} only helps the shots after it). One file per program: "glp-" + SHA-256 of driver + source, holding the
 * binary format and the bytes. The driver string (renderer, GL version, build fingerprint) is part of the name, so a driver
 * update never loads an old binary; a binary the driver still refuses is deleted by the caller and compiled as before.
 * Files are written on a background thread; at most {@link #MAX_FILES} / {@link #MAX_BYTES}, least recently used first out.
 * <p>
 * P237 (speed-ux B5): the X7 Ultra compiled all 36 programs (790 ms) on its first shot and again after the P211 reboot (746 ms):
 * a binary written shortly before a kernel panic was still in the page cache. Each file is now fsync'ed before its rename; the
 * cache counts its hits, misses, damaged files, writes and evictions (GLProg.takeProgramStats logs them per shot); and every
 * program's source is kept too ("gls-" + SHA-256 of the source, driver-independent, {@link #MAX_SOURCES} / {@link
 * #MAX_SOURCE_BYTES}), so {@link #missingSources} gives the programs whose binary this driver lacks (a driver update, an evicted
 * binary) for GLProg.warmUp to compile while the camera is idle.
 */
final class GLProgramDiskCache {
    static final int MAX_FILES = 160;
    static final long MAX_BYTES = 64L << 20;
    private static final int MAGIC = 0x474c5031; // "GLP1"
    /** P237: sources kept for the idle warm-up (a post pipeline has ~40 programs, a few KB each). */
    static final int MAX_SOURCES = 160;
    static final long MAX_SOURCE_BYTES = 16L << 20;
    private final File dir;
    /** P237: counted since the last {@link #takeStats}. */
    private int hits, misses, damaged, written, evicted;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GLProgramDiskCache");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    GLProgramDiskCache(File dir) { this.dir = dir; }

    static String name(String driver, String key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(driver.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            byte[] d = sha.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("glp-");
            for (int i = 0; i < 16; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", d[i] & 0xff));
            return out.append(".bin").toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** P237: the driver-independent name of a program's kept source. */
    static String sourceName(String key) {
        return "gls-" + name("", key).substring(4, 36) + ".src";
    }

    /** P237: "disk H hit / M miss / D damaged / W written / E evicted" since the last call. */
    synchronized String takeStats() {
        final String s = "disk " + hits + " hit / " + misses + " miss / " + damaged + " damaged / " + written + " written / " + evicted + " evicted";
        hits = misses = damaged = written = evicted = 0;
        return s;
    }

    /** P312: the viewfinder's programs ("V\n" keys, GLProg.viewfinderKey) are kept here too but not counted in the shot's line. */
    private static boolean counted(String key) {
        return !key.startsWith("V\n");
    }

    private synchronized void count(int hit, int miss, int bad, int write, int evict) {
        hits += hit; misses += miss; damaged += bad; written += write; evicted += evict;
    }

    /** {format, bytes} of the stored binary, or null. */
    Object[] load(String driver, String key) {
        File f = new File(dir, name(driver, key));
        if (!f.isFile()) { if (counted(key)) count(0, 1, 0, 0, 0); return null; }
        Object[] out = null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() == MAGIC) {
                int format = in.readInt(), length = in.readInt();
                if (length > 0 && length <= MAX_BYTES && length == f.length() - 12) {
                    byte[] data = new byte[length];
                    in.readFully(data);
                    out = new Object[]{format, data};
                }
            }
        } catch (IOException | RuntimeException e) {
            out = null;
        }
        // closed before the delete / touch (Windows test hosts refuse both on an open file)
        if (out == null) { f.delete(); if (counted(key)) count(0, 1, 1, 0, 0); }
        else {
            final long now = System.currentTimeMillis();
            f.setLastModified(now);
            new File(dir, sourceName(key)).setLastModified(now); // P237: a source in use stays (LRU)
            if (counted(key)) count(1, 0, 0, 0, 0);
        }
        return out;
    }

    void store(String driver, String key, int format, byte[] data) {
        final String name = name(driver, key);
        writer.execute(() -> {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, name + ".tmp"), f = new File(dir, name);
            try (FileOutputStream file = new FileOutputStream(tmp); DataOutputStream out = new DataOutputStream(file)) {
                out.writeInt(MAGIC);
                out.writeInt(format);
                out.writeInt(data.length);
                out.write(data);
                out.flush();
                file.getFD().sync(); // P237: on disk before the rename (a kernel panic lost the page cache's copy)
            } catch (IOException e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(f)) { tmp.delete(); return; }
            if (counted(key)) count(0, 0, 0, 1, 0);
            keepSource(key);
            prune();
        });
    }

    void drop(String driver, String key) {
        final File f = new File(dir, name(driver, key));
        writer.execute(f::delete);
    }

    /** Waits for the pending writes (tests). */
    void flush() throws InterruptedException {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        writer.execute(done::countDown);
        done.await();
    }

    /** P237: the program's source beside its binary (written once, fsync'ed; on the writer thread). */
    private void keepSource(String key) {
        final File f = new File(dir, sourceName(key));
        if (f.isFile()) { f.setLastModified(System.currentTimeMillis()); return; }
        final File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(key.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    /**
     * P237: the kept sources whose binary this {@code driver} does not have (a driver update, an evicted or lost binary), newest
     * first, at most {@code limit}; empty without a cache directory.
     */
    java.util.List<String> missingSources(String driver, int limit) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.startsWith("gls-") && n.endsWith(".src"));
        if (files == null || driver == null) return out;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File f : files) {
            if (out.size() >= limit) break;
            final String key;
            try {
                key = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (!sourceName(key).equals(f.getName())) { f.delete(); continue; } // damaged
            if (!new File(dir, name(driver, key)).isFile()) out.add(key);
        }
        return out;
    }

    private void prune() {
        evict("glp-", ".bin", MAX_FILES, MAX_BYTES, true);
        evict("gls-", ".src", MAX_SOURCES, MAX_SOURCE_BYTES, false);
    }

    private void evict(String prefix, String suffix, int maxFiles, long maxBytes, boolean counted) {
        File[] files = dir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(suffix));
        if (files == null) return;
        long bytes = 0;
        for (File f : files) bytes += f.length();
        if (files.length <= maxFiles && bytes <= maxBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int count = files.length;
        for (File f : files) {
            if (count <= maxFiles && bytes <= maxBytes) break;
            long length = f.length();
            if (f.delete()) { count--; bytes -= length; if (counted) count(0, 0, 0, 0, 1); }
        }
    }
}
