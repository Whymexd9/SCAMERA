package com.particlesdevs.photoncamera.settings;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Reads and edits scam_dev.txt (the app's external files dir, "key value" lines) for the «Для разработчика» page. Other lines
 * (comments, keys not on the page) stay as they are; a key's line is replaced in place or appended; removing it restores the
 * code's default. PreferenceKeys re-reads the file when it changes (at most every 2 s).
 */
public final class ScamDevFile {
    private ScamDevFile() {}

    @Nullable
    public static File file(@Nullable Context context) {
        File dir = context == null ? null : context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "scam_dev.txt");
    }

    static List<String> lines(@Nullable File f) {
        List<String> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        try {
            for (String l : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\r?\n", -1)) out.add(l);
            if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The value text of a key's line, null without one. */
    @Nullable
    public static String get(@Nullable Context context, String key) {
        return valueOf(lines(file(context)), key);
    }

    @Nullable
    static String valueOf(List<String> lines, String key) {
        for (String l : lines) {
            String[] p = l.trim().split("\\s+");
            if (p.length == 2 && p[0].equals(key)) return p[1];
        }
        return null;
    }

    /** Sets (value != null) or removes (null) a key's line; true when the file was written. */
    public static boolean set(@Nullable Context context, String key, @Nullable String value) {
        File f = file(context);
        if (f == null) return false;
        List<String> lines = edit(lines(f), key, value);
        try {
            File tmp = new File(f.getParentFile(), "scam_dev.txt.tmp");
            Files.write(tmp.toPath(), (String.join("\n", lines) + (lines.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8));
            return tmp.renameTo(f) || (f.delete() && tmp.renameTo(f));
        } catch (Exception e) {
            return false;
        }
    }

    /** Removes the lines of these keys; true when written. */
    public static boolean removeAll(@Nullable Context context, Collection<String> keys) {
        File f = file(context);
        if (f == null || !f.isFile()) return false;
        List<String> lines = lines(f);
        for (String k : keys) lines = edit(lines, k, null);
        try {
            Files.write(f.toPath(), (String.join("\n", lines) + (lines.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The lines with the key's line replaced (first one), dropped (value null; every copy) or appended. */
    static List<String> edit(List<String> lines, String key, @Nullable String value) {
        List<String> out = new ArrayList<>();
        boolean done = false;
        for (String l : lines) {
            String[] p = l.trim().split("\\s+");
            if (p.length >= 1 && p[0].equals(key) && !l.trim().startsWith("#")) {
                if (value != null && !done) out.add(key + " " + value);
                done = true;
                continue;
            }
            out.add(l);
        }
        if (!done && value != null) out.add(key + " " + value);
        return out;
    }

    /** A number as the file holds it: integers without ".0". */
    public static String number(float v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? String.valueOf((long) v) : String.format(Locale.ROOT, "%s", v);
    }
}
