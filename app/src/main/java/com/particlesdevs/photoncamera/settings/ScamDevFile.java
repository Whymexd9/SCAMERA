package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.ScameraFiles;
import com.particlesdevs.photoncamera.util.ScameraFolder;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Reads and edits scam_dev.txt ("key value" lines) for the «Для разработчика» page. Other lines (comments, keys not on the
 * page) stay as they are; a key's line is replaced in place or appended; removing it restores the code's default.
 * PreferenceKeys re-reads the file when it changes (at most every 2 s).
 * <p>
 * P191 (owner, 2026-10-17: «переноси всё туда чтобы в data ничего не хранилось»): the file is Download/SCAMERA/scam_dev.txt,
 * not the package's external files dir (a file manager on Android 16 cannot open Android/data); the first call moves the
 * old file there. The File API reads and writes it; a copy the owner put there with a PC or a file manager (not the app's
 * own, so hidden from the File API on Android 11+) goes through the folder grant of Download/SCAMERA ({@link ScameraFolder#ROOT}).
 */
public final class ScamDevFile {
    private ScamDevFile() {}

    public static final String NAME = "scam_dev.txt";
    /** The path shown to the user. */
    public static final String SHOWN = ScameraFiles.RELATIVE + "/" + NAME;
    private static volatile boolean migrated;

    /** Download/SCAMERA/scam_dev.txt; the first call with a context moves the old Android/data copy there (P191). */
    public static File file(@Nullable Context context) {
        if (context != null && !migrated) {
            migrated = true;
            ScameraFiles.migrate(context, ScameraFiles.legacy(context, NAME), NAME);
        }
        return ScameraFiles.file(NAME);
    }

    /** The file's text: the File API, else through the folder grant; null without the file. */
    @Nullable
    public static String read(@Nullable Context context) {
        File f = file(context);
        if (f.isFile()) {
            try {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // not the app's own file: the grant below
            }
        }
        return readGranted(context);
    }

    /** The text through the grant of Download/SCAMERA (a file the File API does not see); null without one. */
    @Nullable
    static String readGranted(@Nullable Context context) {
        try {
            Uri doc = context == null ? null : ScameraFolder.ROOT.grantedDocument(context, NAME);
            if (doc == null) return null;
            try (InputStream in = context.getContentResolver().openInputStream(doc)) {
                return in == null ? null : new String(ScameraFiles.readAll(in), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> lines(@Nullable String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String l : text.split("\\r?\\n", -1)) out.add(l);
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /**
     * Writes the lines: the File API (a temporary file renamed over it), else into the grant's document of the file ("wt");
     * false when neither works.
     */
    private static boolean write(@Nullable Context context, List<String> lines) {
        byte[] data = (String.join("\n", lines) + (lines.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8);
        File f = file(context);
        File tmp = new File(f.getParentFile(), NAME + ".tmp");
        try {
            Files.write(tmp.toPath(), data);
            if (tmp.renameTo(f) || (f.delete() && tmp.renameTo(f))) return true;
        } catch (Exception ignored) {
            // the grant below
        }
        tmp.delete();
        try {
            Uri doc = context == null ? null : ScameraFolder.ROOT.grantedDocument(context, NAME);
            if (doc == null) return false;
            try (OutputStream out = context.getContentResolver().openOutputStream(doc, "wt")) {
                if (out == null) return false;
                out.write(data);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** The value text of a key's line, null without one. */
    @Nullable
    public static String get(@Nullable Context context, String key) {
        return valueOf(lines(read(context)), key);
    }

    /**
     * P303 (owner, 2026-10-20: «при переходе по пунктам меню появилась очень долгая задержка»): every key's value from ONE read of
     * the file. The developer page is built with every settings screen; it read the file once per key (179 keys), and a file the
     * owner wrote with a file manager is read through the folder grant (tens of ms each): seconds on every screen.
     */
    public static java.util.Map<String, String> snapshot(@Nullable Context context) {
        return snapshotOf(read(context));
    }

    static java.util.Map<String, String> snapshotOf(@Nullable String text) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String l : lines(text)) {
            String[] p = l.trim().split("\\s+");
            // P319: a line written before the Why rename shows under its new key (a new line wins)
            if (p.length == 2 && !out.containsKey(BrandMigration.why(p[0]))) out.put(BrandMigration.why(p[0]), p[1]);
        }
        return out;
    }

    @Nullable
    static String valueOf(List<String> lines, String key) {
        for (String l : lines) {
            String[] p = l.trim().split("\\s+");
            if (p.length == 2 && (p[0].equals(key) || BrandMigration.why(p[0]).equals(key))) return p[1]; // P319: old names too
        }
        return null;
    }

    /** Sets (value != null) or removes (null) a key's line; true when the file was written. */
    public static boolean set(@Nullable Context context, String key, @Nullable String value) {
        return write(context, edit(lines(read(context)), key, value));
    }

    /** Removes the lines of these keys; true when written. */
    public static boolean removeAll(@Nullable Context context, Collection<String> keys) {
        String text = read(context);
        if (text == null) return false;
        List<String> lines = lines(text);
        for (String k : keys) lines = edit(lines, k, null);
        return write(context, lines);
    }

    /**
     * The full settings reset: the file goes to scam_dev.txt.bak beside it (its lines can be taken back by hand). A file the
     * File API cannot rename (P191: not the app's own) is copied to the .bak and deleted through the grant. True when no
     * scam_dev.txt is left.
     */
    public static boolean setAside(@Nullable Context context) {
        File f = file(context);
        File bak = new File(f.getParentFile(), NAME + ".bak");
        if (f.isFile()) {
            if (bak.isFile() && !bak.delete()) return false;
            if (f.renameTo(bak)) return true;
        }
        String text = read(context);
        if (text == null) return true;
        try {
            if (bak.isFile() && !bak.delete()) return false;
            Files.write(bak.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        if (f.isFile() && f.delete()) return true;
        try {
            Uri doc = context == null ? null : ScameraFolder.ROOT.grantedDocument(context, NAME);
            return doc != null && DocumentsContract.deleteDocument(context.getContentResolver(), doc);
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
