package com.particlesdevs.photoncamera.util;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * P214b (engineering.md A3): one text log per phone in Download/SCAMERA that every process appends to, rotated by size.
 *
 * <p>Before, SCAMERA-debug.log was a MediaStore entry inserted as "SCAMERA-debug.log" with MIME text/plain: MediaStore
 * stored it as "SCAMERA-debug.log.txt", the next process looked it up by the name without ".txt", never found it and
 * inserted "SCAMERA-debug.log (1).txt", (2), ... - 32 files on the OPPO Find X7 Ultra by 2026-10-17, the timings log the
 * same way, and the header printed a path that did not exist. Now a log is {@code <stem>.txt} (the extension its type
 * has, so nothing renames it), written through the File API like the crash report (P191, {@link ScameraFiles}: the app's
 * own files in Download need no permission) and found again by that exact name. At {@code cap} bytes it becomes
 * {@code <stem>.1.txt} ({@code .1} becomes {@code .2}, ..., the oldest generation is deleted), so at most
 * {@code cap x generations} bytes stay.
 *
 * <p>One file rather than one per session: the owner sends one obvious file whatever happened - a crash, the restart after
 * it and the next sessions are in it in order, each under its own session header with the build line - and nothing has to
 * be picked by time. The OPPO's 32 sessions were 10 KB - 1 MB each, so 8 MB holds days of them, 3 x 8 MB a week or more.
 *
 * <p>A file of an earlier install of the app (uninstalled and installed again, e.g. a local APK after a CI one) keeps its
 * name but is no longer the app's, and the File API cannot open it. The log then goes to the alternate stem, one per
 * install ({@link #installStem}), found again the same way by every later process of that install. A rotation target that
 * cannot be taken drops that generation instead; a file that can be neither moved nor deleted is started again.
 *
 * <p>Not thread-safe: the owner calls it under its own lock, on its log thread (the crash handlers excepted).
 */
public final class RotatingLogFile {
    /** Called after every open: the text put at the top of the file (null: none). rotated: it continues a file at the cap. */
    public interface Opened {
        @Nullable String text(File file, boolean rotated);
    }

    public static final long CAP_BYTES = 8L * 1024 * 1024;
    public static final int GENERATIONS = 3;
    static final long RETRY_NS = 5_000_000_000L;
    private static final String TAG = "RotatingLogFile";

    private final String stem;
    private final String extension;
    private final long cap;
    private final int generations;
    private final Supplier<File> dir;
    private final Supplier<String> alternateStem;
    private final Opened opened;

    private Writer writer;
    private volatile File file;
    private String activeStem;
    private long size;
    private boolean retryArmed;
    private long retryAtNs;

    /**
     * @param dir           the folder, asked only when a file is opened (Download/SCAMERA)
     * @param alternateStem the stem of this install for when {@code <stem>.txt} cannot be opened, asked only then (null
     *                      result: none)
     */
    public RotatingLogFile(String stem, String extension, long cap, int generations, Supplier<File> dir,
                           @Nullable Supplier<String> alternateStem, @Nullable Opened opened) {
        this.stem = stem;
        this.extension = extension;
        this.cap = cap;
        this.generations = Math.max(1, generations);
        this.dir = dir;
        this.alternateStem = alternateStem;
        this.opened = opened;
    }

    /** Generation 0 is the newest: {@code stem.txt}, then {@code stem.1.txt}, {@code stem.2.txt}, ... */
    public static String name(String stem, String extension, int generation) {
        return generation <= 0 ? stem + extension : stem + "." + generation + extension;
    }

    /** The file being written or last written (null before the first open); readable from any thread. */
    @Nullable
    public File file() {
        return file;
    }

    public boolean isOpen() {
        return writer != null;
    }

    /** Opens the current file for appending when none is open; false when none could be (tried again after 5 s). */
    public boolean ensureOpen() {
        return writer != null || open(false);
    }

    /** Appends {@code text}, opening the file first; at the cap the file is rotated and a new one started. */
    public boolean write(String text) {
        if (!ensureOpen()) return false;
        try {
            writer.write(text);
            size += utf8Length(text);
        } catch (IOException | RuntimeException e) {
            close();
            return false;
        }
        if (size >= cap) {
            close();
            open(true);
        }
        return true;
    }

    public void flush() {
        if (writer == null) return;
        try {
            writer.flush();
        } catch (IOException | RuntimeException e) {
            close();
        }
    }

    public void close() {
        Writer w = writer;
        writer = null;
        if (w == null) return;
        try {
            w.close();
        } catch (IOException | RuntimeException ignored) {
            // nothing useful to do; the next write opens the file again
        }
    }

    /** The stems in the order they are tried: the one in use, else the shared one, then this install's. */
    private String[] stems() {
        String alt = null;
        if (alternateStem != null) {
            try {
                alt = alternateStem.get();
            } catch (RuntimeException ignored) {
                alt = null;
            }
        }
        if (alt == null || alt.equals(stem)) return new String[]{stem};
        if (alt.equals(activeStem)) return new String[]{alt, stem};
        return new String[]{stem, alt};
    }

    private boolean open(boolean forceRotate) {
        long now = System.nanoTime();
        if (retryArmed && now - retryAtNs < 0) return false;
        File folder;
        try {
            folder = dir.get();
        } catch (RuntimeException e) {
            folder = null;
        }
        if (folder != null) {
            String first = activeStem != null ? activeStem : stem;
            // the alternate is asked for (a package lookup) only when the shared name failed
            if (openStem(folder, first, forceRotate)) return true;
            for (String s : stems()) {
                if (!s.equals(first) && openStem(folder, s, false)) return true;
            }
        }
        retryArmed = true;
        retryAtNs = now + RETRY_NS;
        return false;
    }

    private boolean openStem(File folder, String s, boolean forceRotate) {
        File f = new File(folder, name(s, extension, 0));
        try {
            boolean full = forceRotate || f.length() >= cap;
            if (full) rotate(folder, s, extension, generations);
            // a full file that could be neither moved nor deleted is started again
            boolean append = !(full && f.exists());
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, append), StandardCharsets.UTF_8), 8192);
            file = f;
            activeStem = s;
            size = append ? f.length() : 0;
            retryArmed = false;
            String head = opened == null ? null : opened.text(f, full);
            if (head != null && !head.isEmpty()) {
                writer.write(head);
                size += utf8Length(head);
            }
            // logcat only: util.Log would mirror into the full debug log, the caller of this very write
            android.util.Log.i(TAG, (full ? "rotated, writing " : "writing ") + f);
            return true;
        } catch (IOException | RuntimeException e) {
            close();
            android.util.Log.w(TAG, "cannot open " + f + ": " + e);
            return false;
        }
    }

    /**
     * {@code stem.(n-2)} becomes {@code stem.(n-1)}, ..., {@code stem} becomes {@code stem.1}; the oldest generation is
     * deleted. A target name that cannot be taken (a file of an earlier install) drops the source generation instead.
     */
    public static void rotate(File folder, String stem, String extension, int generations) {
        if (generations <= 1) {
            new File(folder, name(stem, extension, 0)).delete();
            return;
        }
        File oldest = new File(folder, name(stem, extension, generations - 1));
        if (oldest.exists()) oldest.delete();
        for (int g = generations - 2; g >= 0; g--) {
            File from = new File(folder, name(stem, extension, g));
            if (!from.exists()) continue;
            File to = new File(folder, name(stem, extension, g + 1));
            if (!from.renameTo(to)) from.delete();
        }
    }

    /** UTF-8 bytes of {@code s} (a surrogate pair is 4). */
    static long utf8Length(CharSequence s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
        }
        return n;
    }

    /**
     * This install's stem, {@code <stem>-<first install time>} (e.g. SCAMERA-debug-20261018-142233): the same for every
     * process of one install, new after a reinstall. Null without a context or package info.
     */
    @Nullable
    public static String installStem(@Nullable Context context, String stem) {
        if (context == null) return null;
        try {
            long installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).firstInstallTime;
            if (installed <= 0) return null;
            return installStem(stem, installed);
        } catch (Exception e) {
            return null;
        }
    }

    static String installStem(String stem, long firstInstallTimeMs) {
        return stem + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(firstInstallTimeMs));
    }
}
