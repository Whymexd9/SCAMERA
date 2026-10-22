package com.particlesdevs.photoncamera.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriPermission;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * A folder of the app in Download/SCAMERA: XML for the configs (ConfigFolder), ToneCurves for the own tone curves (P144). Android
 * 10+ writes through MediaStore (no storage permission); MediaStore lists only the files this app created there, so a file
 * copied in from another phone is opened with the system picker, which starts in the folder ({@link #initialPickerUri()}).
 * <p>
 * Files that other apps, a PC or a file manager put there are not listed by MediaStore (Android 11+ shows an app only its
 * own non-media files). Once the folder is opened to the app through the system folder picker (Download/SCAMERA, a lasting
 * grant: {@link #grantedTree}), every file in it is listed, opened and deleted through that grant (owner, 2026-10-16: «наш
 * апк должен автоматически добавлять новые конфиги в список в случае если я закину его к нам в папку
 * Download/SCAMERA/XML»; «Android/data не подходит»).
 */
public final class ScameraFolder {
    public static final ScameraFolder CONFIGS = new ScameraFolder("XML", "text/xml", ".xml", ".json");
    /** P231: our .txt curves and WhyCam's .json / .curve files (saved as .txt). */
    public static final ScameraFolder CURVES = new ScameraFolder("ToneCurves", "text/plain", ".txt", ".json", ".curve");
    /** P134: film / look LUTs (.cube) for «Плёночный LUT». */
    public static final ScameraFolder LUTS = new ScameraFolder("LUT", "application/octet-stream", ".cube");
    /**
     * P191 (owner, 2026-10-17: «переноси всё туда чтобы в data ничего не хранилось»): Download/SCAMERA itself (scam_dev.txt,
     * the adb markers), every name; its document id is "primary:Download/SCAMERA", no trailing slash. A file there that a
     * PC or a file manager wrote is opened through the grant ({@link #grantedDocument}).
     */
    public static final ScameraFolder ROOT = new ScameraFolder("", "text/plain", "");
    private static final String TAG = "ScameraFolder";

    /** Download/SCAMERA/&lt;sub&gt; as MediaStore's RELATIVE_PATH (and the path shown to the user). */
    public final String relative;
    private final String sub, mime;
    private final String[] extensions;

    private ScameraFolder(String sub, String mime, String... extensions) {
        this.sub = sub;
        this.mime = mime;
        this.extensions = extensions;
        this.relative = relativeOf(sub);
    }

    /** Download/SCAMERA/&lt;sub&gt;; the folder itself for "" (P191: {@link #ROOT}). */
    static String relativeOf(String sub) {
        return Environment.DIRECTORY_DOWNLOADS + "/SCAMERA" + (sub.isEmpty() ? "" : "/" + sub);
    }

    private static final String EXTERNAL = "com.android.externalstorage.documents";
    /** The system's «Загрузки» root: a folder picked there has the id "raw:/storage/emulated/0/Download/..." (P246). */
    private static final String DOWNLOADS = "com.android.providers.downloads.documents";
    private static final String PRIMARY_PATH = "/storage/emulated/0";

    /** The folder's document id on the primary storage ("primary:Download/SCAMERA/XML"). */
    public String documentId() {
        return "primary:" + relative;
    }

    /** Where the folder picker starts for the grant: Download/SCAMERA (one grant covers XML and ToneCurves). */
    public static Uri grantHint() {
        return DocumentsContract.buildDocumentUri(EXTERNAL, "primary:" + Environment.DIRECTORY_DOWNLOADS + "/SCAMERA");
    }

    /**
     * Whether a tree granted at {@code treeId} reaches the document {@code docId} (the same folder or one inside it). Case does
     * not matter (owner, 2026-10-18, P246: shared storage ignores it, a picker may report «download/Scamera»).
     */
    public static boolean covers(String treeId, String docId) {
        if (treeId == null || docId == null) return false;
        String tree = treeId.toLowerCase(Locale.ROOT), doc = docId.toLowerCase(Locale.ROOT);
        if (doc.equals(tree)) return true;
        String prefix = tree.endsWith(":") || tree.endsWith("/") ? tree : tree + "/";
        return doc.startsWith(prefix);
    }

    /**
     * A granted tree's document id as the external storage's ("primary:Download/SCAMERA"): as it is for that provider, mapped from
     * the «Загрузки» root's "raw:/storage/emulated/0/..." path (P246: a folder picked there was ignored), null otherwise.
     */
    static String primaryId(String authority, String treeId) {
        if (treeId == null) return null;
        if (EXTERNAL.equals(authority)) return treeId;
        if (DOWNLOADS.equals(authority) && treeId.startsWith("raw:")) {
            String path = treeId.substring(4);
            if (path.equals(PRIMARY_PATH)) return "primary:";
            if (path.startsWith(PRIMARY_PATH + "/")) return "primary:" + path.substring(PRIMARY_PATH.length() + 1);
        }
        return null;
    }

    /** This folder's document id under a tree of {@code authority} (the «Загрузки» root names it by its path). */
    String documentIdFor(String authority) {
        return DOWNLOADS.equals(authority) ? "raw:" + PRIMARY_PATH + "/" + relative : documentId();
    }

    /** A lasting folder grant that reaches this folder, null without one. */
    @androidx.annotation.Nullable
    public Uri grantedTree(Context context) {
        try {
            for (UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
                Uri u = p.getUri();
                if (!p.isReadPermission() || u == null) continue;
                try {
                    if (covers(primaryId(u.getAuthority(), DocumentsContract.getTreeDocumentId(u)), documentId())) return u;
                } catch (IllegalArgumentException notATree) {
                    // a single document, not a folder
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "folder grants unreadable: " + e);
        }
        return null;
    }

    /**
     * P302: the document at {@code relative} of the primary storage ("Download/SCAMERA/DCIM/Camera/x.jpg") through the lasting
     * grant of Download/SCAMERA ({@link #ROOT}), null without a grant that reaches it. The document need not exist.
     */
    @androidx.annotation.Nullable
    public static Uri grantedPath(Context context, String relative) {
        Uri tree = context == null || relative == null ? null : ROOT.grantedTree(context);
        if (tree == null) return null;
        try {
            if (!covers(primaryId(tree.getAuthority(), DocumentsContract.getTreeDocumentId(tree)), "primary:" + relative)) return null;
            String id = DOWNLOADS.equals(tree.getAuthority()) ? "raw:" + PRIMARY_PATH + "/" + relative : "primary:" + relative;
            return DocumentsContract.buildDocumentUriUsingTree(tree, id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** P302: names of the files (not folders) in the folder at {@code relative} through the grant of {@link #ROOT}. */
    public static Map<String, Uri> grantedChildren(Context context, String relative) {
        Map<String, Uri> out = new LinkedHashMap<>();
        Uri tree = context == null ? null : ROOT.grantedTree(context);
        Uri dir = grantedPath(context, relative);
        if (tree == null || dir == null) return out;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir));
        try (Cursor c = context.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) continue;
                out.put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "listing of " + relative + " through the grant failed: " + e);
        }
        return out;
    }

    /** P191: a file directly in this folder through the lasting grant, null without the grant or the file. */
    @androidx.annotation.Nullable
    public Uri grantedDocument(Context context, String name) {
        return name == null || context == null ? null : treeFiles(context).get(name);
    }

    /** The folder's files (name -> document) through the grant; empty without one or when the folder is not there. */
    Map<String, Uri> treeFiles(Context context) {
        Map<String, Uri> out = new LinkedHashMap<>();
        Uri tree = grantedTree(context);
        if (tree == null) return out;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentIdFor(tree.getAuthority()));
        try (Cursor c = context.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) || !accepts(name)) continue;
                out.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "folder listing through the grant failed: " + e);
        }
        return out;
    }

    private File legacyDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub.isEmpty() ? "SCAMERA" : "SCAMERA/" + sub);
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
        names.addAll(treeFiles(context).keySet());
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
        // P304 (owner, 2026-10-20: «при загрузке конфигов периодически получаю ошибку загрузки»): a file another install wrote
        // (the configs saved before the package became com.scamera.app) is listed by the File API but its open fails with EACCES;
        // the folder's grant opens it. The grant first only when the file cannot be opened directly.
        IOException denied = null;
        if (file.isFile()) {
            try {
                return new FileInputStream(file);
            } catch (IOException | SecurityException e) {
                denied = e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
            }
        }
        Uri doc = treeFiles(context).get(name);
        InputStream in = null;
        try {
            in = doc == null ? null : context.getContentResolver().openInputStream(doc);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "open through the grant failed: " + e);
        }
        if (in != null) return in;
        if (denied != null)
            throw new IOException(Lang.t(context, "нет доступа к файлу (его сохранила прежняя установка): откройте папку кнопкой «Открыть папку» или выберите файл через «Загрузить из файла»",
                    "no access to the file (an earlier install saved it): open the folder with «Open folder» or pick the file with «Load from file»"), denied);
        throw new IOException("file not found: " + relative + "/" + name);
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
        if (!gone) {
            Uri doc = treeFiles(context).get(name);
            try {
                if (doc != null) gone = DocumentsContract.deleteDocument(context.getContentResolver(), doc);
            } catch (Exception e) {
                Log.w(TAG, "delete through the grant failed: " + e);
            }
        }
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
