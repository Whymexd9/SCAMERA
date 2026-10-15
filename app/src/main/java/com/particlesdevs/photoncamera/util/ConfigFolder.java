package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

/**
 * Download/SCAMERA/XML: where configs are saved and listed for import ({@link ScameraFolder#CONFIGS}). Android 10+ writes
 * through MediaStore (no storage permission); MediaStore lists only the files this app created there, so a config copied in
 * from another phone is opened with the system picker, which starts in this folder ({@link #initialPickerUri()}).
 */
public final class ConfigFolder {
    public static final String RELATIVE = ScameraFolder.CONFIGS.relative;

    private ConfigFolder() {}

    /** Writes the file (replacing a config of the same name this app saved) and returns its shown path. */
    public static String save(Context context, String name, byte[] data) throws IOException {
        return ScameraFolder.CONFIGS.save(context, name, data);
    }

    /** Config names in the folder (.xml, and the old .json backups if someone put them there), sorted. */
    public static String[] list(Context context) {
        return ScameraFolder.CONFIGS.list(context);
    }

    public static InputStream open(Context context, String name) throws IOException {
        return ScameraFolder.CONFIGS.open(context, name);
    }

    /**
     * P143: deletes a config of the folder (MediaStore: one this app saved; the File API for the rest), true when it is gone.
     * Only {@link #isConfig} names inside the folder: a name with a path never leaves it.
     */
    public static boolean delete(Context context, String name) {
        return ScameraFolder.CONFIGS.delete(context, name);
    }

    /** Document id of the folder for ACTION_OPEN_DOCUMENT's EXTRA_INITIAL_URI. */
    public static Uri initialPickerUri() {
        return ScameraFolder.CONFIGS.initialPickerUri();
    }

    static boolean isConfig(String name) {
        return ScameraFolder.CONFIGS.accepts(name);
    }
}
