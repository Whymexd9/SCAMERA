package com.particlesdevs.photoncamera.capture;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.ArrayList;
import java.util.List;
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
 * lease (keys 40 / 33) and the CTS package category work on their firmware. P184 (owner, 2026-10-17: «На х100 ультра не
 * появляются кнопки с переключением фулреза, исправь. на 300у они тоже должны быть»): they are on, the owner tests them;
 * scam_dev.txt vivo_fullres_x100u / vivo_fullres_x300u 0 turns a phone off, vivo_fullres_mode_* picks another row of the
 * table (never on the X200 Ultra). Whatever the row, every shot proves its mosaic from the pixels: a hardware-remosaiced
 * Bayer frame fails the shot (VivoSdkFullResCapture), it never passes as Quad / Tetra; a lease the firmware refuses fails the
 * session and FULLRES goes off.
 * <p>
 * P194: «FULLRES: аппаратный ремозаик» acts here too - on the 50 MP size of a camera whose table has a row the HAL gives
 * remosaicked ({@link #hardwareRow}: the X300 Ultra main 3 row 2, the X100 Ultra main 2 row 0, from the owner's P186 trials),
 * taken by this native route as a plain Bayer profile (nativeBlock 1); a failed hardware row is remembered and the camera
 * takes its own mosaic again. Not verified on the phones.
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

    /** Native FULLRES may run on this phone: the X200 Ultra, the X100 / X300 Ultra unless their switch turns them off. */
    public static boolean device() {
        Phone p = phone();
        return p != null && allowed(p, p == Phone.X100_ULTRA ? PreferenceKeys.scamDevSwitch("vivo_fullres_x100u", true)
                : p == Phone.X300_ULTRA && PreferenceKeys.scamDevSwitch("vivo_fullres_x300u", true));
    }

    static boolean allowed(Phone phone, boolean testSwitch) {
        return phone == Phone.X200_ULTRA || testSwitch;
    }

    /**
     * The camera offers two sizes (200 MP and 50 MP): a FULLRES button for each. P186: on the X100 / X300 Ultra only while both
     * sizes still have a row to try.
     */
    static boolean twoSizes(String cameraId) {
        return twoSizes(phone(), cameraId, true);
    }

    /**
     * {@link #twoSizes(String)} on a given phone (null: as the X200 Ultra); {@code live}: only while both sizes have a row - P194
     * the 50 MP size also by its hardware-remosaic row while the switch is on.
     */
    static boolean twoSizes(@Nullable Phone p, String cameraId, boolean live) {
        if (p == null || p == Phone.X200_ULTRA) return twoSizes(Phone.X200_ULTRA, cameraId);
        return twoSizes(p, cameraId) && (!live || profile(p, cameraId, true, true) != null && (profile(p, cameraId, false, true) != null
                || VivoSdkFullRes.hardwareRemosaicOn() && hardwareProfile(p, cameraId, true) != null));
    }

    static boolean twoSizes(Phone phone, String cameraId) {
        switch (phone) {
            case X100_ULTRA: return "4".equals(cameraId);
            case X300_ULTRA: return "3".equals(cameraId) || "5".equals(cameraId);
            default: return "5".equals(cameraId);
        }
    }

    /**
     * The camera's native FULLRES profile on this phone ({@code large}: the 200 MP size of a camera with two). P186: on the X100
     * / X300 Ultra a size whose rows all failed gives way to the camera's other size.
     */
    @Nullable
    static VivoSdkFullRes.Profile profile(String cameraId, boolean large) {
        return profile(phone(), cameraId, large);
    }

    /** {@link #profile(String, boolean)} on a given phone (null: as the X200 Ultra). */
    @Nullable
    static VivoSdkFullRes.Profile profile(@Nullable Phone p, String cameraId, boolean large) {
        if (p == null || p == Phone.X200_ULTRA) return profile(Phone.X200_ULTRA, cameraId, large, false);
        VivoSdkFullRes.Profile own = profile(p, cameraId, large, true);
        return own != null || !twoSizes(p, cameraId) ? own : profile(p, cameraId, !large, true);
    }

    /**
     * P194 (owner, 2026-10-17: «FULLRES: аппаратный ремозаик» on the X100 / X300 Ultra too): the row of a camera's 50 MP size
     * whose frames the HAL gives remosaicked - a plain Bayer at the size of that table row - measured in the owner's P186 row
     * trials (MosaicBlockDetector block 1): the X300 Ultra main camera 3 row 2 (8160x6128), the X100 Ultra main camera 2 row 0
     * (8192x6144; its listed main 0 is the same sensor's other profile). -1 for any other camera (the X300 ultra-wide and the
     * telephotos of both gave their own Quad on the rows tried): there the switch changes nothing. Never the X200 Ultra (its
     * hardware remosaic is the SDK route).
     */
    static int hardwareRow(@Nullable Phone phone, String cameraId) {
        if (phone == Phone.X300_ULTRA && "3".equals(cameraId)) return 2;
        if (phone == Phone.X100_ULTRA && ("2".equals(cameraId) || "0".equals(cameraId))) return 0;
        // P210 (the X100 Ultra's log on 30966, 2026-10-17): its ultra-wide's row 0 gives the HAL's remosaic too (block 1)
        if (phone == Phone.X100_ULTRA && "3".equals(cameraId)) return 0;
        return -1;
    }

    /**
     * P194: the hardware-remosaic profile of a camera (the native route, nativeBlock 1: plain Bayer at the 50 MP size), null
     * without a {@link #hardwareRow} or ({@code live}) once that row failed on this phone - the camera's own mosaic then.
     */
    @Nullable
    static VivoSdkFullRes.Profile hardwareProfile(@Nullable Phone phone, String cameraId, boolean live) {
        final int row = hardwareRow(phone, cameraId);
        final int[] size = row < 0 ? null : size(phone, cameraId, false);
        if (size == null || live && hardwareFailed(cameraId)) return null;
        return new VivoSdkFullRes.Profile(size[0], size[1], row, 1);
    }

    /**
     * {@code live}: the row this phone tries now - the test's own (scam_dev.txt vivo_fullres_mode_*), else the first of the
     * table's {@link #candidates} not yet failed (null once all failed); else the table's first. Never on the X200 Ultra.
     */
    @Nullable
    static VivoSdkFullRes.Profile profile(Phone phone, String cameraId, boolean large, boolean live) {
        if (cameraId == null) return null;
        switch (phone) {
            case X100_ULTRA:
            case X300_ULTRA: {
                final int[] rows = candidates(phone, cameraId, large);
                final int[] size = size(phone, cameraId, large);
                if (rows.length == 0 || size == null) return null;
                final boolean big = twoSizes(phone, cameraId) && large;
                final int row = live ? rowFor(rows, failures(cameraId, big), forced(role(phone, cameraId, large))) : rows[0];
                return row < 0 ? null : new VivoSdkFullRes.Profile(size[0], size[1], row, size[2],
                        stillOnly(size[0], size[1]) && (!live || stillOnlySwitch()));
            }
            default:
                // the X200 Ultra (P179, measured on the phone): main / ultra-wide mode 4 Quad, tele mode 0 Tetra / 1 Quad
                if ("3".equals(cameraId) || "4".equals(cameraId)) return new VivoSdkFullRes.Profile(8192, 6144, 4, 2);
                if ("5".equals(cameraId)) return large ? VivoSdkFullRes.TELE_200 : VivoSdkFullRes.TELE_50;
                return null;
        }
    }

    /**
     * P186: the rows of a camera's size in its own sensor-mode table (tag 0x8038 X100 / 0x8017 X300), tried in this order:
     * X100 main (2, and the listed 0: its other profile, the same table) 0 / 11 / 31 8192x6144, ultra-wide (3) 0 / 9 8192x6144,
     * tele (4) 0 16320x12288 or 1 / 14 / 18 / 20 8160x6144; X300 main (3) 0 16320x12256 or 3 / 1 8160x6128, ultra-wide (4)
     * 0 / 4 8192x6144, tele (5) 0 16320x12288 or 1 / 14 / 27 8160x6144.
     * P200 (owner, X300 Ultra 2026-10-17: «на основе не с первого раза»): its main's 50 MP row 3 first - the row its log shows
     * working (native Quad, merged); row 1 gave no frame, row 2 is the hardware-remosaic row (P194, {@link #hardwareRow}).
     * P207 (owner, X100 Ultra 2026-10-17: «Нам нужен фулрез без ремозаика»): its main's row 0 is the hardware-remosaic row (a
     * plain Bayer in its log, the still refused as no Quad), so its own mosaic tries the other 8192x6144 rows 11 / 31 only.
     */
    static int[] candidates(Phone phone, String cameraId, boolean large) {
        if (phone == Phone.X100_ULTRA) {
            if ("0".equals(cameraId) || "2".equals(cameraId)) return new int[]{11, 31};
            if ("3".equals(cameraId)) return new int[]{9}; // P210: row 0 is its hardware-remosaic row
            if ("4".equals(cameraId)) return large ? new int[]{0} : new int[]{1, 14, 18, 20};
        } else if (phone == Phone.X300_ULTRA) {
            if ("3".equals(cameraId)) return large ? new int[]{0} : new int[]{3, 1};
            if ("4".equals(cameraId)) return new int[]{0, 4};
            if ("5".equals(cameraId)) return large ? new int[]{0} : new int[]{1, 14, 27};
        }
        return new int[0];
    }

    /** {width, height, colour block} of a camera's size on the X100 / X300 Ultra, null for another camera. */
    @Nullable
    static int[] size(Phone phone, String cameraId, boolean large) {
        if (phone == Phone.X100_ULTRA) {
            if ("0".equals(cameraId) || "2".equals(cameraId) || "3".equals(cameraId)) return new int[]{8192, 6144, 2};
            if ("4".equals(cameraId)) return large ? new int[]{16320, 12288, 4} : new int[]{8160, 6144, 2};
        } else if (phone == Phone.X300_ULTRA) {
            if ("3".equals(cameraId)) return large ? new int[]{16320, 12256, 4} : new int[]{8160, 6128, 2};
            if ("4".equals(cameraId)) return new int[]{8192, 6144, 2};
            if ("5".equals(cameraId)) return large ? new int[]{16320, 12288, 4} : new int[]{8160, 6144, 2};
        }
        return null;
    }

    /**
     * P208 (owner's X300 Ultra, 2026-10-17: «200мп не работают»; X100 Ultra the same): a session held in the 200 MP mode
     * (forceSensorMode 0, the RAW stream at 16320 wide) gives no frame (telephotos) or kills the camera provider (X300 main, X100
     * tele: nodeTimeout). The stock camera's 200 MP session (dumpsys) keeps its viewfinder at 30 fps in the normal mode and has
     * the 16320 x 12288 RAW stream beside it for the stills only: so does this route for every 200 MP size of these phones.
     */
    static boolean stillOnly(int width, int height) {
        return (long) width * height > 100_000_000L;
    }

    /** P208: scam_dev.txt "vivo_fullres200_stock 0" holds the sensor in the 200 MP mode again (the route before P208). */
    static boolean stillOnlySwitch() {
        return PreferenceKeys.scamDevSwitch("vivo_fullres200_stock", true);
    }

    /** P208: scam_dev.txt "vivo_fullres200_force 1" puts the 200 MP mode key on the stills of the still-only route. */
    static boolean forceOnStills() {
        return PreferenceKeys.scamDevSwitch("vivo_fullres200_force", false);
    }

    /** The dev key of a camera's size: main, main50, wide, tele, tele50. */
    static String role(Phone phone, String cameraId, boolean large) {
        if (phone == Phone.X100_ULTRA) {
            if ("3".equals(cameraId)) return "wide";
            if ("4".equals(cameraId)) return large ? "tele" : "tele50";
            return "main";
        }
        if ("4".equals(cameraId)) return "wide";
        if ("5".equals(cameraId)) return large ? "tele" : "tele50";
        return large ? "main" : "main50";
    }

    /** The test's own row of a size (scam_dev.txt vivo_fullres_mode_<role>, 0..63), -1 for none. */
    private static int forced(String role) {
        float v;
        switch (role) {
            case "main": v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_main", -1); break;
            case "main50": v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_main50", -1); break;
            case "wide": v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_wide", -1); break;
            case "tele": v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_tele", -1); break;
            default: v = PreferenceKeys.scamDevNumber("vivo_fullres_mode_tele50", -1); break;
        }
        return rowOf(v, -1);
    }

    /** The row to try: the test's own, else the first candidate not yet failed, -1 once all failed. */
    static int rowFor(int[] rows, int tried, int forced) {
        if (forced >= 0) return forced;
        return tried >= 0 && tried < rows.length ? rows[tried] : -1;
    }

    /**
     * P186 (owner, X300 Ultra 2026-10-17: «На основном объективе - 50/200мп не работают, видоискатель зависает, переключает на
     * ширик. Телевик - 50мп работают, 200мп нет»): the rows a size has tried and failed (a session that gave no frame, a camera
     * that died), kept per camera and size; bumping ROW_TRIALS starts them again in a later build. P214h: ROW_TRIALS is the
     * route revision of the trials' stamp (FullResTrials: with Build.FINGERPRINT, so a firmware update tries the rows again),
     * each failed row keeps its reason, and «Попробовать снова» forgets a camera's trials ({@link #clearFailures}).
     */
    static final int ROW_TRIALS = 1;
    private static final String FAILED = "module_fullres_rowfail_", FAILED_TRIALS = "module_fullres_rowfail_trials";
    /** P194: a camera's hardware-remosaic row failed (no frame, the camera died, not a plain Bayer): suffix of FAILED + camera. */
    private static final String HW_FAILED = "_hw";

    /** P214h: the base of a camera's trial keys (its sizes' tried counts, the hardware row, the reasons, the stamp). */
    static String base(String cameraId) {
        return FAILED + cameraId;
    }

    /** P214h: the stamp of this build's trials on this firmware. */
    static String stamp() {
        return FullResTrials.stamp(ROW_TRIALS);
    }

    @Nullable
    private static SharedPreferences prefs() {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean hardwareFailed(String cameraId) {
        return hardwareFailed(prefs(), cameraId, stamp());
    }

    /** P214h: {@link #hardwareFailed(String)} in {@code p} under the stamp {@code stamp} (another stamp's failure is passed over). */
    static boolean hardwareFailed(@Nullable SharedPreferences p, String cameraId, String stamp) {
        if (p == null || cameraId == null) return false;
        try {
            final String key = FAILED + cameraId + HW_FAILED;
            return p.getInt(FAILED_TRIALS, ROW_TRIALS) == ROW_TRIALS && FullResTrials.counts(p, base(cameraId), key, stamp)
                    && p.getBoolean(key, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The failure key of a size. P200: the X300 Ultra main's 50 MP rows changed order (3 / 1, were 1 / 2 / 3), so its count of
     * tried rows starts again under a key of its own (a count of the old order would pick the wrong row).
     */
    static String failureKey(String cameraId, boolean big) {
        return failureKey(phone(), cameraId, big);
    }

    /** P207: likewise the X100 Ultra main's rows (11 / 31, were 0 / 11 / 31) count under a key of their own. */
    static String failureKey(@Nullable Phone phone, String cameraId, boolean big) {
        final String rows = big ? "" : phone == Phone.X300_ULTRA && "3".equals(cameraId) ? "_r3"
                : phone == Phone.X100_ULTRA && ("0".equals(cameraId) || "2".equals(cameraId)) ? "_r11"
                : phone == Phone.X100_ULTRA && "3".equals(cameraId) ? "_r9" : "";
        return FAILED + cameraId + (big ? "_200" : "_50") + rows;
    }

    static int failures(String cameraId, boolean big) {
        return failures(prefs(), phone(), cameraId, big, stamp());
    }

    /** P214h: {@link #failures(String, boolean)} in {@code p} on {@code phone} under the stamp {@code stamp}. */
    static int failures(@Nullable SharedPreferences p, @Nullable Phone phone, String cameraId, boolean big, String stamp) {
        if (p == null || cameraId == null) return 0;
        try {
            if (p.getInt(FAILED_TRIALS, ROW_TRIALS) != ROW_TRIALS) return 0;
            final String key = failureKey(phone, cameraId, big);
            return FullResTrials.counts(p, base(cameraId), key, stamp) ? p.getInt(key, 0) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** P214h: the reasons (row:code,...) of a size's failed rows; null for none or another stamp's. */
    @Nullable
    static String reasons(SharedPreferences p, @Nullable Phone phone, String cameraId, boolean big, String stamp) {
        final String key = failureKey(phone, cameraId, big) + FullResTrials.WHY;
        return FullResTrials.counts(p, base(cameraId), key, stamp) ? FullResTrials.string(p, key) : null;
    }

    /** P214h: a size's tried count after {@code row} failed for {@code why}, and that row's reason, under the stamp {@code stamp}. */
    static void noteRow(@Nullable SharedPreferences p, @Nullable Phone phone, String cameraId, boolean big, int tried, int row,
                        @Nullable String why, String stamp) {
        if (p == null || cameraId == null) return;
        try {
            final String key = failureKey(phone, cameraId, big);
            final String reasons = FullResTrials.withReason(reasons(p, phone, cameraId, big, stamp), row, FullResTrials.code(why));
            FullResTrials.edit(p, base(cameraId), stamp).putInt(FAILED_TRIALS, ROW_TRIALS).putInt(key, tried)
                    .putString(key + FullResTrials.WHY, reasons).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /** P214h: the camera's hardware-remosaic row failed for {@code why}, under the stamp {@code stamp}. */
    static void noteHardware(@Nullable SharedPreferences p, String cameraId, @Nullable String why, String stamp) {
        if (p == null || cameraId == null) return;
        try {
            FullResTrials.edit(p, base(cameraId), stamp).putInt(FAILED_TRIALS, ROW_TRIALS)
                    .putBoolean(FAILED + cameraId + HW_FAILED, true)
                    .putString(FAILED + cameraId + HW_FAILED + FullResTrials.WHY, FullResTrials.code(why)).apply();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * P214h: «Попробовать снова» - the camera's trials are forgotten (every size, its hardware-remosaic row, the reasons, the
     * stamp; the keys of the builds that renamed them, P200 / P207 / P210, too): its next FULLRES starts at its first row.
     * Returns the number of keys removed.
     */
    static int clearFailures(@Nullable SharedPreferences p, @Nullable String cameraId) {
        if (p == null || cameraId == null) return 0;
        return FullResTrials.clear(p, base(cameraId));
    }

    /** P214h: the camera has a FULLRES table on this phone (a profile, whatever its trials say). */
    static boolean hasTable(@Nullable Phone phone, @Nullable String cameraId) {
        return (phone == Phone.X100_ULTRA || phone == Phone.X300_ULTRA) && profile(phone, cameraId, false, false) != null;
    }

    /**
     * P214h: the size of a camera with two ({@code large}: 200 MP) has no row left - all failed (the 50 MP one also without a
     * live hardware-remosaic row while {@code hardwareSwitch} is on): its button stays, dimmed.
     */
    static boolean sizeExhausted(@Nullable Phone phone, @Nullable String cameraId, boolean large, boolean hardwareSwitch) {
        if (phone != Phone.X100_ULTRA && phone != Phone.X300_ULTRA || cameraId == null || !twoSizes(phone, cameraId)) return false;
        if (!large && hardwareSwitch && hardwareProfile(phone, cameraId, true) != null) return false;
        return profile(phone, cameraId, large, false) != null && profile(phone, cameraId, large, true) == null;
    }

    /**
     * P214h (speed-ux C2: «rows 11/31 gave no frame; row 0 is the camera's own remosaic -> «FULLRES: аппаратный ремозаик»»):
     * the camera's trials in words - per size the rows tried and why each failed (the size off, or the row tried next), then
     * its hardware-remosaic row; null without a failed row (the X200 Ultra keeps none).
     */
    @Nullable
    static String status(@Nullable SharedPreferences p, @Nullable Phone phone, @Nullable String cameraId, String stamp,
                         boolean hardwareSwitch) {
        if (p == null || cameraId == null || phone != Phone.X100_ULTRA && phone != Phone.X300_ULTRA) return null;
        final List<String> lines = new ArrayList<>();
        final boolean two = twoSizes(phone, cameraId);
        boolean failed50 = false;
        for (boolean large : two ? new boolean[]{false, true} : new boolean[]{false}) {
            final int[] rows = candidates(phone, cameraId, large), size = size(phone, cameraId, large);
            if (rows.length == 0 || size == null) continue;
            final boolean big = two && large;
            final String line = FullResTrials.sizeLine(
                    com.particlesdevs.photoncamera.settings.FullResProfiles.megapixels(size[0], size[1]), rows,
                    failures(p, phone, cameraId, big, stamp), FullResTrials.reasons(reasons(p, phone, cameraId, big, stamp)));
            if (line == null) continue;
            lines.add(line);
            if (!large) failed50 = true;
        }
        final boolean hwFailed = hardwareFailed(p, cameraId, stamp);
        final String hw = FullResTrials.hardwareLine(hardwareRow(phone, cameraId), hwFailed,
                hwFailed ? FullResTrials.string(p, FAILED + cameraId + HW_FAILED + FullResTrials.WHY) : null, hardwareSwitch, failed50);
        if (hw != null) lines.add(hw);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /** The tried count after {@code failedRow} failed: one more when it is the row tried now, as it was otherwise. */
    static int next(int[] rows, int tried, int failedRow) {
        return tried >= 0 && tried < rows.length && rows[tried] == failedRow ? tried + 1 : tried;
    }

    /**
     * P186: a native FULLRES session of this phone failed (no frame, the camera died): the row is noted and the next one of its
     * size is tried at the next FULLRES; the owner's message (null on the X200 Ultra, whose rows are measured, or under a test
     * row of scam_dev.txt).
     */
    @Nullable
    public static String failed(String cameraId, VivoSdkFullRes.Profile profile, String why) {
        Phone phone = phone();
        if (phone == null || phone == Phone.X200_ULTRA || profile == null || cameraId == null) return null;
        final boolean huge = (long) profile.size.getWidth() * profile.size.getHeight() > 100_000_000L;
        final boolean big = twoSizes(phone, cameraId) && huge;
        final int mp = huge ? 200 : 50;
        if (profile.nativeBayer()) {
            // P194: the hardware-remosaic row is noted on its own (the own rows' trials stay); its 50 MP shots take the own mosaic
            noteHardware(prefs(), cameraId, why, stamp()); // P214h: with its reason, under this build's stamp
            com.particlesdevs.photoncamera.util.Log.w("VivoNativeFullRes", "camera " + cameraId + " hardware-remosaic row "
                    + profile.sensorMode + " failed (" + why + "); its own mosaic from now on");
            return com.particlesdevs.photoncamera.util.Lang.t("FULLRES 50 Мп: аппаратный ремозаик (режим " + profile.sensorMode
                            + ") не работает на этой камере; дальше без него",
                    "FULLRES 50 MP: the hardware remosaic (mode " + profile.sensorMode + ") does not work on this camera; without it from now on");
        }
        if (forced(role(phone, cameraId, huge)) >= 0) {
            com.particlesdevs.photoncamera.util.Log.w("VivoNativeFullRes", "camera " + cameraId + " test row " + profile.sensorMode + " failed: " + why);
            return com.particlesdevs.photoncamera.util.Lang.t("FULLRES " + mp + " Мп: режим " + profile.sensorMode + " (scam_dev) не работает",
                    "FULLRES " + mp + " MP: mode " + profile.sensorMode + " (scam_dev) does not work");
        }
        final int[] rows = candidates(phone, cameraId, huge);
        final int tried = next(rows, failures(cameraId, big), profile.sensorMode);
        noteRow(prefs(), phone, cameraId, big, tried, profile.sensorMode, why, stamp()); // P214h: with the row's reason, stamped
        final int nextRow = tried < rows.length ? rows[tried] : -1;
        com.particlesdevs.photoncamera.util.Log.w("VivoNativeFullRes", "camera " + cameraId + " " + mp + " MP row " + profile.sensorMode
                + " failed (" + why + "); next " + (nextRow >= 0 ? "row " + nextRow : "none: this size is off"));
        return nextRow >= 0
                ? com.particlesdevs.photoncamera.util.Lang.t("FULLRES " + mp + " Мп: режим " + profile.sensorMode + " не работает на этой камере; в следующий раз попробую режим " + nextRow,
                        "FULLRES " + mp + " MP: mode " + profile.sensorMode + " does not work on this camera; the next time mode " + nextRow + " is tried")
                : com.particlesdevs.photoncamera.util.Lang.t("FULLRES " + mp + " Мп недоступен на этой камере: ни один его режим не работает",
                        "FULLRES " + mp + " MP is not available on this camera: none of its modes works");
    }

    static int rowOf(float value, int row) {
        return value >= 0f && value < 64f && value == Math.rint(value) ? (int) value : row;
    }
}
