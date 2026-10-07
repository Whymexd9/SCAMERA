package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rows of the FULL level: a section per curated group (and «Добавлено из настроек» for pinned settings outside them),
 * each setting a card with its inline control and a pin. Lists get a segmented control (a long list shows its value and
 * opens the list sheet), switches a switch, sliders a bar with the value pill. Rows are built once per tile-list or
 * catalog change and rebound in place on a value change.
 */
final class ShadeRows {
    interface Actions {
        /** A control set a value: the stored list value (String), a Boolean or a Float. */
        void pick(ShadeCatalog.Entry entry, Object value);

        void togglePin(String key);

        boolean pinned(String key);

        void openList(ShadeCatalog.Entry entry);

        /** A tap on a dimmed row: say why. */
        void blocked(String reason);
    }

    private final Context context;
    private final ShadeCatalog catalog;
    private final Actions actions;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private int accent;

    ShadeRows(Context context, ShadeCatalog catalog, Actions actions) {
        this.context = context;
        this.catalog = catalog;
        this.actions = actions;
    }

    private int dp(float v) {
        return ShadeStyle.dp(context, v);
    }

    /** Builds the sections into {@code into}: the curated groups, then the pinned settings outside them. */
    void build(LinearLayout into, List<ShadeCatalog.Group> groups, List<String> extra) {
        into.removeAllViews();
        rows.clear();
        accent = ShadeStyle.accent(context);
        for (ShadeCatalog.Group group : groups) section(into, context.getString(group.title), group.keys, false);
        if (!extra.isEmpty()) section(into, context.getString(R.string.shade_group_extra), extra, true);
        bindAll();
    }

    /** Values, availability and pins of every row, in place. */
    void bindAll() {
        for (Row row : rows.values()) row.bind();
    }

    @Nullable
    View rowView(String key) {
        Row row = rows.get(key);
        return row == null ? null : row.card;
    }

