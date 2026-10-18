package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Lang;

/**
 * P143: the config the settings came from - the name the shade's config button shows. It lives in its own prefs file, so a
 * config never carries it (BackupRestoreUtil.collect saves the main, module and per-lens files only): it is set when a config
 * is saved or imported and cleared by the reset to defaults. Written with commit(): an import restarts the app right after.
 */
public final class ConfigState {
    static final String PREFS = "scam_config_state", KEY_ACTIVE = "active";
    /** The settings came from a config whose file was deleted: «Без конфига», not the defaults. */
    static final String KEY_DETACHED = "detached";

    private ConfigState() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The active config's name (without the extension), null when the settings came from no config. */
    @Nullable
    public static String active(Context c) {
        String name = prefs(c).getString(KEY_ACTIVE, null);
        return name == null || name.trim().isEmpty() ? null : name;
    }

    /** What the shade shows: the active config, «Без конфига» after its file was deleted, «Стандартный» otherwise. */
    public static String label(Context c) {
        String name = active(c);
        if (name != null) return name;
        return prefs(c).getBoolean(KEY_DETACHED, false) ? Lang.t(c, "Без профиля", "No profile") : Lang.t(c, "Стандартный", "Default");
    }

    /** A config was saved under / imported from {@code fileName} (a path, a document id or a bare name). */
    public static void set(Context c, @Nullable String fileName) {
        String name = base(fileName);
        if (name.isEmpty()) clear(c);
        else prefs(c).edit().putString(KEY_ACTIVE, name).remove(KEY_DETACHED).commit();
    }

    /** The defaults (the reset). */
    public static void clear(Context c) {
        prefs(c).edit().remove(KEY_ACTIVE).remove(KEY_DETACHED).commit();
    }

    /** The active config's file was deleted: the settings stay, they belong to no config now. */
    public static void detach(Context c) {
        prefs(c).edit().remove(KEY_ACTIVE).putBoolean(KEY_DETACHED, true).commit();
    }

    /** «Download/SCAMERA/XML/Night_1.xml» -> «Night_1»; «primary:Download/x.json» -> «x». */
    public static String base(@Nullable String fileName) {
        if (fileName == null) return "";
        String name = fileName.trim();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        if (cut >= 0) name = name.substring(cut + 1);
        return name.replaceAll("(?i)\\.(xml|json)$", "").trim();
    }

    /** A name a config can be saved under: letters, digits, «_» and «-», spaces become «_» (as the settings' save field). */
    public static String fileSafe(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') out.append(ch);
            else if (Character.isWhitespace(ch)) out.append('_');
        }
        return out.toString().replaceAll("_+", "_");
    }
}
