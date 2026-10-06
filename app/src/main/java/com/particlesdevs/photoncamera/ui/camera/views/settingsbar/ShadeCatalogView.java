package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «Добавить в шторку» (P25 step d): a full-screen page in the settings style over the camera screen, the camera keeps
 * running under it (an Activity would close the camera and drop the shade to HIDDEN). Header «SCAMERA» / «Добавить в
 * шторку», a search over the title, the section, the values and keywords in both languages, the results grouped by
 * settings section. Each row shows the icon, the title, where it lives, the current value and «Добавить» / «В шторке»;
 * a tap pins or unpins it. The counter says «В шторке N из 12». Built from the inflated settings tree (ShadeCatalog), the
 * same source as the settings search, so a new setting appears here by itself.
 */
public class ShadeCatalogView extends LinearLayout {
    /** The shade's pins, read and changed by the rows. */
    public interface Pins {
        boolean pinned(String key);

        void toggle(String key);

        int count();
    }

    private static final int HEADER = 0, ROW = 1, NOTE = 2;

    private final ShadeCatalog catalog;
    private final Pins pins;
    private final Runnable onClose;
    private final EditText search;
    private final TextView count;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private final int accent;
    private String query = "";

    public ShadeCatalogView(Context context, ShadeCatalog catalog, Pins pins, Runnable onClose) {
        super(context);
        this.catalog = catalog;
        this.pins = pins;
        this.onClose = onClose;
        accent = ShadeStyle.accent(context);
        setOrientation(VERTICAL);
        setBackgroundColor(ShadeStyle.BG);
        setClickable(true);
        setFocusable(true);
        setTag("shade_catalog");
        setPadding(dp(16), dp(12), dp(16), 0);

        SettingsStyle.Header header = SettingsStyle.header(context, this::close, null);
        header.heading.setText(R.string.shade_catalog_title);
        header.subtitle.setVisibility(GONE);
        header.back.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        header.back.setContentDescription(context.getString(R.string.shade_catalog_close));
        header.back.setTag("shade_catalog_close");
        addView(header.view, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout field = new LinearLayout(context);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(ShadeStyle.card(context, ShadeStyle.CARD, ShadeStyle.LINE, 18));
        field.setPadding(dp(14), 0, dp(4), 0);
        ImageView glass = new ImageView(context);
        glass.setImageResource(R.drawable.settings_ic_search);
        glass.setColorFilter(ShadeStyle.MUTED);
        glass.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.addView(glass, new LayoutParams(dp(20), dp(20)));
        search = new EditText(context);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setTextColor(ShadeStyle.TEXT);
        search.setHintTextColor(ShadeStyle.MUTED);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setHint(R.string.shade_catalog_search);
        search.setContentDescription(context.getString(R.string.shade_catalog_search));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTag("shade_catalog_search");
        search.setPadding(dp(10), dp(14), dp(4), dp(14));
        field.addView(search, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = new TextView(context);
        clear.setText("×");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        clear.setTextColor(accent);
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription(context.getString(R.string.shade_catalog_clear));
        clear.setOnClickListener(v -> search.setText(""));
        field.addView(clear, new LayoutParams(dp(44), dp(44)));
        LayoutParams fp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.setMargins(dp(4), dp(4), dp(4), 0);
        addView(field, fp);

        count = new TextView(context);
        count.setTextColor(ShadeStyle.MUTED);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        count.setGravity(Gravity.CENTER);
        count.setPadding(0, dp(10), 0, dp(6));
        count.setTag("shade_catalog_count");
        count.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(count, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        // A pin changes the row's button in place, without a cross-fade between two copies of the row.
        androidx.recyclerview.widget.DefaultItemAnimator animator = new androidx.recyclerview.widget.DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        list.setItemAnimator(animator);
        list.setClipToPadding(false);
        list.setPadding(dp(4), 0, dp(4), dp(30));
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        list.setTag("shade_catalog_list");
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                render();
                list.scrollToPosition(0);
            }
        });
        render();
    }

    private int dp(float v) {
        return ShadeStyle.dp(getContext(), v);
    }

    /** Empties the search and shows every setting again (on each open). */
    public void reset() {
        if (!search.getText().toString().isEmpty()) search.setText("");
        else render();
        list.scrollToPosition(0);
    }

    public void close() {
        InputMethodManager ime = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        onClose.run();
    }

    /** Keys of the rows the current query shows, in order (tests). */
    public List<String> shownKeys() {
        List<String> out = new ArrayList<>();
        for (Object[] item : adapter.items) if ((int) item[0] == ROW) out.add(((ShadeCatalog.Entry) item[1]).key);
        return out;
    }

