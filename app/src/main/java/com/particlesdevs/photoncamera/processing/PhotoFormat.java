package com.particlesdevs.photoncamera.processing;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Codec of the processed photo (setting «Формат фото» / "Photo format", key {@link #KEY}): JPEG (default, the only
 * format that carries Ultra HDR), HEIC (androidx.heifwriter, API 28+), WebP (Bitmap.compress) or AVIF (the bundled
 * libavif + libaom, processing/avif/AvifEncoder: 10 / 12-bit and 4:4:4; offered from Android 12, which decodes it). The
 * rules here are plain functions so the save path, the settings screen, the shade and the gallery share them.
 */
public enum PhotoFormat {
    JPEG("jpeg", "jpg", "image/jpeg", "JPEG", "J"),
    HEIC("heic", "heic", "image/heic", "HEIC", "H"),
    WEBP("webp", "webp", "image/webp", "WEBP", "W"),
    AVIF("avif", "avif", "image/avif", "AVIF", "A");

    /** ListPreference key of the format; values {@link #value}. */
    public static final String KEY = "pref_photo_format";
    public static final String KEY_HEIC_QUALITY = "pref_heic_quality";
    public static final String KEY_WEBP_QUALITY = "pref_webp_quality";
    public static final String KEY_WEBP_LOSSLESS = "pref_webp_lossless";
    /** AVIF: quality 1-100, «Без потерь», «Глубина цвета» (8 / 10 / 12), chroma (444 / 420) and the encoder speed. */
    public static final String KEY_AVIF_QUALITY = "pref_avif_quality";
    public static final String KEY_AVIF_LOSSLESS = "pref_avif_lossless";
    public static final String KEY_AVIF_DEPTH = "pref_avif_depth";
    public static final String KEY_AVIF_CHROMA = "pref_avif_chroma";
    public static final String KEY_AVIF_SPEED = "pref_avif_speed";
    /** «Также сохранять JPEG»: a JPEG (with Ultra HDR when that is on) next to the HEIC / WebP / AVIF photo. */
    public static final String KEY_ALSO_JPEG = "pref_photo_also_jpeg";
    public static final int DEFAULT_QUALITY = 90;
    /** HEIC needs the platform HEIF support of Android 9 (androidx.heifwriter's minSdk, HEIF decode in the gallery). */
    public static final int HEIC_MIN_SDK = 28;
    /** Largest side a WebP file can have (14-bit dimensions). */
    public static final int WEBP_MAX_SIDE = 16383;
    /**
     * AVIF needs Android 12: ImageDecoder / BitmapFactory decode it from there (the gallery, Google Photos, other apps);
     * below it AVIF is not offered and a stored AVIF choice is saved as JPEG.
     */
    public static final int AVIF_MIN_SDK = 31;
    /**
     * Largest AVIF photo: 64 MP holds the 50 MP sensors' full resolution. A 200 MP photo would need ~7 GB of encoder
     * memory and a minute of encoding, so it is saved as JPEG.
     */
    public static final long AVIF_MAX_PIXELS = 64_000_000L;
    /** AVIF defaults: 10-bit (more than JPEG's 8 bits), libavif speed 6 (12 MP in about 1-2 s on an 8-core phone). */
    public static final int AVIF_DEFAULT_DEPTH = 10, AVIF_DEFAULT_SPEED = 6;

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
        return effective(chosen, sdk, true);
    }

    /**
     * The format a shot is written in: HEIC below Android 9 and AVIF below Android 12 or without the AVIF encoder
     * ({@code avifEncoder}: AvifEncoder.available()) are saved as JPEG.
     */
    public static PhotoFormat effective(PhotoFormat chosen, int sdk, boolean avifEncoder) {
        if (chosen == HEIC && sdk < HEIC_MIN_SDK) return JPEG;
        if (chosen == AVIF && !avifOffered(sdk, avifEncoder)) return JPEG;
        return chosen;
    }

    /** Whether AVIF is offered: Android 12+ (it decodes AVIF) with the encoder library loaded. */
    public static boolean avifOffered(int sdk, boolean avifEncoder) {
        return avifEncoder && sdk >= AVIF_MIN_SDK;
    }

    /** Whether an image of this size can be written in the format (WebP: at most 16383 px per side; AVIF: 64 MP). */
    public boolean fits(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        if (this == WEBP) return width <= WEBP_MAX_SIDE && height <= WEBP_MAX_SIDE;
        if (this == AVIF) return (long) width * height <= AVIF_MAX_PIXELS;
        return true;
    }

    /** The size limit of the format for the log ("max side 16383"), "" when it has none. */
    public String limit() {
        if (this == WEBP) return "max side " + WEBP_MAX_SIDE;
        if (this == AVIF) return "max " + AVIF_MAX_PIXELS / 1_000_000 + " MP";
        return "";
    }

    /** Whether the shot writes a JPEG file: the JPEG format itself, or «Также сохранять JPEG» next to HEIC / WebP / AVIF. */
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

    /** Full label of the save mode with this codec ("RAW + HEIC", "RAW + AVIF"). */
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
            case "avif": return "image/avif";
            case "dng": return "image/x-adobe-dng";
            case "png": return "image/png";
            default: return null;
        }
    }

    /** Whether the gallery lists a file of this name (photos in every format the camera writes, and DNG). */
    public static boolean isGalleryFile(String name) {
        switch (extensionOf(name)) {
            case "jpg": case "jpeg": case "dng": case "heic": case "heif": case "webp": case "avif": return true;
            default: return false;
        }
    }

    /** Whether the platform decodes the file in the gallery: HEIC / HEIF from Android 9, AVIF from 12, the rest always. */
    public static boolean decodable(String name, int sdk) {
        String ext = extensionOf(name);
        if (ext.equals("heic") || ext.equals("heif")) return sdk >= HEIC_MIN_SDK;
        if (ext.equals("avif")) return sdk >= AVIF_MIN_SDK;
        return isGalleryFile(name);
    }

    /** A photo encoded as HEIC / HEIF, WebP or AVIF (not JPEG, not DNG): the gallery decodes these with a bitmap fallback. */
    public static boolean isModernPhoto(String name) {
        String ext = extensionOf(name);
        return ext.equals("heic") || ext.equals("heif") || ext.equals("webp") || ext.equals("avif");
    }
}
