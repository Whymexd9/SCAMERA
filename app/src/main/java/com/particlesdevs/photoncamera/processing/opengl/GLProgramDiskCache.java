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
 */
final class GLProgramDiskCache {
    static final int MAX_FILES = 160;
    static final long MAX_BYTES = 64L << 20;
    private static final int MAGIC = 0x474c5031; // "GLP1"
    private final File dir;
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

    /** {format, bytes} of the stored binary, or null. */
    Object[] load(String driver, String key) {
        File f = new File(dir, name(driver, key));
        if (!f.isFile()) return null;
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
        if (out == null) f.delete(); else f.setLastModified(System.currentTimeMillis());
        return out;
    }

    void store(String driver, String key, int format, byte[] data) {
        final String name = name(driver, key);
        writer.execute(() -> {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, name + ".tmp"), f = new File(dir, name);
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
                out.writeInt(MAGIC);
                out.writeInt(format);
                out.writeInt(data.length);
                out.write(data);
            } catch (IOException e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(f)) { tmp.delete(); return; }
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

    private void prune() {
        File[] files = dir.listFiles((d, n) -> n.startsWith("glp-") && n.endsWith(".bin"));
        if (files == null) return;
        long bytes = 0;
        for (File f : files) bytes += f.length();
        if (files.length <= MAX_FILES && bytes <= MAX_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int count = files.length;
        for (File f : files) {
            if (count <= MAX_FILES && bytes <= MAX_BYTES) break;
            long length = f.length();
            if (f.delete()) { count--; bytes -= length; }
        }
    }
}
