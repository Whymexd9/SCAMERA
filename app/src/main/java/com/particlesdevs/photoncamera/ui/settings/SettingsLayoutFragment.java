package com.particlesdevs.photoncamera.ui.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.SettingsLayout;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * P385: «Расположение» of a settings page - its groups and rows in the user's order. Two views of the page: «Группы» (the page's
 * groups and loose rows, each a card) and «Пункты» (the rows under their group's title; a row also goes to another group of the
 * page). A card is dragged by its grip or after a long press, moves one place with the accessibility actions «Выше» / «Ниже»; every
 * drop is saved at once ({@link SettingsLayout#KEY}: carried by the config file). The header's reset puts this page back in the
 * app's order. The settings pages take the new order when they show again (SettingsFragment.onResume).
 */
public class SettingsLayoutFragment extends Fragment {
    static final String ARG_PAGE = "layout_page", ARG_TITLE = "layout_title", ARG_ORDER = "layout_order", ARG_OPEN = "layout_open",
            ARG_GROUPS = "layout_groups", ARG_IDS = "layout_ids", ARG_TITLES = "layout_titles", ARG_HIDDEN = "layout_hidden",
            STATE_MODE = "layout_mode";
    static final int GROUPS = 0, ROWS = 1;
    /** The icons of the page last opened (only the look: after the process is gone a row shows without one). */
    private static final Map<String, Drawable.ConstantState> ICONS = new HashMap<>();

    private SettingsLayout.Page page;
    private final Map<String, String> titles = new HashMap<>();
    private final Set<String> hidden = new HashSet<>(), groups = new HashSet<>();
    private LinkedHashMap<String, List<String>> order;
    private int mode = GROUPS;
    private final List<Item> items = new ArrayList<>();
    private Adapter adapter;
    private RecyclerView list;
    private TextView tip;
    @Nullable private Segments segments;

    /** The editor of {@code s}, the page {@code title}. */
    static SettingsLayoutFragment of(SettingsLayoutTree.Snapshot s, CharSequence title) {
        final Bundle b = new Bundle();
        b.putString(ARG_PAGE, s.model.key);
        b.putString(ARG_TITLE, title == null ? "" : title.toString());
        b.putString(ARG_ORDER, SettingsLayout.text(s.model.containers));
        b.putStringArrayList(ARG_OPEN, new ArrayList<>(s.model.open));
        b.putStringArrayList(ARG_GROUPS, new ArrayList<>(s.groups));
        final ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>();
        ICONS.clear();
        for (Map.Entry<String, Preference> e : s.items.entrySet()) {
            final Preference p = e.getValue();
            ids.add(e.getKey());
            names.add(p.getTitle() == null ? "" : p.getTitle().toString());
            final Drawable icon = p.getIcon();
            if (icon != null && icon.getConstantState() != null) ICONS.put(e.getKey(), icon.getConstantState());
        }
        b.putStringArrayList(ARG_IDS, ids);
        b.putStringArrayList(ARG_TITLES, names);
        b.putStringArrayList(ARG_HIDDEN, new ArrayList<>(SettingsLayoutTree.hidden(s)));
        final SettingsLayoutFragment f = new SettingsLayoutFragment();
        f.setArguments(b);
        return f;
    }

    /** Whether {@code s} has anything to arrange: two cards on the page, or two rows in a group, or a row and a second group. */
    static boolean editable(@Nullable SettingsLayoutTree.Snapshot s) {
        if (s == null) return false;
        final Set<String> off = SettingsLayoutTree.hidden(s);
        int top = 0, open = 0, openRows = 0;
        for (Map.Entry<String, List<String>> e : s.model.containers.entrySet()) {
            int shown = 0;
            for (String id : e.getValue()) if (!off.contains(id)) shown++;
            if (e.getKey().equals(s.model.key)) top = shown;
            else if (shown >= 2) return true;
            if (s.model.open.contains(e.getKey()) && !off.contains(e.getKey())) {
                open++;
                openRows += shown;
            }
        }
        return top >= 2 || open >= 2 && openRows >= 1;
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        final Bundle b = requireArguments();
        page = new SettingsLayout.Page(b.getString(ARG_PAGE, ""));
        page.containers.putAll(SettingsLayout.parse(b.getString(ARG_ORDER, "")));
        if (!page.containers.containsKey(page.key)) page.containers.put(page.key, new ArrayList<>());
        page.open.addAll(list(b, ARG_OPEN));
        groups.addAll(list(b, ARG_GROUPS));
        hidden.addAll(list(b, ARG_HIDDEN));
        final List<String> ids = list(b, ARG_IDS), names = list(b, ARG_TITLES);
        for (int i = 0; i < ids.size() && i < names.size(); i++) titles.put(ids.get(i), names.get(i));
        order = SettingsLayout.apply(page, stored());
        mode = state != null ? state.getInt(STATE_MODE, GROUPS) : offers(GROUPS) ? GROUPS : ROWS;
        if (!offers(mode)) mode = mode == GROUPS ? ROWS : GROUPS;
    }

    private static List<String> list(Bundle b, String key) {
        final ArrayList<String> l = b.getStringArrayList(key);
        return l == null ? Collections.emptyList() : l;
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_MODE, mode);
    }

    private SharedPreferences prefs() {
        return ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
    }

    private String stored() {
        return SettingsLayoutTree.stored(prefs());
    }

    /** «Группы»: two cards on the page; «Пункты»: a group with a row to show. */
    private boolean offers(int m) {
        if (m == GROUPS) return shown(page.key).size() >= 2;
        for (String g : shown(page.key)) if (container(g)) return true;
        return false;
    }

    private List<String> shown(String container) {
        final List<String> out = new ArrayList<>();
        final List<String> all = order.get(container);
        if (all != null) for (String id : all) if (!hidden.contains(id)) out.add(id);
        return out;
    }

    private boolean container(String id) {
        return !id.equals(page.key) && page.containers.containsKey(id);
    }

    // ───── the page ─────

    @NonNull @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        final Context c = requireContext();
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SettingsStyle.BG);
        root.setPadding(dp(16), dp(12), dp(16), 0);
        final SettingsStyle.Header header = SettingsStyle.header(c, () -> requireActivity().getOnBackPressedDispatcher().onBackPressed(),
                null, this::askReset);
        header.heading.setText(Lang.t(c, "Расположение", "Layout"));
        header.subtitle.setText(subtitle(c));
        header.subtitle.setVisibility(View.VISIBLE);
        header.reset.setContentDescription(Lang.t(c, "Порядок по умолчанию", "Default order"));
        root.addView(header.view, new LinearLayout.LayoutParams(-1, -2));
        if (offers(GROUPS) && offers(ROWS)) {
            segments = new Segments(c, new String[]{Lang.t(c, "Группы", "Groups"), Lang.t(c, "Пункты", "Rows")}, mode, this::setMode);
            final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(40));
            sp.bottomMargin = dp(10);
            root.addView(segments.view, sp);
        }
        tip = SettingsStyle.text(c, "", 12.5f, SettingsStyle.MUTED);
        tip.setTag("settings_layout_tip");
        final LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.leftMargin = dp(4);
        tp.bottomMargin = dp(10);
        root.addView(tip, tp);
        list = new RecyclerView(c);
        list.setTag("settings_layout_list");
        list.setLayoutManager(new LinearLayoutManager(c));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(28));
        list.setVerticalScrollBarEnabled(false);
        list.setItemAnimator(new SettingsMotion.ItemAnimator(c));
        adapter = new Adapter();
        list.setAdapter(adapter);
        helper.attachToRecyclerView(list);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        rebuild(false);
        return root;
    }

    private String subtitle(Context c) {
        final String title = requireArguments().getString(ARG_TITLE, "");
        return "prefscreen".equals(page.key) || title.isEmpty() ? Lang.t(c, "Главная страница настроек", "The main settings page")
                : Lang.t(c, "Страница «" + title + "»", "Page «" + title + "»");
    }

    private void setMode(int m) {
        if (m == mode || !offers(m)) return;
        mode = m;
        rebuild(true);
    }

    private void showTip() {
        final Context c = requireContext();
        if (mode == GROUPS) tip.setText(Lang.t(c, "Перетащите карточку за ⠿, чтобы поставить её выше или ниже. Порядок сохраняется сразу и попадает в конфиг.",
                "Drag a card by ⠿ to move it up or down. The order is saved at once and goes into the config."));
        else tip.setText(Lang.t(c, "Перетащите пункт за ⠿ — внутри группы или в другую группу этой страницы.",
                "Drag a row by ⠿ - within its group or into another group of this page."));
    }

    /** The cards of the current view; {@code animate}: the list glides from the cards it showed (DiffUtil on the cards' ids). */
    private void rebuild(boolean animate) {
        final List<Item> next = new ArrayList<>();
        if (mode == GROUPS) {
            for (String id : shown(page.key)) next.add(new Item(groups.contains(id) ? Item.GROUP : Item.ROW, id, page.key));
        } else {
            for (String g : shown(page.key)) {
                if (!container(g)) continue;
                next.add(new Item(Item.HEADER, g, g));
                final List<String> rows = shown(g);
                for (String r : rows) next.add(new Item(Item.ROW, r, g));
                if (rows.isEmpty()) next.add(new Item(Item.EMPTY, g, g));
            }
        }
        showTip();
        if (!animate || adapter == null) {
            items.clear();
            items.addAll(next);
            if (adapter != null) adapter.notifyDataSetChanged();
            return;
        }
        final List<Item> before = new ArrayList<>(items);
        final DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return before.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) { return before.get(o).same(next.get(n)); }
            @Override public boolean areContentsTheSame(int o, int n) { return before.get(o).equals(next.get(n)); }
        });
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(adapter);
    }

    /** The order the cards now show, saved; the cards again (a group left empty shows its drop place). */
    private void commit() {
        if (mode == GROUPS) {
            final List<String> shown = new ArrayList<>();
            for (Item i : items) shown.add(i.id);
            order.put(page.key, SettingsLayout.arrange(order.get(page.key), shown, hidden));
        } else {
            final LinkedHashMap<String, List<String>> by = new LinkedHashMap<>();
            String group = null;
            for (Item i : items) {
                if (i.kind == Item.HEADER) by.put(group = i.id, new ArrayList<>());
                else if (i.kind == Item.ROW && group != null) by.get(group).add(i.id);
            }
            for (Map.Entry<String, List<String>> e : by.entrySet())
                order.put(e.getKey(), SettingsLayout.arrange(order.get(e.getKey()), e.getValue(), hidden));
        }
        final String before = stored(), after = SettingsLayout.store(before, page, order);
        if (!after.equals(before)) prefs().edit().putString(SettingsLayout.KEY, after).apply();
        // a drop posted just before the page closed is saved all the same
        if (isAdded() && getView() != null) rebuild(true);
    }

    private void askReset() {
        final Context c = getContext();
        if (c == null) return;
        if (SettingsLayout.isDefault(page, stored())) {
            android.widget.Toast.makeText(c, Lang.t(c, "Страница уже в порядке по умолчанию", "This page is in the default order already"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        DialogStyle.builder(c)
                .setTitle(Lang.t(c, "Вернуть порядок по умолчанию?", "Restore the default order?"))
                .setMessage(Lang.t(c, "Группы и пункты этой страницы встанут на свои места. Другие страницы не изменятся.",
                        "The groups and rows of this page go back to their places. Other pages stay as they are."))
                .setPositiveButton(Lang.t(c, "Вернуть", "Restore"), (d, w) -> {
                    prefs().edit().putString(SettingsLayout.KEY, SettingsLayout.reset(stored(), page)).apply();
                    order = SettingsLayout.apply(page, stored());
                    rebuild(true);
                    SettingsMotion.tick(list);
                })
                .setNegativeButton(Lang.t(c, "Отмена", "Cancel"), null)
                .show();
    }

    /** The cards shown, in order ("group:", "row:", "header:", "empty:" and the id; the tests read it). */
    List<String> cards() {
        final String[] kinds = {"group:", "row:", "header:", "empty:"};
        final List<String> out = new ArrayList<>();
        for (Item i : items) out.add(kinds[i.kind] + i.id);
        return out;
    }

    // ───── moving ─────

    /** The group a row at {@code at} of {@code l} belongs to (the title above it); null above the first one. */
    @Nullable
    private static String groupAt(List<Item> l, int at) {
        for (int i = at - 1; i >= 0; i--) if (l.get(i).kind == Item.HEADER) return l.get(i).id;
        return null;
    }

    /** The card at {@code from} put at {@code to}, where it may go. */
    boolean move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size() || to >= items.size()) return false;
        final Item it = items.get(from);
        if (!it.movable()) return false;
        if (mode == ROWS) {
            final List<Item> l = new ArrayList<>(items);
            l.add(to, l.remove(from));
            final String target = groupAt(l, to);
            if (target == null || !page.moves(it.container, target)) return false;
            items.remove(from);
            items.add(to, new Item(Item.ROW, it.id, target));
        } else items.add(to, items.remove(from));
        adapter.notifyItemMoved(from, to);
        return true;
    }

    /** A step up or down (the accessibility actions): the next place the card may go, saved. */
    private boolean step(int at, int dir) {
        for (int to = at + dir; to >= 0 && to < items.size(); to += dir) {
            if (move(at, to)) {
                commit();
                return true;
            }
            if (mode == GROUPS) break;
        }
        return false;
    }

    private final ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
        @Override public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
            final int at = h.getBindingAdapterPosition();
            return at >= 0 && at < items.size() && items.get(at).movable() ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
        }

        @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h, @NonNull RecyclerView.ViewHolder target) {
            final boolean moved = move(h.getBindingAdapterPosition(), target.getBindingAdapterPosition());
            if (moved) h.itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            return moved;
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) { }

        @Override public boolean isLongPressDragEnabled() { return true; }

        @Override public void onSelectedChanged(@Nullable RecyclerView.ViewHolder h, int state) {
            super.onSelectedChanged(h, state);
            if (h != null && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                h.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                lift(h.itemView, true);
            }
        }

        @Override public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
            super.clearView(rv, h);
            lift(h.itemView, false);
            // after the layout pass of the last move (a rebuild inside it would be refused)
            rv.post(SettingsLayoutFragment.this::commit);
        }
    });

    /** The dragged card rises (a little larger, its shadow, the accent outline) and settles back. */
    private void lift(View v, boolean up) {
        final Object card = v.getTag(R.id.settings_layout_card);
        if (card instanceof GradientDrawable)
            ((GradientDrawable) card).setStroke(dp(up ? 1.5f : 1f), up ? SettingsStyle.accent(v.getContext()) : SettingsStyle.LINE);
        if (!SettingsMotion.enabled(v)) {
            v.setScaleX(up ? 1.03f : 1f);
            v.setScaleY(up ? 1.03f : 1f);
            v.setTranslationZ(up ? dp(8) : 0);
            return;
        }
        v.animate().scaleX(up ? 1.03f : 1f).scaleY(up ? 1.03f : 1f).translationZ(up ? dp(8) : 0)
                .setDuration(up ? 160 : 240).setInterpolator(SettingsMotion.EASE_OUT).start();
    }

    // ───── the cards ─────

    static final class Item {
        static final int GROUP = 0, ROW = 1, HEADER = 2, EMPTY = 3;
        final int kind;
        final String id, container;

        Item(int kind, String id, String container) {
            this.kind = kind;
            this.id = id;
            this.container = container;
        }

        boolean movable() {
            return kind == GROUP || kind == ROW;
        }

        /** A group's card and its title in the other view are the same card (the list turns one into the other). */
        boolean same(Item o) {
            final boolean group = kind == GROUP || kind == HEADER, other = o.kind == GROUP || o.kind == HEADER;
            return id.equals(o.id) && (group == other) && (kind == EMPTY) == (o.kind == EMPTY);
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Item)) return false;
            final Item i = (Item) o;
            return kind == i.kind && id.equals(i.id) && container.equals(i.container);
        }

        @Override public int hashCode() {
            return Objects.hash(kind, id, container);
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final TextView title, summary;
        @Nullable final ImageView icon, grip;
        final List<Integer> actions = new ArrayList<>();

        Holder(View v, TextView title, @Nullable TextView summary, @Nullable ImageView icon, @Nullable ImageView grip) {
            super(v);
            this.title = title;
            this.summary = summary;
            this.icon = icon;
            this.grip = grip;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public int getItemCount() { return items.size(); }

        @Override public int getItemViewType(int position) { return items.get(position).kind; }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int kind) {
            final Context c = parent.getContext();
            if (kind == Item.HEADER) {
                final TextView t = SettingsStyle.text(c, "", 11.5f, SettingsStyle.accent(c));
                t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                t.setLetterSpacing(0.09f);
                t.setAllCaps(true);
                t.setPadding(dp(6), dp(18), dp(6), dp(8));
                t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new Holder(t, t, null, null, null);
            }
            if (kind == Item.EMPTY) {
                final TextView t = SettingsStyle.text(c, Lang.t(c, "Перетащите сюда пункт", "Drop a row here"), 13, SettingsStyle.MUTED);
                t.setGravity(Gravity.CENTER);
                t.setMinHeight(dp(52));
                final GradientDrawable d = new GradientDrawable();
                d.setCornerRadius(dp(16));
                d.setStroke(dp(1), SettingsStyle.LINE, dp(6), dp(5));
                t.setBackground(d);
                final RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
                lp.bottomMargin = dp(6);
                t.setLayoutParams(lp);
                return new Holder(t, t, null, null, null);
            }
            final LinearLayout card = new LinearLayout(c);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setMinimumHeight(dp(kind == Item.GROUP ? 64 : 56));
            card.setPadding(dp(14), dp(10), dp(8), dp(10));
            final GradientDrawable bg = SettingsStyle.shape(c, SettingsStyle.CARD, SettingsStyle.LINE, 16);
            card.setBackground(bg);
            card.setTag(R.id.settings_layout_card, bg);
            final ImageView icon = new ImageView(c);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(24), dp(24));
            ip.setMarginEnd(dp(14));
            card.addView(icon, ip);
            final LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = SettingsStyle.text(c, "", kind == Item.GROUP ? 15.5f : 14.5f, SettingsStyle.TEXT);
            if (kind == Item.GROUP) title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
            final TextView summary = SettingsStyle.text(c, "", 12.5f, SettingsStyle.SUMMARY);
            summary.setMaxLines(2);
            summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            final LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(3);
            texts.addView(summary, sp);
            card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            final ImageView grip = new ImageView(c);
            grip.setImageResource(R.drawable.settings_ic_grip);
            grip.setImageTintList(ColorStateList.valueOf(SettingsStyle.MUTED));
            grip.setScaleType(ImageView.ScaleType.CENTER);
            grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(grip, new LinearLayout.LayoutParams(dp(44), dp(44)));
            final RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            card.setLayoutParams(lp);
            final Holder h = new Holder(card, title, summary, icon, grip);
            grip.setOnTouchListener(gripTouch(h));
            return h;
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            final Item it = items.get(position);
            final Context c = h.itemView.getContext();
            for (int id : h.actions) ViewCompat.removeAccessibilityAction(h.itemView, id);
            h.actions.clear();
            if (it.kind == Item.EMPTY) return;
            final String name = title(it.id);
            h.title.setText(name);
            if (!it.movable()) return;
            final String summary = it.kind == Item.GROUP ? groupSummary(c, it.id) : "";
            h.summary.setText(summary);
            h.summary.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
            final Drawable.ConstantState icon = ICONS.get(it.id);
            if (h.icon != null) {
                if (it.kind == Item.GROUP) {
                    h.icon.setImageResource(R.drawable.settings_ic_layers);
                    h.icon.setVisibility(View.VISIBLE);
                } else if (icon != null) {
                    h.icon.setImageDrawable(icon.newDrawable(c.getResources()).mutate());
                    h.icon.setVisibility(View.VISIBLE);
                } else h.icon.setVisibility(View.GONE);
            }
            h.itemView.setContentDescription(name + (summary.isEmpty() ? "" : ", " + summary));
            h.actions.add(ViewCompat.addAccessibilityAction(h.itemView, Lang.t(c, "Выше", "Move up"), (v, args) -> {
                final int at = h.getBindingAdapterPosition();
                return at >= 0 && step(at, -1);
            }));
            h.actions.add(ViewCompat.addAccessibilityAction(h.itemView, Lang.t(c, "Ниже", "Move down"), (v, args) -> {
                final int at = h.getBindingAdapterPosition();
                return at >= 0 && step(at, 1);
            }));
        }
    }

    /** The grip starts the drag at once (no long press). */
    @SuppressLint("ClickableViewAccessibility")
    private View.OnTouchListener gripTouch(Holder h) {
        return (v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                final int at = h.getBindingAdapterPosition();
                if (at >= 0 && at < items.size() && items.get(at).movable()) helper.startDrag(h);
            }
            return false;
        };
    }

    private String title(String id) {
        final String t = titles.get(id);
        return t == null || t.isEmpty() ? id : t;
    }

    /** What a group holds: its first rows by name («Яркость, Контраст, Насыщенность и ещё 5»), or what the code builds there. */
    private String groupSummary(Context c, String id) {
        if (!container(id)) return SettingsLayoutTree.BUILT.contains(id) ? Lang.t(c, "Собирается автоматически", "Built automatically") : "";
        final List<String> rows = shown(id);
        if (rows.isEmpty()) return Lang.t(c, "Пусто", "Empty");
        final List<String> names = new ArrayList<>();
        for (String r : rows.subList(0, Math.min(3, rows.size()))) names.add(title(r));
        final int more = rows.size() - names.size();
        return String.join(", ", names) + (more > 0 ? Lang.t(c, " и ещё " + more, " and " + more + " more") : "");
    }

    private int dp(float v) {
        return SettingsStyle.dp(requireContext(), v);
    }

    /** The two views' switch: a pill that glides under the chosen label (SettingsMotion.glide). */
    private static final class Segments {
        final FrameLayout view;
        final View pill;
        final TextView[] labels;
        int chosen, previous = -1;

        Segments(Context c, String[] names, int chosen, java.util.function.IntConsumer pick) {
            this.chosen = chosen;
            view = new FrameLayout(c);
            view.setPadding(SettingsStyle.dp(c, 3), SettingsStyle.dp(c, 3), SettingsStyle.dp(c, 3), SettingsStyle.dp(c, 3));
            view.setBackground(SettingsStyle.shape(c, SettingsStyle.CARD, SettingsStyle.LINE, 999));
            view.setTag("settings_layout_modes");
            pill = new View(c);
            pill.setBackground(SettingsStyle.shape(c, SettingsStyle.tint(SettingsStyle.accent(c), .22f), 0, 999));
            view.addView(pill, new FrameLayout.LayoutParams(0, -1));
            final LinearLayout row = new LinearLayout(c);
            labels = new TextView[names.length];
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                final TextView t = SettingsStyle.text(c, names[i], 14, SettingsStyle.TEXT);
                t.setGravity(Gravity.CENTER);
                t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                t.setFocusable(true);
                t.setOnClickListener(v -> {
                    if (index == this.chosen) return;
                    previous = this.chosen;
                    this.chosen = index;
                    place(true);
                    SettingsMotion.tick(v);
                    pick.accept(index);
                });
                labels[i] = t;
                row.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
            }
            view.addView(row, new FrameLayout.LayoutParams(-1, -1));
            // placed after the pass that laid the labels out (a size set inside it would wait a frame, with a warning)
            row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) v.post(() -> place(false));
            });
        }

        void place(boolean animate) {
            final TextView t = labels[chosen];
            if (t.getWidth() == 0) return;
            final int accent = SettingsStyle.accent(t.getContext());
            if (animate) SettingsMotion.glide(pill, t.getLeft(), t.getWidth(), k -> paint(accent, (float) k));
            else {
                previous = -1;
                pill.setTranslationX(t.getLeft());
                final ViewGroup.LayoutParams lp = pill.getLayoutParams();
                lp.width = t.getWidth();
                pill.setLayoutParams(lp);
                paint(accent, 1f);
            }
        }

        /** The chosen label turns to the accent with the pill's glide ({@code k} 0..1), the one left turns back. */
        private void paint(int accent, float k) {
            for (int i = 0; i < labels.length; i++)
                labels[i].setTextColor(i == chosen ? SettingsMotion.mix(SettingsStyle.TEXT, accent, k)
                        : i == previous ? SettingsMotion.mix(accent, SettingsStyle.TEXT, k) : SettingsStyle.TEXT);
        }
    }
}
