package com.particlesdevs.photoncamera.gallery.raw;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P352 (owner, 2026-10-26: «галерея должна работать с любыми Рав форматами»): which files are camera RAW - by extension, by the
 * MIME type MediaStore / other apps give them, and by the first bytes (Glide decoders see only a stream). DNG goes through the
 * app's own Java reader (DngPreview / DngRaw, P59 / P351); every other RAW through LibRaw (LibRawBridge). Pure Java.
 */
public final class RawFormats {
    private RawFormats() {}

    /** Camera RAW extensions LibRaw reads (DNG and GoPro GPR - a DNG - included). */
    public static final Set<String> EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "dng", "gpr", "cr2", "cr3", "crw", "nef", "nrw", "arw", "sr2", "srf", "raf", "orf", "ori", "rw2", "rwl", "raw",
            "pef", "ptx", "srw", "3fr", "fff", "iiq", "dcr", "kdc", "dcs", "mrw", "mdc", "x3f", "erf", "mef", "mos", "cap",
            "eip", "k25", "bay", "ari", "rwz", "mfw", "sti", "cs1", "qtk")));

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        String[][] m = {
                {"dng", "image/x-adobe-dng"}, {"gpr", "image/x-adobe-dng"}, {"cr2", "image/x-canon-cr2"},
                {"cr3", "image/x-canon-cr3"}, {"crw", "image/x-canon-crw"}, {"nef", "image/x-nikon-nef"},
                {"nrw", "image/x-nikon-nrw"}, {"arw", "image/x-sony-arw"}, {"sr2", "image/x-sony-sr2"},
                {"srf", "image/x-sony-srf"}, {"raf", "image/x-fuji-raf"}, {"orf", "image/x-olympus-orf"},
                {"ori", "image/x-olympus-orf"}, {"rw2", "image/x-panasonic-rw2"}, {"raw", "image/x-panasonic-raw"},
                {"rwl", "image/x-leica-rwl"}, {"pef", "image/x-pentax-pef"}, {"ptx", "image/x-pentax-pef"},
                {"srw", "image/x-samsung-srw"}, {"3fr", "image/x-hasselblad-3fr"}, {"fff", "image/x-hasselblad-fff"},
                {"iiq", "image/x-phaseone-iiq"}, {"dcr", "image/x-kodak-dcr"}, {"kdc", "image/x-kodak-kdc"},
                {"dcs", "image/x-kodak-dcs"}, {"mrw", "image/x-minolta-mrw"}, {"x3f", "image/x-sigma-x3f"},
                {"erf", "image/x-epson-erf"}, {"mef", "image/x-mamiya-mef"}, {"mos", "image/x-leaf-mos"},
                {"ari", "image/x-arri-ari"},
        };
        for (String[] e : m) MIME.put(e[0], e[1]);
    }

    /** MIME types other apps send for RAW files (besides the per-camera image/x-* ones). */
    public static final Set<String> GENERIC_RAW_MIMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "image/x-adobe-dng", "image/dng", "image/x-dng", "image/x-dcraw", "image/x-raw", "image/raw")));

    /** The lower-case extension of a file name ("" without one). */
    public static String extensionOf(@Nullable String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isRawName(@Nullable String name) {
        String e = extensionOf(name);
        return !e.isEmpty() && EXTENSIONS.contains(e);
    }

    public static boolean isDngName(@Nullable String name) {
        String e = extensionOf(name);
        return e.equals("dng") || e.equals("gpr");
    }

    /** A RAW MIME type: image/x-&lt;maker&gt;-&lt;format&gt; of a known extension, or one of the generic ones. */
    public static boolean isRawMime(@Nullable String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        if (GENERIC_RAW_MIMES.contains(m)) return true;
        if (!m.startsWith("image/x-")) return false;
        return MIME.containsValue(m) || EXTENSIONS.contains(m.substring(m.lastIndexOf('-') + 1));
    }

    /** The MIME type of a RAW file name, null when it is not one. */
    @Nullable
    public static String mimeForName(@Nullable String name) {
        String e = extensionOf(name);
        if (!EXTENSIONS.contains(e)) return null;
        String m = MIME.get(e);
        return m != null ? m : "image/x-dcraw";
    }

    // ---------------------------------------------------------------- magic

    static boolean isTiff(byte[] h, int n) {
        return n >= 4 && ((h[0] == 'I' && h[1] == 'I' && h[2] == 42 && h[3] == 0) || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && h[3] == 42));
    }

    private static boolean at(byte[] h, int n, int off, String s) {
        if (off + s.length() > n) return false;
        for (int i = 0; i < s.length(); i++) if (h[off + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    /**
     * A TIFF whose first IFD (within {@code n} header bytes) has the DNGVersion tag; a TIFF whose first IFD lies beyond the
     * header counts as DNG (the reader then decides, as before P352).
     */
    public static boolean looksDng(byte[] h, int n) {
        if (!isTiff(h, n)) return false;
        boolean le = h[0] == 'I';
        long ifd = u32(h, 4, le);
        if (ifd + 2 > n) return true;
        int count = u16(h, (int) ifd, le);
        for (int i = 0; i < count; i++) {
            int e = (int) ifd + 2 + 12 * i;
            if (e + 2 > n) return true;
            if (u16(h, e, le) == 0xC612) return true;
        }
        return false;
    }

    /** A camera RAW other than DNG by its first bytes (TIFF-based without DNGVersion, CR3, RAF, ORF, RW2, MRW, X3F, CRW, IIQ...). */
    public static boolean looksCameraRaw(byte[] h, int n) {
        if (isTiff(h, n)) return !looksDng(h, n);
        return at(h, n, 4, "ftypcrx") || at(h, n, 0, "FUJIFILM") || at(h, n, 0, "IIRO") || at(h, n, 0, "IIRS")
                || at(h, n, 0, "MMOR") || (at(h, n, 0, "IIU") && n > 3 && h[3] == 0) || at(h, n, 0, "\0MRM")
                || at(h, n, 0, "FOVb") || at(h, n, 6, "HEAPCCDR") || at(h, n, 0, "IIII") || at(h, n, 0, "ARRI");
    }

    private static int u16(byte[] h, int o, boolean le) {
        if (o + 2 > h.length) return 0;
        return le ? (h[o] & 0xFF) | (h[o + 1] & 0xFF) << 8 : (h[o] & 0xFF) << 8 | (h[o + 1] & 0xFF);
    }

    private static long u32(byte[] h, int o, boolean le) {
        if (o + 4 > h.length) return Long.MAX_VALUE;
        long v = le ? (h[o] & 0xFF) | (h[o + 1] & 0xFF) << 8 | (h[o + 2] & 0xFF) << 16 | (long) (h[o + 3] & 0xFF) << 24
                : (long) (h[o] & 0xFF) << 24 | (h[o + 1] & 0xFF) << 16 | (h[o + 2] & 0xFF) << 8 | (h[o + 3] & 0xFF);
        return v;
    }

    /** LibRaw's flip (0, 3 = 180, 5 = 90 counter-clockwise, 6 = 90 clockwise) as an EXIF orientation. */
    public static int orientationOfFlip(int flip) {
        switch (flip) {
            case 3: return 3;
            case 5: return 8;
            case 6: return 6;
            default: return 1;
        }
    }
}
