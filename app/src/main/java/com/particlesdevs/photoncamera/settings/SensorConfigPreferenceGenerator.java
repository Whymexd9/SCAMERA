package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyDialog;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyPreference;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.settings.annotations.SensorConfig;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Automatically generates per-module sensor preference UI from {@code @SensorConfig} annotations.
 * One category per module; only the selected module's category is shown. P384 (owner, 2026-10-28: «А то что на третьем скриншоте
 * сделать как отдельную группу настроек на главной странице разделенную по модулям»): the categories live in «Сенсор по модулям»
 * ({@link #GROUP_KEY}) of the settings' main page, the module is chosen by its chips (ModuleRows, ModuleChipsPreference) - none at
 * first, so the page stays short - or by the module profile page that shows it; see {@link ModuleSelection}.
 */
public class SensorConfigPreferenceGenerator {
    private static final String TAG = "SensorConfigPrefGen";

    /** P384: «Сенсор по модулям» of the main page (was the page pref_sensor_config_submenu). */
    public static final String GROUP_KEY = "expert_module_sensor";
    /** The key prefix of a module's category. */
    public static final String CATEGORY_PREFIX = "pref_category_sensor_";
    /** Rows in this order: RAW levels, stabilization, session; unknown fields after them. */
    private static final List<String> FIELD_ORDER = java.util.Arrays.asList(
            "blackLevelOverride", "whiteLevelOverride", "oisMode", "sessionType");
    /** P384: the icons of the generated list and text rows by field (the sliders keep theirs). */
    private static final Map<String, Integer> FIELD_ICONS = new HashMap<>();

    static {
        FIELD_ICONS.put("oismode", com.particlesdevs.photoncamera.R.drawable.settings_ic_motion);
        FIELD_ICONS.put("sessiontype", com.particlesdevs.photoncamera.R.drawable.settings_ic_chip);
        FIELD_ICONS.put("ispsurface", com.particlesdevs.photoncamera.R.drawable.settings_ic_layers);
        FIELD_ICONS.put("rawviewfinder", com.particlesdevs.photoncamera.R.drawable.settings_ic_eye);
    }

    private SensorConfigPreferenceGenerator() {}

    /**
     * The module whose sensor settings the page shows, or none. Choosing another one only changes what the page shows: it
     * neither switches the camera nor writes a preference.
     */
    public static final class ModuleSelection {
        private final List<String> slots;
        private final Map<String, PreferenceCategory> categories;
        /** P384: the camera each category was built for (ModuleRows rebuilds a category whose module changed its camera). */
        private final Map<String, String> cameras = new HashMap<>();
        @Nullable private String selected;

        ModuleSelection(List<String> slots, Map<String, PreferenceCategory> categories, @Nullable String selected) {
            this.slots = new ArrayList<>(slots);
            this.categories = categories;
            for (String slot : slots) cameras.put(slot, cameraOf(slot));
            select(selected);
        }

        /** The modules the page can show, in zoom-bar order. */
        public List<String> slots() { return Collections.unmodifiableList(slots); }

        /** The module shown, null when none is. */
        @Nullable public String selected() { return selected; }

        /** «1× · ID 3»: the title of the module's category. */
        public String title(String slot) {
            PreferenceCategory category = categories.get(slot);
            return category != null && category.getTitle() != null ? category.getTitle().toString() : slot;
        }

        @Nullable public PreferenceCategory category(String slot) { return categories.get(slot); }

        /** P384: the camera the module's category was built for. */
        @Nullable public String camera(String slot) { return cameras.get(slot); }

        /** Show {@code slot}'s category only; null (or a module not listed) shows none. */
        public void select(@Nullable String slot) {
            if (slot != null && !categories.containsKey(slot)) slot = null;
            selected = slot;
            for (Map.Entry<String, PreferenceCategory> entry : categories.entrySet()) {
                boolean shown = entry.getKey().equals(slot);
                if (entry.getValue().isVisible() != shown) entry.getValue().setVisible(shown);
            }
        }

        /** P384: a module's category added (ModuleRows.sync: a new copy), the modules kept in {@code order}. */
        public void put(String slot, PreferenceCategory category, List<String> order) {
            categories.put(slot, category);
            cameras.put(slot, cameraOf(slot));
            if (!slots.contains(slot)) slots.add(slot);
            order(order);
            category.setVisible(slot.equals(selected));
        }

        /** P384: a module's category gone (a deleted copy). */
        public void remove(String slot) {
            categories.remove(slot);
            cameras.remove(slot);
            slots.remove(slot);
            if (slot.equals(selected)) selected = null;
        }

        /** P384: the modules in {@code order} (zoom order after a zoom change). */
        public void order(List<String> order) {
            slots.sort(java.util.Comparator.comparingInt(s -> order.indexOf(s) < 0 ? Integer.MAX_VALUE : order.indexOf(s)));
        }

        static String cameraOf(String slot) {
            try {
                return ModuleRegistry.slots().contains(slot) ? ModuleRegistry.camera(slot) : slot;
            } catch (RuntimeException e) {
                return slot;
            }
        }
    }

    /**
     * P384: the modules of the settings' main page (cards, Camera ID rows, sensor categories): every slot but the untouched filler
     * slots (a free place for a copy, P148), the back ones by zoom, then the front ones; a hidden module stays at its place.
     * {@code keep} is listed even when it is a filler (a module profile page shows its own module).
     */
    public static List<String> moduleSlots(@Nullable String keep) {
        List<String> slots = new ArrayList<>(ModuleRegistry.slots());
        slots.removeIf(slot -> !slot.equals(keep) && ModuleRegistry.freeSlot(slot));
        slots.sort(java.util.Comparator.comparing((String slot) -> slot.startsWith("front")).thenComparingDouble(ModuleRegistry::zoom));
        return slots;
    }

    /** A category of one module (pref_category_sensor_&lt;slot&gt;). */
    public static boolean isModuleCategory(@Nullable androidx.preference.Preference p) {
        return p instanceof PreferenceCategory && p.getKey() != null && p.getKey().startsWith(CATEGORY_PREFIX);
    }

    /** Generate the per-module sensor rows; no module is shown at first. */
    public static ModuleSelection generatePreferences(Context context, PreferenceGroup root) {
        return generatePreferences(context, root, null);
    }

    /** As {@link #generatePreferences(Context, PreferenceGroup, String, Runnable, Consumer)} without callbacks. */
    public static ModuleSelection generatePreferences(Context context, PreferenceGroup root, String preferred) {
        return generatePreferences(context, root, preferred, null, null);
    }

    /**
     * Generate and add per-module sensor preferences to «Сенсор по модулям» of {@code root}.
     *
     * @param preferred the module shown at first (a module profile page shows its own), null for none
     * @param onChange  run after a camera key was added, edited, deleted or switched (the main page's chips and summaries)
     * @param onReset   asked to reset a module's sensor settings (the fragment confirms, resets and recreates)
     * @return the module selection of the page, null when nothing was generated
     */
    public static ModuleSelection generatePreferences(Context context, PreferenceGroup root, String preferred,
                                                      @Nullable Runnable onChange, @Nullable Consumer<String> onReset) {
        PreferenceGroup group = GROUP_KEY.equals(root.getKey()) ? root : root.findPreference(GROUP_KEY);
        if (group == null) {
            Log.w(TAG, "Sensor group not found! Preferences will not be generated.");
            return null;
        }

        try {
            List<String> physicalIds = moduleSlots(preferred);
            if (physicalIds.isEmpty()) physicalIds = getSortedPhysicalIds();
            if (physicalIds.isEmpty()) {
                Log.w(TAG, "No camera ids found, cannot generate sensor config preferences.");
                addNoSensorsPreference(context, group);
                return null;
            }
            if (scanFields().isEmpty()) {
                Log.w(TAG, "No @SensorConfig fields found - UI will not be generated!");
                return null;
            }
            Map<String, PreferenceCategory> categories = new LinkedHashMap<>();
            for (String slot : physicalIds) categories.put(slot, addModuleCategory(context, group, slot, onChange, onReset));
            String selected = physicalIds.contains(preferred) ? preferred : null;
            Log.d(TAG, "Generated sensor config preferences for " + physicalIds.size() + " sensors");
            return new ModuleSelection(physicalIds, categories, selected);
        } catch (Exception e) {
            Log.e(TAG, "ERROR in generatePreferences: " + Log.getStackTraceString(e));
            return null;
        }
    }

    /**
     * P384: one module's category under {@code group} (built, or the existing one): its {@code @SensorConfig} rows, the sensor mode
     * of the camera's table, its camera keys, the frame-rate check, adding a key and its reset.
     */
    public static PreferenceCategory addModuleCategory(Context context, PreferenceGroup group, String slot,
                                                       @Nullable Runnable onChange, @Nullable Consumer<String> onReset) {
        if (ModuleRegistry.slots().contains(slot)) ModuleSensorSettings.ensure(slot);
        PreferenceCategory category = findOrCreateCategory(context, group, slot, getCameraLensMap().get(slot));
        for (TunableFieldInfo info : scanFields()) addPreference(context, category, slot, info);
        addTunableKeySection(context, category, slot, onChange);
        addResetRow(context, category, slot, onReset);
        return category;
    }

    /** P384: a module's category gone (a deleted copy, or rebuilt for another camera). */
    public static void removeModuleCategory(PreferenceGroup group, String slot) {
        androidx.preference.Preference category = group.findPreference(CATEGORY_PREFIX + slot);
        if (category != null && category.getParent() != null) category.getParent().removePreference(category);
    }

    /** P384 (was the sensor page's one reset row): «Сбросить сенсор «X»» at the end of the module's rows, asked first by the fragment. */
    private static void addResetRow(Context context, PreferenceCategory category, String slot, @Nullable Consumer<String> onReset) {
        String key = "pref_reset_sensor_config_settings_" + slot;
        if (category.findPreference(key) != null) return;
        androidx.preference.Preference reset = new androidx.preference.Preference(context);
        reset.setKey(key);
        reset.setLayoutResource(com.particlesdevs.photoncamera.R.layout.preference_card);
        reset.setTitle(context.getString(com.particlesdevs.photoncamera.R.string.module_sensor_reset,
                ModuleRegistry.slots().contains(slot) ? ModuleRegistry.label(slot) : slot));
        reset.setSummary(context.getString(com.particlesdevs.photoncamera.R.string.module_sensor_reset_summary));
        reset.setIcon(com.particlesdevs.photoncamera.R.drawable.settings_ic_reset);
        reset.setOrder(20000);
        reset.setOnPreferenceClickListener(preference -> {
            if (onReset != null) onReset.accept(slot);
            return true;
        });
        category.addPreference(reset);
    }

    private static void addTunableKeySection(Context context, PreferenceCategory category, String physicalId,
                                             @Nullable Runnable onChange) {
        // P384: the list is rebuilt only after a key was added, edited or deleted in its sheet (a switch updates its row in place)
        Runnable refresh = () -> {
            refreshTunableKeys(context, category, physicalId, onChange);
            if (onChange != null) onChange.run();
        };

        List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(context, physicalId);
        for (int i = 0; i < keys.size(); i++) {
            TunableKeyPreference keyPref = new TunableKeyPreference(context, physicalId, i, refresh, onChange);
            keyPref.setOrder(100 + i);
            category.addPreference(keyPref);
        }

        addSensorModePick(context, category, physicalId);

        // P266 «Проверить частоты кадров»: asked here, run by the next session of this module (FpsProbe)
        androidx.preference.Preference probe = new androidx.preference.Preference(context);
        probe.setKey("pref_sensorconfig_" + physicalId + "_fpsprobe");
        probe.setLayoutResource(com.particlesdevs.photoncamera.R.layout.preference_card);
        probe.setTitle(Lang.t(context, "Проверить частоты кадров", "Check the frame rates"));
        probe.setSummary(fpsProbeSummary(context, physicalId));
        probe.setIcon(com.particlesdevs.photoncamera.R.drawable.settings_ic_fps);
        probe.setOrder(9999);
        probe.setOnPreferenceClickListener(preference -> {
            com.particlesdevs.photoncamera.capture.FpsProbe.request(context, physicalId);
            preference.setSummary(fpsProbeSummary(context, physicalId));
            return true;
        });
        category.addPreference(probe);

        androidx.preference.Preference addButton = new androidx.preference.Preference(context);
        addButton.setKey("pref_sensorconfig_" + physicalId + "_add_tunablekey");
        addButton.setLayoutResource(com.particlesdevs.photoncamera.R.layout.preference_card);
        // P384: the plain name first, «vendor tag» only in the description
        addButton.setTitle(context.getString(com.particlesdevs.photoncamera.R.string.module_key_add));
        addButton.setSummary(context.getString(com.particlesdevs.photoncamera.R.string.module_key_add_summary));
        addButton.setIcon(com.particlesdevs.photoncamera.R.drawable.settings_ic_plus);
        addButton.setOrder(10000);
        addButton.setOnPreferenceClickListener(preference -> {
            TunableKeyDialog.show(context, physicalId, -1, refresh);
            return true;
        });
        category.addPreference(addButton);
    }

    /**
     * P276 «Режим сенсора (SensorModeTable)»: Auto or a row of the camera's own table ("Mode #4: 8192x6144 @ 30fps", as WhyCam
     * lists it); a picked row opens the RAW stream at its size and sends the brand's mode key (SensorModePick). Shown only when
     * the module's camera has a table.
     */
    private static void addSensorModePick(Context context, PreferenceCategory category, String slot) {
        java.util.List<int[]> rows;
        try {
            final String camera = ModuleRegistry.slots().contains(slot) ? ModuleSensorSettings.physical(slot) : toPhysicalId(slot);
            final android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            rows = com.particlesdevs.photoncamera.capture.SensorModePick.rows(manager.getCameraCharacteristics(camera));
        } catch (Exception unavailable) {
            return;
        }
        if (rows.isEmpty()) return;
        category.addPreference(sensorModeRow(context, slot, rows));
    }

    /** The «Режим сенсора» row of {@code slot} for the rows of its camera's table (P384: built apart for its bind test). */
    public static ListPreference sensorModeRow(Context context, String slot, java.util.List<int[]> rows) {
        final String[] entries = new String[rows.size() + 1], values = new String[rows.size() + 1];
        entries[0] = Lang.t(context, "Авто (по умолчанию)", "Auto (default)");
        values[0] = "-1";
        for (int i = 0; i < rows.size(); i++) {
            entries[i + 1] = com.particlesdevs.photoncamera.capture.SensorModePick.label(rows.get(i));
            values[i + 1] = String.valueOf(rows.get(i)[0]);
        }
        ListPreference pick = new ListPreference(context);
        pick.setKey(com.particlesdevs.photoncamera.capture.SensorModePick.prefKey(slot));
        pick.setTitle(ModuleSensorSettings.title("Sensor Mode"));
        pick.setDialogTitle(ModuleSensorSettings.title("Sensor Mode"));
        pick.setEntries(entries);
        pick.setEntryValues(values);
        pick.setDefaultValue("-1");
        pick.setIcon(com.particlesdevs.photoncamera.R.drawable.settings_ic_sensor);
        pick.setOrder(99);
        // P316 (settings audit): the ISP stream stays out only with «Поток ISP» Auto (or Off); on OPPO / OnePlus / realme a picked
        // row's shots come from the ring alone (P315 SensorModePick.seriesAllowed), which the row says
        // P384: the picked row is the value on the right (it was the summary's first line too, and the title said SensorModeTable);
        // the restart pill says when it applies (SessionKeys: a new session)
        final String hint = context.getString(com.particlesdevs.photoncamera.R.string.module_sensor_mode_hint)
                + (com.particlesdevs.photoncamera.capture.SensorModePick.pickTakesRingOnly() ? " " + Lang.t(context,
                        "OPPO / OnePlus / realme: снимок только из буфера — без кадров светов и теней, ручной длинной выдержки и подстройки под мерцание ламп.",
                        "OPPO / OnePlus / realme: the shot comes from the ring only - no highlight or shadow frames, no manual long shutter, no lamp-flicker timing.") : "");
        pick.setSummaryProvider(p -> hint);
        return pick;
    }

    /** The probe's row: asked (runs on the camera), the last result, or what it does. */
    static String fpsProbeSummary(Context context, String slot) {
        if (com.particlesdevs.photoncamera.capture.FpsProbe.requested(context, slot))
            return Lang.t(context, "Откройте камеру с этим модулем: проверка займёт несколько секунд",
                    "Open the camera on this module: the check takes a few seconds");
        final String last = com.particlesdevs.photoncamera.capture.FpsProbe.lastResult(context, slot);
        return last != null ? Lang.t(context, "Запрошено → получено к/с (режим сенсора): ", "Asked → delivered fps (sensor mode): ") + last
                : Lang.t(context, "Каждая частота на секунду: что камера реально даёт и какой режим сенсора выбирает",
                "Each rate for a second: what the camera really delivers and which sensor mode it picks");
    }

    private static void refreshTunableKeys(Context context, PreferenceCategory category, String physicalId,
                                           @Nullable Runnable onChange) {
        List<androidx.preference.Preference> toRemove = new ArrayList<>();
        for (int i = 0; i < category.getPreferenceCount(); i++) {
            androidx.preference.Preference p = category.getPreference(i);
            // P384: the sensor mode row too - the section adds it again (a second row of one key after each key edit)
            if (p instanceof TunableKeyPreference || ("pref_sensorconfig_" + physicalId + "_add_tunablekey").equals(p.getKey())
                    || ("pref_sensorconfig_" + physicalId + "_fpsprobe").equals(p.getKey())
                    || com.particlesdevs.photoncamera.capture.SensorModePick.prefKey(physicalId).equals(p.getKey())) {
                toRemove.add(p);
            }
        }
        for (androidx.preference.Preference p : toRemove) {
            category.removePreference(p);
        }
        addTunableKeySection(context, category, physicalId, onChange);
    }

    private static void addNoSensorsPreference(Context context, PreferenceGroup submenu) {
        try {
            androidx.preference.Preference info = new androidx.preference.Preference(context);
            info.setKey("pref_sensor_config_no_sensors");
            info.setTitle(Lang.t(context, "Сенсоры не найдены", "No sensors found"));
            info.setSummary(Lang.t(context, "Камеры ещё не определены. Откройте видоискатель и вернитесь в это меню.",
                    "Cameras are not detected yet. Open the viewfinder and come back to this menu."));
            info.setSelectable(false);
            if (submenu.findPreference(info.getKey()) == null) submenu.addPreference(info);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add no-sensors message: " + Log.getStackTraceString(e));
        }
    }

    /**
     * Resolve the sorted list of available physical camera ids from the scanned cameras preference.
     */
    private static List<String> getSortedPhysicalIds() {
        Set<String> sensorIds = getAvailableSensorIds();
        if (sensorIds == null || sensorIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> physicalIds = new ArrayList<>();
        for (String id : sensorIds) {
            String physicalId = toPhysicalId(id);
            if (!physicalIds.contains(physicalId)) {
                physicalIds.add(physicalId);
            }
        }
        physicalIds.sort((a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        });
        return physicalIds;
    }

    /**
     * Resolve the set of available physical camera ids from the scanned cameras preference.
     */
    private static Set<String> getAvailableSensorIds() {
        try {
            SettingsManager settingsManager = com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic();
            if (settingsManager == null) {
                Log.w(TAG, "SettingsManager is null, cannot read camera ids");
                return null;
            }
            Set<String> ids = settingsManager.getStringSet(
                    PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME.mValue,
                    PreferenceKeys.Key.ALL_CAMERA_IDS_KEY, null);
            if (ids == null) return null;
            java.util.LinkedHashSet<String> physical = new java.util.LinkedHashSet<>();
            for (String id : ids) {
                physical.add(toPhysicalId(id));
            }
            return physical;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read camera ids: " + Log.getStackTraceString(e));
            return null;
        }
    }

    private static List<TunableFieldInfo> scanFields() {
        List<TunableFieldInfo> fields = new ArrayList<>();
        for (Class<?> clazz : SensorConfigRegistry.SENSOR_CONFIG_CLASSES) {
            scanClass(clazz, fields);
        }
        // getDeclaredFields() has no defined order: sort, so related rows stay together on every device
        fields.sort(java.util.Comparator.comparingInt(info -> rank(info.fieldName)));
        return fields;
    }

    /** Position of a {@code @SensorConfig} field on the page (and in the module copy list); unknown fields last. */
    public static int rank(String fieldName) {
        int i = FIELD_ORDER.indexOf(fieldName);
        return i < 0 ? FIELD_ORDER.size() : i;
    }

    /**
     * Strip the logical part of a "logical-physical" id (e.g. "0-1" -> "1").
     */
    public static String toPhysicalId(String id) {
        if (id != null && id.contains("-")) {
            return id.split("-")[1];
        }
        return id;
    }

    private static void scanClass(Class<?> clazz, List<TunableFieldInfo> fields) {
        String className = clazz.getSimpleName();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(SensorConfig.class)) {
                SensorConfig annotation = field.getAnnotation(SensorConfig.class);
                if (annotation == null) continue;

                TunableFieldInfo info = new TunableFieldInfo();
                info.className = className;
                info.fieldName = field.getName();
                info.fieldType = field.getType();
                info.annotation = annotation;
                fields.add(info);
            }
        }
    }

    private static PreferenceCategory findOrCreateCategory(Context context, PreferenceGroup screen, String sensorId, CameraLensData lens) {
        String categoryKey = CATEGORY_PREFIX + sensorId;

        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            if (screen.getPreference(i) instanceof PreferenceCategory) {
                PreferenceCategory cat = (PreferenceCategory) screen.getPreference(i);
                if (categoryKey.equals(cat.getKey())) {
                    return cat;
                }
            }
        }

        PreferenceCategory category = new PreferenceCategory(context);
        category.setKey(categoryKey);
        category.setTitle(buildCategoryTitle(sensorId, lens));
        // P384: the chosen module's caption under the chips, not a group title of the page
        category.setLayoutResource(com.particlesdevs.photoncamera.R.layout.preference_subcategory_layout);
        category.setIconSpaceReserved(false);
        screen.addPreference(category);

        return category;
    }

    private static String buildCategoryTitle(String physicalId, CameraLensData lens) {
        if(ModuleRegistry.slots().contains(physicalId))return ModuleRegistry.label(physicalId)+" · ID "+ModuleRegistry.camera(physicalId)+(ModuleRegistry.visible(physicalId)?"":Lang.t(" · скрыт"," · hidden"));
        StringBuilder title = new StringBuilder(Lang.t("Сенсор ", "Sensor ")).append(physicalId);
        if (lens != null) {
            if (lens.getCameraId() != null) {
                title.append(" \u00b7 ID ").append(lens.getCameraId());
            }
            title.append(" \u00b7 ").append(String.format(Locale.ROOT, "%.1fx", lens.getZoomFactor()));
        }
        return title.toString();
    }

    /**
     * Load a map of physical id -> lens data from the scanned cameras preference.
     */
    private static Map<String, CameraLensData> getCameraLensMap() {
        Map<String, CameraLensData> lensMap = new HashMap<>();
        try {
            SettingsManager settingsManager = com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic();
            if (settingsManager == null) return lensMap;
            Set<String> jsonSet = settingsManager.getStringSet(
                    PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME.mValue,
                    PreferenceKeys.Key.ALL_CAMERA_LENS_KEY, null);
            if (jsonSet == null) return lensMap;
            Gson gson = new Gson();
            for (String json : jsonSet) {
                try {
                    CameraLensData data = gson.fromJson(json, CameraLensData.class);
                    if (data != null && data.getCameraId() != null) {
                        lensMap.put(toPhysicalId(data.getCameraId()), data);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read camera lens data: " + Log.getStackTraceString(e));
        }
        return lensMap;
    }

    private static void addPreference(Context context, PreferenceCategory category, String sensorId, TunableFieldInfo info) {
        // Skip OIS configuration for sensors that do not physically support hardware OIS
        if ("oisMode".equalsIgnoreCase(info.fieldName) && !isOisSupported(context, ModuleSensorSettings.physical(sensorId))) {
            return;
        }

        SensorConfig annotation = info.annotation;
        String prefKey = "pref_sensorconfig_" + sensorId + "_" + info.fieldName.toLowerCase(java.util.Locale.ROOT);

        // Heal preference type in background if corrupted by config import
        ensureStringPreference(context, prefKey);

        if (annotation.entries().length > 0 && annotation.entryValues().length > 0) {
            addListPreference(context, category, prefKey, info);
            return;
        }

        if (annotation.step() == 0f) {
            addFreeTextPreference(context, category, prefKey, info);
            return;
        }

        TunableSeekBarPreference seekBar = new TunableSeekBarPreference(context);
        seekBar.setKey(prefKey);
        seekBar.setTitle(ModuleSensorSettings.title(annotation.title()));

        if (!annotation.description().isEmpty()) {
            seekBar.setSummary(ModuleSensorSettings.description(annotation.description()));
        }

        seekBar.setMinValue(annotation.min());
        seekBar.setMaxValue(annotation.max());

        float step = annotation.step();
        boolean isFloat = PreferenceNumber.floating(info.fieldType);
        seekBar.setIsFloat(isFloat);

        float stepsPerUnit = 1.0f / step;
        seekBar.setStepPerUnit(stepsPerUnit);

        float defaultValue = getFieldDefaultValue(info);
        seekBar.setDefaultValue(defaultValue);
        rawLevelRow(context, seekBar, sensorId, info.fieldName, prefKey);

        category.addPreference(seekBar);
    }

    /**
     * P149: the module's own RAW levels for the «Уровень чёрного / белого» rows: {black, white, bits} of the module's camera
     * (characteristics: the black pattern's largest value, the white level), null when the camera is unknown. Tests set it.
     */
    static java.util.function.BiFunction<Context, String, int[]> cameraLevels = SensorConfigPreferenceGenerator::readCameraLevels;

    private static int[] readCameraLevels(Context context, String physicalId) {
        try {
            android.hardware.camera2.CameraManager manager = context.getSystemService(android.hardware.camera2.CameraManager.class);
            if (manager == null || physicalId == null) return null;
            android.hardware.camera2.CameraCharacteristics c = manager.getCameraCharacteristics(physicalId);
            final int white = com.particlesdevs.photoncamera.processing.RawLevels.staticWhite(c);
            final float[] black = com.particlesdevs.photoncamera.processing.RawLevels.staticBlack(c);
            if (white <= 0) return null;
            float b = 0;
            if (black != null) for (float v : black) b = Math.max(b, v);
            return new int[]{Math.round(b), white, com.particlesdevs.photoncamera.processing.RawLevels.bitsOfWhite(white)};
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * P149: «Авто» for -1 in the value pill, the module's own levels under the description, and a range in that module's RAW
     * units: black up to a quarter of its white (and 4x its black), white up to four bits above its white (streams wider than
     * declared, P127) - instead of 0..8192 / 0..65535, where one step of the bar was hundreds of levels. A stored value outside
     * that range widens it (it is what the shot uses).
     */
    private static void rawLevelRow(Context context, TunableSeekBarPreference seekBar, String slot, String field, String prefKey) {
        final boolean black = "blackLevelOverride".equals(field);
        if (!black && !"whiteLevelOverride".equals(field)) return;
        seekBar.setAutoValue(-1f, Lang.t("Авто", "Auto"));
        final int[] camera = cameraLevels.apply(context, ModuleSensorSettings.physical(slot));
        if (camera == null) return;
        float max = black ? Math.max(4f * camera[0], (camera[1] + 1) / 4f) : Math.min(65535f, (camera[1] + 1) * 16f - 1f);
        try {
            float stored = (float) PreferenceNumber.read(PreferenceValue.get(
                    PreferenceManager.getDefaultSharedPreferences(context), prefKey), -1);
            if (Float.isFinite(stored)) max = Math.max(max, stored);
        } catch (Exception ignored) {
        }
        seekBar.setMaxValue((float) Math.ceil(max));
        CharSequence summary = seekBar.getSummary();
        seekBar.setSummary((summary == null ? "" : summary + "\n") + String.format(Locale.ROOT,
                Lang.t("Авто у этого модуля: чёрный %d, белый %d (%d бит)", "Auto for this module: black %d, white %d (%d bit)"),
                camera[0], camera[1], camera[2]));
    }

    private static float getFieldDefaultValue(TunableFieldInfo info) {
        float annotationDefault = info.annotation.defaultValue();
        if (annotationDefault != -999999f) {
            return annotationDefault;
        }
        return info.annotation.min();
    }

    /**
     * Creates a plain text input preference (no slider) when the annotation step is 0.
     * The value is stored as a String and parsed back to the field type on injection.
     */
    private static void addFreeTextPreference(Context context, PreferenceCategory category, String prefKey, TunableFieldInfo info) {
        SensorConfig annotation = info.annotation;
        float defaultValue = getFieldDefaultValue(info);
        Class<?> fieldType = info.fieldType;

        // P384: the value on the right is the one the camera uses - the default while none is stored (it showed «—» there and
        // the value again under the description)
        final String shownDefault = formatDefault(defaultValue, fieldType);
        EditTextPreference editText = new EditTextPreference(context) {
            @Override public String getText() {
                String text = super.getText();
                return text == null ? shownDefault : text;
            }
        };
        editText.setKey(prefKey);
        editText.setTitle(ModuleSensorSettings.title(annotation.title()));
        editText.setDialogTitle(ModuleSensorSettings.title(annotation.title()));
        editText.setIconSpaceReserved(false);
        Integer icon = FIELD_ICONS.get(info.fieldName.toLowerCase(Locale.ROOT));
        if (icon != null) editText.setIcon(icon);

        int inputType;
        if (fieldType == float.class || fieldType == Float.class
                || fieldType == double.class || fieldType == Double.class) {
            inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        } else if (fieldType == int.class || fieldType == Integer.class
                || fieldType == long.class || fieldType == Long.class
                || fieldType == short.class || fieldType == Short.class
                || fieldType == byte.class || fieldType == Byte.class) {
            inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
        } else {
            inputType = InputType.TYPE_CLASS_TEXT;
        }

        editText.setOnBindEditTextListener(edit -> {
            edit.setInputType(inputType);
            if (editText.getText() == null) {
                edit.setText(formatDefault(defaultValue, fieldType));
            }
        });

        editText.setOnPreferenceChangeListener((preference, input) -> {
            if (fieldType == String.class) return true;
            double number = PreferenceNumber.read(input, Double.NaN);
            boolean valid = Double.isFinite(number) && number >= annotation.min() && number <= annotation.max()
                    && (PreferenceNumber.floating(fieldType) || number == Math.rint(number));
            if (!valid) android.widget.Toast.makeText(context, Lang.t(context, "Число от " + annotation.min() + " до " + annotation.max(),
                    "Number from " + annotation.min() + " to " + annotation.max()), android.widget.Toast.LENGTH_LONG).show();
            return valid;
        });

        String description = ModuleSensorSettings.description(annotation.description());
        editText.setSummaryProvider(preference -> description.isEmpty() ? editText.getText() : description);

        category.addPreference(editText);
    }

    /**
     * Creates a ListPreference (dropdown/dialog list) when the annotation provides entries and entryValues.
     */
    private static void addListPreference(Context context, PreferenceCategory category, String prefKey, TunableFieldInfo info) {
        SensorConfig annotation = info.annotation;
        ListPreference listPref = new ListPreference(context);
        listPref.setKey(prefKey);
        listPref.setTitle(ModuleSensorSettings.title(annotation.title()));
        listPref.setDialogTitle(ModuleSensorSettings.title(annotation.title()));

        listPref.setEntries(annotation.entries());
        listPref.setEntryValues(annotation.entryValues());

        // Resolve default value from annotation defaultValue
        String defaultValue = annotation.entryValues()[0];
        if (annotation.defaultValue() != -999999f) {
            float def = annotation.defaultValue();
            for (String val : annotation.entryValues()) {
                try {
                    if (Float.parseFloat(val) == def) {
                        defaultValue = val;
                        break;
                    }
                } catch (NumberFormatException ignored) {
                    if (val.equals(String.valueOf(def))) {
                        defaultValue = val;
                        break;
                    }
                }
            }
        }
        // No entryValue matched the annotation default, so fall back to the first one.
        // Leaving this null means the preference is added with no value at all, and
        // SimpleSummaryProvider throws NullPointerException the moment the row is bound.
        if (defaultValue == null && annotation.entryValues().length > 0) {
            defaultValue = annotation.entryValues()[0];
        }
        listPref.setDefaultValue(defaultValue);
        listPref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        Integer icon = FIELD_ICONS.get(info.fieldName.toLowerCase(Locale.ROOT));
        if (icon != null) listPref.setIcon(icon);

        category.addPreference(listPref);
        if (listPref.getValue() == null && defaultValue != null) {
            listPref.setValue(defaultValue);
        }
    }

    /**
     * Checks if the physical camera sensor supports hardware Optical Image Stabilization (OIS).
     */
    private static boolean isOisSupported(Context context, String sensorId) {
        try {
            android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) {
                android.hardware.camera2.CameraCharacteristics chars = manager.getCameraCharacteristics(sensorId);
                int[] modes = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                return CaptureController.isOisSupported(modes);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String formatDefault(float defaultValue, Class<?> fieldType) {
        if (fieldType == float.class || fieldType == Float.class
                || fieldType == double.class || fieldType == Double.class) {
            return String.valueOf(defaultValue);
        }
        return String.valueOf((int) defaultValue);
    }

    private static class TunableFieldInfo {
        String className;
        String fieldName;
        Class<?> fieldType;
        SensorConfig annotation;
    }
    /**
     * Inspects the SharedPreferences entry for the given key and ensures it is stored 
     * as a String. If it contains a mismatched type (like Integer due to config import), 
     * it automatically heals it on the fly to prevent ClassCastException in ListPreference/EditTextPreference.
     */
    private static void ensureStringPreference(Context context, String key) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.contains(key)) {
            try {
                // Try reading as a String to verify correct type
                prefs.getString(key, null);
            } catch (ClassCastException e) {
                Log.w(TAG, "Mismatched type detected for key: " + key + ". Healing to String.");
                try {
                    // Attempt to heal if stored as Integer
                    int intVal = prefs.getInt(key, -999999);
                    if (intVal != -999999) {
                        prefs.edit().putString(key, String.valueOf(intVal)).apply();
                        return;
                    }
                } catch (Exception ignored) {}
                try {
                    // Attempt to heal if stored as Float
                    float floatVal = prefs.getFloat(key, -999999f);
                    if (floatVal != -999999f) {
                        prefs.edit().putString(key, String.valueOf(floatVal)).apply();
                        return;
                    }
                } catch (Exception ignored) {}
                try {
                    // Attempt to heal if stored as Boolean
                    boolean boolVal = prefs.getBoolean(key, false);
                    prefs.edit().putString(key, boolVal ? "1" : "0").apply();
                } catch (Exception ignored) {}
            }
        }
    }
}