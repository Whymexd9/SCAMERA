package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.Locale;

/**
 * P180 (owner, 2026-10-16: «Изучи и реализуй, учитывай что х300у есть для разных регионов»): the vivo phones whose FULLRES is
 * the sensor's own mosaic without hardware remosaic, taken by the X200 Ultra's native route (the CTS package, the SDK lease,
 * one RAW_SENSOR stream with forceSensorMode, the own RAW viewfinder, matched serial stills, SHDR with the own remosaic,
 * a 4:3 photo). Their ISZ / DCG (forceSensorMode modules) are not touched here.
 * <ul>
 * <li>X200 Ultra (PD2454): verified on the phone (P179) - nothing of it changes.</li>
 * <li>X100 Ultra (PD2366 / V2366*): main 2, ultra-wide 3, telephoto 4 (SCAM TEST 1.11, 2026-10-11, its Android IDs).</li>
 * <li>X300 Ultra of any region - PD2547 / V2547A / V2547DA (China, satellite edition), V2562 (global), any vivo whose market
 * name says X300 Ultra ({@link VivoQcomIsz#x300Ultra}): main 3, ultra-wide 4, telephoto 5 (SCAM TEST 1.13, 2026-10-13).</li>
 * </ul>
 * The X100 / X300 Ultra profiles are NOT verified. Their sizes are the full-size rows of their own sensor-mode tables (vendor
 * tag 0x8038 / 0x8017: row 0 16320x12288 on both telephotos, 16320x12256 on the X300 main, 8192x6144 elsewhere; row 1 the
 * first 50 MP full-field row of a 200 MP sensor), but neither the reports nor the selectors say which row the sensor gives
 * without remosaic (DNGSATREMOSAIC names no index; the sensor-module descriptors are not in the reports), nor that the SDK
 * lease (keys 40 / 33) and the CTS package category work on their firmware. So they stay off until a targeted test turns
 * them on (scam_dev.txt vivo_fullres_x100u / vivo_fullres_x300u 1); vivo_fullres_mode_* picks another row of the table for
 * that test, never on the X200 Ultra. Whatever the row, every shot proves its mosaic from the pixels: a hardware-remosaiced
 * Bayer frame fails the shot (VivoSdkFullResCapture), it never passes as Quad / Tetra.
 */
public final class VivoNativeFullRes {
    enum Phone { X200_ULTRA, X100_ULTRA, X300_ULTRA }

    private VivoNativeFullRes() { }

    /** The phone of a native FULLRES table, null for any other. */
    @Nullable
    static Phone phone() {
        if (VivoQcomModes.device()) return Phone.X200_ULTRA;
        return phone(android.os.Build.MANUFACTURER, android.os.Build.DEVICE, android.os.Build.MODEL, VivoQcomIsz.marketName());
    }

    @Nullable
    static Phone phone(@Nullable String manufacturer, @Nullable String device, @Nullable String model, @Nullable String market) {
        if (manufacturer == null || !manufacturer.equalsIgnoreCase("vivo")) return null;
        String d = device == null ? "" : device.toUpperCase(Locale.ROOT), m = model == null ? "" : model.toUpperCase(Locale.ROOT);
        if (d.equals("PD2454")) return Phone.X200_ULTRA;
        if (VivoQcomIsz.x300Ultra(manufacturer, device, model, market)) return Phone.X300_ULTRA;
        String name = market == null ? "" : market.toUpperCase(Locale.ROOT).replace(" ", "");
        if (d.startsWith("PD2366") || m.startsWith("V2366") || name.contains("X100ULTRA")) return Phone.X100_ULTRA;
        return null;
    }

    /** Native FULLRES may run on this phone: the X200 Ultra, the X100 / X300 Ultra once their test switch is on. */
    public static boolean device() {
        Phone p = phone();
        return p != null && allowed(p, p == Phone.X100_ULTRA ? PreferenceKeys.scamDevSwitch("vivo_fullres_x100u", false)
                : p == Phone.X300_ULTRA && PreferenceKeys.scamDevSwitch("vivo_fullres_x300u", false));
    }

