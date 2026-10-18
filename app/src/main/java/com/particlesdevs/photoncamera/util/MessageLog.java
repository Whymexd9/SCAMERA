package com.particlesdevs.photoncamera.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * P214f (speed-ux.md C3): the history of the camera screen's one message channel ({@link UiMessages}), plain Java so it is
 * tested on the JVM. Before, a message was a 2 s system toast (CaptureController), a 1.5 s card (CameraFragment) or a short
 * snackbar, and a FULLRES row failure of ~120 characters was gone before it could be read; nothing was kept.
 * <ul>
 *   <li>{@link #durationMs}: how long the band shows an information message, 1.3 s + 35 ms per character (SIMPLE_UI: the
 *       short one-line texts), at most 7 s (an error stays until it is tapped, the card decides that);</li>
 *   <li>{@link #add}: a repeat (the same text and kind within {@link #MERGE_WINDOW_MS} of its last time) is merged into one
 *       entry with a counter and moves to the newest place, so a restart loop is one line "x7", not seven;</li>
 *   <li>the last {@link #KEEP} entries are kept, written to a small private file ({@link #save} / {@link #loadOlder}) so the
 *       messages before an app restart (e.g. «Камера не отвечает ... перезапустите приложение») can still be read.</li>
 * </ul>
 * Thread-safe: messages come from the camera threads and the main thread.
 */
public final class MessageLog {
    public static final int KEEP = 20;
    public static final long MIN_MS = 1300, PER_CHAR_MS = 35, MAX_MS = 7000;
    /** A repeat later than this is a new entry: a counter that grew over a whole day would say nothing on the card. */
    public static final long MERGE_WINDOW_MS = 10 * 60_000L;
    private static final String HEADER = "scam-messages 1";

    /** One message (immutable; a repeat replaces the entry with a new one, count + 1). Times are wall-clock ms. */
    public static final class Entry {
        public final String text;
        public final boolean error;
        public final int count;
        public final long firstMs, lastMs;

        public Entry(String text, boolean error, int count, long firstMs, long lastMs) {
            this.text = text;
            this.error = error;
            this.count = Math.max(1, count);
            this.firstMs = firstMs;
            this.lastMs = lastMs;
        }

        boolean same(String t, boolean e) {
            return error == e && text.equals(t);
        }
    }

    /** Oldest first. */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    /** How long the card shows {@code text} as an information message: 1.5 s + 50 ms per character, 1.5 ... 7 s. */
    public static long durationMs(CharSequence text) {
        int n = text == null ? 0 : text.length();
        return Math.min(MAX_MS, MIN_MS + PER_CHAR_MS * n);
    }

    /**
     * Records a message at {@code nowMs} and returns its entry (the merged one for a repeat). Blank text records nothing
     * (null). The text is trimmed.
     */
    public synchronized Entry add(CharSequence text, boolean error, long nowMs) {
        if (text == null) return null;
        String t = text.toString().trim();
        if (t.isEmpty()) return null;
        Entry merged = null;
        for (Iterator<Entry> it = entries.descendingIterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.same(t, error) && nowMs - e.lastMs <= MERGE_WINDOW_MS && nowMs >= e.lastMs) {
                it.remove();
                merged = new Entry(t, error, e.count + 1, e.firstMs, nowMs);
                break;
            }
        }
        Entry out = merged != null ? merged : new Entry(t, error, 1, nowMs, nowMs);
        entries.addLast(out);
        while (entries.size() > KEEP) entries.pollFirst();
        return out;
    }

    /** The kept entries, newest first. */
    public synchronized List<Entry> newestFirst() {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Iterator<Entry> it = entries.descendingIterator(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    /**
     * Puts entries of an earlier run (oldest first) before the ones of this run; the newest {@link #KEEP} of both stay.
     * Used when the saved file is read after messages of this run were already recorded.
     */
    public synchronized void prependOlder(List<Entry> older) {
        if (older == null || older.isEmpty()) return;
        ArrayDeque<Entry> merged = new ArrayDeque<>(older);
        merged.addAll(entries);
        while (merged.size() > KEEP) merged.pollFirst();
        entries.clear();
        entries.addAll(merged);
    }

    // ───── the file: a header line, then one entry per line, oldest first: firstMs \t lastMs \t count \t E|I \t text

    public synchronized String serialize() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            out.append(e.firstMs).append('\t').append(e.lastMs).append('\t').append(e.count).append('\t')
                    .append(e.error ? 'E' : 'I').append('\t').append(escape(e.text)).append('\n');
        }
        return out.toString();
    }

    /** The entries of {@link #serialize}'s text, oldest first; a damaged line (or a file of another format) is skipped. */
    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0].trim())) return out;
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t", 5);
            if (f.length != 5 || !("E".equals(f[3]) || "I".equals(f[3]))) continue;
            try {
                String msg = unescape(f[4]).trim();
                if (msg.isEmpty()) continue;
                out.add(new Entry(msg, "E".equals(f[3]), Integer.parseInt(f[2]), Long.parseLong(f[0]), Long.parseLong(f[1])));
            } catch (NumberFormatException ignored) {
                // a damaged line
            }
        }
        while (out.size() > KEEP) out.remove(0);
        return out;
    }

    /** Writes the entries to {@code file} through a temporary file beside it (a cut write leaves the old file). */
    public void save(File file) throws IOException {
        byte[] bytes = serialize().getBytes(StandardCharsets.UTF_8);
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            Files.write(file.toPath(), bytes);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Reads {@code file} (missing or unreadable: nothing) and puts its entries before this run's. */
    public void loadOlder(File file) {
        if (file == null || !file.isFile() || file.length() > 256 * 1024) return;
        try {
            prependOlder(parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException ignored) {
            // a message history is a convenience: an unreadable file is an empty history
        }
    }

    /** Backslash, newline, carriage return and tab escaped; other control characters dropped. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c >= 0x20) out.append(c);
            }
        }
        return out.toString();
    }

    static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            out.append(n == 'n' ? '\n' : n == 'r' ? '\r' : n == 't' ? '\t' : n);
        }
        return out.toString();
    }
}
