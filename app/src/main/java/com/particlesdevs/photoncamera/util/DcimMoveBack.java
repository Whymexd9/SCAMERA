package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P302 (owner, 2026-10-20: «/storage/emulated/0/Download/SCAMERA/DCIM/Camera/ и /storage/emulated/0/Download/SCAMERA/DCIM/SCAMERA/Raw/
 * перенести обратно в /storage/emulated/0/DCIM/. Это всё не нужно хранить в загрузках»): the photos and RAW files the app wrote
 * to Download/SCAMERA/DCIM (SimpleStorageHelper took the Download/SCAMERA grant of P191 for the DCIM one) go back to DCIM/Camera
 * and DCIM/SCAMERA/Raw, once. A file is renamed where the File API allows it (this install's own), else copied to a hidden
 * temporary name in DCIM, the copy read back and compared byte for byte, given the old file's time, renamed to its name and only
 * then the old one deleted (the File API, else through the Download/SCAMERA folder grant): an interrupted copy never leaves a
 * broken photo under a real name (its leftover goes on the next start). The same bytes already in DCIM under the name or a "_1",
 * "_2"... one (an earlier run that could not delete the old file): the old file just goes, no second copy; other bytes under the
 * name: the moved one is named "_1", "_2"... A file that cannot be read or deleted stays and is tried again on the next start;
 * the move counts as done once the old folders are gone, or after three starts that moved nothing (not counted while the app
 * sees the selected photos only and nothing it sees is left: the rest waits for the full access).
 */
public final class DcimMoveBack {
    private DcimMoveBack() {}

    private static final String TAG = "DcimMoveBack";
    static final String DONE_KEY = "p302_dcim_moved_back";
    static final String TRIES_KEY = "p302_dcim_tries";
    static final String PART = ".scam-move-";
    private static volatile boolean running;

    /** One file of an old folder: what the move needs of it (the File API or the folder grant behind it). */
    interface Source {
        String name();
        InputStream open() throws IOException;
        /** Moves it to {@code target} in one step where the storage allows it (false: copy and delete instead). */
        boolean renameTo(File target);
        boolean delete();
        /** Its size in bytes, -1 when unknown (a file only the grant shows). */
        default long length() { return -1; }
        /** Its time (ms), 0 when unknown. */
        default long lastModified() { return 0; }
    }

    static final class Result {
        int moved, same, kept;
        final List<File> targets = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
    }

    /** Starts the move in the background unless it is done (or running). */
    public static void runOnce(@Nullable Context context) {
        if (context == null || running) return;
        final Context app = context.getApplicationContext();
        try {
            if (android.preference.PreferenceManager.getDefaultSharedPreferences(app).getBoolean(DONE_KEY, false)) return;
        } catch (RuntimeException e) {
            return;
        }
        running = true;
        Thread t = new Thread(() -> {
            try {
                run(app);
            } catch (Throwable e) {
                Log.e(TAG, "move back to DCIM failed: " + e);
            } finally {
                running = false;
            }
        }, "DcimMoveBack");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void run(Context context) {
        final File root = Environment.getExternalStorageDirectory();
        final String oldBase = Environment.DIRECTORY_DOWNLOADS + "/SCAMERA/DCIM";
        final String[][] pairs = {
                {oldBase + "/Camera", FileManager.sDCIM_CAMERA.getAbsolutePath()},
                {oldBase + "/SCAMERA/Raw", FileManager.sRAW_DIR.getAbsolutePath()}};
        int left = 0, progress = 0;
        List<String> scan = new ArrayList<>();
        StringBuilder log = new StringBuilder("P302 move back to DCIM:");
        for (String[] pair : pairs) {
            File oldDir = new File(root, pair[0]), newDir = new File(pair[1]);
            // the photos' place must be out of Download now (SimpleStorageHelper.dcimOf); else nothing moves
            if (newDir.getAbsolutePath().contains("/" + Environment.DIRECTORY_DOWNLOADS + "/")) {
                Log.w(TAG, "the photo folder is still " + newDir + ": nothing moved");
                return;
            }
            removeParts(newDir);
            List<Source> sources = sources(context, oldDir, pair[0]);
            if (sources.isEmpty()) continue;
            if (!newDir.isDirectory() && !newDir.mkdirs()) {
                left += sources.size();
                log.append(' ').append(newDir).append(": cannot be made;");
                continue;
            }
            Result r = move(sources, newDir);
            left += r.kept;
            progress += r.moved + r.same;
            for (File f : r.targets) scan.add(f.getAbsolutePath());
            for (Source s : sources) scan.add(new File(oldDir, s.name()).getAbsolutePath());
            log.append(' ').append(pair[0]).append(" -> ").append(newDir).append(": moved ").append(r.moved)
               .append(", already there ").append(r.same).append(", left ").append(r.kept).append(';');
            for (String p : r.problems) log.append(" [").append(p).append(']');
        }
        if (!scan.isEmpty()) {
            try {
                android.media.MediaScannerConnection.scanFile(context, scan.toArray(new String[0]), null, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "media scan failed: " + e);
            }
        }
        // the emptied old folders go (a folder with anything left stays)
        boolean oldLeft = false;
        for (String sub : new String[]{oldBase + "/SCAMERA/Raw", oldBase + "/SCAMERA", oldBase + "/Camera", oldBase}) {
            File d = new File(root, sub);
            String[] rest = d.list();
            if (d.isDirectory() && rest != null && rest.length == 0) d.delete();
            // still there: files this run could not see (with access to selected photos only, another install's files stay
            // invisible to the File API) or could not move; the move is not done then
            if (d.isDirectory()) oldLeft = true;
        }
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        // files that stay (unreadable, undeletable) are tried on the next starts; after three starts without progress the move stops
        final boolean done = left == 0 && !oldLeft;
        // only files the File API cannot see are left and the app sees the selected photos only: nothing to try until the full
        // access is given (each start looks again, no try counted)
        final boolean waiting = !done && left == 0 && !fullMediaAccess(context);
        int tries = done ? 0 : progress > 0 ? 1 : prefs.getInt(TRIES_KEY, 0) + (waiting ? 0 : 1);
        if (!done && !waiting && tries >= 3) log.append(" ").append(left).append(" file(s) left after ").append(tries).append(" tries: stopped");
        if (waiting) log.append(" files left that only the full photo access shows: waiting for it");
        prefs.edit().putInt(TRIES_KEY, tries).putBoolean(DONE_KEY, done || !waiting && tries >= 3).apply();
        if (scan.isEmpty()) log.append(" nothing to move");
        Log.i(TAG, log.toString());
    }

    /** The app reads every photo (not only the ones the user selected, Android 14+). */
    private static boolean fullMediaAccess(Context context) {
        final String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_IMAGES : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        try {
            return context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The leftovers of an interrupted copy (hidden temporary names) in a target folder. */
    static void removeParts(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.isFile() && f.getName().startsWith(PART)) f.delete();
    }

    /** The files of an old folder: those the File API lists, and those only the folder grant shows. */
    private static List<Source> sources(Context context, File dir, String relative) {
        Map<String, Source> out = new LinkedHashMap<>();
        Map<String, Uri> granted = ScameraFolder.grantedChildren(context, relative);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.isFile()) out.put(f.getName(), fileSource(context, f, granted.get(f.getName())));
        for (Map.Entry<String, Uri> e : granted.entrySet()) {
            if (!out.containsKey(e.getKey())) out.put(e.getKey(), grantSource(context, e.getKey(), e.getValue()));
        }
        return new ArrayList<>(out.values());
    }

    /** A file the File API sees; reading or deleting it through the grant when the File API is refused (another install's file). */
    static Source fileSource(@Nullable Context context, File f, @Nullable Uri doc) {
        return new Source() {
            @Override public String name() { return f.getName(); }
            @Override public InputStream open() throws IOException {
                try {
                    return new FileInputStream(f);
                } catch (IOException | SecurityException e) {
                    if (context == null || doc == null) throw e instanceof IOException ? (IOException) e : new IOException(e);
                    InputStream in = context.getContentResolver().openInputStream(doc);
                    if (in == null) throw new IOException("no stream for " + doc);
                    return in;
                }
            }
            @Override public boolean renameTo(File target) {
                try {
                    return f.renameTo(target);
                } catch (SecurityException e) {
                    return false;
                }
            }
            @Override public long length() { return f.length(); }
            @Override public long lastModified() { return f.lastModified(); }
            @Override public boolean delete() {
                boolean gone = false;
                try {
                    gone = f.delete();
                } catch (SecurityException ignored) {
                    // through the grant below
                }
                if (!gone && context != null && doc != null) {
                    try {
                        gone = DocumentsContract.deleteDocument(context.getContentResolver(), doc);
                    } catch (Exception e) {
                        Log.w(TAG, "delete through the grant failed: " + e);
                    }
                }
                return gone || !f.exists();
            }
        };
    }

    private static Source grantSource(Context context, String name, Uri doc) {
        return new Source() {
            @Override public String name() { return name; }
            @Override public InputStream open() throws IOException {
                InputStream in = context.getContentResolver().openInputStream(doc);
                if (in == null) throw new IOException("no stream for " + doc);
                return in;
            }
            @Override public boolean renameTo(File target) { return false; }
            @Override public boolean delete() {
                try {
                    return DocumentsContract.deleteDocument(context.getContentResolver(), doc);
                } catch (Exception e) {
                    Log.w(TAG, "delete through the grant failed: " + e);
                    return false;
                }
            }
        };
    }

    /** Moves the sources into {@code dir} (see the class comment); never overwrites a file there. */
    static Result move(List<Source> sources, File dir) {
        Result r = new Result();
        for (Source s : sources) {
            String name = s.name();
            if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) {
                r.kept++;
                r.problems.add("bad name " + name);
                continue;
            }
            try {
                // its bytes already in DCIM (the name, or "_1", "_2"... of an earlier run): no second copy
                if (existingCopy(s, dir, name) != null) {
                    if (s.delete()) r.same++; else { r.kept++; r.problems.add(name + ": already in DCIM, the old one cannot be deleted"); }
                    continue;
                }
                File target = new File(dir, name);
                if (target.exists()) target = free(dir, name);
                if (s.renameTo(target) && target.isFile()) {
                    r.moved++;
                    r.targets.add(target);
                    continue;
                }
                // a hidden temporary name first: only a complete, verified copy takes the photo's name
                File part = new File(dir, PART + name);
                boolean done = false;
                try {
                    copy(s, part);
                    if (!sameBytes(s, part)) {
                        r.kept++;
                        r.problems.add(name + ": the copy differs");
                        continue;
                    }
                    long time = s.lastModified();
                    if (time > 0) part.setLastModified(time);
                    if (!part.renameTo(target)) {
                        r.kept++;
                        r.problems.add(name + ": the copy cannot be named " + target.getName());
                        continue;
                    }
                    done = true;
                } finally {
                    if (!done) part.delete();
                }
                r.targets.add(target);
                if (s.delete()) r.moved++;
                else { r.kept++; r.problems.add(name + ": copied, the old one cannot be deleted"); }
            } catch (IOException | RuntimeException e) {
                r.kept++;
                r.problems.add(name + ": " + e.getMessage());
            }
        }
        return r;
    }

    /** The file in {@code dir} that already holds the source's bytes: the name, then "name_1.ext", "name_2.ext"... while they exist. */
    static File existingCopy(Source s, File dir, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        long size = s.length();
        for (int i = 0; ; i++) {
            File f = new File(dir, i == 0 ? name : base + "_" + i + ext);
            if (!f.exists()) return null;
            if (f.isFile() && (size < 0 || f.length() == size) && sameBytes(s, f)) return f;
        }
    }

    /** "name_1.ext", "name_2.ext"... the first not in {@code dir}. */
    static File free(File dir, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            File f = new File(dir, base + "_" + i + ext);
            if (!f.exists()) return f;
        }
    }

    private static void copy(Source s, File target) throws IOException {
        try (InputStream in = s.open(); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            out.getFD().sync(); // on the storage before the old file goes (a crash in the writeback window must not lose it)
        }
    }

    /** True when {@code target} holds the source's bytes (both read through, in blocks). */
    static boolean sameBytes(Source s, File target) throws IOException {
        try (InputStream a = s.open(); InputStream b = new FileInputStream(target)) {
            byte[] x = new byte[1 << 16], y = new byte[1 << 16];
            while (true) {
                int n = readFully(a, x), m = readFully(b, y);
                if (n != m) return false;
                if (n <= 0) return true;
                for (int i = 0; i < n; i++) if (x[i] != y[i]) return false;
            }
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }
}
