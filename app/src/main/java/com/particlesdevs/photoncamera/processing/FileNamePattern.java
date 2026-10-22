package com.particlesdevs.photoncamera.processing;

import java.util.Calendar;
import java.util.Locale;

/**
 * «Название фото» (P119, owner 2026-10-13: «вместо IMG сделать SCA», «настраивать как это сделано в WhyCam»): the file name of a new
 * photo from a template. The date / time variables {@link #VARIABLES} are replaced, everything else is kept as it is (so «SCA» or
 * «Отпуск» need no quoting). Characters a file name cannot hold are dropped; an empty result falls back to {@link #DEFAULT}.
 */
public final class FileNamePattern {
    private FileNamePattern() {}

    public static final String KEY = "pref_file_name_pattern";
    public static final String DEFAULT = "SCA_yyyyMMdd_HHmmss";
    /** Variables, longest first where one starts another (yyyy before yy). */
    public static final String[] VARIABLES = {"yyyy", "yy", "MM", "dd", "HH", "mm", "ss", "SSS"};
    public static final String[] PRESETS = {DEFAULT, "SCA_yyyyMMdd_HHmmss_SSS", "IMG_yyyyMMdd_HHmmss", "yyyy-MM-dd_HH-mm-ss"};
    static final int MAX_LENGTH = 96;

    /** The name (no extension) of a photo taken at {@code millis} with {@code pattern}. */
    public static String format(String pattern, long millis) {
        final String p = clean(pattern);
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < p.length(); ) {
            String token = null;
            for (String v : VARIABLES) if (p.startsWith(v, i)) { token = v; break; }
            if (token == null) { out.append(p.charAt(i++)); continue; }
            out.append(value(token, c));
            i += token.length();
        }
        return out.toString();
    }

    private static String value(String token, Calendar c) {
        switch (token) {
            case "yyyy": return String.format(Locale.ROOT, "%04d", c.get(Calendar.YEAR));
            case "yy": return String.format(Locale.ROOT, "%02d", c.get(Calendar.YEAR) % 100);
            case "MM": return String.format(Locale.ROOT, "%02d", c.get(Calendar.MONTH) + 1);
            case "dd": return String.format(Locale.ROOT, "%02d", c.get(Calendar.DAY_OF_MONTH));
            case "HH": return String.format(Locale.ROOT, "%02d", c.get(Calendar.HOUR_OF_DAY));
            case "mm": return String.format(Locale.ROOT, "%02d", c.get(Calendar.MINUTE));
            case "ss": return String.format(Locale.ROOT, "%02d", c.get(Calendar.SECOND));
            default: return String.format(Locale.ROOT, "%03d", c.get(Calendar.MILLISECOND));
        }
    }

    /** The template as it is used: no characters a file name cannot hold, no dots at the ends, at most {@link #MAX_LENGTH}. */
    public static String clean(String pattern) {
        if (pattern == null) return DEFAULT;
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            final char ch = pattern.charAt(i);
            if (ch < 0x20 || "/\\:*?\"<>|".indexOf(ch) >= 0) continue;
            b.append(ch);
        }
        String s = b.toString().trim();
        while (s.startsWith(".")) s = s.substring(1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        s = s.trim();
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH);
        return s.isEmpty() ? DEFAULT : s;
    }
}
