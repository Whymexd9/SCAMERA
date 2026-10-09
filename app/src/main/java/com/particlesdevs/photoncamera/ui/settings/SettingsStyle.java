package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.DialogPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.EditTextPreferenceAccess;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.BackupPreferences;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ResetPreferences;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.RestorePreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.RouteSelectorPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * One look for every settings screen (P6b): the card style of «Камеры и сенсоры». The tokens and builders are shared with
 * {@link ModuleConceptFragment}; {@link #apply} picks each preference's row layout by its class, {@link #bind} adds what
 * depends on the user's accent and on the current state (value line, switch and slider tint, dimmed rows).
 */
public final class SettingsStyle {
    private SettingsStyle() {}

    // The shared tokens live in circularbarlib's UiTokens (one source of truth for the settings, the shade and the manual
    // controls); res/values/settings_style.xml mirrors them for the layouts.
    public static final int BG = UiTokens.BG, CARD = UiTokens.CARD, TEXT = UiTokens.TEXT, MUTED = UiTokens.MUTED, LINE = UiTokens.LINE;
    public static final int FIELD = UiTokens.FIELD, SHEET = UiTokens.SHEET, INK = UiTokens.INK;
    public static final int WARN = 0xFFFFB4A8;
    public static final int OFF_TRACK = 0xFF3A3E45, OFF_THUMB = 0xFF9AA0A7;
    public static final float DIMMED = UiTokens.DIMMED;

    /** «Конфиг»: Сохранить / Восстановить / Сбросить всё share one row of three tiles. */
    static final Set<String> TILES = new HashSet<>(Arrays.asList("pref_backup_preferences_key", "pref_restore_preferences_key", "pref_reset_preferences_key"));
    /** Plain preferences that open a page (handled in SettingsFragment.onPreferenceTreeClick): a chevron, not an action. */
    static final Set<String> NAVIGATION = new HashSet<>(Arrays.asList("pref_dcp_profile_key", "pref_theme_accent_key", "scam_hdr_ark_link"));

    public static int dp(Context c, float v) { return UiTokens.dp(c, v); }

    public static int accent(Context c) { return AccentPalette.color(c); }

    /** The accent at a given alpha over the card colour (chips, pills). */
    public static int tint(int accent, float alpha) {
        return UiTokens.tint(accent, alpha);
    }

    public static GradientDrawable shape(Context c, int color, int stroke, float radius) {
        return UiTokens.shape(c, color, stroke, radius);
    }

    public static TextView text(Context c, CharSequence value, float size, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ───── Header: accent back arrow, letter-spaced SCAMERA eyebrow, centred bold title ─────

    public static final class Header {
        public FrameLayout view;
        public TextView heading, subtitle;
        public ImageView back, search;
    }

    /**
     * The header of the module screen. view = FrameLayout {titles column (eyebrow, heading, subtitle), back[, search]}.
     * {@code onSearch} null: no search button.
     */
    public static Header header(Context c, Runnable onBack, Runnable onSearch) {
        Header h = new Header();
        int accent = accent(c);
        h.view = new FrameLayout(c);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(c, 42), 0, dp(c, 42), dp(c, 16));
        TextView brand = text(c, "SCAMERA", 10, MUTED);
        brand.setLetterSpacing(.16f);
        brand.setGravity(Gravity.CENTER);
        titles.addView(brand);
        h.heading = text(c, "", 20, TEXT);
        h.heading.setTypeface(null, android.graphics.Typeface.BOLD);
        h.heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 8);
        titles.addView(h.heading, lp);
        h.subtitle = text(c, "", 13, MUTED);
        h.subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(c, 6);
        titles.addView(h.subtitle, sp);
        h.view.addView(titles, new FrameLayout.LayoutParams(-1, -2));
        h.back = new ImageView(c);
        h.back.setImageResource(R.drawable.settings_ic_back);
        h.back.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        h.back.setPadding(dp(c, 9), dp(c, 9), dp(c, 9), dp(c, 9));
        h.back.setContentDescription(Lang.t(c,"Назад","Back"));
        h.back.setFocusable(true);
        h.back.setOnClickListener(v -> onBack.run());
        h.view.addView(h.back, new FrameLayout.LayoutParams(dp(c, 40), dp(c, 44), Gravity.START | Gravity.TOP));
        if (onSearch != null) {
            h.search = new ImageView(c);
            h.search.setImageResource(R.drawable.settings_ic_search);
            h.search.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
            h.search.setPadding(dp(c, 9), dp(c, 9), dp(c, 9), dp(c, 9));
            h.search.setContentDescription(Lang.t(c,"Поиск настройки","Search settings"));
            h.search.setTag("settings_search");
            h.search.setFocusable(true);
            h.search.setOnClickListener(v -> onSearch.run());
            h.view.addView(h.search, new FrameLayout.LayoutParams(dp(c, 40), dp(c, 44), Gravity.END | Gravity.TOP));
        }
        return h;
    }

    /** The module screen's chip: «Настраивается: …», «Активна: …». */
    public static TextView chip(Context c, CharSequence value) {
        int accent = accent(c);
        TextView t = text(c, value, 13, accent);
        t.setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9));
        t.setBackground(shape(c, tint(accent, .16f), 0, 20));
        t.setTag("settings_chip");
        return t;
    }

    // ───── Tree: row layout by preference class ─────

    public static void apply(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (p instanceof PreferenceCategory) {
                p.setLayoutResource(R.layout.preference_category_layout);
                apply((PreferenceGroup) p);
                continue;
            }
            p.setSingleLineTitle(false);
            if (p instanceof RouteSelectorPreference) continue;
            if (p.getKey() != null && TILES.contains(p.getKey())) {
                p.setLayoutResource(R.layout.preference_tile);
                continue;
            }
            if (p instanceof PreferenceGroup) {
                p.setLayoutResource(R.layout.preference_card);
                p.setWidgetLayoutResource(R.layout.settings_widget_chevron);
                apply((PreferenceGroup) p);
                defaultIcon(p, R.drawable.settings_ic_gear);
                continue;
            }
            if (p instanceof UniversalSeekBarPreference || p instanceof TunableSeekBarPreference) {
                p.setLayoutResource(R.layout.preference_seekbar);
                defaultIcon(p, R.drawable.settings_ic_sliders);
            } else if (p instanceof TwoStatePreference) {
                p.setLayoutResource(R.layout.preference_card);
                defaultIcon(p, R.drawable.settings_ic_toggle);
            } else if (p instanceof ListPreference) {
                p.setLayoutResource(R.layout.preference_card);
                p.setWidgetLayoutResource(R.layout.settings_widget_chevron);
                defaultIcon(p, R.drawable.settings_ic_list);
            } else if (p instanceof EditTextPreference) {
                p.setLayoutResource(R.layout.preference_card);
                p.setWidgetLayoutResource(R.layout.settings_widget_chevron);
                defaultIcon(p, R.drawable.settings_ic_edit);
            } else if (p instanceof DialogPreference || p instanceof TunableKeyPreference) {
                p.setLayoutResource(R.layout.preference_card);
            } else if (p.getClass() == Preference.class && !p.isSelectable() && p.getIcon() == null) {
                p.setLayoutResource(R.layout.preference_info);
            } else {
                p.setLayoutResource(R.layout.preference_card);
                if (p.getFragment() != null || p.getIntent() != null || (p.getKey() != null && NAVIGATION.contains(p.getKey())))
                    p.setWidgetLayoutResource(R.layout.settings_widget_chevron);
                defaultIcon(p, p.isSelectable() ? R.drawable.settings_ic_gear : R.drawable.settings_ic_info);
            }
            p.setIconSpaceReserved(true);
        }
    }

    private static void defaultIcon(Preference p, int icon) {
        if (p.getIcon() == null) p.setIcon(icon);
    }

    /** A clickable plain row that is an action (import, probe, export): the title in the accent, as in the concept. */
    private static boolean action(Preference p) {
        return p.getClass() == Preference.class && p.isSelectable() && p.getWidgetLayoutResource() == 0
                && p.getFragment() == null && p.getIntent() == null;
    }

    // ───── Bind: accent, value line, state ─────

    public static void bind(PreferenceViewHolder holder, Preference p) {
        View row = holder.itemView;
        Context c = row.getContext();
        int accent = accent(c);
        float alpha = p.isEnabled() ? 1f : DIMMED;
        String key = p.getKey();
        if (key != null && !inRoute(key)) alpha = DIMMED; // the root row of the route that is not selected still opens
        row.setAlpha(alpha);
        if (p instanceof PreferenceCategory) {
            TextView title = (TextView) holder.findViewById(android.R.id.title);
            if (title != null) title.setTextColor(accent);
            return;
        }
        ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);
        boolean warn = "pref_reset_preferences_key".equals(key);
        if (icon != null && !(p instanceof TunableKeyPreference)) icon.setColorFilter(warn ? WARN : accent, PorterDuff.Mode.SRC_IN);
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (title != null && warn) title.setTextColor(WARN);
        else if (title != null && action(p)) title.setTextColor(accent);
        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        TextView value = (TextView) holder.findViewById(R.id.settings_value);
        if (value != null) {
            CharSequence shown = valueOf(p);
            value.setVisibility(TextUtils.isEmpty(shown) ? View.GONE : View.VISIBLE);
            value.setText(shown);
            value.setTextColor(accent);
            if (summary != null && !TextUtils.isEmpty(shown) && summary.getText() != null) {
                // the value has its own line: do not repeat it at the start of the summary ("%s. …" summaries)
                String s = summary.getText().toString(), v = shown.toString();
                if (s.equals(v)) summary.setVisibility(View.GONE);
                else if (s.startsWith(v + ". ")) summary.setText(s.substring(v.length() + 2));
            }
        }
        View widget = holder.findViewById(androidx.preference.R.id.switchWidget);
        if (widget instanceof SwitchCompat) {
            SwitchCompat sw = (SwitchCompat) widget;
            int[][] states = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, OFF_THUMB}));
            sw.setTrackTintList(new ColorStateList(states, new int[]{accent, OFF_TRACK}));
        }
        View bar = holder.findViewById(R.id.seekbar);
        if (bar instanceof SeekBar) {
            SeekBar s = (SeekBar) bar;
            s.setProgressTintList(ColorStateList.valueOf(accent));
            s.setThumbTintList(ColorStateList.valueOf(accent));
            s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF4A5058));
            TextView pill = (TextView) holder.findViewById(R.id.seekbar_value);
            if (pill != null) {
                pill.setBackgroundTintList(ColorStateList.valueOf(tint(accent, .18f)));
                if (!(p instanceof TunableSeekBarPreference)) pill.setTextColor(accent); // tunables keep their green «changed» mark
            }
        }
    }

    /** False for the root row of the merge route that is not selected. */
    static boolean inRoute(String key) {
        String route;
        try { route = PreferenceKeys.mergeRoute(); } catch (RuntimeException e) { return true; }
        if ("scam_hybrid_screen".equals(key)) return "hybrid".equals(route);
        if ("scam_hdr_screen".equals(key)) return !"hybrid".equals(route);
        return true;
    }

    static CharSequence valueOf(Preference p) {
        if (p instanceof RestorePreference || p instanceof RouteSelectorPreference) return null;
        if (p instanceof ListPreference) return ((ListPreference) p).getEntry();
        if (p instanceof EditTextPreference && !(p instanceof BackupPreferences)) {
            String t = ((EditTextPreference) p).getText();
            return TextUtils.isEmpty(t) ? "—" : t;
        }
        return null;
    }

    // ───── Pickers: bottom sheets ─────

    /** True when the sheet replaced the stock dialog. Backup / Restore / Reset keep their own dialogs. */
    public static boolean showDialog(Context c, Preference p) {
        if (p instanceof RestorePreference || p instanceof BackupPreferences || p instanceof ResetPreferences) return false;
        if (p instanceof ListPreference) { listSheet(c, (ListPreference) p); return true; }
        if (p instanceof EditTextPreference) { editSheet(c, (EditTextPreference) p); return true; }
        return false;
    }

    private static LinearLayout sheetBody(Context c, CharSequence title) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET);
        float r = dp(c, 26);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        body.setBackground(bg);
        body.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 24));
        View grab = new View(c);
        grab.setBackground(shape(c, OFF_TRACK, 0, 2));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(c, 14);
        body.addView(grab, gp);
        TextView t = text(c, title, 18, TEXT);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 12));
        body.addView(t);
        return body;
    }

    private static BottomSheetDialog sheet(Context c, View body) {
        BottomSheetDialog d = new BottomSheetDialog(c);
        d.setContentView(body);
        d.setOnShowListener(x -> {
            View host = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (host != null) host.setBackgroundColor(0);
            d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            d.getBehavior().setSkipCollapsed(true);
        });
        return d;
    }

    /** A radio dot: an accent ring with a dot when selected, a muted ring otherwise. */
    static View radio(Context c, boolean on, int accent) {
        return new View(c) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(c, 10);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(c, 2));
                paint.setColor(on ? accent : MUTED);
                canvas.drawCircle(cx, cy, r, paint);
                if (on) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, dp(c, 5), paint); }
            }
        };
    }

    static BottomSheetDialog listSheet(Context c, ListPreference p) {
        CharSequence[] entries = p.getEntries(), values = p.getEntryValues();
        if (entries == null || values == null) entries = values = new CharSequence[0];
        final CharSequence[] tags = values;
        return optionSheet(c, p.getDialogTitle() != null ? p.getDialogTitle() : p.getTitle(), entries, tags,
                p.findIndexOfValue(p.getValue()), accent(c), i -> {
                    String v = tags[i].toString();
                    if (!v.equals(p.getValue()) && p.callChangeListener(v)) p.setValue(v);
                });
    }

    /**
     * The list picker: a bottom sheet with a radio row per option (tag "option_" + the option's tag), the selected one
     * tinted. A tap reports the option's index and closes the sheet. Also the list sheet of the camera's quick-settings
     * shade (P25), there with the camera accent.
     */
    public static BottomSheetDialog optionSheet(Context c, CharSequence title, CharSequence[] labels, CharSequence[] tags,
                                                int selected, int accent, java.util.function.IntConsumer onPick) {
        return optionSheet(c, title, labels, tags, selected, accent, null, onPick);
    }

    /**
     * The list picker with an accent icon per option (between the radio and the name), e.g. the format choice of the top
     * bar and the shade. {@code icons} null or shorter than the list: no icon for the rest.
     */
    public static BottomSheetDialog optionSheet(Context c, CharSequence title, CharSequence[] labels, CharSequence[] tags,
                                                int selected, int accent, int[] icons, java.util.function.IntConsumer onPick) {
        LinearLayout body = sheetBody(c, title);
        LinearLayout options = new LinearLayout(c);
        options.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(options);
        int max = (int) (c.getResources().getDisplayMetrics().heightPixels * .6f);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        BottomSheetDialog d = sheet(c, body);
        for (int i = 0; labels != null && i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 6), dp(c, 13), dp(c, 6), dp(c, 13));
            row.setMinimumHeight(dp(c, 52));
            row.setBackground(shape(c, i == selected ? tint(accent, .10f) : 0, 0, 14));
            row.addView(radio(c, i == selected, accent), new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
            if (icons != null && i < icons.length && icons[i] != 0) {
                ImageView icon = new ImageView(c);
                icon.setImageResource(icons[i]);
                icon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
                ip.leftMargin = dp(c, 14);
                row.addView(icon, ip);
            }
            TextView label = text(c, labels[i], 16, TEXT);
            label.setPadding(dp(c, 14), 0, 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            row.setTag("option_" + (tags != null && i < tags.length ? tags[i] : String.valueOf(i)));
            row.setOnClickListener(x -> {
                onPick.accept(index);
                d.dismiss();
            });
            options.addView(row);
        }
        scroll.post(() -> { if (scroll.getHeight() > max) scroll.getLayoutParams().height = max; scroll.requestLayout(); });
        d.show();
        return d;
    }

    static BottomSheetDialog editSheet(Context c, EditTextPreference p) {
        int accent = accent(c);
        LinearLayout body = sheetBody(c, p.getDialogTitle() != null ? p.getDialogTitle() : p.getTitle());
        if (!TextUtils.isEmpty(p.getDialogMessage())) {
            TextView m = text(c, p.getDialogMessage(), 14, MUTED);
            m.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 10));
            body.addView(m);
        }
        EditText field = new EditText(c);
        field.setSingleLine(true);
        field.setTextSize(17);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setBackground(shape(c, FIELD, LINE, 14));
        field.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        field.setText(p.getText());
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTag("sheet_field");
        EditTextPreference.OnBindEditTextListener setup = EditTextPreferenceAccess.listener(p);
        if (setup != null) setup.onBindEditText(field); // the same input type / filters as the stock dialog
        field.setSelection(field.getText().length());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.bottomMargin = dp(c, 14);
        body.addView(field, fp);
        TextView save = text(c, Lang.t(c,"Сохранить","Save"), 16, INK);
        save.setTypeface(null, android.graphics.Typeface.BOLD);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, dp(c, 15), 0, dp(c, 15));
        save.setBackground(shape(c, accent, 0, 16));
        save.setTag("sheet_save");
        body.addView(save, new LinearLayout.LayoutParams(-1, -2));
        BottomSheetDialog d = sheet(c, body);
        Runnable commit = () -> {
            String v = field.getText().toString();
            if (p.callChangeListener(v)) { p.setText(v); d.dismiss(); }
        };
        save.setOnClickListener(x -> commit.run());
        field.setOnEditorActionListener((v, action, e) -> { if (action == EditorInfo.IME_ACTION_DONE) { commit.run(); return true; } return false; });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        field.requestFocus();
        return d;
    }

    /** Whether the page holds settings stored per module (then the page shows the module chip). */
    static boolean hasModuleSettings(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (p instanceof PreferenceGroup) { if (hasModuleSettings((PreferenceGroup) p)) return true; }
            else if (p.getKey() != null && com.particlesdevs.photoncamera.settings.ModuleProfiles.isLocal(p.getKey())) return true;
        }
        return false;
    }
}
