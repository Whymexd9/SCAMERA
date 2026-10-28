package com.particlesdevs.photoncamera.ui.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;

import com.particlesdevs.photoncamera.settings.SettingsLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P385: the user's order ({@link SettingsLayout}) on a settings page as built. The page's containers are the page itself and its
 * groups (PreferenceCategory children); a group whose rows the code builds (the module cards) moves as a whole, its rows never.
 * Rows move between the page's groups, except into or out of the groups a module page hides whole (ModulePageFilter.NOT_MODULE:
 * a module's own value there would vanish from its module page). A group left without rows is hidden while it stays empty.
 */
final class SettingsLayoutTree {
    private SettingsLayoutTree() {}

    /** Groups built by code: in the page's order, their rows untouched. */
    static final Set<String> BUILT = new HashSet<>(java.util.Collections.singletonList(ModuleRows.GROUP));

    /** A page as the app built it (before any stored order) and what the stored order made of it. */
    static final class Snapshot {
        final SettingsLayout.Page model;
        final PreferenceGroup page;
        final Map<String, Preference> items = new HashMap<>();
        final Map<String, PreferenceGroup> containers = new HashMap<>();
        /** The page's own groups (containers, and the built ones) - the editor's group cards. */
        final Set<String> groups = new HashSet<>();
        final Set<String> hiddenEmpty = new HashSet<>();
        /** The groups the stored order took rows from. */
        final Set<String> lost = new HashSet<>();
        String applied = "";

        Snapshot(PreferenceGroup page) {
            this.page = page;
            this.model = new SettingsLayout.Page(page.getKey());
        }
    }

    /** The id of an item: its key, or (no usable key) its place in the app's order. */
    static String id(Preference p, String container, int index) {
        final String key = p.getKey();
        return SettingsLayout.storable(key) ? key : "#" + container + "." + index;
    }

    /** The page's snapshot; null when it has no key (nothing to store it under). */
    @Nullable
    static Snapshot snapshot(@Nullable PreferenceGroup page) {
        if (page == null || !SettingsLayout.storable(page.getKey())) return null;
        final Snapshot s = new Snapshot(page);
        final String pageKey = page.getKey();
        final List<String> top = new ArrayList<>();
        s.model.add(pageKey, top);
        s.containers.put(pageKey, page);
        final LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
        for (int i = 0; i < page.getPreferenceCount(); i++) {
            final Preference p = page.getPreference(i);
            final String id = id(p, pageKey, i);
            top.add(id);
            s.items.put(id, p);
            if (!(p instanceof PreferenceCategory) || !SettingsLayout.storable(p.getKey())) continue;
            s.groups.add(id);
            if (BUILT.contains(id)) continue;
            final PreferenceGroup g = (PreferenceGroup) p;
            final List<String> rows = new ArrayList<>();
            for (int j = 0; j < g.getPreferenceCount(); j++) {
                final Preference r = g.getPreference(j);
                final String rid = id(r, id, j);
                rows.add(rid);
                s.items.put(rid, r);
            }
            groups.put(id, rows);
            s.containers.put(id, g);
            if (!ModulePageFilter.NOT_MODULE.contains(id)) s.model.open.add(id);
        }
        s.model.containers.get(pageKey).clear();
        s.model.containers.get(pageKey).addAll(top);
        for (Map.Entry<String, List<String>> e : groups.entrySet()) s.model.add(e.getKey(), e.getValue());
        return s;
    }

    static String stored(SharedPreferences prefs) {
        try {
            return prefs.getString(SettingsLayout.KEY, "");
        } catch (ClassCastException e) {
            return "";
        }
    }

    /** The page in the {@code stored} order (from whatever order it has now: the order is set, rows go to their group). */
    static void apply(Snapshot s, @Nullable String stored) {
        final LinkedHashMap<String, List<String>> layout = SettingsLayout.apply(s.model, stored);
        for (Map.Entry<String, List<String>> e : layout.entrySet()) {
            final PreferenceGroup group = s.containers.get(e.getKey());
            if (group == null) continue;
            final List<String> ids = e.getValue();
            for (int i = 0; i < ids.size(); i++) {
                final Preference p = s.items.get(ids.get(i));
                // a row the page's code took out meanwhile stays out
                if (p == null || p.getParent() == null) continue;
                if (p.getParent() != group) {
                    // one row at a time: every other row stays in the tree, so the row's dependency is found again
                    p.getParent().removePreference(p);
                    p.setOrder(i);
                    group.addPreference(p);
                } else if (p.getOrder() != i) p.setOrder(i);
            }
        }
        s.lost.clear();
        for (Map.Entry<String, List<String>> e : s.model.containers.entrySet())
            if (!layout.get(e.getKey()).containsAll(e.getValue())) s.lost.add(e.getKey());
        s.applied = stored == null ? "" : stored;
        hideEmpty(s);
    }

    /**
     * A group that gave rows to another one is hidden while none of its rows is shown, and shown again once one is (the page's
     * availability shows and hides rows: called after it too).
     */
    static void hideEmpty(Snapshot s) {
        for (Map.Entry<String, PreferenceGroup> e : s.containers.entrySet()) {
            final PreferenceGroup g = e.getValue();
            if (g == s.page) continue;
            boolean shown = false;
            for (int i = 0; i < g.getPreferenceCount() && !shown; i++) shown = g.getPreference(i).isVisible();
            if (s.lost.contains(e.getKey()) && !shown) {
                if (g.isVisible()) {
                    g.setVisible(false);
                    s.hiddenEmpty.add(e.getKey());
                }
            } else if (s.hiddenEmpty.remove(e.getKey())) g.setVisible(true);
        }
    }

    /**
     * The items the editor does not show: rows hidden on this phone or page, rows without a title (the notes: they stay after the
     * row they follow). A group hidden for being empty is shown (a row can be put back into it).
     */
    static Set<String> hidden(Snapshot s) {
        final Set<String> out = new HashSet<>();
        for (Map.Entry<String, Preference> e : s.items.entrySet()) {
            final Preference p = e.getValue();
            final boolean untitled = p.getTitle() == null || p.getTitle().length() == 0;
            if (p.getParent() == null || !p.isVisible() && !s.hiddenEmpty.contains(e.getKey()) || untitled) out.add(e.getKey());
        }
        return out;
    }
}
