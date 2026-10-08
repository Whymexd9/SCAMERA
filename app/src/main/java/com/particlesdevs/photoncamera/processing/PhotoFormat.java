package com.particlesdevs.photoncamera.processing;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Codec of the processed photo (setting «Формат фото» / "Photo format", key {@link #KEY}): JPEG (default, the only
 * format that carries Ultra HDR), HEIC (androidx.heifwriter, API 28+) or WebP (Bitmap.compress). The rules here are
 * plain functions so the save path, the settings screen, the shade and the gallery share them.
 */
public enum PhotoFormat {
    JPEG("jpeg", "jpg", "image/jpeg", "JPEG", "J"),
    HEIC("heic", "heic", "image/heic", "HEIC", "H"),
    WEBP("webp", "webp", "image/webp", "WEBP", "W");

    /** ListPreference key of the format; values {@link #value}. */
    public static final String KEY = "pref_photo_format";
    public static final String KEY_HEIC_QUALITY = "pref_heic_quality";
    /** «HEIC 10 бит»: the HEIC is coded in HEVC Main10 from a 10-bit final image (Android 13+, heif/Heic10Support). */
    public static final String KEY_HEIC_10BIT = "pref_heic_10bit";
    public static final String KEY_WEBP_QUALITY = "pref_webp_quality";
    public static final String KEY_WEBP_LOSSLESS = "pref_webp_lossless";
    /** «Также сохранять JPEG»: a JPEG (with Ultra HDR when that is on) next to the HEIC / WebP photo. */
    public static final String KEY_ALSO_JPEG = "pref_photo_also_jpeg";
    public static final int DEFAULT_QUALITY = 90;
    /** HEIC needs the platform HEIF support of Android 9 (androidx.heifwriter's minSdk, HEIF decode in the gallery). */
    public static final int HEIC_MIN_SDK = 28;
    /** Largest side a WebP file can have (14-bit dimensions). */
    public static final int WEBP_MAX_SIDE = 16383;

    public final String value;
    public final String extension;
    public final String mime;
    /** Short label of the codec (top bar chip, shade tile). */
    public final String label;
    /** Second letter of the RAW + codec label ("R+J", "R+H", "R+W"). */
    final String letter;

    PhotoFormat(String value, String extension, String mime, String label, String letter) {
        this.value = value;
        this.extension = extension;
        this.mime = mime;
        this.label = label;
        this.letter = letter;
    }

    /** The stored list value; anything unknown (or null) is JPEG. */
    public static PhotoFormat parse(Object stored) {
        if (stored != null) {
            String v = stored.toString().trim().toLowerCase(Locale.ROOT);
            for (PhotoFormat f : values()) if (f.value.equals(v)) return f;
        }
        return JPEG;
    }

    /** The format a shot is written in on this Android version: HEIC below Android 9 is saved as JPEG. */
    public static PhotoFormat effective(PhotoFormat chosen, int sdk) {
        return chosen == HEIC && sdk < HEIC_MIN_SDK ? JPEG : chosen;
    }

    /** Whether an image of this size can be written in the format (WebP: at most 16383 px per side). */
    public boolean fits(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        return this != WEBP || (width <= WEBP_MAX_SIDE && height <= WEBP_MAX_SIDE);
    }

    /** Whether the shot writes a JPEG file: the JPEG format itself, or «Также сохранять JPEG» next to HEIC / WebP. */
    public static boolean writesJpeg(PhotoFormat format, boolean alsoJpeg) {
        return format == JPEG || alsoJpeg;
    }

    /** Ultra HDR is a JPEG feature: it applies when the setting is on and the shot writes a JPEG. */
    public static boolean ultraHdrApplies(boolean ultraHdr, PhotoFormat format, boolean alsoJpeg) {
        return ultraHdr && writesJpeg(format, alsoJpeg);
    }

    /** {@code base} (a path without extension) with this format's extension. */
    public Path fileFor(Path base) {
        return base.resolveSibling(base.getFileName().toString() + '.' + extension);
    }

    /** Short label of the save mode (0 = photo only, 1 = RAW + photo, 2 = RAW only) with this codec. */
    public String saveModeShort(int saveMode) {
        if (saveMode == 2) return "RAW";
        return saveMode == 1 ? "R+" + letter : label;
    }

    /** Full label of the save mode with this codec ("RAW + HEIC"). */
    public String saveModeLong(int saveMode) {
        String name = this == WEBP ? "WebP" : label;
        if (saveMode == 2) return "RAW";
        return saveMode == 1 ? "RAW + " + name : name;
    }

    /** Lower-case extension of a file name without the dot, "" when there is none. */
    public static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot <= slash ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** MIME type of a photo / RAW file name (null for other files). MimeTypeMap lacks HEIC on older Android versions. */
    public static String mimeForName(String name) {
        switch (extensionOf(name)) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "webp": return "image/webp";
            case "dng": return "image/x-adobe-dng";
            case "png": return "image/png";
            default: return null;
        }
    }

    /** Whether the gallery lists a file of this name (photos in every format the camera writes, and DNG). */
    public static boolean isGalleryFile(String name) {
        switch (extensionOf(name)) {
            case "jpg": case "jpeg": case "dng": case "heic": case "heif": case "webp": return true;
            default: return false;
        }
    }

    /** Whether the platform decodes the file in the gallery: HEIC / HEIF from Android 9, the rest always. */
    public static boolean decodable(String name, int sdk) {
        String ext = extensionOf(name);
        if (ext.equals("heic") || ext.equals("heif")) return sdk >= HEIC_MIN_SDK;
        return isGalleryFile(name);
    }

    /** A photo encoded as HEIC / HEIF or WebP (not JPEG, not DNG): the gallery decodes these with a bitmap fallback. */
    public static boolean isModernPhoto(String name) {
        String ext = extensionOf(name);
        return ext.equals("heic") || ext.equals("heif") || ext.equals("webp");
    }
}
