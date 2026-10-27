package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.util.ScameraFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;

/**
 * P363 (owner, 2026-10-27, lab screenshot: «Снимок не читается: /storage/emulated/0/Download/SCAMERA/Lab/2026-10-21_18-40-36/
 * meta.json»): the lab's files in Download/SCAMERA written by an EARLIER installation of the app (another uid after a
 * reinstall - a CI APK over a local one and back) cannot be read through the File API on Android 11+: MediaProvider denies
 * it, and Files.readAllBytes throws an AccessDeniedException whose message is just the path. Every read of a lab shot or a
 * kept series therefore falls back to the lasting grant of Download/SCAMERA (ScameraFolder.ROOT, as scam_dev.txt does); without
 * a grant it throws {@link Unreadable}, on which the lab offers to open the folder. Deleting falls back the same way.
 */
public final class LabFiles {
    private LabFiles() {}

    /** A lab file the app may not read without the folder grant (written by an earlier installation). */
    public static final class Unreadable extends IOException {
        public final File file;

        Unreadable(File file, @Nullable Throwable cause) {
            super(Lang.t("Файл записан прошлой установкой приложения, и Android не даёт его прочитать: " + file.getName()
                            + ". Откройте приложению папку Download/SCAMERA - кнопка ниже.",
                    "The file was written by an earlier installation of the app and Android does not let it be read: " + file.getName()
                            + ". Open the Download/SCAMERA folder to the app - the button below."), cause);
            this.file = file;
        }
    }

    @Nullable
    private static Context context() {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The path of {@code f} below the primary storage ("Download/SCAMERA/Lab/..."), null when it is not there. */
    @Nullable
    static String relative(File f) {
        final String p = f.getPath().replace('\\', '/'); // the lab's files are absolute already (ScameraFiles.dir)
        for (String root : new String[]{"/storage/emulated/0/", "/sdcard/", "/storage/self/primary/"}) {
            if (p.startsWith(root)) return p.substring(root.length());
        }
        return null;
    }

    /** The document of {@code f} through the folder grant, null without a grant that reaches it. */
    @Nullable
    static Uri granted(File f) {
        final Context c = context();
        final String rel = relative(f);
        return c == null || rel == null ? null : ScameraFolder.grantedPath(c, rel);
    }

    /** Whether the folder grant of Download/SCAMERA is there (the lab hides its «open the folder» button then). */
    public static boolean grantPresent() {
        final Context c = context();
        return c != null && ScameraFolder.ROOT.grantedTree(c) != null;
    }

    /** The whole file: the File API, else through the folder grant. */
    public static byte[] read(File f) throws IOException {
        try {
            return java.nio.file.Files.readAllBytes(f.toPath());
        } catch (IOException | SecurityException e) {
            final Uri u = granted(f);
            final Context c = context();
            if (u == null || c == null) throw new Unreadable(f, e);
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                if (in == null) throw new Unreadable(f, e);
                return com.particlesdevs.photoncamera.util.ScameraFiles.readAll(in);
            } catch (IOException | RuntimeException g) {
                throw new Unreadable(f, g);
            }
        }
    }

    /** The file for reading as a descriptor (the worker maps a burst through it): the File API, else through the grant. */
    public static ParcelFileDescriptor open(File f) throws IOException {
        try {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException | SecurityException e) {
            final Uri u = granted(f);
            final Context c = context();
            if (u == null || c == null) throw new Unreadable(f, e);
            try {
                final ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(u, "r");
                if (pfd == null) throw new Unreadable(f, e);
                return pfd;
            } catch (IOException | RuntimeException g) {
                throw new Unreadable(f, g);
            }
        }
    }

    /** A read channel on the file (closing it closes the descriptor). */
    public static FileChannel channel(File f) throws IOException {
        try {
            return new FileInputStream(f).getChannel();
        } catch (IOException | SecurityException e) {
            return new ParcelFileDescriptor.AutoCloseInputStream(open(f)).getChannel();
        }
    }

    /** A picture file decoded (null when it is not there or not a picture). */
    @Nullable
    public static Bitmap decode(File f, @Nullable BitmapFactory.Options o) {
        if (!f.isFile()) return null;
        final Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (b != null) return b;
        try (ParcelFileDescriptor pfd = open(f)) {
            return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, o);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Deletes a lab file: the File API, else through the grant; true when it is gone. */
    public static boolean delete(File f) {
        if (!f.exists() || f.delete()) return true;
        final Uri u = granted(f);
        final Context c = context();
        if (u == null || c == null) return false;
        try {
            return DocumentsContract.deleteDocument(c.getContentResolver(), u) || !f.exists();
        } catch (Exception e) {
            Log.w("SCAM_LAB", "not deleted through the folder grant: " + f + ": " + e);
            return false;
        }
    }

    /** {@code b.length} bytes from {@code pos} of a channel (an IOException at its end). */
    public static void readFully(FileChannel ch, long pos, byte[] b) throws IOException {
        final java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(b);
        while (buf.hasRemaining()) {
            final int n = ch.read(buf, pos + buf.position());
            if (n < 0) throw new IOException("short file");
        }
    }

    /** A stream on the file (the File API, else the grant). */
    public static InputStream stream(File f) throws IOException {
        try {
            return new FileInputStream(f);
        } catch (IOException | SecurityException e) {
            return new ParcelFileDescriptor.AutoCloseInputStream(open(f));
        }
    }
}
