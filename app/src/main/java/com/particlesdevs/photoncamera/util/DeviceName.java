package com.particlesdevs.photoncamera.util;

import android.os.Build;

import androidx.annotation.Nullable;

/**
 * P196: the phone a photo names in its EXIF Make / Model (owner: «нам нужно чтобы в информации о фото писало ... модель
 * смартфона»). Make: Build.BRAND (Build.MANUFACTURER without one), as before. Model: the phone's market name when the system
 * has one - vivo ro.vivo.market.name (the property VivoQcomIsz reads, «vivo X200 Ultra»), OPPO / OnePlus
 * ro.vendor.oplus.market.name, Xiaomi ro.product.marketname (XiaomiTeleZoom) - otherwise Build.MODEL («V2446A»). EXIF
 * Model is ASCII: a market name with other characters keeps its ASCII start («vivo X300 Ultra 卫星通信版» → «vivo X300 Ultra»).
 */
public final class DeviceName {
    static final String[] MARKET_PROPERTIES = {
            "ro.vivo.market.name", "ro.vendor.oplus.market.name", "ro.product.marketname", "ro.product.vendor.marketname"};

    private static volatile String sModel;

    private DeviceName() {}

    /** EXIF Make. */
    public static String make() {
        String brand = Build.BRAND;
        return brand != null && !brand.trim().isEmpty() ? brand : Build.MANUFACTURER;
    }

    /** EXIF Model: the market name, else Build.MODEL. Read once. */
    public static String model() {
        String m = sModel;
        if (m != null) return m;
        String[] market = new String[MARKET_PROPERTIES.length];
        for (int i = 0; i < market.length; i++) market[i] = property(MARKET_PROPERTIES[i]);
        m = model(Build.MODEL, market);
        if (m == null) m = "";
        sModel = m;
        return m;
    }

    /** Reads the properties again at the next {@link #model()} (tests that set them). */
    @androidx.annotation.VisibleForTesting
    public static void forget() {
        sModel = null;
    }

    /** The first market name with ASCII text (its ASCII part), else {@code buildModel}. */
    @Nullable
    static String model(@Nullable String buildModel, @Nullable String... marketNames) {
        if (marketNames != null) {
            for (String name : marketNames) {
                String a = ascii(name);
                if (!a.isEmpty()) return a;
            }
        }
        return buildModel;
    }

    /**
     * The printable ASCII text up to the first other character, runs of spaces made one, trimmed; empty when that has no
     * letter («小米 17 Ultra» gives nothing rather than «17 Ultra»).
     */
    static String ascii(@Nullable String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c >= 0x7F) break;
            b.append(c);
        }
        String out = b.toString().trim().replaceAll(" +", " ");
        return out.matches(".*[A-Za-z].*") ? out : "";
    }

    private static String property(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v instanceof String ? (String) v : "";
        } catch (Exception | LinkageError e) {
            return "";
        }
    }
}
