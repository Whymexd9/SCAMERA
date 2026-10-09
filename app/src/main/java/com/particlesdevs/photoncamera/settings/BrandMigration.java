package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P55: settings stored before the SCAM rename get the new names, once per launch and before every other migration: the keys
 * of every SharedPreferences file (and the file names), the identifiers inside string values (shade tiles, profiles, list
 * values), the developer file and the old extracted bundle. The rules are the ones of the rename tool (rebrand.py: the
 * stored-key prefixes first, then the words); the old words are kept reversed so the APK carries no old names.
 */
public final class BrandMigration {
    private BrandMigration() {}

    private static String r(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    /** A word with one of the old names: [A-Za-z0-9_]* around (lmc | vivo | nice), any case. */
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z0-9_]*(?:[Ll][Mm][Cc]|[Vv][Ii][Vv][Oo]|[Nn][Ii][Cc][Ee])[A-Za-z0-9_]*");

    /** Stored-key prefixes (old reversed, new); no new prefix starts another one, so startsWith checks keep their meaning. */
    private static final String[][] PREF = {
            {r("_dirbyh_cml_ferp"), "pref_scam_hybrid_"},
            {r("_ecin_oviv_ferp"), "pref_scamhdr_"},
            {r("_ecin_ferp"), "pref_scamold_"},
            {r("_rdh_oviv_ferp"), "pref_scamroute_"},
    };

    /** The word replacements in order (old reversed, new). */
    private static final String[][] ORDER = {
            {r("eciNeciNoviV"), "ScamScam"},
            {r("ECIN_OVIV"), "SCAM"}, {r("eciNoviV"), "Scam"}, {r("eciNoviv"), "scam"}, {r("ecin_oviv"), "scam"},
            {r("ecinoviv"), "scam"},
            {r("LARUEN_OVIV"), "SCAM_NEURAL"}, {r("larueNoviV"), "ScamNeural"}, {r("larueNoviv"), "scamNeural"},
            {r("laruen_oviv"), "scam_neural"},
            {r("DIRBYH_CML"), "SCAM_HYBRID"}, {r("dirbyHcmL"), "ScamHybrid"}, {r("dirbyHcml"), "scamHybrid"},
            {r("dirbyh_cml"), "scam_hybrid"},
            {r("oviV"), "Scam"}, {r("oviv"), "scam"}, {r("OVIV"), "SCAM"},
            {r("eciN"), "Scam"}, {r("ecin"), "scam"}, {r("ECIN"), "SCAM"},
            {r("cmL"), "Scam"}, {r("cml"), "scam"}, {r("CML"), "SCAM"},
    };

    private static final Pattern COLLAPSE_CAP = Pattern.compile("(Scam)(Scam)+");
    private static final Pattern COLLAPSE_LOW = Pattern.compile("(scam)(_?scam)+");
    private static final Pattern COLLAPSE_UP = Pattern.compile("(SCAM)(_?SCAM)+");

    /** One word with an old name -> its new name (the rename tool's rule for identifiers and stored keys). */
    static String word(String token) {
        String out = token;
        for (String[] p : PREF) {
            if (out.startsWith(p[0])) {
                out = p[1] + out.substring(p[0].length());
                break;
            }
        }
        for (String[] o : ORDER) out = out.replace(o[0], o[1]);
        out = COLLAPSE_CAP.matcher(out).replaceAll("$1");
        out = COLLAPSE_LOW.matcher(out).replaceAll("$1");
        out = COLLAPSE_UP.matcher(out).replaceAll("$1");
        return out;
    }

    /**
     * Every old word of a key or a string value renamed. A word next to a '.' stays: vendor tags (vivo.control.*), vendor
     * packages and library files keep their names.
     */
    public static String text(String s) {
        if (s == null || s.isEmpty()) return s;
        Matcher m = TOKEN.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            boolean dotted = (m.start() > 0 && s.charAt(m.start() - 1) == '.') || (m.end() < s.length() && s.charAt(m.end()) == '.');
            String n = dotted ? m.group() : word(m.group());
            if (n.equals(m.group())) continue;
            if (out == null) out = new StringBuilder(s.length());
            out.append(s, last, m.start()).append(n);
            last = m.end();
        }
        if (out == null) return s;
        return out.append(s, last, s.length()).toString();
    }

    @SuppressWarnings("unchecked")
    static Object value(Object v) {
        if (v instanceof String) return text((String) v);
        if (v instanceof Set) {
            Set<String> out = new HashSet<>();
            boolean changed = false;
            for (Object o : (Set<Object>) v) {
                String s = o == null ? null : o.toString();
                String n = text(s);
                changed |= n != null && !n.equals(s);
                out.add(n);
            }
            return changed ? out : v;
        }
        return v;
    }

    /** A settings map (backup, module profile) with the new names; a new key already present wins over its old twin. */
    public static Map<String, Object> map(Map<String, ?> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values == null) return out;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            String k = text(e.getKey());
            if (!k.equals(e.getKey()) && values.containsKey(k)) continue;
            out.put(k, value(e.getValue()));
        }
        return out;
    }

    /** Renames the old keys and values of one preferences file in place. Returns whether anything changed. */
    @SuppressWarnings("unchecked")
    public static boolean migrate(SharedPreferences prefs) {
        if (prefs == null) return false;
        Map<String, ?> all = prefs.getAll();
        SharedPreferences.Editor e = null;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String k = entry.getKey(), nk = text(k);
            Object v = entry.getValue(), nv = value(v);
            if (nk.equals(k) && nv == v) continue;
            if (e == null) e = prefs.edit();
            if (!nk.equals(k)) {
                e.remove(k);
                if (all.containsKey(nk)) continue; // written by the new version already
            }
            put(e, nk, nv);
        }
        return e != null && e.commit();
    }

    @SuppressWarnings("unchecked")
    static void put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
        else if (v != null) e.putString(k, v.toString());
    }

    /**
     * All of the app's preferences files (an old file name moves to its new name), the developer file of the external files
     * dir and the old extracted bundle of the diagnostics screen. Runs before any other settings code reads a value.
     */
    public static void migrateAll(Context context) {
        if (context == null) return;
        try {
            File dir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (!name.endsWith(".xml")) continue;
                    name = name.substring(0, name.length() - 4);
                    String newName = text(name);
                    SharedPreferences old = context.getSharedPreferences(name, Context.MODE_PRIVATE);
                    if (newName.equals(name)) {
                        migrate(old);
                        continue;
                    }
                    SharedPreferences target = context.getSharedPreferences(newName, Context.MODE_PRIVATE);
                    Map<String, ?> existing = target.getAll();
                    SharedPreferences.Editor e = target.edit();
                    for (Map.Entry<String, Object> entry : map(old.getAll()).entrySet())
                        if (!existing.containsKey(entry.getKey())) put(e, entry.getKey(), entry.getValue());
                    e.commit();
                    migrate(target);
                    old.edit().clear().commit();
                    context.deleteSharedPreferences(name);
                }
            }
        } catch (RuntimeException ignored) {
            // settings stay readable under their old names at worst
        }
        try {
            File ext = context.getExternalFilesDir(null);
            File oldDev = ext == null ? null : new File(ext, r("txt.ved_ecin"));
            File newDev = ext == null ? null : new File(ext, "scam_dev.txt");
            if (oldDev != null && oldDev.isFile() && !newDev.exists()) oldDev.renameTo(newDev);
        } catch (RuntimeException ignored) {
            // the owner renames it by hand
        }
        deleteTree(new File(context.getFilesDir(), r("1v-deldnub-ecin")));
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }
}
