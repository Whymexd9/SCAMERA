package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * P191 (owner, 2026-10-17: «переноси всё туда чтобы в data ничего не хранилось»): the one folder of the app's own files for
 * the user - scam_dev.txt, the crash report, the adb markers (dump-forward, dump-denoise, noeff), the debug dumps, the kept
 * bursts - is Download/SCAMERA, next to SCAMERA-debug.txt. Android/data/&lt;package&gt;/files is no longer used: a file
 * manager on Android 16 cannot open it. The File API writes there without a permission (the files are the app's own);
 * files a PC, adb or a file manager put there are reached through the folder grant ({@link ScameraFolder#ROOT}).
 */
public final class ScameraFiles {
    private static final String TAG = "ScameraFiles";
    /** Download/SCAMERA as the user sees it. */
    public static final String RELATIVE = Environment.DIRECTORY_DOWNLOADS + "/SCAMERA";

    private ScameraFiles() {}

    /** Download/SCAMERA (made when missing, best effort). */
    public static File dir() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "SCAMERA");
        if (downloads != null && !dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static File file(String name) {
        return new File(dir(), name);
    }

    /**
     * P214b: below Android 11 a file made through the File API is not in MediaStore until the scanner sees it, so it is
     * missing over USB (MTP); the logs that used to be MediaStore entries there (SCAMERA-debug, SCAMERA-timings) ask for
     * that scan when they open a file. From Android 11 on the storage daemon indexes it itself: nothing to do.
     */
    public static void index(@Nullable Context context, File file) {
        if (context == null || file == null || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) return;
        try {
            android.media.MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()},
                    new String[]{"text/plain"}, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "scan of " + file + " failed: " + e);
        }
    }

    /**
     * Whether a file (an adb marker such as dump-forward) is in Download/SCAMERA: the File API, else through the folder grant
     * (adb's own files are not visible to the app's File API on Android 11+).
     */
    public static boolean exists(@Nullable Context context, String name) {
        if (file(name).exists()) return true;
        if (context == null) return false;
        // the grant's listing at most every 2 s per name (markers are asked for on every shot)
        long now = System.nanoTime();
        synchronized (GRANTED) {
            long[] seen = GRANTED.get(name);
            if (seen != null && now - seen[0] < 2_000_000_000L) return seen[1] != 0;
        }
        boolean found;
        try {
            found = ScameraFolder.ROOT.grantedDocument(context, name) != null;
        } catch (RuntimeException e) {
            found = false;
        }
        synchronized (GRANTED) {
            GRANTED.put(name, new long[]{now, found ? 1 : 0});
        }
        return found;
    }

    private static final java.util.Map<String, long[]> GRANTED = new java.util.HashMap<>();

    /**
     * A file of the old place, Android/data/&lt;package&gt;/files/{@code name}, for a migration or a fallback read (the path
     * only: the folder is not made); null without a context.
     */
    @Nullable
    public static File legacy(@Nullable Context context, String name) {
        if (context == null) return null;
        File storage = Environment.getExternalStorageDirectory();
        return storage == null ? null : new File(storage, "Android/data/" + context.getPackageName() + "/files/" + name);
    }

    /**
     * One-time move of a file of the old place (the package's external files dir) to Download/SCAMERA/{@code name}: only when
     * the new file is not there yet (neither for the File API nor through the grant); the old file is deleted once the copy
     * reads back the same. True when moved.
     */
    public static boolean migrate(@Nullable Context context, @Nullable File old, String name) {
        try {
            if (old == null || !old.isFile()) return false;
            File target = file(name);
            if (target.exists() || (context != null && ScameraFolder.ROOT.grantedDocument(context, name) != null)) return false;
            boolean moved = move(old, target);
            Log.i(TAG, (moved ? "moved " : "NOT moved (the copy differs, the old file stays) ") + old + " to " + target);
            return moved;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "move of " + old + " failed: " + e);
            return false;
        }
    }

    /**
     * Copies {@code old} to {@code target} (never over an existing one) and deletes {@code old} once the copy reads back the
     * same bytes; true when the copy is verified (the old file may stay when it cannot be deleted).
     */
    static boolean move(File old, File target) throws IOException {
        if (target.exists()) return false;
        byte[] data = Files.readAllBytes(old.toPath());
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(data);
        }
        byte[] back;
        try (InputStream in = new FileInputStream(target)) {
            back = readAll(in);
        }
        if (!Arrays.equals(data, back)) return false;
        old.delete();
        return true;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
