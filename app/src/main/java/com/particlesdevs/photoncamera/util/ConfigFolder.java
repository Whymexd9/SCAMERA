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
import java.util.TreeSet;

/**
 * Download/SCAMERA/XML: where configs are saved and listed for import. Android 10+ writes through MediaStore (no storage
 * permission); MediaStore lists only the files this app created there, so a config copied in from another phone is opened
 * with the system picker, which starts in this folder ({@link #initialPickerUri()}).
 */
public final class ConfigFolder {
    public static final String RELATIVE = Environment.DIRECTORY_DOWNLOADS + "/SCAMERA/XML";
    private static final String TAG = "ConfigFolder";

    private ConfigFolder() {}

    private static File legacyDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SCAMERA/XML");
    }

    private static boolean mediaStore() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Writes the file (replacing a config of the same name this app saved) and returns its shown path. */
    public static String save(Context context, String name, byte[] data) throws IOException {
        if (!mediaStore()) {
            File dir = legacyDir();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File file = new File(dir, name);
            try (OutputStream out = new FileOutputStream(file)) { out.write(data); }
            return RELATIVE + "/" + file.getName();
        }
        ContentResolver resolver = context.getContentResolver();
        Uri existing = find(context, name);
        if (existing != null) {
            try (OutputStream out = resolver.openOutputStream(existing, "wt")) {
                if (out == null) throw new IOException("cannot open " + name);
                out.write(data);
            }
            return RELATIVE + "/" + name;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/xml");
        values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE);
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
        return RELATIVE + "/" + kept;
    }

    /** Config names in the folder (.xml, and the old .json backups if someone put them there), sorted. */
    public static String[] list(Context context) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (mediaStore()) {
            try (Cursor c = context.getContentResolver().query(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    new String[]{MediaStore.Downloads.DISPLAY_NAME}, MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{RELATIVE + "/"}, null)) {
                while (c != null && c.moveToNext()) if (isConfig(c.getString(0))) names.add(c.getString(0));
            } catch (RuntimeException e) {
                Log.w(TAG, "MediaStore list failed: " + e);
            }
        }
        // the File API sees what MediaStore hides on some builds (and everything below Android 10)
        String[] files = legacyDir().list((dir, n) -> isConfig(n));
        if (files != null) names.addAll(Arrays.asList(files));
        return names.toArray(new String[0]);
    }

    public static InputStream open(Context context, String name) throws IOException {
        if (mediaStore()) {
            Uri uri = find(context, name);
            if (uri != null) {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in != null) return in;
            }
        }
        File file = new File(legacyDir(), name);
        if (!file.isFile()) throw new IOException("file not found: " + RELATIVE + "/" + name);
        return new FileInputStream(file);
    }

    /** Document id of the folder for ACTION_OPEN_DOCUMENT's EXTRA_INITIAL_URI. */
    public static Uri initialPickerUri() {
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + RELATIVE);
    }

    static boolean isConfig(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".xml") || n.endsWith(".json");
    }

    private static Uri find(Context context, String name) {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (Cursor c = context.getContentResolver().query(collection, new String[]{MediaStore.Downloads._ID},
                MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                new String[]{name, RELATIVE + "/"}, null)) {
            if (c != null && c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0));
        } catch (RuntimeException e) {
            Log.w(TAG, "MediaStore lookup failed: " + e);
        }
        return null;
    }
}
