package com.particlesdevs.photoncamera.settings;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P385 (owner, 2026-10-28: «Добавляем возможность в настройках приложения менять расположение настроек так как захочет
 * пользователь, сделай это меню удобным в использовании и понятным»; «Учитывай что это должно сохраняться и в конфигах при
 * импорте/экспорте»): the user's order of the settings - on every page the order of its groups and rows, in each group the order
 * of its rows, and rows moved from one group of a page to another. One main setting ({@link #KEY}: a settings_ key, so shared by
 * every module and carried by the config file with the main settings), one line per container that differs from the app's own
 * order: {@code container=item,item,...;container=...} (preference keys hold none of {@code =,;}).
 * <p>
 * A container is a page (its groups and loose rows) or a group of it. The stored order is applied to the page as built, as the
 * LUT order is (ItemOrder): an item it does not know (a row of a newer build, a row generated on this phone only) keeps its place
 * after the item it follows in the app's order, an item no longer there is dropped; a row goes to another container only between
 * two groups of the same page that share rows ({@link Page#open}).
 */
public final class SettingsLayout {
    private SettingsLayout() {}

    public static final String KEY = "settings_layout";
    static final char LINE = ';', EQ = '=', ITEM = ',';

    /** One page as the app builds it: its containers (the page first) with their items in the app's order. */
    public static final class Page {
        public final String key;
        public final LinkedHashMap<String, List<String>> containers = new LinkedHashMap<>();
        /** The groups whose rows may move between each other. */
        public final Set<String> open = new HashSet<>();

        public Page(String key) {
            this.key = key;
        }

        public Page add(String container, List<String> items) {
            containers.put(container, new ArrayList<>(items));
            return this;
        }

        /** The container an item is in by the app's order (null: not on this page). */
        @Nullable
        public String home(String item) {
            for (Map.Entry<String, List<String>> e : containers.entrySet()) if (e.getValue().contains(item)) return e.getKey();
            return null;
        }

        /** Whether {@code item} (now in {@code from}) may go to {@code to}. */
        public boolean moves(String from, String to) {
            return from.equals(to) || open.contains(from) && open.contains(to);
        }
    }

    /** The stored text as containers and their items (in the text's order); malformed lines are skipped. */
    public static LinkedHashMap<String, List<String>> parse(@Nullable String stored) {
        final LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty()) return out;
        for (String line : stored.split(String.valueOf(LINE))) {
            final int eq = line.indexOf(EQ);
            if (eq <= 0) continue;
            final String container = line.substring(0, eq).trim();
            final List<String> items = new ArrayList<>();
            for (String item : line.substring(eq + 1).split(String.valueOf(ITEM))) {
                final String s = item.trim();
                if (!s.isEmpty() && !items.contains(s)) items.add(s);
            }
            if (!container.isEmpty() && !out.containsKey(container)) out.put(container, items);
        }
        return out;
    }

    public static String text(Map<String, List<String>> layout) {
        final StringBuilder b = new StringBuilder();
        for (Map.Entry<String, List<String>> e : layout.entrySet()) {
            if (!storable(e.getKey())) continue;
            final List<String> items = new ArrayList<>();
            for (String item : e.getValue()) if (storable(item)) items.add(item);
            if (b.length() > 0) b.append(LINE);
            b.append(e.getKey()).append(EQ).append(String.join(String.valueOf(ITEM), items));
        }
        return b.toString();
    }

    public static boolean storable(@Nullable String id) {
        return id != null && !id.isEmpty() && id.indexOf(LINE) < 0 && id.indexOf(EQ) < 0 && id.indexOf(ITEM) < 0;
    }

    /** {@code page} in the {@code stored} order (every container of the page, in the page's order). */
    public static LinkedHashMap<String, List<String>> apply(Page page, Map<String, List<String>> stored) {
        final LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        final Map<String, String> home = new HashMap<>();
        for (Map.Entry<String, List<String>> e : page.containers.entrySet())
            for (String item : e.getValue()) home.put(item, e.getKey());
        final Set<String> placed = new HashSet<>();
        for (String container : page.containers.keySet()) {
            final List<String> list = new ArrayList<>();
            final List<String> s = stored.get(container);
            if (s != null) for (String item : s) {
                final String from = home.get(item);
                if (from == null || placed.contains(item) || !page.moves(from, container)) continue;
                list.add(item);
                placed.add(item);
            }
            out.put(container, list);
        }
        // the items the stored order does not place: after the nearest item before them in the app's order still in their own
        // container, else at its start (in their own order)
        for (Map.Entry<String, List<String>> e : page.containers.entrySet()) {
            final List<String> own = e.getValue(), list = out.get(e.getKey());
            int head = 0;
            for (int i = 0; i < own.size(); i++) {
                final String item = own.get(i);
                if (placed.contains(item)) continue;
                int at = -1;
                for (int j = i - 1; j >= 0 && at < 0; j--) {
                    final int k = list.indexOf(own.get(j));
                    if (k >= 0) at = k + 1;
                }
                if (at < 0) at = head++;
                list.add(at, item);
                placed.add(item);
            }
        }
        return out;
    }

    public static LinkedHashMap<String, List<String>> apply(Page page, @Nullable String stored) {
        return apply(page, parse(stored));
    }

    /**
     * The stored text with {@code page}'s containers as {@code edited} (the containers that equal the app's order are left out, so
     * that the app's own later changes reach them); the other pages' lines are kept.
     */
    public static String store(@Nullable String stored, Page page, Map<String, List<String>> edited) {
        final LinkedHashMap<String, List<String>> all = parse(stored);
        for (String container : page.containers.keySet()) all.remove(container);
        final LinkedHashMap<String, List<String>> own = apply(page, Collections.emptyMap());
        for (String container : page.containers.keySet()) {
            final List<String> items = edited.get(container);
            if (items != null && !items.equals(own.get(container))) all.put(container, new ArrayList<>(items));
        }
        return text(all);
    }

    /** The stored text without {@code page}'s lines (the page in the app's order again). */
    public static String reset(@Nullable String stored, Page page) {
        return store(stored, page, Collections.emptyMap());
    }

    /** Whether {@code page} is in the app's order under {@code stored}. */
    public static boolean isDefault(Page page, @Nullable String stored) {
        return apply(page, stored).equals(apply(page, Collections.emptyMap()));
    }

    /**
     * A container's items after a move among the items shown: {@code shown} in their new order (an item moved in from another group
     * among them), each hidden item of {@code old} kept after the shown item it followed (or at the start), an item moved out gone.
     */
    public static List<String> arrange(List<String> old, List<String> shown, Set<String> hidden) {
        final Set<String> stays = new HashSet<>(shown);
        final Map<String, List<String>> after = new HashMap<>();
        final List<String> head = new ArrayList<>();
        String prev = null;
        for (String item : old) {
            if (hidden.contains(item)) {
                if (prev == null) head.add(item);
                else after.computeIfAbsent(prev, k -> new ArrayList<>()).add(item);
            } else if (stays.contains(item)) prev = item;
        }
        final List<String> out = new ArrayList<>(head);
        for (String item : shown) {
            if (hidden.contains(item)) continue;
            out.add(item);
            final List<String> tail = after.get(item);
            if (tail != null) out.addAll(tail);
        }
        return out;
    }
}