    private void section(LinearLayout into, String title, List<String> keys, boolean showPath) {
        TextView label = new TextView(context);
        label.setText(title);
        ShadeStyle.label(label, accent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), dp(22), dp(6), dp(10));
        into.addView(label, lp);
        for (String key : keys) {
            ShadeCatalog.Entry e = catalog.entry(key);
            if (e == null || rows.containsKey(key)) continue;
            Row row = e.kind == ShadeCatalog.TOGGLE ? new ToggleRow(e, showPath)
                    : e.kind == ShadeCatalog.SLIDER ? new SliderRow(e, showPath)
                    : e.isLongList() ? new LongListRow(e, showPath) : new ListRow(e, showPath);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(10);
            into.addView(row.card, rp);
            rows.put(key, row);
        }
    }

    /** A row card. While the setting is unavailable every touch but the pin's is a tap that shows the reason. */
    static final class RowCard extends LinearLayout {
        @Nullable String reason;
        @Nullable View pin;
        @Nullable Actions actions;
        private float downX, downY;
        private final int slop;

        RowCard(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        private boolean onPin(MotionEvent e) {
            if (pin == null) return false;
            int[] at = new int[2], me = new int[2];
            pin.getLocationOnScreen(at);
            getLocationOnScreen(me);
            float x = e.getX() + me[0] - at[0], y = e.getY() + me[1] - at[1];
            return x >= 0 && y >= 0 && x < pin.getWidth() && y < pin.getHeight();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (reason == null) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && onPin(e)) return false;
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (reason == null) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop && actions != null)
                        actions.blocked(reason);
                    return true;
                default:
                    return true;
            }
        }
    }

    // ───────────────────────────────── rows

    private abstract class Row {
        final ShadeCatalog.Entry entry;
        final RowCard card;
        final LinearLayout header;
        final ImageView icon;
        final TextView title;
        final LinearLayout texts;
        final ImageView pin;

        Row(ShadeCatalog.Entry entry, boolean showPath) {
            this.entry = entry;
            card = new RowCard(context);
            card.actions = actions;
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(10), dp(14));
            card.setBackground(ShadeStyle.card(context, ShadeStyle.CARD, ShadeStyle.LINE, 20));
            card.setMinimumHeight(dp(66));
            card.setTag("shade_row_" + entry.key);
            header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            icon = new ImageView(context);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(26), dp(26));
            ip.setMarginEnd(dp(14));
            header.addView(icon, ip);
            texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            header.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            title = new TextView(context);
            title.setText(entry.title);
            title.setTextColor(ShadeStyle.TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setEllipsize(null);
            texts.addView(title);
            String crumb = showPath ? entry.section(context) + (entry.crumb().isEmpty() ? "" : " › " + entry.crumb()) : "";
            if (!crumb.isEmpty()) {
                TextView path = new TextView(context);
                path.setText(crumb);
                path.setTextColor(ShadeStyle.MUTED);
                path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                pp.topMargin = dp(3);
                texts.addView(path, pp);
            }
            pin = new ImageView(context);
            pin.setScaleType(ImageView.ScaleType.CENTER);
            pin.setTag("shade_pin_" + entry.key);
            pin.setBackground(ShadeStyle.pressable(context, 0, 0, 12));
            pin.setOnClickListener(v -> actions.togglePin(entry.key));
            card.pin = pin;
        }

        /** The pin goes last in the header. */
        void addPin() {
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(40), dp(40));
            pp.setMarginStart(dp(4));
            header.addView(pin, pp);
        }

        void bind() {
            String reason = catalog.unavailable(entry);
            card.reason = reason;
            card.setAlpha(reason == null ? 1f : ShadeStyle.DIMMED);
            android.graphics.drawable.Drawable d = catalog.settingIcon(entry);
            icon.setImageDrawable(d);
            icon.setImageTintList(ColorStateList.valueOf(accent));
            boolean pinned = actions.pinned(entry.key);
            pin.setImageResource(pinned ? R.drawable.ic_sheet_pin_on : R.drawable.ic_sheet_pin);
            pin.setImageTintList(ColorStateList.valueOf(pinned ? accent : ShadeStyle.MUTED));
            pin.setContentDescription(context.getString(pinned ? R.string.shade_unpin : R.string.shade_pin, entry.shortTitle));
            pin.setSelected(pinned);
            bindValue();
        }

        abstract void bindValue();
    }

    private final class ListRow extends Row {
        final SegmentedView segments;

        ListRow(ShadeCatalog.Entry entry, boolean showPath) {
            super(entry, showPath);
            addPin();
            segments = new SegmentedView(context);
            CharSequence[] shorts = new CharSequence[entry.values.length];
            for (int i = 0; i < shorts.length; i++) shorts[i] = ShadeCatalog.label(entry, i, true);
            segments.setOptions(shorts, ShadeCatalog.labels(entry));
            segments.setOnPick(i -> actions.pick(entry, entry.values[i].toString()));
            segments.setContentDescription(entry.title);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(12);
            sp.setMarginEnd(dp(6));
            card.addView(segments, sp);
        }

        @Override
        void bindValue() {
            segments.setSelectedIndex(catalog.index(entry));
        }
    }

    private final class LongListRow extends Row {
        final TextView value;

        LongListRow(ShadeCatalog.Entry entry, boolean showPath) {
            super(entry, showPath);
            value = new TextView(context);
            value.setTextColor(accent);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vp.topMargin = dp(3);
            texts.addView(value, vp);
            TextView chevron = new TextView(context);
            chevron.setText("›");
            chevron.setTextColor(ShadeStyle.MUTED);
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            header.addView(chevron, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addPin();
            card.setBackground(ShadeStyle.pressable(context, ShadeStyle.CARD, ShadeStyle.LINE, 20));
            card.setOnClickListener(v -> actions.openList(entry));
        }

        @Override
        void bindValue() {
            String v = catalog.valueText(entry, false);
            value.setText(v);
            card.setContentDescription(entry.title + ": " + v);
        }
    }

    private final class ToggleRow extends Row {
        final SwitchCompat toggle;

        ToggleRow(ShadeCatalog.Entry entry, boolean showPath) {
            super(entry, showPath);
            toggle = new SwitchCompat(context);
            toggle.setClickable(false);
            toggle.setFocusable(false);
            toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int[][] states = {{android.R.attr.state_checked}, {}};
            toggle.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFF9AA0A7}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{accent, 0xFF3A3E45}));
            header.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addPin();
            card.setBackground(ShadeStyle.pressable(context, ShadeStyle.CARD, ShadeStyle.LINE, 20));
            card.setOnClickListener(v -> actions.pick(entry, !catalog.on(entry)));
            card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCheckable(true);
                    info.setChecked(toggle.isChecked());
                }
            });
        }

        @Override
        void bindValue() {
            boolean on = catalog.on(entry);
            if (toggle.isChecked() != on) toggle.setChecked(on);
            card.setContentDescription(entry.title);
        }
    }

    private final class SliderRow extends Row {
        final TextView pill;
        final SeekBar bar;
        boolean tracking;

        SliderRow(ShadeCatalog.Entry entry, boolean showPath) {
            super(entry, showPath);
            pill = valuePill(context, accent);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pp.setMarginStart(dp(8));
            header.addView(pill, pp);
            addPin();
            bar = slider(context, entry, accent);
            bar.setContentDescription(entry.title);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) pill.setText(ShadeCatalog.formatNumber(entry, valueAt(entry, progress)));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    tracking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    tracking = false;
                    actions.pick(entry, valueAt(entry, seekBar.getProgress()));
                }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            bp.topMargin = dp(6);
            card.addView(bar, bp);
        }

        @Override
        void bindValue() {
            if (tracking) return;
            float v = catalog.number(entry);
            pill.setText(ShadeCatalog.formatNumber(entry, v));
            bar.setProgress(progressOf(entry, v));
        }
    }

    // ───────────────────────────────── shared builders (also used by the tile slider card)

    static TextView valuePill(Context context, int accent) {
        TextView pill = new TextView(context);
        pill.setTextColor(accent);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        pill.setFontFeatureSettings("tnum");
        pill.setPadding(ShadeStyle.dp(context, 11), ShadeStyle.dp(context, 3), ShadeStyle.dp(context, 11), ShadeStyle.dp(context, 3));
        pill.setBackground(ShadeStyle.card(context, ShadeStyle.tint(accent, .18f), 0, 100));
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        return pill;
    }

    static SeekBar slider(Context context, ShadeCatalog.Entry entry, int accent) {
        SeekBar bar = new SeekBar(context);
        bar.setMax(steps(entry));
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF4A5058));
        bar.setTag("shade_slider_" + entry.key);
        return bar;
    }

    static int steps(ShadeCatalog.Entry e) {
        return Math.max(1, Math.round((e.max - e.min) / e.step));
    }

    static int progressOf(ShadeCatalog.Entry e, float value) {
        return Math.max(0, Math.min(steps(e), Math.round((value - e.min) / e.step)));
    }

    static float valueAt(ShadeCatalog.Entry e, int progress) {
        float v = e.min + progress * e.step;
        // Snap to the step grid without float noise (0.1 * 3 = 0.30000001).
        int decimals = 0;
        while (decimals < 4 && Math.abs(e.step * Math.pow(10, decimals) - Math.round(e.step * Math.pow(10, decimals))) > 1e-4) decimals++;
        double scale = Math.pow(10, decimals);
        return (float) (Math.round(v * scale) / scale);
    }
}
