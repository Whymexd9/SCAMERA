package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import com.anggrayudi.storage.file.DocumentFileCompat;
import com.anggrayudi.storage.file.DocumentFileType;
import com.anggrayudi.storage.file.FileFullPath;
import com.anggrayudi.storage.file.StorageId;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper around SimpleStorage library for folder access and path resolution.
 * Uses DCIM/SCAMERA as the target path; persists via SAF so export/import and native writing work.
 *
 * <p>Call {@link #init(Context)} once from {@code ScameraApp.initModules()} so all
 * methods can operate without a per-call Context parameter.
 *
 * @see <a href="https://github.com/anggrayudi/SimpleStorage">SimpleStorage</a>
 */
public final class SimpleStorageHelper {

    private static final String TAG = "SimpleStorageHelper";

    private static Context sContext;

    /** Initialize the module. Call once from {@code ScameraApp.initModules()}. */
    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    /** Base path we request access to (picker opens here when supported). */
    public static final String DCIM_BASE_PATH = "DCIM";

    /** The app's folder: RAW files (Raw/), logs (Log/). */
    public static final String SCAMERA_RELATIVE_PATH = "DCIM/SCAMERA";

    /** True if the app has SAF access to DCIM (a grant of DCIM, of a folder inside it, or of a whole storage root). */
    public static boolean hasStorageAccess(Context context) {
        if (context == null) return false;
        try {
            return dcimOf(accessiblePaths(context)) != null;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "hasStorageAccess: " + t.getMessage());
            return false;
        }
    }

    private static java.util.List<String> accessiblePaths(Context context) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Map<String, Set<String>> pathMap = DocumentFileCompat.getAccessibleAbsolutePaths(context);
        if (pathMap == null) return out;
        for (Set<String> paths : pathMap.values()) if (paths != null) for (String p : paths) if (p != null) out.add(p);
        return out;
    }

    private static final java.util.regex.Pattern STORAGE_DCIM =
            java.util.regex.Pattern.compile("^(/storage/(?:emulated/[0-9]+|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}))(?:/(DCIM)(?:/.*)?)?/?$");

    /**
     * P302 (owner, 2026-10-20: «/storage/emulated/0/Download/SCAMERA/DCIM/Camera/ и …/Download/SCAMERA/DCIM/SCAMERA/Raw/ перенести
     * обратно в /storage/emulated/0/DCIM/»): the DCIM folder ("/storage/emulated/0/DCIM/") of the lasting grants - of DCIM
     * itself, of a folder inside it or of a whole storage root (the primary storage first); null for any other. The first grant
     * of all was taken before, whatever it was: the Download/SCAMERA grant of P191 made "Download/SCAMERA/DCIM/" the photos' place
     * and counted as DCIM access.
     */
    static String dcimOf(Iterable<String> paths) {
        String other = null;
        for (String path : paths) {
            if (path == null) continue;
            java.util.regex.Matcher m = STORAGE_DCIM.matcher(path);
            if (!m.matches()) continue;
            String dcim = m.group(1) + "/DCIM/";
            if (m.group(1).startsWith("/storage/emulated/")) return dcim;
            if (other == null) other = dcim;
        }
        return other;
    }

    /**
     * Updates FileManager static paths from SAF accessible paths: the DCIM of a grant ({@link #dcimOf}), else the primary
     * storage's DCIM (the File API writes photos and DNGs there itself).
     */
    public static void updateFileManagerPaths(Context context) {
        if (context == null) return;
        String dcimPath = null;
        try {
            dcimPath = dcimOf(accessiblePaths(context));
        } catch (Throwable t) {
            Log.e(TAG, "updateFileManagerPaths: " + t.getMessage());
        }
        if (dcimPath == null) dcimPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/";
        FileManager.sDCIM_CAMERA = new File(dcimPath + "Camera");
        FileManager.sSCAMERA_DIR = new File(dcimPath + "SCAMERA");
        FileManager.sRAW_DIR = new File(dcimPath + "SCAMERA/Raw");
        Log.d(TAG, "Updated paths: DCIM_CAMERA=" + FileManager.sDCIM_CAMERA);
    }

    /** True if a file at the given path (relative to primary storage root) exists and is accessible. */
    public static boolean fileExistsByPath(Context context, String relativePath) {
        try {
            DocumentFile file = DocumentFileCompat.INSTANCE.fromSimplePath(context, StorageId.PRIMARY, relativePath);
            return file != null && file.exists();
        } catch (Throwable t) {
            Log.e(TAG, "fileExistsByPath: " + t.getMessage());
            return false;
        }
    }

    /** Opens an InputStream for a file at the given path relative to primary storage root. Caller must close it. */
    public static InputStream openInputStreamByPath(Context context, String relativePath) throws Exception {
        DocumentFile file = DocumentFileCompat.INSTANCE.fromSimplePath(context, StorageId.PRIMARY, relativePath);
        if (file == null || !file.exists())
            throw new java.io.FileNotFoundException("File not found: " + relativePath);
        InputStream is = context.getContentResolver().openInputStream(file.getUri());
        if (is == null) throw new IOException("Failed to open stream for: " + relativePath);
        return is;
    }

    /** Builds the initial path for RequestStorageAccessContract so the picker opens at DCIM. */
    public static FileFullPath createDcimInitialPath(Context context) {
        return new FileFullPath(context, StorageId.PRIMARY, DCIM_BASE_PATH);
    }

    /**
     * Creates a file at {@code absolutePath} via SAF and returns a detached raw POSIX fd for
     * native writing. Required on Android 11+ where the FUSE MediaProvider blocks {@code fopen()}
     * for non-image file types (e.g. {@code .flac}) inside DCIM directories.
     *
     * <p>SimpleStorage's {@code fromSimplePath} automatically resolves the SAF-granted tree and
     * navigates to any subdirectory under the granted root — no manual tree walking needed.
     *
     * @return raw file descriptor ≥ 0, or -1 on failure
     */
    public static int openFdForWrite(String absolutePath) {
        if (sContext == null || absolutePath == null) return -1;
        try {
            DocumentFile newFile = createDocumentFile(absolutePath);
            if (newFile == null) return -1;

            ParcelFileDescriptor pfd = sContext.getContentResolver()
                    .openFileDescriptor(newFile.getUri(), "w");
            if (pfd == null) return -1;
            return pfd.detachFd();

        } catch (Exception e) {
            Log.e(TAG, "openFdForWrite: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Creates a file at {@code absolutePath} via SAF and returns an {@link OutputStream} for
     * Java-side writing (text, JSON, CSV, etc.). Required on Android 11+ for the same FUSE
     * reasons as {@link #openFdForWrite}.
     *
     * <p>Falls back to {@code null} on failure; callers should fall back to
     * {@link java.nio.file.Files#newOutputStream} for app-specific directories.
     *
     * @return writable {@link OutputStream}, or {@code null} on failure
     */
    public static OutputStream openOutputStreamByAbsPath(String absolutePath) {
        if (sContext == null || absolutePath == null) return null;
        try {
            DocumentFile newFile = createDocumentFile(absolutePath);
            if (newFile == null) return null;
            return sContext.getContentResolver().openOutputStream(newFile.getUri(), "w");
        } catch (Exception e) {
            Log.e(TAG, "openOutputStreamByAbsPath: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the parent directory via SAF, deletes any pre-existing file with the same name,
     * and creates a new {@link DocumentFile} ready for writing.
     *
     * @return the new {@link DocumentFile}, or {@code null} on any failure
     */
    private static DocumentFile createDocumentFile(String absolutePath) {
        File target = new File(absolutePath);
        String fileName = target.getName();
        String parentAbs = target.getParent();
        if (parentAbs == null) return null;

        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (!parentAbs.startsWith(storageRoot)) return null;
        String relativeParent = parentAbs.substring(storageRoot.length()).replaceAll("^/+", "");

        DocumentFile parentDir = DocumentFileCompat.fromSimplePath(
                sContext, StorageId.PRIMARY, relativeParent, DocumentFileType.FOLDER, true);
        if (parentDir == null || !parentDir.exists()) {
            Log.e(TAG, "createDocumentFile: folder not found via SAF: " + relativeParent);
            return null;
        }

        DocumentFile existing = parentDir.findFile(fileName);
        if (existing != null) existing.delete();

        DocumentFile newFile = parentDir.createFile(guessMime(fileName), fileName);
        if (newFile == null)
            Log.e(TAG, "createDocumentFile: createFile failed for " + fileName);
        return newFile;
    }

    private static String guessMime(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".flac"))             return "audio/flac";
        if (lower.endsWith(".png"))              return "image/png";
        // Photos in every format the camera writes (jpg / heic / heif / webp / avif); DNG stays a plain binary as before.
        String photo = com.particlesdevs.photoncamera.processing.PhotoFormat.mimeForName(lower);
        if (photo != null && photo.startsWith("image/") && !lower.endsWith(".dng")) return photo;
        if (lower.endsWith(".csv")
                || lower.endsWith(".gcsv"))      return "text/csv";
        if (lower.endsWith(".txt"))              return "text/plain";
        if (lower.endsWith(".json"))             return "application/json";
        return "application/octet-stream";
    }
}
