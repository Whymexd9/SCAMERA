package com.particlesdevs.photoncamera.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.TreeSet;

/**
 * A folder of the app in Download/SCAMERA: XML for the configs (ConfigFolder), ToneCurves for the own tone curves (P144). Android
 * 10+ writes through MediaStore (no storage permission); MediaStore lists only the files this app created there, so a file
 * copied in from another phone is opened with the system picker, which starts in the folder ({@link #initialPickerUri()}).
 */
public final class ScameraFolder {
    public static final ScameraFolder CONFIGS = new ScameraFolder("XML", "text/xml", ".xml", ".json");
    public static final ScameraFolder CURVES = new ScameraFolder("ToneCurves", "text/plain", ".txt");
    private static final String TAG = "ScameraFolder";

    /** Download/SCAMERA/&lt;sub&gt; as MediaStore's RELATIVE_PATH (and the path shown to the user). */
    public final String relative;
    private final String sub, mime;
    private final String[] extensions;

    private ScameraFolder(String sub, String mime, String... extensions) {
        this.sub = sub;
        this.mime = mime;
        this.extensions = extensions;
        this.relative = Environment.DIRECTORY_DOWNLOADS + "/SCAMERA/" + sub;
    }

    private File legacyDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SCAMERA/" + sub);
    }

    private static boolean mediaStore() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Writes the file (replacing one of the same name this app saved) and returns its shown path. */
    public String save(Context context, String name, byte[] data) throws IOException {
        if (!mediaStore()) {
            File dir = legacyDir();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File file = new File(dir, name);
            try (OutputStream out = new FileOutputStream(file)) { out.write(data); }
            return relative + "/" + file.getName();
        }
        ContentResolver resolver = context.getContentResolver();
        Uri existing = find(context, name);
        if (existing != null) {
            try (OutputStream out = resolver.openOutputStream(existing, "wt")) {
                if (out == null) throw new IOException("cannot open " + name);
                out.write(data);
            }
            return relative + "/" + name;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, relative);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("MediaStore refused " + name);
        try (OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("cannot open " + name);
            out.write(data);
        } catch (IOException | RuntimeException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        // MediaStore renames on a clash with a file another app owns: report the name it kept.
        String kept = name;
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) kept = c.getString(0);
        }
        return relative + "/" + kept;
    }

    /** The names of the folder's files with one of its extensions, sorted. */
    public String[] list(Context context) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (mediaStore()) {
            try (Cursor c = context.getContentResolver().query(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    new String[]{MediaStore.Downloads.DISPLAY_NAME}, MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{relative + "/"}, null)) {
                while (c != null && c.moveToNext()) if (accepts(c.getString(0))) names.add(c.getString(0));
            } catch (RuntimeException e) {
                Log.w(TAG, "MediaStore list failed: " + e);
            }
        }
        // the File API sees what MediaStore hides on some builds (and everything below Android 10)
        String[] files = legacyDir().list((dir, n) -> accepts(n));
        if (files != null) names.addAll(Arrays.asList(files));
        return names.toArray(new String[0]);
    }

    public InputStream open(Context context, String name) throws IOException {
        if (mediaStore()) {
            Uri uri = find(context, name);
            if (uri != null) {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in != null) return in;
            }
        }
        File file = new File(legacyDir(), name);
        if (!file.isFile()) throw new IOException("file not found: " + relative + "/" + name);
        return new FileInputStream(file);
    }

    /**
     * Deletes a file of the folder (MediaStore: one this app saved; the File API for the rest), true when it is gone. Only
     * names with the folder's extensions: a name with a path never leaves it.
     */
    public boolean delete(Context context, String name) {
        if (!accepts(name) || name.contains("/") || name.contains("\\")) return false;
        boolean gone = false;
        if (mediaStore()) {
            Uri uri = find(context, name);
            if (uri != null) {
                try {
                    gone = context.getContentResolver().delete(uri, null, null) > 0;
                } catch (RuntimeException e) {
                    Log.w(TAG, "MediaStore delete failed: " + e);
                }
            }
        }
        File file = new File(legacyDir(), name);
        if (file.isFile()) gone = file.delete() || gone;
        return gone;
    }

    /** Document id of the folder for ACTION_OPEN_DOCUMENT's EXTRA_INITIAL_URI. */
    public Uri initialPickerUri() {
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + relative);
    }

    /** Whether the name has one of the folder's extensions. */
    public boolean accepts(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : extensions) if (n.endsWith(e)) return true;
        return false;
    }

    /**
     * A file name typed by the user: trimmed, the folder's extension dropped, spaces to «_», characters a file name cannot hold
     * to «_»; "" when nothing is left.
     */
    public String baseName(String typed) {
        String base = typed == null ? "" : typed.trim();
        for (String e : extensions) {
            if (base.toLowerCase(Locale.ROOT).endsWith(e)) base = base.substring(0, base.length() - e.length());
        }
        return base.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private Uri find(Context context, String name) {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (Cursor c = context.getContentResolver().query(collection, new String[]{MediaStore.Downloads._ID},
                MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                new String[]{name, relative + "/"}, null)) {
            if (c != null && c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0));
        } catch (RuntimeException e) {
            Log.w(TAG, "MediaStore lookup failed: " + e);
        }
        return null;
    }
}