    static boolean allowed(Phone phone, boolean testSwitch) {
        return phone == Phone.X200_ULTRA || testSwitch;
    }

    /** The camera offers two sizes (200 MP and 50 MP): a FULLRES button for each. */
    static boolean twoSizes(String cameraId) {
        Phone p = phone();
        return twoSizes(p == null ? Phone.X200_ULTRA : p, cameraId);
    }

    static boolean twoSizes(Phone phone, String cameraId) {
        switch (phone) {
            case X100_ULTRA: return "4".equals(cameraId);
            case X300_ULTRA: return "3".equals(cameraId) || "5".equals(cameraId);
            default: return "5".equals(cameraId);
        }
    }

    /** The camera's native FULLRES profile on this phone ({@code large}: the 200 MP size of a camera with two). */
    @Nullable
    static VivoSdkFullRes.Profile profile(String cameraId, boolean large) {
        Phone p = phone();
        return p == null || p == Phone.X200_ULTRA ? profile(Phone.X200_ULTRA, cameraId, large, false) : profile(p, cameraId, large, true);
    }

    /** {@code override}: the test's own rows (scam_dev.txt vivo_fullres_mode_*), never on the X200 Ultra. */
    @Nullable
    static VivoSdkFullRes.Profile profile(Phone phone, String cameraId, boolean large, boolean override) {
        if (cameraId == null) return null;
        switch (phone) {
            case X100_ULTRA:
                // tag 0x8038: main (2) rows 0 / 11 / 31 8192x6144; ultra-wide (3) rows 0 / 9 8192x6144; tele (4) row 0
                // 16320x12288, rows 1 / 14 / 18 / 20 8160x6144
                if ("2".equals(cameraId)) return new VivoSdkFullRes.Profile(8192, 6144, row("main", 0, override), 2);
                if ("3".equals(cameraId)) return new VivoSdkFullRes.Profile(8192, 6144, row("wide", 0, override), 2);
                if ("4".equals(cameraId)) return large ? new VivoSdkFullRes.Profile(16320, 12288, row("tele", 0, override), 4)
                        : new VivoSdkFullRes.Profile(8160, 6144, row("tele50", 1, override), 2);
                return null;
            case X300_ULTRA:
                // tag 0x8017: main (3) row 0 16320x12256, rows 1-3 8160x6128; ultra-wide (4) rows 0 / 4 8192x6144; tele (5)
                // row 0 16320x12288, rows 1 / 14 / 27 8160x6144
                if ("3".equals(cameraId)) return large ? new VivoSdkFullRes.Profile(16320, 12256, row("main", 0, override), 4)
                        : new VivoSdkFullRes.Profile(8160, 6128, row("main50", 1, override), 2);
                if ("4".equals(cameraId)) return new VivoSdkFullRes.Profile(8192, 6144, row("wide", 0, override), 2);
                if ("5".equals(cameraId)) return large ? new VivoSdkFullRes.Profile(16320, 12288, row("tele", 0, override), 4)
                        : new VivoSdkFullRes.Profile(8160, 6144, row("tele50", 1, override), 2);
                return null;
            default:
                // the X200 Ultra (P179, measured on the phone): main / ultra-wide mode 4 Quad, tele mode 0 Tetra / 1 Quad
                if ("3".equals(cameraId) || "4".equals(cameraId)) return new VivoSdkFullRes.Profile(8192, 6144, 4, 2);
                if ("5".equals(cameraId)) return large ? VivoSdkFullRes.TELE_200 : VivoSdkFullRes.TELE_50;
                return null;
        }
    }

    /** The sensor-mode row of a profile: the table's, or the test's own (scam_dev.txt vivo_fullres_mode_<role>, 0..63). */
    private static int row(String role, int row, boolean override) {
        if (!override) return row;
        float v;
        switch (role) {
            case "main": v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_main", row); break;
            case "main50": v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_main50", row); break;
            case "wide": v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_wide", row); break;
            case "tele": v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_tele", row); break;
            default: v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_tele50", row); break;
        }
        return rowOf(v, row);
    }

    static int rowOf(float value, int row) {
        return value >= 0f && value < 64f && value == Math.rint(value) ? (int) value : row;
    }
}
