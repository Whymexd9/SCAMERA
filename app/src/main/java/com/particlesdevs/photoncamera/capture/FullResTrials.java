package com.particlesdevs.photoncamera.capture;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P214h (speed-ux C2, engineering B3; P210 on the X100 Ultra: «all own rows fail, the size goes off, the button hides»): what a
 * camera's FULLRES row trials remember - VivoNativeFullRes' module_fullres_rowfail_&lt;camera&gt;_* (the tried count of each size,
 * the hardware-remosaic row) and OppoNativeFullRes' module_oppo_fullres_fail_&lt;camera&gt;* - why each row failed, and how they
 * start again.
 * <ul>
 * <li>A camera's trials carry a stamp: the route's revision (VivoNativeFullRes / OppoNativeFullRes.ROW_TRIALS) and
 * Build.FINGERPRINT. Trials of another revision or firmware are passed over, the camera's rows are tried again (one failed
 * attempt per row after a firmware update, where a size stayed off until a build renamed its keys, P210's _r11 / _r9), and the
 * next failure replaces them. Trials of a build before P214h have no stamp: they are taken as this firmware's and get its
 * stamp (an update of the app alone does not stall the owner's phones on their dead rows again).</li>
 * <li>{@link #clear} forgets one camera's trials: «Попробовать снова» on the dimmed FULLRES button and in «Настройки по
 * модулям»; the next FULLRES of the camera starts at its first row.</li>
 * <li>Each failed row keeps a short reason ({@link #code}: no frame, the camera died, the HAL's remosaic...) for
 * {@link #status}, which says what was tried and why it is off.</li>
 * </ul>
 */
public final class FullResTrials {
    private static final String TAG = "FullResTrials";
    /** Suffixes under a camera's key base: its stamp, a trial's reasons (row:code,row:code). */
    static final String STAMP = "_stamp", WHY = "_why";
    /** Reason codes of a failed row (stored, never shown: {@link #phrase} is the text). */
    static final String NO_FRAME = "noframe", DIED = "died", REFUSED = "refused", HW_REMOSAIC = "hwremosaic", MOSAIC = "mosaic",
            MODE = "mode", OTHER = "other";

    private FullResTrials() { }

    /** The stamp of trials made by a route of {@code revision} on the firmware {@code fingerprint}. */
    static String stamp(int revision, @Nullable String fingerprint) {
        return "r" + revision + " " + (fingerprint == null ? "" : fingerprint);
    }

    /** The stamp of this build's trials (the route's revision, this firmware). */
    static String stamp(int revision) {
        return stamp(revision, android.os.Build.FINGERPRINT);
    }

    @Nullable
    static String string(SharedPreferences p, String key) {
        try {
            return p.getString(key, null);
        } catch (ClassCastException other) {
            return null;
        }
    }

    /**
     * The value under {@code key}, a trial of the camera whose keys start with {@code base}, counts: it exists and the camera's
     * stamp is {@code now}; with no stamp (a build before P214h) the camera's trials get {@code now} and count.
     */
    static boolean counts(SharedPreferences p, String base, String key, String now) {
        if (!p.contains(key)) return false;
        final String stored = string(p, base + STAMP);
        if (stored == null) {
            p.edit().putString(base + STAMP, now).apply();
            return true;
        }
        return stored.equals(now);
    }

    /**
     * An editor for a new trial of the camera {@code base}: its trials of another stamp go first (a missing stamp is a build
     * before P214h: kept, as {@link #counts} keeps them), the stamp is {@code now}. The caller puts the trial and applies it.
     */
    static SharedPreferences.Editor edit(SharedPreferences p, String base, String now) {
        final SharedPreferences.Editor e = p.edit();
        final String stored = string(p, base + STAMP);
        if (stored != null && !stored.equals(now)) for (String k : keys(p, base)) e.remove(k);
        return e.putString(base + STAMP, now);
    }

    /** The keys of the camera {@code base}: the base itself and base_* (camera 2's never take camera 20's), sorted. */
    static List<String> keys(SharedPreferences p, String base) {
        final List<String> out = new ArrayList<>();
        for (String k : p.getAll().keySet()) if (k.equals(base) || k.startsWith(base + "_")) out.add(k);
        Collections.sort(out);
        return out;
    }

    /** Forgets the camera {@code base}'s trials (every key, the stamp); the number of keys removed. */
    static int clear(SharedPreferences p, String base) {
        final List<String> keys = keys(p, base);
        if (keys.isEmpty()) return 0;
        final SharedPreferences.Editor e = p.edit();
        for (String k : keys) e.remove(k);
        e.apply();
        return keys.size();
    }

    /** The camera {@code base}'s keys and values in one line, for the log. */
    static String dump(SharedPreferences p, String base) {
        final StringBuilder sb = new StringBuilder();
        final Map<String, ?> all = p.getAll();
        for (String k : keys(p, base)) sb.append(sb.length() == 0 ? "" : " ").append(k).append('=').append(all.get(k));
        return sb.toString();
    }

    /**
     * The reason code of a failure as CaptureController / VivoSdkFullResCapture word it: «no preview frame for 3000 ms», «camera
     * error 4», «session: …», «the frames are remosaicked by the HAL (plain Bayer, no mosaic)», «Native FULLRES mosaic does not
     * match its sensor profile», «SDK FULLRES50 sensor mode is not active».
     */
    static String code(@Nullable String why) {
        final String w = why == null ? "" : why.toLowerCase(Locale.ROOT);
        if (w.contains("no preview frame") || w.contains("no frame")) return NO_FRAME;
        if (w.contains("camera error") || w.contains("disconnected")) return DIED;
        if (w.contains("remosaicked by the hal")) return HW_REMOSAIC;
        if (w.contains("mosaic")) return MOSAIC;
        if (w.contains("sensor mode is not active")) return MODE;
        if (w.contains("session") || w.contains("preview") || w.contains("configur")) return REFUSED;
        return OTHER;
    }

    /** The stored reasons with {@code row}'s set to {@code code} (in the order the rows failed). */
    static String withReason(@Nullable String stored, int row, String code) {
        final Map<Integer, String> reasons = reasons(stored);
        reasons.remove(row);
        reasons.put(row, code);
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : reasons.entrySet())
            sb.append(sb.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(e.getValue());
        return sb.toString();
    }

    /** row -> reason code of the stored «row:code,row:code» (malformed entries are passed over). */
    static Map<Integer, String> reasons(@Nullable String stored) {
        final Map<Integer, String> out = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) return out;
        for (String entry : stored.split(",")) {
            final int colon = entry.indexOf(':');
            if (colon <= 0) continue;
            try {
                out.put(Integer.parseInt(entry.substring(0, colon).trim()), entry.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The text of a reason ({@code many}: said of several modes). */
    static String phrase(@Nullable String code, boolean many) {
        if (code == null) code = OTHER;
        switch (code) {
            case NO_FRAME: return Lang.t("нет кадра", "no frame");
            case DIED: return Lang.t("камера упала", "the camera failed");
            case REFUSED: return Lang.t("камера отклонила сессию", "the camera refused the session");
            case HW_REMOSAIC: return Lang.t("это аппаратный ремозаик камеры (обычный Байер)", "it is the camera's hardware remosaic (plain Bayer)");
            case MOSAIC: return Lang.t("не та мозаика", "the wrong mosaic");
            case MODE: return Lang.t("режим не включился", "the mode did not switch on");
            default: return many ? Lang.t("не работают", "do not work") : Lang.t("не работает", "does not work");
        }
    }

    /** «режимы 11, 31 — нет кадра; режим 9 — камера упала»: the failed rows, those of one reason together, in the order tried. */
    static String rows(int[] failed, Map<Integer, String> reasons) {
        final Map<String, List<Integer>> byReason = new LinkedHashMap<>();
        for (int row : failed) {
            final String code = reasons.containsKey(row) ? reasons.get(row) : OTHER;
            List<Integer> list = byReason.get(code);
            if (list == null) byReason.put(code, list = new ArrayList<>());
            list.add(row);
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Integer>> e : byReason.entrySet()) {
            final boolean many = e.getValue().size() > 1;
            final StringBuilder list = new StringBuilder();
            for (int row : e.getValue()) list.append(list.length() == 0 ? "" : ", ").append(row);
            sb.append(sb.length() == 0 ? "" : "; ").append(many ? Lang.t("режимы ", "modes ") : Lang.t("режим ", "mode "))
                    .append(list).append(" — ").append(phrase(e.getKey(), many));
        }
        return sb.toString();
    }

    /**
     * One size's line: «FULLRES 50 Мп выключен: режимы 11, 31 — нет кадра.» once every row of {@code rows} failed, «FULLRES
     * 50 Мп: режим 3 — нет кадра; дальше режим 1.» while one is left, null before any failed. {@code rows} null (an OPPO camera
     * not opened in this run: its table unknown) lists the rows the reasons name. {@code megapixels} 0: no size in the text.
     */
    @Nullable
    static String sizeLine(int megapixels, @Nullable int[] rows, int tried, Map<Integer, String> reasons) {
        if (tried <= 0) return null;
        final String size = megapixels > 0 ? "FULLRES " + megapixels + Lang.t(" Мп", " MP") : "FULLRES";
        if (rows == null) {
            if (reasons.isEmpty()) return size + Lang.t(": не работает режимов — " + tried + ".", ": modes that do not work — " + tried + ".");
            final int[] named = new int[reasons.size()];
            int i = 0;
            for (int row : reasons.keySet()) named[i++] = row;
            return size + ": " + rows(named, reasons) + ".";
        }
        if (rows.length == 0) return null;
        final int n = Math.min(tried, rows.length);
        final int[] failed = new int[n];
        System.arraycopy(rows, 0, failed, 0, n);
        return n >= rows.length
                ? size + Lang.t(" выключен: ", " is off: ") + rows(failed, reasons) + "."
                : size + ": " + rows(failed, reasons) + Lang.t("; дальше режим ", "; next mode ") + rows[n] + ".";
    }

    /**
     * The line of a camera's hardware-remosaic row ({@code row} &lt; 0: none) beside its 50 MP line ({@code sized}: the 50 MP size
     * has failed rows): failed - its reason; else with «FULLRES: аппаратный ремозаик» on - the 50 MP shots take it now; off - the
     * row is the camera's own remosaic and the switch takes it. Null when nothing is to be said.
     */
    @Nullable
    static String hardwareLine(int row, boolean failed, @Nullable String code, boolean switchOn, boolean sized) {
        if (row < 0) return null;
        if (failed) return Lang.t("Аппаратный ремозаик (режим " + row + ") — ", "The hardware remosaic (mode " + row + ") — ")
                + phrase(code, false) + ".";
        if (!sized) return null;
        return switchOn
                ? Lang.t("Сейчас 50 Мп снимаются через аппаратный ремозаик камеры (режим " + row + ").",
                        "50 MP shots are taken by the camera's hardware remosaic now (mode " + row + ").")
                : Lang.t("Режим " + row + " — аппаратный ремозаик самой камеры: с «FULLRES: аппаратный ремозаик» (Съёмка) 50 Мп снимаются через него.",
                        "Mode " + row + " is the camera's own hardware remosaic: with “FULLRES: hardware remosaic” (Shooting) 50 MP shots are taken by it.");
    }

    @Nullable
    private static SharedPreferences prefs() {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * What a camera's FULLRES trials say (the dimmed button, its long-press, «Настройки по модулям»): the rows tried and why each
     * failed, per size, and the hardware-remosaic row; null when no row of the camera failed (or the phone keeps no trials: the
     * X200 Ultra's rows are measured).
     */
    @Nullable
    public static String status(@Nullable String cameraId) {
        final SharedPreferences p = prefs();
        if (p == null || cameraId == null) return null;
        try {
            if (OppoNativeFullRes.brand()) return OppoNativeFullRes.status(p, cameraId, OppoNativeFullRes.stamp());
            return VivoNativeFullRes.status(p, VivoNativeFullRes.phone(), cameraId, VivoNativeFullRes.stamp(),
                    VivoSdkFullRes.hardwareRemosaicOn());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** «Попробовать снова»: forgets the camera's trials (both routes' keys); its next FULLRES starts at its first row. */
    public static void retry(@Nullable String cameraId) {
        final SharedPreferences p = prefs();
        if (p == null || cameraId == null) return;
        final int removed = VivoNativeFullRes.clearFailures(p, cameraId) + OppoNativeFullRes.clearFailures(p, cameraId);
        com.particlesdevs.photoncamera.util.Log.i(TAG, "camera " + cameraId + ": FULLRES trials forgotten (" + removed
                + " keys); its rows are tried again from the first");
    }

    /** The camera's remembered trials as stored, for the log (B3: the failures known when the camera opens). */
    public static String dump(@Nullable String cameraId) {
        final SharedPreferences p = prefs();
        if (p == null || cameraId == null) return "";
        try {
            final String vivo = dump(p, VivoNativeFullRes.base(cameraId)), oppo = dump(p, OppoNativeFullRes.base(cameraId));
            return vivo.isEmpty() ? oppo : oppo.isEmpty() ? vivo : vivo + " " + oppo;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
