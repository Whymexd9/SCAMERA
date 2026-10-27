package com.particlesdevs.photoncamera.gallery.editor;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.raw.RawFormats;
import com.particlesdevs.photoncamera.gallery.ui.GalleryActivity;
import com.particlesdevs.photoncamera.processing.ImagePath;

import org.apache.commons.io.FileUtils;

import java.util.Locale;

/**
 * P351: routing of «Изменить»: which files the built-in editor opens, the editor's arguments, and the old route - another
 * app's editor through ACTION_EDIT (kept for a long press on «Изменить» and «Открыть в другом приложении»; this gallery's own
 * ACTION_EDIT entry is left out of that chooser).
 */
public final class ExternalEdit {
    public static final String ARG_URI = "editUri", ARG_NAME = "editName", ARG_PATH = "editPath", ARG_MIME = "editMime",
            ARG_EXTERNAL = "editExternal";
    /**
     * P367 test hook (the owner's helper checks the full-resolution zoom over adb, without touching the screen): optional float
     * extras of the gallery's ACTION_EDIT intent, copied into the editor's arguments ({@link #withZoomHook}). With
     * {@link #ARG_ZOOM} (1.0 = 100 % of the original's pixels, clamped to the fit .. 400 %) the editor starts zoomed once the
     * picture is shown, the crop point {@link #ARG_ZOOM_CX}, {@link #ARG_ZOOM_CY} (0..1 of the crop, 0.5 when absent) at the
     * view's centre; logcat then has EditorView's «P367 full-res region shown» line. Without the extras nothing changes:
     * <pre>
     * adb shell am start -a android.intent.action.EDIT -t image/jpeg -d content://media/external/images/media/ID \
     *   -n com.scamera.app/com.particlesdevs.photoncamera.gallery.ui.GalleryActivity \
     *   --ef scam_edit_zoom 1.0 --ef scam_edit_cx 0.5 --ef scam_edit_cy 0.5
     * </pre>
     */
    public static final String ARG_ZOOM = "scam_edit_zoom", ARG_ZOOM_CX = "scam_edit_cx", ARG_ZOOM_CY = "scam_edit_cy";

    private ExternalEdit() {}

    /** Photos the editor opens: JPEG, HEIC / HEIF, WebP, AVIF, PNG, GIF, BMP and every camera RAW (by name or MIME). */
    public static boolean editable(@Nullable String name, @Nullable String mime) {
        if (RawFormats.isRawName(name) || RawFormats.isRawMime(mime)) return true;
        switch (RawFormats.extensionOf(name)) {
            case "jpg": case "jpeg": case "heic": case "heif": case "webp": case "avif": case "png": case "gif": case "bmp":
                return true;
            default:
                return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/");
        }
    }

    public static Bundle args(Uri uri, String name, @Nullable String path, @Nullable String mime, boolean external) {
        Bundle b = new Bundle();
        b.putString(ARG_URI, uri.toString());
        b.putString(ARG_NAME, name);
        b.putString(ARG_PATH, path);
        b.putString(ARG_MIME, mime);
        b.putBoolean(ARG_EXTERNAL, external);
        return b;
    }

    /** P367: the test hook's extras of {@code intent} ({@link #ARG_ZOOM}) into the editor's {@code args}; nothing when absent. */
    public static Bundle withZoomHook(Bundle args, @Nullable Intent intent) {
        if (intent == null || !intent.hasExtra(ARG_ZOOM)) return args;
        float z = intent.getFloatExtra(ARG_ZOOM, 0f);
        if (!(z > 0f) || Float.isInfinite(z)) return args;
        args.putFloat(ARG_ZOOM, z);
        args.putFloat(ARG_ZOOM_CX, intent.getFloatExtra(ARG_ZOOM_CX, 0.5f));
        args.putFloat(ARG_ZOOM_CY, intent.getFloatExtra(ARG_ZOOM_CY, 0.5f));
        return args;
    }

    /** MIME type for an edit / share intent (the photo formats and RAWs mapped here; MimeTypeMap lacks some). */
    public static String mediaTypeOf(String fileName) {
        String known = com.particlesdevs.photoncamera.processing.PhotoFormat.mimeForName(fileName);
        if (known != null) return known;
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileUtils.getExtension(fileName));
        return guessed != null ? guessed : "image/*";
    }

    /** Another app's editor (the chooser without this gallery); the result comes back as Constants.REQUEST_EDIT_IMAGE. */
    public static void openInOtherApp(Fragment f, Uri uri, String fileName) {
        Intent edit = new Intent(Intent.ACTION_EDIT);
        edit.setDataAndType(uri, mediaTypeOf(fileName));
        String out = uri.toString().replace(fileName, ImagePath.newBaseName() + '.' + FileUtils.getExtension(fileName));
        edit.putExtra(MediaStore.EXTRA_OUTPUT, out);
        edit.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(edit, null);
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{
                new ComponentName(f.requireContext(), GalleryActivity.class),
                new ComponentName(f.requireContext().getPackageName(), GalleryActivity.class.getName() + "Launcher")});
        f.startActivityForResult(chooser, Constants.REQUEST_EDIT_IMAGE);
    }
}