    /** Results grouped by section in catalog order, the counter, the note on free-text settings. */
    private void render() {
        List<ShadeCatalog.Entry> found = catalog.search(query);
        Map<String, List<ShadeCatalog.Entry>> sections = new LinkedHashMap<>();
        for (ShadeCatalog.Entry e : found) {
            String section = e.section(getContext());
            List<ShadeCatalog.Entry> rows = sections.get(section);
            if (rows == null) sections.put(section, rows = new ArrayList<>());
            rows.add(e);
        }
        List<Object[]> items = new ArrayList<>();
        for (Map.Entry<String, List<ShadeCatalog.Entry>> s : sections.entrySet()) {
            items.add(new Object[]{HEADER, s.getKey()});
            for (ShadeCatalog.Entry e : s.getValue()) items.add(new Object[]{ROW, e});
        }
        items.add(new Object[]{NOTE, getContext().getString(found.isEmpty() ? R.string.shade_catalog_nothing : R.string.shade_catalog_note)});
        adapter.items = items;
        adapter.notifyDataSetChanged();
        updateCount(found.size());
    }

    private void updateCount(int found) {
        int pinned = pins.count();
        count.setText(query.trim().isEmpty()
                ? getContext().getString(R.string.shade_catalog_count, pinned, ShadeCatalog.MAX_TILES, catalog.all().size())
                : getContext().getString(R.string.shade_catalog_found, found, pinned, ShadeCatalog.MAX_TILES));
    }

    private void onRowTap(ShadeCatalog.Entry e, int position) {
        pins.toggle(e.key);
        adapter.notifyItemChanged(position);
        updateCount(shownKeys().size());
    }

    // ───────────────────────────────── list

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title, crumb, value, button;

        Holder(View view, ImageView icon, TextView title, TextView crumb, TextView value, TextView button) {
            super(view);
            this.icon = icon;
            this.title = title;
            this.crumb = crumb;
            this.value = value;
            this.button = button;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Object[]> items = new ArrayList<>();

        @Override
        public int getItemViewType(int position) {
            return (int) items.get(position)[0];
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            if (viewType != ROW) {
                TextView t = new TextView(c);
                if (viewType == HEADER) {
                    ShadeStyle.label(t, accent);
                    t.setPadding(dp(6), dp(18), dp(6), dp(10));
                } else {
                    t.setTextColor(ShadeStyle.MUTED);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(dp(6), dp(18), dp(6), 0);
                }
                t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(t) { };
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(14), dp(14));
            row.setMinimumHeight(dp(66));
            row.setBackground(ShadeStyle.pressable(c, ShadeStyle.CARD, ShadeStyle.LINE, 20));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            row.setLayoutParams(lp);
            ImageView icon = new ImageView(c);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams ip = new LayoutParams(dp(26), dp(26));
            ip.setMarginEnd(dp(14));
            row.addView(icon, ip);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            row.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView title = new TextView(c);
            title.setTextColor(ShadeStyle.TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            texts.addView(title);
            TextView crumb = new TextView(c);
            crumb.setTextColor(ShadeStyle.MUTED);
            crumb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            texts.addView(crumb);
            TextView value = new TextView(c);
            value.setTextColor(accent);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            texts.addView(value);
            TextView button = new TextView(c);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            button.setPadding(dp(14), dp(8), dp(14), dp(8));
            button.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams bp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.setMarginStart(dp(10));
            row.addView(button, bp);
            return new Holder(row, icon, title, crumb, value, button);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object[] item = items.get(position);
            int type = (int) item[0];
            if (type != ROW) {
                ((TextView) holder.itemView).setText((String) item[1]);
                return;
            }
            ShadeCatalog.Entry e = (ShadeCatalog.Entry) item[1];
            Holder h = (Holder) holder;
            h.icon.setImageDrawable(catalog.settingIcon(e));
            h.icon.setImageTintList(ColorStateList.valueOf(accent));
            h.title.setText(e.title);
            String crumb = e.crumb();
            h.crumb.setText(crumb);
            h.crumb.setVisibility(crumb.isEmpty() ? GONE : VISIBLE);
            String value = catalog.valueText(e, false);
            h.value.setText(value);
            h.value.setVisibility(value.isEmpty() ? GONE : VISIBLE);
            boolean on = pins.pinned(e.key);
            h.button.setText(on ? R.string.shade_catalog_pinned : R.string.shade_add);
            h.button.setTextColor(on ? ShadeStyle.INK : accent);
            h.button.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            h.button.setBackground(ShadeStyle.card(getContext(), on ? accent : ShadeStyle.tint(accent, .16f), 0, 100));
            h.itemView.setTag("catalog_row_" + e.key);
            h.itemView.setContentDescription(getContext().getString(on ? R.string.shade_catalog_row_pinned : R.string.shade_catalog_row_add,
                    e.title + (crumb.isEmpty() ? "" : ", " + crumb)));
            h.itemView.setOnClickListener(v -> {
                int at = h.getBindingAdapterPosition();
                if (at != RecyclerView.NO_POSITION) onRowTap(e, at);
            });
        }
    }
}
