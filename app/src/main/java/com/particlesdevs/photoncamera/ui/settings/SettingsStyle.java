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
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.widget.NestedScrollView;
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
    /** P139: a row's description (dimmer than MUTED); titles take UiTokens.title (medium weight). */
    public static final int SUMMARY = UiTokens.SUMMARY;

    /** Rows drawn as tiles three to a line (SIMPLE_UI §4: the profile actions are rows now, as the concept; none left). */
    static final Set<String> TILES = new HashSet<>();
    /** Plain preferences that open a page (handled in SettingsFragment.onPreferenceTreeClick): a chevron, not an action. */
    static final Set<String> NAVIGATION = new HashSet<>(Arrays.asList("pref_dcp_profile_key", "pref_theme_accent_key",
            "pref_file_name_pattern", "photo_style_lut"));

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

    // ───── Header (SIMPLE_UI §4, the concept's .shead): a 44dp back disc, the title on the left (20sp, 600), the search disc ─────

    public static final class Header {
        public FrameLayout view;
        public TextView heading, subtitle;
        public ImageView back, search;
    }

    /**
     * The header of a settings page. view = FrameLayout {row (back disc, titles column (heading, subtitle), search disc)}.
     * {@code onSearch} null: no search button.
     */
    public static Header header(Context c, Runnable onBack, Runnable onSearch) {
        Header h = new Header();
        h.view = new FrameLayout(c);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(c, 10));
        h.back = disc(c, R.drawable.ui_ic_back);
        h.back.setContentDescription(Lang.t(c,"Назад","Back"));
        h.back.setFocusable(true);
        h.back.setOnClickListener(v -> onBack.run());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44));
        bp.setMarginEnd(dp(c, 6));
        row.addView(h.back, bp);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        h.heading = text(c, "", 20, TEXT);
        h.heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        h.heading.setMaxLines(2);
        titles.addView(h.heading, new LinearLayout.LayoutParams(-1, -2));
        h.subtitle = text(c, "", 12.5f, MUTED);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(c, 2);
        titles.addView(h.subtitle, sp);
        row.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        if (onSearch != null) {
            h.search = disc(c, R.drawable.ui_ic_search);
            h.search.setContentDescription(Lang.t(c,"Поиск настройки","Search settings"));
            h.search.setTag("settings_search");
            h.search.setFocusable(true);
            h.search.setOnClickListener(v -> onSearch.run());
            LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44));
            qp.setMarginStart(dp(c, 6));
            row.addView(h.search, qp);
        }
        h.view.addView(row, new FrameLayout.LayoutParams(-1, -2));
        return h;
    }

    /**
     * SIMPLE_UI §4: the concept's .search under the root's header - a 48dp card pill with a line border, the search icon and
     * «Найти настройку» in the muted colour; a tap opens the search page.
     */
    public static LinearLayout searchPill(Context c, Runnable onSearch) {
        LinearLayout pill = new LinearLayout(c);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setMinimumHeight(dp(c, 48));
        pill.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        pill.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), shape(c, CARD, LINE, 999), null));
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ui_ic_search);
        icon.setImageTintList(ColorStateList.valueOf(MUTED));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22));
        ip.setMarginEnd(dp(c, 10));
        pill.addView(icon, ip);
        pill.addView(text(c, Lang.t(c, "Найти настройку", "Find a setting"), 15, MUTED), new LinearLayout.LayoutParams(0, -2, 1f));
        pill.setContentDescription(Lang.t(c, "Поиск настройки", "Search settings"));
        pill.setTag("settings_search");
        pill.setFocusable(true);
        pill.setOnClickListener(v -> onSearch.run());
        return pill;
    }

    /** The concept's .back: a 44dp card disc with a line icon in the text colour. */
    static ImageView disc(Context c, int icon) {
        ImageView v = new ImageView(c);
        v.setImageResource(icon);
        v.setImageTintList(ColorStateList.valueOf(TEXT));
        v.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(CARD);
        v.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), d, null));
        return v;
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
            // P144: the own tone curve draws its own card (graph and shortcuts); P162: so does the crop picture
            if (p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference
                    || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.RawCropPreference
                    || page(p)) {
                p.setIconSpaceReserved(false);
                continue;
            }
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
            if (com.particlesdevs.photoncamera.processing.color.FilmLut.STRENGTH_KEY.equals(p.getKey())) {
                p.setLayoutResource(R.layout.preference_seekbar_inline);   // SIMPLE_UI §4c: «Сила» on the LUT page
            } else if (p instanceof UniversalSeekBarPreference || p instanceof TunableSeekBarPreference) {
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

    /**
     * SIMPLE_UI §4: rows that draw their own card or are no row (the tone curve, the crop picture, the «Конфиг» tiles, the
     * info notes): they stand outside the group cards of SettingsCardDecoration.
     */
    public static boolean standalone(@androidx.annotation.Nullable Preference p) {
        if (p == null) return true;
        if (p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.RawCropPreference) return true;
        if (p.getKey() != null && TILES.contains(p.getKey())) return true;
        if (page(p) || p.getLayoutResource() == R.layout.preference_seekbar_inline) return true;
        return p.getLayoutResource() == R.layout.preference_info;
    }

    /** SIMPLE_UI §4c: the parts of the «Плёночный LUT» page draw themselves (preview, strip, «Что меняется»). */
    static boolean page(Preference p) {
        return p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmLutPreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmSimPreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.LutChangesPreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.LensFilterPreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.PhotoStylePreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.ShadeTransparencyPreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.ProfilesPreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.FormatPickerPreference
                || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.SizePickerPreference;
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

    /** P248: the right-hand value's share of a row's text width at most (the title and the description keep the rest). */
    static final float VALUE_SHARE = 0.38f;

    /**
     * The widest the value on the right may be, in pixels: its share of what a settings row leaves for text on this screen (the
     * screen less the card's margins, the row's padding, the icon tile and the chevron).
     */
    public static int valueShare(Context c) {
        float density = c.getResources().getDisplayMetrics().density;
        float text = c.getResources().getDisplayMetrics().widthPixels - (16 * 2 + 14 * 2 + 36 + 12 + 24) * density;
        return Math.max(Math.round(48 * density), Math.round(VALUE_SHARE * text));
    }

    /** A value whose one line is wider than {@code share} pixels goes under the title (on the right it squeezed the text). */
    static boolean valueBelow(float valueWidth, int share) {
        return valueWidth > share;
    }

    public static void bind(PreferenceViewHolder holder, Preference p) {
        View row = holder.itemView;
        Context c = row.getContext();
        int accent = accent(c);
        float alpha = p.isEnabled() ? 1f : DIMMED;
        String key = p.getKey();
        row.setAlpha(alpha);
        if (p instanceof PreferenceCategory) {
            // a category is never "enabled" (PreferenceCategory.isEnabled is false): its label is not a dimmed row
            row.setAlpha(1f);
            TextView title = (TextView) holder.findViewById(android.R.id.title);
            if (title != null) title.setTextColor(accent);
            return;
        }
        ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);
        boolean warn = "pref_reset_preferences_key".equals(key) || "pref_reset_sensor_config_settings".equals(key);
        // P136: the row icons are the multi-tone glyphs of tools/make_ui_icons.py (light body, accent details from the theme):
        // drawn as they are; only the reset rows tint theirs in the warning colour
        if (icon != null && !(p instanceof TunableKeyPreference)) {
            if (warn) icon.setColorFilter(WARN, PorterDuff.Mode.SRC_IN); else icon.clearColorFilter();
        }
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (title != null && warn) title.setTextColor(WARN);
        else if (title != null && action(p)) title.setTextColor(accent);
        // P214g: «↻» - the camera session reads it when it is built (SessionKeys: the module's session type, OIS, vendor keys,
        // 16:9, live RAW ...): a change takes a new session, the camera restarts with it
        // SIMPLE_UI §4: as the concept, a row with its own restart pill says it in words («Камера перезапустится») under the
        // description; the other rows keep the mark on the title
        boolean restarts = p instanceof TunableKeyPreference || com.particlesdevs.photoncamera.settings.SessionKeys.needsNewSession(key);
        TextView restart = (TextView) holder.findViewById(R.id.settings_restart);
        if (restart != null) {
            restart.setVisibility(restarts ? View.VISIBLE : View.GONE);
            if (restarts) {
                restart.setText(Lang.t(c, "Камера перезапустится", "The camera restarts"));
                restart.setTextColor(accent);
                restart.setBackground(shape(c, tint(accent, .12f), 0, 999));
            }
        }
        if (title != null && !TextUtils.isEmpty(p.getTitle()) && restarts && restart == null)
            title.setText(com.particlesdevs.photoncamera.settings.SessionKeys.marked(p.getTitle()));
        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        TextView value = (TextView) holder.findViewById(R.id.settings_value);
        if (value != null) {
            CharSequence shown = valueOf(p);
            value.setText(shown);
            value.setTextColor(MUTED);   // SIMPLE_UI §4: the value on the right, muted (the concept's .rv)
            // P248 (owner, 2026-10-18: «Режим автофокуса», «Кадры светов», «Разрешение» - the title and the description one
            // word a line beside a long value): a value wider than its share of the row goes under the title
            TextView below = (TextView) holder.findViewById(R.id.settings_value_below);
            int share = valueShare(c);
            boolean under = below != null && !TextUtils.isEmpty(shown) && valueBelow(value.getPaint().measureText(shown.toString()), share);
            value.setMaxWidth(share);
            value.setVisibility(TextUtils.isEmpty(shown) || under ? View.GONE : View.VISIBLE);
            if (below != null) {
                below.setText(under ? shown : null);
                below.setVisibility(under ? View.VISIBLE : View.GONE);
            }
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
                boolean changed = p instanceof UniversalSeekBarPreference ? ((UniversalSeekBarPreference) p).isChanged()
                        : p instanceof TunableSeekBarPreference && ((TunableSeekBarPreference) p).isChanged();
                paintPill(pill, changed, accent);
            }
        }
    }

    /**
     * P168 (owner, 2026-10-16: «Если параметр отличается от стандартного, то пускай пилюля со значением меняет цвет на
     * противоположный от используемой темы»): the value pill of a setting off its default in the theme's opposite - the
     * theme's light text colour as its fill, its dark background colour as the text; at the default the accent's tint with
     * the accent, as before.
     */
    public static void paintPill(TextView pill, boolean changed, int accent) {
        pill.setBackgroundTintList(ColorStateList.valueOf(changed ? TEXT : tint(accent, .18f)));
        pill.setTextColor(changed ? BG : accent);
    }

    static CharSequence valueOf(Preference p) {
        if (p instanceof RestorePreference) return null;
        // SIMPLE_UI §4: «Плёночный LUT» shows the chosen look on the right (the concept's .rv)
        try {
            if ("output_settings_screen".equals(p.getKey())) return com.particlesdevs.photoncamera.settings.FormatChoice.current().shortLabel();
            if ("photo_size_screen".equals(p.getKey()))
                return com.particlesdevs.photoncamera.ui.camera.PhotoSize.label(com.particlesdevs.photoncamera.ui.camera.PhotoSize.current());
        } catch (RuntimeException e) {
            return null;   // no camera state yet (tests)
        }
        if ("backup_settings_screen".equals(p.getKey()))
            return com.particlesdevs.photoncamera.settings.ConfigState.label(p.getContext());
        if ("shade_transparency_screen".equals(p.getKey()))
            return com.particlesdevs.photoncamera.ui.settings.custompreferences.ShadeTransparencyPreference.label(p.getContext(),
                    Math.round((1f - com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle.sheetAlpha(p.getContext())) * 100f));
        if ("photo_style_screen".equals(p.getKey()) && p.getSharedPreferences() != null)
            return com.particlesdevs.photoncamera.settings.PhotoStyle.name(p.getContext(),
                    com.particlesdevs.photoncamera.settings.PhotoStyle.current(p.getSharedPreferences()));
        if (("film_lut_screen".equals(p.getKey()) || "photo_style_lut".equals(p.getKey())) && p.getSharedPreferences() != null)
            return com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmLutPreference.displayName(p.getContext(),
                    p.getSharedPreferences().getString(com.particlesdevs.photoncamera.processing.color.FilmLut.KEY, ""));
        if ("lens_filter_screen".equals(p.getKey()) && p.getSharedPreferences() != null)
            return com.particlesdevs.photoncamera.processing.color.LensFilters.name(p.getContext(),
                    p.getSharedPreferences().getString(com.particlesdevs.photoncamera.processing.color.LensFilters.KEY, ""));
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
        return sheetBody(c, title, null);
    }

    /**
     * A picker's sheet: the grab handle and the title; with {@code pinKey} a setting the camera's shade can hold, the title
     * line also carries «В шторку» (P137: from any value editor of the settings straight into the shade).
     */
    public static LinearLayout sheetBody(Context c, CharSequence title, String pinKey) {
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
        TextView pin = pinChip(c, pinKey);
        if (pin == null) {
            body.addView(t);
            return body;
        }
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.TOP);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.setMarginStart(dp(c, 10));
        head.addView(pin, pp);
        body.addView(head);
        return body;
    }

    /**
     * «В шторку» / «В шторке»: pins the setting in the camera's quick-settings shade or takes it out (ShadeTiles; the shade
     * reloads its tiles when the camera comes back). Null for keys the shade cannot show.
     */
    static TextView pinChip(Context c, String key) {
        if (key == null) return null;
        boolean known;
        try {
            known = com.particlesdevs.photoncamera.settings.ShadeCatalog.get(c).isKnown(key);
        } catch (RuntimeException e) {
            known = false;
        }
        if (!known) return null;
        final android.content.SharedPreferences prefs = com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        int accent = accent(c);
        TextView chip = text(c, "", 13, accent);
        chip.setTag("sheet_pin");
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(c, 10), dp(c, 7), dp(c, 14), dp(c, 7));
        chip.setCompoundDrawablePadding(dp(c, 6));
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(accent));
        Runnable paint = () -> {
            boolean on = com.particlesdevs.photoncamera.settings.ShadeTiles.isPinned(prefs, key);
            chip.setText(on ? Lang.t(c, "В шторке", "In the shade") : Lang.t(c, "В шторку", "To the shade"));
            chip.setContentDescription(on ? Lang.t(c, "Убрать из шторки", "Remove from the shade") : Lang.t(c, "Добавить в шторку", "Add to the shade"));
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(on ? R.drawable.ic_sheet_pin_on : R.drawable.ic_sheet_pin, 0, 0, 0);
            chip.setBackground(shape(c, on ? tint(accent, .22f) : 0, on ? 0 : tint(accent, .55f), 18));
            chip.setSelected(on);
        };
        chip.setOnClickListener(v -> {
            com.particlesdevs.photoncamera.settings.ShadeTiles.toggle(prefs, key);
            paint.run();
        });
        paint.run();
        return chip;
    }

    /**
     * A bottom sheet over the dark surface, opened full height (the pickers and the slider's exact-value editor). The body
     * sits in a nested-scrolling view: a sheet taller than the window (landscape) scrolls instead of being cut at the edge.
     */
    public static BottomSheetDialog sheet(Context c, View body) {
        BottomSheetDialog d = new BottomSheetDialog(c);
        NestedScrollView scroll = new NestedScrollView(c);
        scroll.setTag("sheet_scroll");
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
        d.setContentView(scroll);
        d.setOnShowListener(x -> {
            View host = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (host != null) host.setBackgroundColor(0);
            d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            d.getBehavior().setSkipCollapsed(true);
        });
        return d;
    }

    /** A radio dot: an accent ring with a dot when selected, a muted ring otherwise. */
    public static View radio(Context c, boolean on, int accent) {
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
                p.findIndexOfValue(p.getValue()), accent(c), null, i -> {
                    String v = tags[i].toString();
                    if (!v.equals(p.getValue()) && p.callChangeListener(v)) p.setValue(v);
                }, p.getKey());
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
        return optionSheet(c, title, labels, tags, selected, accent, icons, onPick, null);
    }

    /** The list picker of a setting: {@code pinKey} adds «В шторку» to its title line (P137). */
    static BottomSheetDialog optionSheet(Context c, CharSequence title, CharSequence[] labels, CharSequence[] tags,
                                         int selected, int accent, int[] icons, java.util.function.IntConsumer onPick, String pinKey) {
        LinearLayout body = sheetBody(c, title, pinKey);
        LinearLayout options = new LinearLayout(c);
        options.setOrientation(LinearLayout.VERTICAL);
        // nested scrolling: a drag scrolls the list (a plain ScrollView let the sheet take the drag and slide away)
        NestedScrollView scroll = new NestedScrollView(c);
        scroll.setTag("option_list");
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
        scroll.post(() -> {
            View window = d.getWindow() != null ? d.getWindow().getDecorView() : null;
            int cap = listCap(window != null ? window.getHeight() : 0, body.getHeight() - scroll.getHeight(), max,
                    c.getResources().getDisplayMetrics().density);
            if (scroll.getHeight() > cap) { scroll.getLayoutParams().height = cap; scroll.requestLayout(); }
        });
        d.show();
        return d;
    }

    /**
     * The option list's height limit: 60 % of the screen ({@code max}), and never more than the window leaves under the
     * sheet's title (landscape: 5 options did not fit and the last one was under the screen edge), at least two rows.
     */
    public static int listCap(int windowHeight, int rest, int max, float density) {
        if (windowHeight <= 0) return max;
        // the sheet opens below the status bar: on the X200 Ultra in landscape it starts 62 dp down
        int room = windowHeight - rest - Math.round(72 * density);
        return Math.max(Math.round(104 * density), Math.min(max, room));
    }

    static BottomSheetDialog editSheet(Context c, EditTextPreference p) {
        int accent = accent(c);
        LinearLayout body = sheetBody(c, p.getDialogTitle() != null ? p.getDialogTitle() : p.getTitle(), p.getKey());
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
