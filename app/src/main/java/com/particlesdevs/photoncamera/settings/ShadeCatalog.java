package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ManagedSwitchPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.RestorePreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableCheckBoxPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Data model of the quick-settings shade (P25, docs/settings-plan/SHADE_TASK.md): every setting a tile can show.
 * <p>
 * Two sources:
 * <ul>
 * <li>virtual entries, camera controls with no row in the settings tree (flash, self-timer, file format, Camera2
 * metering); their values go through PreferenceKeys and their writes through CameraUIController ({@link Entry#settingType});</li>
 * <li>the settings tree itself (res/xml/preferences.xml plus the generated tunables), walked like the settings search, so
 * a new setting is pinnable without code. It is inflated lazily, on the first lookup of a tree key, with a store that
 * returns the defaults and writes nothing.</li>
 * </ul>
 * The curated rows of the FULL level ({@link #GROUPS}) add a short tile name, short value labels (at most 8 characters)
 * and icons. Values are read from and written to the same SharedPreferences keys as the settings screen, so per-module
 * keys go through ModuleProfiles as usual.
 * <p>
 * The effective-route, MediaTek and dependency rules of the shade live here and not in {@link SettingsAvailability}: the
 * CI host check (tools/check_settings_model.py) compiles that class on its own.
 */
public final class ShadeCatalog {
    public static final int TOGGLE = 0, LIST = 1, SLIDER = 2;
    /** How a value is stored: as the settings screen stores it. */
    static final int STORE_STRING = 0, STORE_BOOLEAN = 1, STORE_INT = 2, STORE_FLOAT = 3, STORE_VIRTUAL = 4;
    /** Most pinned tiles. */
    public static final int MAX_TILES = 12;
    /** A list with more values than this opens the list sheet instead of cycling on a tap. */
    public static final int LONG_LIST = 5;

    public static final String FLASH = "pref_ae_mode_key";
    public static final String TIMER = "pref_countdown_timer_key";
    public static final String FORMAT = "pref_save_raw_key";
    public static final String METERING_STD = "pref_ae_metering_std_mode_key";
    public static final String ROUTE = LmcHybridKeys.ROUTE;
    private static final String OUTPUT = "pref_lmc_hybrid_output", DOWNSAMPLER = "pref_lmc_hybrid_downsampler";
    /** Virtual entries, in catalog order. */
    public static final List<String> VIRTUAL = Collections.unmodifiableList(Arrays.asList(FLASH, TIMER, FORMAT, METERING_STD));

    /** First value of ui_shade_tiles (owner's answer 10: after the user's old pins). */
    public static final List<String> DEFAULT_TILES = Collections.unmodifiableList(Arrays.asList(FLASH, TIMER, FORMAT, ROUTE,
            OUTPUT, "pref_show_grid_key", "pref_ultrahdr_key", "pref_lmc_hybrid_bento"));

    /** A curated group of the FULL level. */
    public static final class Group {
        @StringRes public final int title;
        public final List<String> keys;

        Group(@StringRes int title, String... keys) {
            this.title = title;
            this.keys = Collections.unmodifiableList(Arrays.asList(keys));
        }
    }

    /**
     * The FULL level's groups (SHADE_TASK.md §3 with the owner's answers 6 and 8: «замер» is the ARK metering slider,
     * «тон ARK» and Exposure Fusion are gone, Luma / Chroma are the gcam engine's multipliers, «удлинение L» a 0-2 slider).
     */
    public static final List<Group> GROUPS = Collections.unmodifiableList(Arrays.asList(
            new Group(R.string.shade_group_shoot, FLASH, TIMER, "pref_lmc_hybrid_ark_metering", ROUTE),
            new Group(R.string.shade_group_format, FORMAT, OUTPUT, DOWNSAMPLER, "pref_ultrahdr_key", "pref_wide169_key",
                    "pref_show_watermark_key"),
            new Group(R.string.shade_group_hybrid, "pref_lmc_hybrid_bento", "pref_lmc_hybrid_bento_frames",
                    "pref_lmc_hybrid_shasta", "pref_lmc_hybrid_zsl_frames", "pref_lmc_hybrid_dn_luma_mult",
                    "pref_lmc_hybrid_dn_chroma_mult", "pref_lmc_hybrid_sharp_mode", "pref_lmc_hybrid_cdm"),
            new Group(R.string.shade_group_scamhdr, "pref_vivo_nice_zsl_frames", "pref_vivo_nice_long_boost_ev",
                    "pref_vivo_nice_mosaic"),
            new Group(R.string.shade_group_color, "pref_lmc_tone_curve", "pref_sharp_usm_enabled_key"),
            new Group(R.string.shade_group_view, "pref_show_grid_key", "pref_peak_method_key", "pref_live_viewfinder_raw_key",
                    "pref_show_afdata_key")));

    /** Read when the camera session is built: a change restarts the camera (owner's answer 7). */
    static final Set<String> SESSION_KEYS = new HashSet<>(Arrays.asList("pref_live_viewfinder_raw_key", "pref_wide169_key",
            "pref_raw_stream_format", "pref_zsl_buffer_count_key", "pref_af_mode_key", "pref_preview_format_key"));
    /** Free-text rows that hold one number: a slider with the bounds of SettingsNumericRules and this step. */
    private static final Map<String, Float> NUMERIC_TEXT = new HashMap<>();
    static {
        NUMERIC_TEXT.put("pref_vivo_nice_long_boost_ev", 0.1f);
    }
    /** Never pinnable: switches a sensor mode (Quad) or belongs to one phone only. */
    private static final Set<String> NOT_PINNABLE = new HashSet<>(Arrays.asList(
            PreferenceKeys.Key.KEY_QUAD_BAYER.mValue,
            PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue, PreferenceKeys.Key.CAMERA_MODE.mValue));

    /** Curated look of a key: short tile name, short value labels, icon of the setting and of each value. */
    private static final class Spec {
        @StringRes final int shortTitle;
        final int shortLabels;
        @DrawableRes final int icon;
        final int[] valueIcons;

        Spec(int shortTitle, int shortLabels, int icon, int[] valueIcons) {
            this.shortTitle = shortTitle;
            this.shortLabels = shortLabels;
            this.icon = icon;
            this.valueIcons = valueIcons;
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private static void spec(String key, @StringRes int shortTitle, int shortLabels, @DrawableRes int icon, int... valueIcons) {
        SPECS.put(key, new Spec(shortTitle, shortLabels, icon, valueIcons.length == 0 ? null : valueIcons));
    }

    static {
        spec(FLASH, R.string.shade_t_flash, R.array.shade_s_flash, R.drawable.ic_flash_on, R.drawable.ic_torch, R.drawable.ic_flash_off);
        spec(TIMER, R.string.shade_t_timer, R.array.shade_s_timer, R.drawable.ic_sheet_timer,
                R.drawable.ic_timeroff, R.drawable.ic_timer3s, R.drawable.ic_timer10s);
        spec(FORMAT, R.string.shade_t_format, R.array.shade_s_format, R.drawable.ic_shade_jpeg,
                R.drawable.ic_shade_jpeg, R.drawable.ic_shade_rawjpeg, R.drawable.ic_shade_raw);
        spec(METERING_STD, R.string.shade_t_metering_std, R.array.shade_s_metering_std, R.drawable.ic_sheet_metering);
        spec("pref_lmc_hybrid_ark_metering", R.string.shade_t_metering, 0, R.drawable.ic_shade_meter);
        spec(ROUTE, R.string.shade_t_route, R.array.shade_s_route, R.drawable.settings_ic_layers);
        spec(OUTPUT, R.string.shade_t_output, R.array.shade_s_output, R.drawable.settings_ic_zoom);
        spec(DOWNSAMPLER, R.string.shade_t_downsampler, R.array.shade_s_downsampler, R.drawable.ic_sheet_downsampler);
        spec("pref_ultrahdr_key", R.string.shade_t_ultrahdr, 0, R.drawable.settings_ic_hdr);
        spec("pref_wide169_key", R.string.shade_t_wide169, 0, R.drawable.settings_ic_ratio);
        spec("pref_show_watermark_key", R.string.shade_t_watermark, 0, R.drawable.settings_ic_water);
        spec("pref_lmc_hybrid_bento", R.string.shade_t_bento, R.array.shade_s_bento, R.drawable.settings_ic_flash);
        spec("pref_lmc_hybrid_bento_frames", R.string.shade_t_bento_frames, R.array.shade_s_bento_frames, R.drawable.settings_ic_frames);
        spec("pref_lmc_hybrid_shasta", R.string.shade_t_shasta, 0, R.drawable.settings_ic_layers);
        spec("pref_lmc_hybrid_zsl_frames", R.string.shade_t_hybrid_frames, 0, R.drawable.settings_ic_frames);
        spec("pref_lmc_hybrid_dn_luma_mult", R.string.shade_t_luma, 0, R.drawable.settings_ic_noise);
        spec("pref_lmc_hybrid_dn_chroma_mult", R.string.shade_t_chroma, 0, R.drawable.settings_ic_palette);
        spec("pref_lmc_hybrid_sharp_mode", R.string.shade_t_sharp, R.array.shade_s_sharp, R.drawable.settings_ic_sharp);
        spec("pref_lmc_hybrid_cdm", R.string.shade_t_rejection, 0, R.drawable.settings_ic_eye);
        spec("pref_vivo_nice_zsl_frames", R.string.shade_t_scam_frames, 0, R.drawable.settings_ic_frames);
        spec("pref_vivo_nice_long_boost_ev", R.string.shade_t_long_boost, 0, R.drawable.settings_ic_sun);
        spec("pref_vivo_nice_mosaic", R.string.shade_t_mosaic, R.array.shade_s_mosaic, R.drawable.settings_ic_mosaic);
        spec("pref_lmc_tone_curve", R.string.shade_t_tone_curve, 0, R.drawable.settings_ic_diag);
        spec("pref_sharp_usm_enabled_key", R.string.shade_t_usm, 0, R.drawable.settings_ic_sharp);
        spec("pref_show_grid_key", R.string.shade_t_grid, R.array.shade_s_grid, R.drawable.settings_ic_grid,
                R.drawable.ic_sheet_grid_off, R.drawable.ic_grid_3x3, R.drawable.ic_grid_4x4, R.drawable.ic_grid_golden,
                R.drawable.ic_grid_diagonal);
        spec("pref_peak_method_key", R.string.shade_t_peak, R.array.shade_s_peak, R.drawable.ic_shade_focus);
        spec("pref_live_viewfinder_raw_key", R.string.shade_t_live_raw, 0, R.drawable.settings_ic_eye);
        spec("pref_show_afdata_key", R.string.shade_t_debug, R.array.shade_s_debug, R.drawable.settings_ic_diag);
    }

    /**
     * Search keywords in the other language (bilingual search, owner's answer 11): a setting whose text matches the
     * pattern is also found by these words, so «люма» finds Luma and «grid» finds «Сетка». The patterns hold the English
     * and the Russian UI words, so the keywords are added whichever language the titles are in.
     */
    private static final String[][] KEYWORDS = {
            {"denoise|шумодав|шумопод|noise|шум|despeckle", "шум шумоподавление шумодав denoise noise"},
            {"luma|люма|ярк|bright", "яркость люма luma brightness"},
            {"chroma|хрома|цвет|colo", "цвет хрома chroma color colour"},
            {"sharp|резк|usm|unsharp|деконвол|deconv|ореол|halo|чётк|четк", "резкость sharpness sharpen usm детали detail"},
            {"bento", "bento бенто короткий ультракороткий кадр пересвет света short highlights"},
            {"shasta", "shasta шаста длинный кадр тени long shadows"},
            {"zsl|кадр|frame", "кадры frames zsl буфер buffer"},
            {"hdr|fusion|экспоз|exposure|тонмап|tone map|тон |tone |ae ", "hdr экспозиция тон яркость exposure tone"},
            {"мозаик|mosaic|quad|tetra|байер|bayer|hp9", "мозаика ремозаик quad tetra bayer mosaic"},
            {"сетк|grid", "сетка grid"},
            {"фокус|focus|peak", "фокус focus пик peaking"},
            {"отладк|hud|диагност|diagnost|журнал|debug", "отладка debug hud лог log"},
            {"водян|подпис|watermark|caption", "водяной знак watermark подпись caption"},
            {"agx|aces|кривая|гамма|gamma|curve", "кривая curve тон agx гамма gamma"},
            {"dcp|матриц|matri", "dcp матрица цвет профиль matrix colour color profile"},
            {"мерцан|flicker|antiband", "мерцание flicker антибандинг antibanding"},
            {"вспышк|фонар|flash|torch", "вспышка flash фонарик torch"},
            {"таймер|timer", "таймер timer"},
            {"raw|jpeg|формат|format", "формат format raw jpeg dng"},
            {"звук|sound", "звук sound"},
            {"замер|\\bmeter", "замер metering экспозамер"},
            {"склейк|merge|route", "склейка merge route hybrid scam"},
            {"разрешен|resolution|даунсемпл|downsampl", "разрешение resolution мп mp размер size"},
    };

    /** One setting a tile can show. */
    public static final class Entry {
        public final String key;
        public int kind;
        public CharSequence title, shortTitle;
        /** Section titles from the settings root (the root itself excluded); empty for a row on the root page. */
        public final List<String> path = new ArrayList<>();
        public CharSequence[] labels, values, shortLabels;
        public float min, max, step = 1f;
        public boolean decimal;
        /** XML / tunable default: a String for lists, Boolean for toggles, Float for sliders. */
        public Object defaultValue;
        @DrawableRes public int icon;
        @Nullable public int[] valueIcons;
        /** The setting's own icon from the tree when the curated spec has none. */
        Drawable.ConstantState treeIcon;
        public boolean sessionTime;
        /** Virtual entries: the CameraUIController path of the write; null for tree keys. */
        @Nullable public SettingType settingType;
        /** Key of the switch this row depends on (android:dependency), or null. */
        @Nullable public String dependency;
        int storage;
        /** Lower-case search text: title, short title, section path, value labels and keywords. */
        String haystack = "";

        Entry(String key) {
            this.key = key;
        }

        public boolean isVirtual() {
            return settingType != null;
        }

        public boolean isCurated() {
            return SPECS.containsKey(key);
        }

        /** Catalog section: the first section of the path, «Съёмка» for camera controls and the root page. */
        public String section(Context context) {
            return path.isEmpty() ? context.getString(R.string.shade_group_shoot) : path.get(0);
        }

        /** Breadcrumb under the section ("Кадры и захват › Bento — ультракороткий кадр"). */
        public String crumb() {
            return path.size() <= 1 ? "" : String.join(" › ", path.subList(1, path.size()));
        }

        /** True for a long list, which opens the list sheet instead of cycling. */
        public boolean isLongList() {
            return kind == LIST && values != null && values.length > LONG_LIST;
        }

        public boolean matches(String query) {
            for (String word : normalize(query).trim().split("\\s+"))
                if (!word.isEmpty() && !haystack.contains(word)) return false;
            return true;
        }
    }

    // ───────────────────────────────── instance

    private static ShadeCatalog instance;

    private final Context context;
    private final Resources res;
    private final SharedPreferences prefs;
    /** Every entry, virtual first, then the tree in settings order. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean treeBuilt;
    private Map<String, Object> deviceDefaults;
    /** The current lens has a flash unit (CameraFragment's characteristics callback). */
    private static boolean flashAvailable = true;

    /** The process-wide catalog (built on the application context, so no Activity is kept). */
    public static synchronized ShadeCatalog get(Context context) {
        if (instance == null) instance = new ShadeCatalog(context.getApplicationContext() != null ? context.getApplicationContext() : context,
                PhotonCamera.getSettingsManagerStatic().getDefaultPreferences());
        return instance;
    }

    /** Drops the process-wide catalog (tests, a config restored in place). */
    public static synchronized void reset() {
        instance = null;
    }

    /** A catalog over the given preferences (tests); {@link #get} for the app. */
    public ShadeCatalog(Context context, SharedPreferences prefs) {
        this.context = context;
        this.res = context.getResources();
        this.prefs = prefs;
        addVirtual(FLASH, R.string.shade_title_flash, R.array.shade_l_flash, new String[]{"0", "1"},
                res.getString(R.string.pref_ae_mode_default).trim(), SettingType.FLASH);
        addVirtual(TIMER, R.string.shade_title_timer, R.array.shade_l_timer, new String[]{"0", "1", "2"}, "0", SettingType.TIMER);
        addVirtual(FORMAT, R.string.shade_title_format, R.array.shade_l_format, new String[]{"0", "1", "2"},
                res.getString(R.string.pref_raw_mode_default_value).trim(), SettingType.RAW);
        addVirtual(METERING_STD, R.string.shade_title_metering_std, R.array.shade_l_metering_std,
                new String[]{"-1", "0", "1", "2"}, "-1", SettingType.AE_METERING_STD);
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    private void addVirtual(String key, @StringRes int title, int labels, String[] values, String def, SettingType type) {
        Entry e = new Entry(key);
        e.kind = LIST;
        e.title = res.getString(title);
        e.labels = res.getTextArray(labels);
        e.values = values;
        e.defaultValue = def;
        e.settingType = type;
        e.storage = STORE_VIRTUAL;
        e.path.add(res.getString(R.string.shade_group_shoot));
        decorate(e);
        entries.put(key, e);
    }

    /** Short title, short labels and icons of the curated spec; search text. */
    private void decorate(Entry e) {
        Spec spec = SPECS.get(e.key);
        if (spec != null) {
            e.shortTitle = res.getString(spec.shortTitle);
            if (spec.shortLabels != 0) e.shortLabels = res.getTextArray(spec.shortLabels);
            e.icon = spec.icon;
            e.valueIcons = spec.valueIcons;
        }
        if (e.shortTitle == null) e.shortTitle = e.title;
        if (e.icon == 0 && e.treeIcon == null)
            e.icon = e.kind == TOGGLE ? R.drawable.settings_ic_toggle : e.kind == LIST ? R.drawable.settings_ic_list : R.drawable.settings_ic_sliders;
        e.sessionTime = SESSION_KEYS.contains(e.key);
        StringBuilder text = new StringBuilder().append(e.title).append(' ').append(e.shortTitle);
        for (String p : e.path) text.append(' ').append(p);
        if (e.labels != null) for (CharSequence l : e.labels) text.append(' ').append(l);
        if (e.shortLabels != null) for (CharSequence l : e.shortLabels) text.append(' ').append(l);
        String base = normalize(text.toString());
        StringBuilder hay = new StringBuilder(base).append(' ').append(e.key.toLowerCase(Locale.ROOT));
        for (String[] k : KEYWORDS) if (java.util.regex.Pattern.compile(k[0]).matcher(base).find()) hay.append(' ').append(k[1]);
        e.haystack = normalize(hay.toString());
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    // ───────────────────────────────── the settings tree

    /** Reads every default and writes nothing: the walk must not touch the real settings. */
    private static final class DefaultsOnly extends PreferenceDataStore {
        @Override public void putString(String key, @Nullable String value) {}
        @Override public void putStringSet(String key, @Nullable Set<String> values) {}
        @Override public void putInt(String key, int value) {}
        @Override public void putLong(String key, long value) {}
        @Override public void putFloat(String key, float value) {}
        @Override public void putBoolean(String key, boolean value) {}
    }

    /** Inflates the settings tree once (the first lookup of a key that is not virtual). */
    public synchronized void ensureTree() {
        if (treeBuilt) return;
        treeBuilt = true;
        Context themed = new ContextThemeWrapper(context, R.style.Theme_Photon_SettingsActivity);
        PreferenceManager manager = new PreferenceManager(themed);
        manager.setPreferenceDataStore(new DefaultsOnly());
        PreferenceScreen tree = manager.inflateFromResource(themed, R.xml.preferences, null);
        manager.setPreferences(tree);
        TunableSettingsManager.ensureTunableClassesRegistered();
        for (Class<?> c : TunableRegistry.TUNABLE_CLASSES) TunablePreferenceGenerator.registerTunableClass(c);
        TunablePreferenceGenerator.generatePreferences(themed, tree);
        applyRuntimeRules(tree);
        walk(tree, new ArrayList<>(), null, true);
    }

    /** The visibility rules of SettingsActivity: no SCAM HDR on MediaTek, the Xiaomi 17 Ultra zoom row only there. */
    private static void applyRuntimeRules(PreferenceScreen tree) {
        Preference scamHdr = tree.findPreference("vivo_hdr_screen");
        if (scamHdr != null && PreferenceKeys.isMediaTekSoc()) scamHdr.setVisible(false);
        Preference xiaomiZoom = tree.findPreference(com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.PREF);
        if (xiaomiZoom != null && !com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.phone()) xiaomiZoom.setVisible(false);
    }

    private void walk(Preference p, List<String> path, @Nullable Drawable.ConstantState inherited, boolean root) {
        if (!p.isVisible()) return;
        if (p instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) p;
            List<String> inner = new ArrayList<>(path);
            if (!root && p.getTitle() != null && p.getTitle().length() > 0) inner.add(p.getTitle().toString());
            Drawable.ConstantState icon = inherited;
            Drawable own = p instanceof PreferenceScreen && !root ? p.getIcon() : null;
            if (own != null && own.getConstantState() != null) icon = own.getConstantState();
            for (int i = 0; i < group.getPreferenceCount(); i++) walk(group.getPreference(i), inner, icon, false);
            return;
        }
        String key = p.getKey();
        if (key == null || !pinnable(key) || entries.containsKey(key)) return;
        Entry e = new Entry(key);
        e.title = p.getTitle() == null ? key : p.getTitle().toString();
        e.path.addAll(path);
        e.dependency = p.getDependency();
        if (p instanceof UniversalSeekBarPreference) {
            UniversalSeekBarPreference s = (UniversalSeekBarPreference) p;
            slider(e, s.minimum(), s.maximum(), s.decimal(), s.stepPerUnit(), s.defaultNumber());
            e.storage = STORE_STRING;
        } else if (p instanceof TunableSeekBarPreference) {
            TunableSeekBarPreference s = (TunableSeekBarPreference) p;
            slider(e, s.minimum(), s.maximum(), s.decimal(), s.stepPerUnit(), s.defaultNumber());
            e.storage = s.decimal() ? STORE_FLOAT : STORE_INT;
        } else if (p instanceof TwoStatePreference) {
            e.kind = TOGGLE;
            if (p instanceof TunableCheckBoxPreference) {
                e.defaultValue = ((TunableCheckBoxPreference) p).defaultInt() != 0;
                e.storage = STORE_INT;
            } else {
                e.defaultValue = p instanceof ManagedSwitchPreference ? ((ManagedSwitchPreference) p).defaultChecked()
                        : ((TwoStatePreference) p).isChecked();
                e.storage = STORE_BOOLEAN;
            }
        } else if (p instanceof ListPreference && !(p instanceof RestorePreference)) {
            ListPreference l = (ListPreference) p;
            if (l.getEntries() == null || l.getEntryValues() == null || l.getEntries().length == 0) return;
            e.kind = LIST;
            e.labels = l.getEntries();
            e.values = l.getEntryValues();
            e.defaultValue = l.getValue() != null ? l.getValue() : e.values[0].toString();
            e.storage = STORE_STRING;
        } else if (p instanceof EditTextPreference && NUMERIC_TEXT.containsKey(key)) {
            double[] bounds = SettingsNumericRules.bounds(key);
            if (bounds == null) return;
            float step = NUMERIC_TEXT.get(key);
            String text = ((EditTextPreference) p).getText();
            slider(e, (float) bounds[0], (float) bounds[1], bounds[2] == 0, 1f / step,
                    (float) PreferenceNumber.read(text, bounds[0]));
            e.storage = STORE_STRING;
        } else {
            return; // screens, actions, free text, multi-select
        }
        Drawable own = p.getIcon();
        Drawable.ConstantState icon = own != null && own.getConstantState() != null ? own.getConstantState() : inherited;
        if (!SPECS.containsKey(key)) e.treeIcon = icon;
        decorate(e);
        entries.put(key, e);
    }

    private static void slider(Entry e, float min, float max, boolean decimal, float stepPerUnit, float def) {
        e.kind = SLIDER;
        e.min = min;
        e.max = max;
        e.decimal = decimal;
        e.step = decimal && stepPerUnit > 0 ? 1f / stepPerUnit : 1f;
        e.defaultValue = Math.max(min, Math.min(max, def));
    }

    /** False for screens of one phone only, the sensor configs, the DCP file, spoofing, themes and Quad. */
    static boolean pinnable(String key) {
        if (key.startsWith("pref_sensorconfig_") || key.startsWith("settings_") || key.startsWith("module_")
                || key.startsWith("lens_") || key.startsWith("pref_theme") || key.contains("spoof")) return false;
        if (NOT_PINNABLE.contains(key) || BackupRestoreUtil.deviceOnly(key)) return false;
        return !key.equals(com.particlesdevs.photoncamera.processing.color.DcpProfiles.KEY);
    }

    // ───────────────────────────────── lookups

    /** The entry of a key, or null when the key is not pinnable on this phone (the tree is inflated on demand). */
    @Nullable
    public Entry entry(String key) {
        if (key == null) return null;
        Entry e = entries.get(key);
        if (e != null || treeBuilt) return e;
        ensureTree();
        return entries.get(key);
    }

    public boolean isKnown(String key) {
        return entry(key) != null;
    }

    /** Every pinnable setting, camera controls first, then the settings tree in its order. */
    public List<Entry> all() {
        ensureTree();
        return new ArrayList<>(entries.values());
    }

    /** Entries matching the query (every word in the title, section, values or keywords), in catalog order. */
    public List<Entry> search(String query) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) if (query == null || query.trim().isEmpty() || e.matches(query)) out.add(e);
        return out;
    }

    /** The curated groups shown on this phone: SCAM HDR is hidden on MediaTek, rows of hidden settings are left out. */
    public List<Group> visibleGroups() {
        List<Group> out = new ArrayList<>();
        for (Group g : GROUPS) {
            List<String> keys = new ArrayList<>();
            for (String k : g.keys) if (isKnown(k)) keys.add(k);
            if (!keys.isEmpty()) out.add(new Group(g.title, keys.toArray(new String[0])));
        }
        return out;
    }

    public static boolean isCurated(String key) {
        for (Group g : GROUPS) if (g.keys.contains(key)) return true;
        return false;
    }

    public static boolean isVirtualKey(String key) {
        return VIRTUAL.contains(key);
    }

    // ───────────────────────────────── values

    /** The stored value, the effective one for the route and the hybrid's output (MediaTek lock, nice_dev.txt). */
    public Object value(Entry e) {
        if (e.isVirtual()) {
            switch (e.key) {
                case FLASH: return String.valueOf(PreferenceKeys.getAeMode());
                case TIMER: return String.valueOf(PreferenceKeys.getCountdownTimerIndex());
                case FORMAT: return String.valueOf(PreferenceKeys.isSaveRaw());
                default: return String.valueOf(PreferenceKeys.getAeMeteringStd());
            }
        }
        switch (e.key) {
            case ROUTE: return PreferenceKeys.mergeRoute();
            case OUTPUT: return PreferenceKeys.hybridOutputMode();
            case DOWNSAMPLER: return PreferenceKeys.hybridDownsampler();
            default: break;
        }
        Object v = PreferenceValue.get(prefs, e.key);
        return v == null ? defaultOf(e) : v;
    }

    /** The default: the phone's factory configuration (DeviceDefaults), else the XML / tunable / PreferenceKeys default. */
    public Object defaultOf(Entry e) {
        if (deviceDefaults == null) {
            Map<String, Object> d = null;
            try { d = DeviceDefaults.forDevice(context); } catch (RuntimeException ignored) { }
            deviceDefaults = d == null ? Collections.emptyMap() : d;
        }
        Object d = deviceDefaults.get(e.key);
        return d != null ? d : e.defaultValue;
    }

    /** LIST: index of the current value, -1 when it is none of the values. */
    public int index(Entry e) {
        return indexOf(e, value(e));
    }

    static int indexOf(Entry e, Object value) {
        if (e.values == null || value == null) return -1;
        String text = value.toString().trim();
        for (int i = 0; i < e.values.length; i++) if (e.values[i].toString().equals(text)) return i;
        double n = PreferenceNumber.read(text, Double.NaN);
        if (Double.isNaN(n)) return -1;
        for (int i = 0; i < e.values.length; i++) if (PreferenceNumber.read(e.values[i], Double.NaN) == n) return i;
        return -1;
    }

    public boolean on(Entry e) {
        return PreferenceNumber.bool(value(e), PreferenceNumber.bool(defaultOf(e), false));
    }

    public float number(Entry e) {
        float def = (float) PreferenceNumber.read(defaultOf(e), e.min);
        return Math.max(e.min, Math.min(e.max, (float) PreferenceNumber.read(value(e), def)));
    }

    /** True when the value differs from the default (lists, toggles and sliders alike; owner's answer 5). */
    public boolean changed(Entry e) {
        switch (e.kind) {
            case TOGGLE:
                return on(e) != PreferenceNumber.bool(defaultOf(e), false);
            case SLIDER:
                return Math.abs(number(e) - (float) PreferenceNumber.read(defaultOf(e), e.min)) > Math.max(1e-6f, e.step / 2f);
            default:
                int current = index(e), def = indexOf(e, defaultOf(e));
                return current != def;
        }
    }

    /** The value as a tile (short) or a toast / catalog row (full) shows it. Numbers use the decimal comma (point in English). */
    public String valueText(Entry e, boolean shortForm) {
        switch (e.kind) {
            case TOGGLE:
                return res.getString(on(e) ? R.string.shade_on : R.string.shade_off);
            case SLIDER:
                return formatNumber(e, number(e));
            default:
                int i = index(e);
                if (i < 0) { Object v = value(e); return v == null ? "" : v.toString(); }
                return label(e, i, shortForm);
        }
    }

    /** Label of the i-th list value: the curated short label, else the entry (shortened for a tile). */
    public static String label(Entry e, int i, boolean shortForm) {
        if (shortForm && e.shortLabels != null && i < e.shortLabels.length) return e.shortLabels[i].toString();
        String full = e.labels != null && i < e.labels.length ? e.labels[i].toString() : e.values[i].toString();
        return shortForm ? autoShort(full) : full;
    }

    /** The part of a long entry before its explanation: "Сенсор (1×, по умолчанию)" -> "Сенсор". */
    static String autoShort(String label) {
        String s = label.split(" \\(| —| →|: | · ")[0].trim();
        if (s.length() > 22) s = s.split(" ")[0];
        return s.isEmpty() ? label : s;
    }

    /** "0,60", "20", "1,1": as many decimals as the step has (at most two), decimal comma (point in English), a real minus sign. */
    public static String formatNumber(Entry e, float v) {
        int decimals = 0;
        if (e.decimal) {
            float step = e.step;
            while (decimals < 2 && Math.abs(step * Math.pow(10, decimals) - Math.round(step * Math.pow(10, decimals))) > 1e-4) decimals++;
        }
        String text = String.format(Locale.ROOT, "%." + decimals + "f", v);
        return (Lang.ru() ? text.replace('.', ',') : text).replace('-', '−');
    }

    /** Icon of the current value, else of the setting. */
    @Nullable
    public Drawable icon(Entry e) {
        int i = e.kind == LIST ? index(e) : -1;
        if (e.valueIcons != null && i >= 0 && i < e.valueIcons.length) return context.getDrawable(e.valueIcons[i]);
        if (e.icon != 0) return context.getDrawable(e.icon);
        return e.treeIcon != null ? e.treeIcon.newDrawable(res).mutate() : null;
    }

    /** The setting's own icon (catalog rows): the icon of its first value for the camera controls. */
    @Nullable
    public Drawable settingIcon(Entry e) {
        if (e.icon != 0) return context.getDrawable(e.icon);
        return e.treeIcon != null ? e.treeIcon.newDrawable(res).mutate() : null;
    }

    /**
     * Stores a value of a tree key the way the settings screen stores it. Virtual keys are written by CameraUIController
     * ({@link Entry#settingType}); the caller handles them.
     */
    public void write(Entry e, Object value) {
        if (e.isVirtual()) throw new IllegalArgumentException(e.key + " is written by CameraUIController");
        SharedPreferences.Editor editor = prefs.edit();
        switch (e.storage) {
            case STORE_BOOLEAN:
                editor.putBoolean(e.key, PreferenceNumber.bool(value, false));
                break;
            case STORE_INT:
                editor.putInt(e.key, value instanceof Boolean ? ((Boolean) value ? 1 : 0) : (int) Math.round(PreferenceNumber.read(value, 0)));
                break;
            case STORE_FLOAT:
                editor.putFloat(e.key, (float) PreferenceNumber.read(value, 0));
                break;
            default:
                editor.putString(e.key, value instanceof Number ? PreferenceNumber.format(((Number) value).floatValue(), e.decimal) : String.valueOf(value));
                break;
        }
        editor.apply();
    }

    /** The next list value (wrapping) as stored, for a tap on a list tile. */
    public String nextValue(Entry e) {
        int i = index(e);
        return e.values[(i + 1 + e.values.length) % e.values.length].toString();
    }

    // ───────────────────────────────── availability

    /** Whether the current lens has a flash (set from the camera characteristics). */
    public static void setFlashAvailable(boolean available) {
        flashAvailable = available;
    }

    /**
     * Why the setting does nothing now (the tile is dimmed and a tap shows this), or null when it is available: no flash
     * on this lens, the MediaTek route lock, a nice_dev.txt override, the other route (SettingsAvailability with the
     * effective route), a switch it depends on.
     */
    @Nullable
    public String unavailable(Entry e) {
        if (FLASH.equals(e.key)) return flashAvailable ? null : res.getString(R.string.shade_reason_no_flash);
        if (ROUTE.equals(e.key)) {
            if (PreferenceKeys.isMediaTekSoc()) return res.getString(R.string.shade_reason_mediatek);
            if (PreferenceKeys.niceDevOverrides("hybrid")) return res.getString(R.string.shade_reason_nice_dev);
            return null;
        }
        if (e.key.startsWith(LmcHybridKeys.PREFIX)
                && PreferenceKeys.niceDevOverrides("hybrid_" + e.key.substring(LmcHybridKeys.PREFIX.length())))
            return res.getString(R.string.shade_reason_nice_dev);
        Map<String, Object> values = new HashMap<>(prefs.getAll());
        values.put(ROUTE, PreferenceKeys.mergeRoute());
        String reason = new SettingsAvailability(values).reason(e.key);
        if (reason != null) return reason;
        if (e.dependency != null) {
            Entry master = entry(e.dependency);
            if (master != null && master.kind == TOGGLE && !on(master))
                return res.getString(R.string.shade_reason_dependency, master.title);
        }
        return null;
    }
}
