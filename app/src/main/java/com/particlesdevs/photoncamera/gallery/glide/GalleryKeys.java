package com.particlesdevs.photoncamera.gallery.glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.signature.ObjectKey;
import com.particlesdevs.photoncamera.gallery.files.MediaFile;

import java.io.File;

/**
 * P81 Glide cache keys of the gallery's pictures (owner, 2026-10-10: a WebP shown blank until the app restarts). The
 * MediaStore row exists from the moment the camera opens the file, and its DATE_ADDED never changes: a picture decoded
 * while the encoder (7-9 s for a WebP / AVIF of 12 MP) or the EXIF / ICC rewrite still ran stayed in the memory and disk
 * caches under the same key. The key now carries the file's current length and modification time (one stat).
 */
public final class GalleryKeys {
    private GalleryKeys() {}

    /** The key of {@code file}'s pictures; {@code prefix} keeps different renditions apart (the histogram's). */
    @NonNull
    public static Key of(@NonNull MediaFile file, @NonNull String prefix) {
        long length = file.getSize(), modified = file.getLastModified();
        final String path = file.getAbsolutePath();
        final File f = path != null ? new File(path) : null;
        if (f != null && f.isAbsolute()) { // a DATA path (a content URI string of an opened file is not)
            final long l = f.length(), m = f.lastModified();
            if (l > 0 || m > 0) {
                length = l;
                modified = m;
            }
        }
        return new ObjectKey(key(prefix, file.getDisplayName(), modified, length));
    }

    @NonNull
    public static Key of(@NonNull MediaFile file) {
        return of(file, "");
    }

    /**
     * P89: while the camera is still writing {@code file} (SavingFiles), runs {@code then} on {@code view}'s thread once it is
     * done (checked every 0.4 s, at most 3 minutes) and returns true: the caller decodes nothing now (a WebP read mid-write
     * comes out partly black and would be shown, and cached, as it is).
     */
    public static boolean waitForFile(@NonNull android.view.View view, @NonNull MediaFile file, @NonNull Runnable then) {
        final String path = file.getAbsolutePath();
        if (!com.particlesdevs.photoncamera.processing.SavingFiles.writing(path)) return false;
        final long until = android.os.SystemClock.uptimeMillis() + 180_000;
        view.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (com.particlesdevs.photoncamera.processing.SavingFiles.writing(path) && android.os.SystemClock.uptimeMillis() < until)
                    view.postDelayed(this, 400);
                else
                    then.run();
            }
        }, 400);
        return true;
    }

    static String key(String prefix, @Nullable String name, long modified, long length) {
        return prefix + name + "|" + modified + "|" + length;
    }
}
