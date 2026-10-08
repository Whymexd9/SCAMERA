package com.particlesdevs.photoncamera.gallery.ui;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P59b: the format of a gallery file with the viewfinder's format icons (FormatChoice: ic_shade_*), and the merge route a
 * SCAMERA photo records in its description («Route=Hybrid» / «Route=SCAM HDR», ParseExif). A photo whose DNG sits next to
 * it (same base name) is «RAW + JPEG» etc.; the DNG itself is «RAW».
 */
public enum GalleryFormat {
    JPEG("JPEG", R.drawable.ic_shade_jpeg),
    HEIC("HEIC", R.drawable.ic_shade_heic),
    WEBP("WebP", R.drawable.ic_shade_webp),
    AVIF("AVIF", R.drawable.ic_shade_avif),
    RAW("RAW", R.drawable.ic_shade_raw),
    RAW_JPEG("RAW + JPEG", R.drawable.ic_shade_rawjpeg),
    RAW_HEIC("RAW + HEIC", R.drawable.ic_shade_rawheic),
    RAW_WEBP("RAW + WebP", R.drawable.ic_shade_rawwebp),
    RAW_AVIF("RAW + AVIF", R.drawable.ic_shade_rawavif),
    OTHER("", R.drawable.ic_shade_jpeg);

    public final String label;
    @DrawableRes public final int icon;

    GalleryFormat(String label, @DrawableRes int icon) {
        this.label = label;
        this.icon = icon;
    }

    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String baseName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    /** The format of {@code name}; {@code withRaw}: a DNG of the same base name is next to it. */
    public static GalleryFormat of(String name, boolean withRaw) {
        switch (extension(name)) {
            case "dng": return RAW;
            case "jpg": case "jpeg": return withRaw ? RAW_JPEG : JPEG;
            case "heic": case "heif": return withRaw ? RAW_HEIC : HEIC;
            case "webp": return withRaw ? RAW_WEBP : WEBP;
            case "avif": return withRaw ? RAW_AVIF : AVIF;
            default: return OTHER;
        }
    }

    /** The base names of the DNGs among {@code items} (for {@link #of}'s {@code withRaw}). */
    public static Set<String> rawBaseNames(Collection<GalleryItem> items) {
        Set<String> out = new HashSet<>();
        if (items == null) return out;
        for (GalleryItem i : items)
            if (i != null && i.getFile() != null && "dng".equals(extension(i.getFile().getDisplayName())))
                out.add(baseName(i.getFile().getDisplayName()));
        return out;
    }

    public static GalleryFormat of(GalleryItem item, Set<String> rawBases) {
        String name = item.getFile().getDisplayName();
        return of(name, !"dng".equals(extension(name)) && rawBases.contains(baseName(name)));
    }

    private static final Pattern ROUTE = Pattern.compile("Route=(Hybrid|SCAM HDR)");

    /** «Hybrid» / «SCAM HDR» from a SCAMERA description, null when the file does not record it. */
    @Nullable
    public static String routeOf(@Nullable String description) {
        if (description == null) return null;
        Matcher m = ROUTE.matcher(description);
        return m.find() ? m.group(1) : null;
    }

    /** The route's monogram (the viewfinder's top bar icons). */
    @DrawableRes
    public static int routeIcon(String route) {
        return "SCAM HDR".equals(route) ? R.drawable.topbar_ic_route_scamhdr : R.drawable.topbar_ic_route_hybrid;
    }
}
