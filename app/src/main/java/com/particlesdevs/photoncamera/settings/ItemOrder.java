package com.particlesdevs.photoncamera.settings;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * P279 (owner, 2026-10-19: «дай возможность менять местами луты, можно сделать в основном меню по зажатию пальцем, при этом в
 * меню из видоискателя тоже должно соответственно меняться»; «тоже самое для фильтров»): the user's order of the LUTs and of the
 * lens filters, one stored list each, read by the settings' strip and by the viewfinder's tray alike. The first item («Нет»,
 * «Без фильтра») stays first; an item the stored order does not know (a new .cube) comes after the known ones in its own order,
 * an item no longer there (a deleted file) is dropped.
 */
public final class ItemOrder {
    private ItemOrder() {}

    public static final String LUT_KEY = "pref_film_lut_order", FILTER_KEY = "pref_lens_filter_order";
    /** Items are stored one per line (a LUT file name may hold commas, never a line break). */
    static final String SEPARATOR = "\n";

    /** {@code items} (the first one fixed) in the {@code stored} order. */
    public static List<String> apply(List<String> items, @Nullable String stored) {
        final List<String> out = new ArrayList<>();
        if (items.isEmpty()) return out;
        out.add(items.get(0));
        final LinkedHashSet<String> rest = new LinkedHashSet<>(items.subList(1, items.size()));
        if (stored != null && !stored.isEmpty())
            for (String s : stored.split(SEPARATOR)) if (rest.remove(s)) out.add(s);
        out.addAll(rest);
        return out;
    }

    /** {@code ordered} with the item at {@code from} put at {@code to} (neither may be the fixed first one); the stored text. */
    public static String move(List<String> ordered, int from, int to) {
        final List<String> l = new ArrayList<>(ordered);
        if (from >= 1 && from < l.size()) {
            final String item = l.remove(from);
            l.add(Math.max(1, Math.min(l.size(), to)), item);
        }
        return text(l);
    }

    static String text(List<String> ordered) {
        return ordered.size() <= 1 ? "" : String.join(SEPARATOR, ordered.subList(1, ordered.size()));
    }

    /** The stored order of {@code key} applied to {@code items}. */
    public static List<String> ordered(String key, List<String> items) {
        return apply(items, stored(key));
    }

    @Nullable
    static String stored(String key) {
        try {
            return com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getString(key, "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Moves an item and keeps the order (both strips read it). */
    public static void store(String key, List<String> ordered, int from, int to) {
        com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit()
                .putString(key, move(ordered, from, to)).apply();
    }

    /** «Нет», our looks, the folder's .cube files - in the user's order. */
    public static List<String> luts(android.content.Context c) {
        final List<String> v = new ArrayList<>();
        v.add("");
        Collections.addAll(v, com.particlesdevs.photoncamera.processing.color.FilmLooks.values());
        try {
            Collections.addAll(v, com.particlesdevs.photoncamera.util.ScameraFolder.LUTS.list(c));
        } catch (RuntimeException ignored) {
        }
        return ordered(LUT_KEY, v);
    }

    /** «Без фильтра» and every lens filter - in the user's order. */
    public static List<String> filters() {
        final List<String> v = new ArrayList<>();
        v.add("");
        Collections.addAll(v, com.particlesdevs.photoncamera.processing.color.LensFilters.IDS);
        return ordered(FILTER_KEY, v);
    }
}
