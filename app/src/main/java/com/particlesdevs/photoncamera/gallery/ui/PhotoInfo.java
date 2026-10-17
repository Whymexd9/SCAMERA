package com.particlesdevs.photoncamera.gallery.ui;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.Locale;

/**
 * P196: the resolution and phone lines of the photo info (owner: «нам нужно чтобы в информации о фото писало фактическое
 * разрешение и модель смартфона»): «8192×6144 · 50 Мп» from the pixels the file stores (ImageHeaderSize, not the settings
 * or the EXIF size tags) and «vivo X200 Ultra» from the file's EXIF Make / Model (nothing when the file has neither). Used by
 * the compare panes' label and the details sheet. The overloads taking the separator and the unit are the plain-JVM test
 * entry points.
 */
public final class PhotoInfo {
    private PhotoInfo() {}

    /** «50 Мп» / "50 MP": whole megapixels from 20 MP up, one decimal below (12,6), two below 1 MP; null without a size. */
    @Nullable
    public static String megapixels(int width, int height) {
        return megapixels(width, height, Lang.ru(), Lang.t(" Мп", " MP"));
    }

    /** {@code comma}: the Russian decimal comma; {@code unit}: « Мп» / " MP". */
    @Nullable
    static String megapixels(int width, int height, boolean comma, String unit) {
        if (width <= 0 || height <= 0) return null;
        double mp = width * (double) height / 1e6;
        int digits = mp >= 19.95 ? 0 : mp >= 0.995 ? 1 : 2;
        String s = String.format(Locale.ROOT, "%." + digits + "f", mp);
        if (digits > 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        if (comma) s = s.replace('.', ',');
        return s + unit;
    }

    /** «8192×6144 · 50 Мп»; null without a size. */
    @Nullable
    public static String resolution(int width, int height) {
        return resolution(width, height, Lang.ru(), Lang.t(" Мп", " MP"));
    }

    @Nullable
    static String resolution(int width, int height, boolean comma, String unit) {
        String mp = megapixels(width, height, comma, unit);
        return mp == null ? null : width + "×" + height + " · " + mp;
    }

    /**
     * The phone from EXIF Make and Model: «vivo X200 Ultra» (Make «vivo», Model «vivo X200 Ultra» or «X200 Ultra»), «Xiaomi
     * 25010PN30G»; the make alone when there is no model; null when the file has neither.
     */
    @Nullable
    public static String device(@Nullable String make, @Nullable String model) {
        String ma = clean(make), mo = clean(model);
        if (mo.isEmpty()) return ma.isEmpty() ? null : ma;
        if (ma.isEmpty()) return mo;
        String lowMa = ma.toLowerCase(Locale.ROOT), lowMo = mo.toLowerCase(Locale.ROOT);
        if (lowMo.equals(lowMa) || lowMo.startsWith(lowMa + " ")) return mo; // the model already names the brand
        return ma + " " + mo;
    }

    /** {width, height} as the photo is shown: swapped for a 90 / 270 degree orientation. */
    public static int[] oriented(int width, int height, int rotationDegrees) {
        int r = ((rotationDegrees % 360) + 360) % 360;
        return r == 90 || r == 270 ? new int[]{height, width} : new int[]{width, height};
    }

    /** Trimmed, NUL padding and control characters removed (EXIF ASCII strings may carry them). */
    private static String clean(@Nullable String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= ' ' && c != 0x7F) b.append(c);
        }
        return b.toString().trim().replaceAll("\\s+", " ");
    }
}
