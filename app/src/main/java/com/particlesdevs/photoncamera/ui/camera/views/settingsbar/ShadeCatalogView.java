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
 * «Добавить в шторку» (P25 step d; SIMPLE_UI §2 restyle after the concept's .catalog): a full-screen page over the camera
 * screen, the camera keeps running under it (an Activity would close the camera and drop the shade to HIDDEN). Header: a
 * 44dp back disc, the title and the counter «В шторке N · всего настроек M»; a search pill over the title, the section, the
 * values and keywords in both languages; the results grouped by settings section, each group one card of rows divided by
 * lines. Each row shows an icon tile, the title, where it lives, the restart pill where a change restarts the camera, and
 * «Добавить» (an accent outline) / «✓ В шторке» (muted); a tap pins or unpins it. Built from the inflated settings tree
 * (ShadeCatalog), the same source as the settings search, so a new setting appears here by itself.
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
        setPadding(dp(10), dp(14), dp(10), 0);

        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(8));
        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ui_ic_back);
        back.setImageTintList(ColorStateList.valueOf(ShadeStyle.TEXT));
        back.setScaleType(ImageView.ScaleType.CENTER);
        android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
        disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        disc.setColor(ShadeStyle.CARD);
        back.setBackground(disc);
        back.setContentDescription(context.getString(R.string.shade_catalog_close));
        back.setTag("shade_catalog_close");
        back.setOnClickListener(v -> close());
        LayoutParams bp = new LayoutParams(dp(44), dp(44));
        bp.setMarginEnd(dp(6));
        head.addView(back, bp);
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(VERTICAL);
        TextView heading = new TextView(context);
        heading.setText(R.string.shade_catalog_title);
        heading.setTextColor(ShadeStyle.TEXT);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(heading);
        count = new TextView(context);
        count.setTextColor(ShadeStyle.MUTED);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        count.setTag("shade_catalog_count");
        count.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        titles.addView(count);
        head.addView(titles, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout field = new LinearLayout(context);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(ShadeStyle.card(context, ShadeStyle.CARD, ShadeStyle.LINE, 100));   // the concept's search pill
        field.setMinimumHeight(dp(48));
        field.setPadding(dp(14), 0, dp(4), 0);
        ImageView glass = new ImageView(context);
        glass.setImageResource(R.drawable.ui_ic_search);
        glass.setColorFilter(ShadeStyle.MUTED);
        glass.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.addView(glass, new LayoutParams(dp(20), dp(20)));
        search = new EditText(context);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setTextColor(ShadeStyle.TEXT);
        search.setHintTextColor(ShadeStyle.MUTED);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setHint(R.string.shade_catalog_search);
        search.setContentDescription(context.getString(R.string.shade_catalog_search));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTag("shade_catalog_search");
        search.setPadding(dp(10), dp(12), dp(4), dp(12));
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
        fp.setMargins(dp(4), dp(4), dp(4), dp(8));
        addView(field, fp);

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
            List<ShadeCatalog.Entry> rows = s.getValue();
            for (int i = 0; i < rows.size(); i++) items.add(new Object[]{ROW, rows.get(i), i == 0, i == rows.size() - 1});
        }
        items.add(new Object[]{NOTE, getContext().getString(found.isEmpty() ? R.string.shade_catalog_nothing : R.string.shade_catalog_note)});
        adapter.items = items;
        adapter.notifyDataSetChanged();
        updateCount(found.size());
    }

    private void updateCount(int found) {
        int pinned = pins.count();
        count.setText(query.trim().isEmpty()
                ? getContext().getString(R.string.shade_catalog_count, pinned, catalog.all().size())
                : getContext().getString(R.string.shade_catalog_found, found, pinned));
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
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setMinimumHeight(dp(58));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            row.setLayoutParams(lp);
            // the concept's .ri: a 36dp tile in the second card colour with the accent icon (20dp)
            ImageView icon = new ImageView(c);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(dp(8), dp(8), dp(8), dp(8));
            icon.setBackground(ShadeStyle.card(c, 0xFF232A2E, 0, 12));
            LayoutParams ip = new LayoutParams(dp(36), dp(36));
            ip.setMarginEnd(dp(12));
            row.addView(icon, ip);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            row.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView title = new TextView(c);
            title.setTextColor(ShadeStyle.TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            texts.addView(title);
            TextView crumb = new TextView(c);
            crumb.setTextColor(ShadeStyle.MUTED);
            crumb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            texts.addView(crumb);
            // the restart pill (the concept's .restart) where a change restarts the camera
            TextView value = new TextView(c);
            value.setText(com.particlesdevs.photoncamera.util.Lang.t(c, "перезапуск камеры", "camera restart"));
            value.setTextColor(accent);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            value.setPadding(dp(7), dp(1), dp(7), dp(1));
            value.setBackground(ShadeStyle.card(c, (accent & 0x00FFFFFF) | 0x1F000000, 0, 100));
            LayoutParams vp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vp.topMargin = dp(3);
            texts.addView(value, vp);
            // «Добавить» (an accent outline) / «✓ В шторке» (muted)
            TextView button = new TextView(c);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(dp(34));
            button.setPadding(dp(10), dp(6), dp(10), dp(6));
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
            boolean first = item.length > 2 && (boolean) item[2], last = item.length > 3 && (boolean) item[3];
            Holder h = (Holder) holder;
            // one card per group: the first row's top corners, the last row's bottom corners, a line between rows
            float r = dp(20);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(ShadeStyle.CARD);
            bg.setCornerRadii(new float[]{first ? r : 0, first ? r : 0, first ? r : 0, first ? r : 0, last ? r : 0, last ? r : 0, last ? r : 0, last ? r : 0});
            android.graphics.drawable.Drawable divided = bg;
            if (!first) {
                android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
                line.setColor(ShadeStyle.LINE);
                android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bg, line});
                layers.setLayerHeight(1, Math.max(1, dp(1)));
                layers.setLayerGravity(1, Gravity.TOP);
                divided = layers;
            }
            h.itemView.setBackground(divided);
            ((RecyclerView.LayoutParams) h.itemView.getLayoutParams()).bottomMargin = last ? dp(6) : 0;
            h.icon.setImageDrawable(catalog.settingIcon(e));
            h.icon.setImageTintList(ColorStateList.valueOf(accent));
            h.title.setText(e.title);
            String crumb = e.crumb();
            h.crumb.setText(crumb);
            h.crumb.setVisibility(crumb.isEmpty() ? GONE : VISIBLE);
            h.value.setVisibility(e.sessionTime ? VISIBLE : GONE);
            boolean on = pins.pinned(e.key);
            h.button.setText(on ? "✓ " + getContext().getString(R.string.shade_catalog_pinned) : getContext().getString(R.string.shade_add));
            h.button.setTextColor(on ? ShadeStyle.MUTED : accent);
            h.button.setBackground(ShadeStyle.card(getContext(), 0, on ? ShadeStyle.LINE : accent, 100));
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
