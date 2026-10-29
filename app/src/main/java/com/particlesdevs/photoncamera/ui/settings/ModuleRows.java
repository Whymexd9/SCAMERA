package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.FullResTrials;
import com.particlesdevs.photoncamera.settings.FullResProfiles;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.ModuleSensorSettings;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SensorConfigPreferenceGenerator;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleChipsPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleRowPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * P384 (owner, 2026-10-28: «Добавь в план до пуша: Переместить эту группу настроек на главный экран, переделай это меню по-своему
 * чтобы им было очень легко и просто пользоваться и чтобы в нём было легко разобраться. Предлагаю сделать отдельную группу где можно
 * будет вписывать айди модулей, отдельную группу для настроек с второго скриншота. А то что на третьем скриншоте сделать как
 * отдельную группу настроек на главной странице разделенную по модулям»): the three camera groups of the settings' main page, built
 * in code from ModuleRegistry as real preferences (P6b: search, page reset, availability and the cards keep working) and kept in
 * sync with it -
 * <ul>
 * <li>«Модули камеры» (root_modules): one card per module (ModuleSection, one open at a time) with the module's name, zoom, viewfinder
 *     button, copies and its own photo settings (the former «Настройки по модулям», so no module is listed twice), then adding a
 *     copy, the per-module switch and copying settings;</li>
 * <li>«Camera ID модулей» (expert_module_ids): a row per module, its ID and where it comes from; a tap opens CameraIdSheet;</li>
 * <li>«Сенсор по модулям» (expert_module_sensor): module chips over the sensor categories (SensorConfigPreferenceGenerator).</li>
 * </ul>
 * Every row stores nothing itself: the dialogs (ModuleActions) write the module keys as the module pages wrote them.
 */
public final class ModuleRows {
    /** What the rows need from their page. */
    public interface Host {
        Context context();

        /** Open a page over the settings (slide in, onto the back stack). */
        void open(Fragment page, String name);

        /** The settings recreated (the main settings took another profile). */
        void recreate();

        @Nullable RecyclerView list();

        /** The page's arguments: the open card and the chosen sensor module survive a recreate there. */
        Bundle args();

        /** The page's header (its chip says which module's settings the page edits). */
        void headerChanged();

        /** «Сбросить сенсор «X»»: asked, reset, the page built again (the sensor rows of a new copy's category too). */
        void resetSensor(String slot);
    }

    static final String GROUP = "root_modules", IDS = "expert_module_ids", SENSOR = SensorConfigPreferenceGenerator.GROUP_KEY;
    /** P390 (owner, 2026-10-28: «Положи эти настройки в одну группу»): the page of the three groups, one row on the main page. */
    static final String PAGE = "modules_screen";
    /**
     * P413 (owner, 2026-10-30: «Сделай настройки линз аналогично Арк, убедись что настройки нигде не дублируются»): one module's
     * lens page (sensor, RAW format, levels, colour matrix, noise model), opened from its card as the reference app's lens button.
     */
    static final String LENS = "lens_screen";
    /** The open card's slot ("" for none), in the page's arguments. */
    static final String ARG_OPEN_MODULE = "modules_open";
    static final String CARD = "module_card_", NAME = "module_row_name_", ZOOM = "module_row_zoom_", BUTTON = "module_row_button_",
            CROP = "module_row_crop_", COPY = "module_row_copy_", DELETE = "module_row_delete_", PROFILE = "module_row_profile_",
            TRIALS = "module_row_trials_", ID = "module_row_id_", LENS_ROW = "module_row_lens_", CARD_ID = "module_row_card_id_";
    static final String ACTIONS = "module_actions", ADD = "module_row_add_copy", PER_LENS = "module_row_per_lens",
            COPY_SETTINGS = "module_copy_settings", NONE = "module_row_none", PICKER = "module_sensor_picker";

    private final Host host;
    @Nullable private final String profile;
    @Nullable private final SensorConfigPreferenceGenerator.ModuleSelection sensors;
    @Nullable private final PreferenceGroup group, ids, sensorGroup;
    @Nullable private ModuleChipsPreference chips;
    @Nullable private Preference none;
    @Nullable private ModuleRowPreference add;
    @Nullable private SwitchPreferenceCompat perLens;
    private final Map<String, ModuleSection> cards = new LinkedHashMap<>();
    /** The card's rows as built (copy, its source, its FULLRES profiles, its trials): another one rebuilds the rows. */
    private final Map<String, String> built = new HashMap<>();
    private final Map<String, ModuleRowPreference> idRows = new LinkedHashMap<>();
    /** Rows this code hides (the page's availability pass would show them again). */
    private final Set<String> hidden = new HashSet<>();

    private ModuleRows(Host host, PreferenceScreen full, @Nullable String profile,
                       @Nullable SensorConfigPreferenceGenerator.ModuleSelection sensors) {
        this.host = host;
        this.profile = profile;
        this.sensors = sensors;
        group = full.findPreference(GROUP);
        ids = full.findPreference(IDS);
        sensorGroup = full.findPreference(SENSOR);
    }

    /**
     * The rows of a root page. On a module profile's root ({@code profile}) only its sensor category is shown (ModulePageFilter
     * hides the other two groups); the main page gets all three.
     */
    static ModuleRows build(Host host, PreferenceScreen full, @Nullable String profile,
                            @Nullable SensorConfigPreferenceGenerator.ModuleSelection sensors) {
        ModuleRows rows = new ModuleRows(host, full, profile, sensors);
        rows.create();
        rows.sync();
        String open = host.args().getString(ARG_OPEN_MODULE, "");
        ModuleSection card = rows.cards.get(open);
        if (card != null) card.setExpanded(true);
        return rows;
    }

    private static SharedPreferences prefs() {
        return ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
    }

    /** The keys this code hides, for the page's availability pass. */
    boolean hides(@Nullable String key) {
        return key != null && hidden.contains(key);
    }

    private void hide(Preference p, boolean hide) {
        if (p.getKey() != null) {
            if (hide) hidden.add(p.getKey());
            else hidden.remove(p.getKey());
        }
        if (p.isVisible() == hide) p.setVisible(!hide);
    }

    // ───── texts of a module (pure: the module keys) ─────

    /** The card's badge: the button's zoom («0.6×», «1.3×»). */
    static String badge(String slot) {
        return ModuleActions.cropText(ModuleRegistry.zoom(slot)) + "×";
    }

    /**
     * What the module is: the front camera, a copy of another, else by its lens's own zoom (the camera's measured field of view
     * where known, so a 2× ISZ module of the main lens is the main camera) - ultra-wide under 0.9×, main under 1.6×, telephoto.
     */
    static String kind(Context c, String slot) {
        if (slot.startsWith("front")) return c.getString(R.string.module_kind_front);
        String source = ModuleRegistry.duplicateOf(slot);
        if (source != null) return c.getString(R.string.module_kind_copy, ModuleRegistry.label(source));
        float lens = 0f;
        try { lens = prefs().getFloat("module_fov_" + ModuleRegistry.camera(slot), 0f); } catch (ClassCastException ignored) { }
        if (lens <= 0f) lens = ModuleRegistry.nativeRatio(slot);
        return c.getString(lens < 0.9f ? R.string.module_kind_ultrawide : lens < 1.6f ? R.string.module_kind_main : R.string.module_kind_tele);
    }

    /**
     * The card's title: the module's own name, else what it is. A copy's automatic name (its zoom «1.3×», or «1× (2)») is no name of
     * its own: the badge shows the zoom already.
     */
    static String cardTitle(Context c, String slot) {
        String name = prefs().getString("module_name_" + slot, "").trim();
        String source = ModuleRegistry.duplicateOf(slot);
        if (source != null && (name.equals(badge(slot)) || name.equals(ModuleRegistry.label(source) + " (2)")
                || name.equals(ModuleActions.cropText(ModuleRegistry.zoom(slot)) + "×"))) name = "";
        return name.isEmpty() ? kind(c, slot) : name;
    }

    /** «Сейчас в камере · кроп 1.3× · скрыта в видоискателе · ID 0». */
    static String cardSummary(Context c, String slot) {
        List<String> parts = new ArrayList<>();
        if (slot.equals(ModuleRegistry.active())) parts.add(c.getString(R.string.module_state_active));
        if (ModuleRegistry.sensorCrop(slot)) parts.add(c.getString(R.string.module_state_sensor_crop));
        else if (ModuleRegistry.crop(slot) > 1.0001f) parts.add(c.getString(R.string.module_state_crop, ModuleActions.cropText(ModuleRegistry.crop(slot))));
        if (!ModuleRegistry.visible(slot)) parts.add(c.getString(R.string.module_state_hidden));
        parts.add(c.getString(R.string.module_id_value, ModuleRegistry.camera(slot)));
        return String.join(" · ", parts);
    }

    /**
     * Where the module's Camera ID comes from: «Авто» (found by SCAMERA) or «Выбран вами»; a copy's «Как у «X»» while it opens its
     * source's camera. « · телефон не отдаёт эту камеру» when the phone lists cameras but does not describe this one (not checked
     * with an empty list: the early start, the tests).
     */
    static String idState(Context c, String slot) {
        String manual = prefs().getString("module_id_" + slot, "").trim();
        String source = ModuleRegistry.duplicateOf(slot);
        String state;
        if (source == null) state = c.getString(manual.isEmpty() ? R.string.module_id_auto : R.string.module_id_chosen);
        else state = manual.equals(ModuleRegistry.camera(source)) ? c.getString(R.string.module_id_as_source, ModuleRegistry.label(source))
                : c.getString(R.string.module_id_chosen);
        if (missing(c, ModuleRegistry.camera(slot))) state += c.getString(R.string.module_id_missing);
        return state;
    }

    private static boolean missing(Context c, String id) {
        try {
            CameraManager cm = c.getSystemService(CameraManager.class);
            if (cm == null || cm.getCameraIdList().length == 0) return false;
            cm.getCameraCharacteristics(id.contains("-") ? id.substring(id.indexOf('-') + 1) : id);
            return false;
        } catch (IllegalArgumentException unknown) {
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /** The chip of a module in «Сенсор по модулям»: its zoom, «· фронт» for the front ones. */
    static String chipLabel(Context c, String slot) {
        return slot.startsWith("front") ? c.getString(R.string.module_chip_front, badge(slot)) : badge(slot);
    }

    // ───── the groups ─────

    private void create() {
        Context c = host.context();
        if (profile == null && group != null) {
            none = new Preference(c);
            none.setKey(NONE);
            none.setSelectable(false);
            none.setPersistent(false);
            none.setSummary(c.getString(R.string.module_rows_none));
            none.setOrder(-1);
            add(group, none);
            PreferenceCategory actions = new PreferenceCategory(c);
            actions.setKey(ACTIONS);
            actions.setOrder(1000);
            actions.setPersistent(false);
            add(group, actions);
            add = row(c, ADD, R.drawable.settings_ic_plus, c.getString(R.string.module_row_add), null);
            add.setOnPreferenceClickListener(p -> {
                List<String> sources = new ArrayList<>();
                for (String slot : SensorConfigPreferenceGenerator.moduleSlots(null)) if (ModuleRegistry.visible(slot)) sources.add(slot);
                ModuleActions.addCopy(host.context(), sources, this::created);
                return true;
            });
            add(actions, add);
            perLens = new SwitchPreferenceCompat(c);
            perLens.setKey(PER_LENS);
            // not persistent on purpose: the stored key may be a "0" / "1" string of an old config (SettingsManager reads both),
            // which a persistent switch would read as a boolean and throw
            perLens.setPersistent(false);
            perLens.setDefaultValue(PreferenceKeys.isPerLensSettingsOn());
            perLens.setTitle(c.getString(R.string.module_row_per_lens));
            perLens.setIcon(R.drawable.settings_ic_lenses);
            perLens.setOnPreferenceChangeListener((p, value) -> {
                ModuleActions.perLensSwitch(Boolean.TRUE.equals(value));
                // the main settings may take another profile: the page is built again (the open card is in its arguments)
                host.recreate();
                return true;
            });
            add(actions, perLens);
            ModuleRowPreference copy = row(c, COPY_SETTINGS, R.drawable.settings_ic_copy, c.getString(R.string.module_copy_settings),
                    c.getString(R.string.module_copy_settings_summary));
            add(actions, copy);   // opened by SettingsFragment.onPreferenceTreeClick (ModuleCopyFragment)
        }
        if (sensorGroup != null && sensors != null) {
            chips = new ModuleChipsPreference(c);
            chips.setKey(PICKER);
            chips.setOrder(-1);
            chips.setOnPick(slot -> {
                sensors.select(slot);
                host.args().putString(SettingsActivity.SettingsFragment.ARG_SENSOR_SLOT, slot == null ? "" : slot);
                RecyclerView list = host.list();
                SettingsMotion.tick(list);
            });
            add(sensorGroup, chips);
        }
    }

    /** {@code p} styled for its place, then added (a row's layout is fixed once the list has seen it). */
    private static void add(PreferenceGroup parent, Preference p) {
        SettingsStyle.style(p, parent);
        parent.addPreference(p);
    }

    private static ModuleRowPreference row(Context c, String key, int icon, CharSequence title, @Nullable CharSequence summary) {
        ModuleRowPreference p = new ModuleRowPreference(c);
        p.setKey(key);
        p.setIcon(icon);
        p.setTitle(title);
        p.setSummary(summary);
        return p;
    }

    /**
     * The groups as the module keys are now: new modules' cards, Camera ID rows and sensor categories added, gone ones removed,
     * every title, summary and value set again (each setter binds its row only when it changed). On resume, after each action and
     * after a camera key changed.
     */
    void sync() {
        Context c = host.context();
        List<String> slots = profile == null ? SensorConfigPreferenceGenerator.moduleSlots(null) : Collections.emptyList();
        if (group != null && profile == null) syncCards(c, slots);
        if (ids != null && profile == null) syncIds(c, slots);
        syncSensors(c);
    }

    private void syncCards(Context c, List<String> slots) {
        if (none != null) hide(none, !slots.isEmpty());
        for (String slot : new ArrayList<>(cards.keySet())) {
            if (slots.contains(slot)) continue;
            ModuleSection gone = cards.remove(slot);
            built.remove(slot);
            if (gone != null) group.removePreference(gone);
            if (slot.equals(host.args().getString(ARG_OPEN_MODULE))) host.args().putString(ARG_OPEN_MODULE, "");
        }
        Set<String> trialCameras = new HashSet<>();
        for (int i = 0; i < slots.size(); i++) {
            String slot = slots.get(i);
            ModuleSection card = cards.get(slot);
            if (card == null) {
                card = new ModuleSection(c, slot);
                card.setKey(CARD + slot);
                final ModuleSection section = card;
                card.setOnPreferenceClickListener(p -> { toggle(section); return true; });
                card.setToggle(() -> toggle(section));
                add(group, card);
                cards.put(slot, card);
            }
            card.setOrder(i);
            String camera = ModuleRegistry.camera(slot);
            boolean trials = trialCameras.add(camera) && FullResTrials.status(camera) != null;
            String shape = ModuleRegistry.duplicateOf(slot) + "|" + FullResProfiles.variants(slot) + "|" + (trials ? camera : "");
            if (!shape.equals(built.get(slot))) {
                card.removeAll();
                buildCard(c, card, slot, trials ? camera : null);
                built.put(slot, shape);
            }
            card.setTitle(cardTitle(c, slot));
            card.setSummary(cardSummary(c, slot));
            card.setBadge(badge(slot), slot.equals(ModuleRegistry.active()), !ModuleRegistry.visible(slot));
            updateCard(c, card, slot);
        }
        if (add != null) {
            int free = 0;
            for (String slot : ModuleRegistry.slots()) if (ModuleRegistry.freeSlot(slot)) free++;
            add.setSummary(c.getString(R.string.module_row_add_summary) + (free > 0 ? c.getString(R.string.module_row_free, free) : ""));
            hide(add, slots.isEmpty());
        }
        if (perLens != null) {
            boolean on = PreferenceKeys.isPerLensSettingsOn();
            if (perLens.isChecked() != on) perLens.setChecked(on);
            perLens.setSummary(c.getString(on ? R.string.module_row_per_lens_on : R.string.module_row_per_lens_off));
        }
    }

    /** A card's rows: name, zoom, the viewfinder button, a copy's crop, copying, a copy's delete, its own photo settings, FULLRES trials. */
    private void buildCard(Context c, ModuleSection card, String slot, @Nullable String trialsCamera) {
        String source = ModuleRegistry.duplicateOf(slot);
        ModuleRowPreference name = row(c, NAME + slot, R.drawable.settings_ic_tag, c.getString(R.string.module_row_name), null);
        name.setOnPreferenceClickListener(p -> { ModuleActions.rename(host.context(), slot, this::sync); return true; });
        add(card, name);
        ModuleRowPreference zoom = row(c, ZOOM + slot, R.drawable.settings_ic_zoom, c.getString(R.string.module_row_zoom), null);
        zoom.setOnPreferenceClickListener(p -> { ModuleActions.zoomDialog(host.context(), slot, this::sync); return true; });
        add(card, zoom);
        // P413: the module's Camera ID in its card (was the separate «Camera ID модулей» list)
        ModuleRowPreference idRow = row(c, CARD_ID + slot, R.drawable.settings_ic_id, c.getString(R.string.module_row_id), null);
        idRow.setOnPreferenceClickListener(p -> { CameraIdSheet.show(host.context(), slot, this::sync); return true; });
        add(card, idRow);
        SwitchPreferenceCompat button = new SwitchPreferenceCompat(c);
        button.setKey(BUTTON + slot);
        button.setPersistent(false);
        // before it is attached: a non-persistent switch takes its default as its state (false without one)
        button.setDefaultValue(ModuleRegistry.visible(slot));
        button.setTitle(c.getString(R.string.module_row_button));
        button.setOnPreferenceChangeListener((p, value) -> {
            prefs().edit().putBoolean("module_visible_" + slot, Boolean.TRUE.equals(value)).apply();
            p.setIcon(Boolean.TRUE.equals(value) ? R.drawable.settings_ic_eye : R.drawable.settings_ic_eye_off);
            p.setSummary(c.getString(Boolean.TRUE.equals(value) ? R.string.module_row_button_on : R.string.module_row_button_off));
            if (host.list() != null) host.list().post(this::sync); else sync();
            return true;
        });
        add(card, button);
        if (source != null) {
            ModuleRowPreference crop = row(c, CROP + slot, R.drawable.settings_ic_crop, c.getString(R.string.module_row_crop), null);
            crop.setOnPreferenceClickListener(p -> { ModuleActions.cropCopy(host.context(), slot, this::sync); return true; });
            add(card, crop);
        }
        ModuleRowPreference copy = row(c, COPY + slot, R.drawable.settings_ic_plus, c.getString(R.string.module_row_copy),
                c.getString(R.string.module_row_copy_summary));
        copy.setOnPreferenceClickListener(p -> {
            ModuleActions.cropSheet(host.context(), slot, 1f, k -> ModuleActions.copyWithCrop(host.context(), slot, k, this::created));
            return true;
        });
        add(card, copy);
        if (source != null) {
            ModuleRowPreference delete = row(c, DELETE + slot, R.drawable.settings_ic_delete, c.getString(R.string.module_row_delete),
                    c.getString(R.string.module_row_delete_summary, ModuleRegistry.label(source)));
            delete.setWarn(true);
            delete.setOnPreferenceClickListener(p -> {
                boolean active = slot.equals(ModuleRegistry.active());
                // the active copy hands the camera to its source (ModuleRegistry.remove -> select): another profile, the page is built again
                ModuleActions.deleteCopy(host.context(), slot, active ? host::recreate : this::sync);
                return true;
            });
            add(card, delete);
        }
        List<String> profiles = new ArrayList<>();
        profiles.add(slot);
        profiles.addAll(FullResProfiles.variants(slot));
        // P413: the lens page of the module and of each of its FULLRES shots (the reference's Binning / Full-Res of a lens)
        for (String id : profiles) {
            boolean variant = FullResProfiles.isVariant(id);
            ModuleRowPreference lens = row(c, LENS_ROW + id, variant ? R.drawable.settings_ic_mosaic : R.drawable.settings_ic_sensor,
                    variant ? c.getString(R.string.module_row_lens) + " · " + FullResProfiles.label(id) : c.getString(R.string.module_row_lens),
                    c.getString(R.string.module_row_lens_summary));
            lens.setOnPreferenceClickListener(x -> { ModuleActions.openLens(host, id); return true; });
            add(card, lens);
        }
        for (String id : profiles) {
            boolean variant = FullResProfiles.isVariant(id);
            ModuleRowPreference p = row(c, PROFILE + id, variant ? R.drawable.settings_ic_mosaic : R.drawable.settings_ic_sliders,
                    variant ? FullResProfiles.label(id) : c.getString(R.string.module_row_profile), null);
            p.setOnPreferenceClickListener(x -> { ModuleActions.openProfile(host, id); return true; });
            add(card, p);
        }
        if (trialsCamera != null) {
            ModuleRowPreference trials = row(c, TRIALS + trialsCamera, R.drawable.settings_ic_diag,
                    c.getString(R.string.module_row_trials, trialsCamera), null);
            trials.setOnPreferenceClickListener(p -> { ModuleActions.retryTrials(host.context(), trialsCamera, this::sync); return true; });
            add(card, trials);
        }
    }

    /** The values of a card's rows. */
    private void updateCard(Context c, ModuleSection card, String slot) {
        ModuleRowPreference name = card.findPreference(NAME + slot);
        if (name != null) name.setValue(ModuleRegistry.label(slot));
        ModuleRowPreference cardId = card.findPreference(CARD_ID + slot);
        if (cardId != null) {
            cardId.setValue(c.getString(R.string.module_id_value, ModuleRegistry.camera(slot)));
            cardId.setSummary(idState(c, slot));
        }
        ModuleRowPreference zoom = card.findPreference(ZOOM + slot);
        if (zoom != null) zoom.setValue(ModuleRegistry.sensorCrop(slot) ? c.getString(R.string.module_row_zoom_sensor, badge(slot)) : badge(slot));
        SwitchPreferenceCompat button = card.findPreference(BUTTON + slot);
        if (button != null) {
            boolean shown = ModuleRegistry.visible(slot);
            if (button.isChecked() != shown) button.setChecked(shown);
            button.setIcon(shown ? R.drawable.settings_ic_eye : R.drawable.settings_ic_eye_off);
            button.setSummary(c.getString(shown ? R.string.module_row_button_on : R.string.module_row_button_off));
        }
        String source = ModuleRegistry.duplicateOf(slot);
        ModuleRowPreference crop = card.findPreference(CROP + slot);
        if (crop != null && source != null) {
            float k = ModuleRegistry.crop(slot) / ModuleRegistry.crop(source);
            crop.setSummary(k <= 1.0001f ? c.getString(R.string.module_row_crop_none, ModuleRegistry.label(source))
                    : c.getString(R.string.module_row_crop_of, ModuleActions.cropText(k), ModuleRegistry.label(source), ModuleActions.cropText(ModuleRegistry.zoom(slot))));
        }
        boolean on = PreferenceKeys.isPerLensSettingsOn();
        String inCamera = FullResProfiles.profileFor(ModuleRegistry.active());
        boolean variants = !FullResProfiles.variants(slot).isEmpty();
        for (int i = 0; i < card.getPreferenceCount(); i++) {
            Preference p = card.getPreference(i);
            if (!(p instanceof ModuleRowPreference) || p.getKey() == null || !p.getKey().startsWith(PROFILE)) continue;
            String id = p.getKey().substring(PROFILE.length());
            ModuleRowPreference row = (ModuleRowPreference) p;
            String summary;
            if (FullResProfiles.isVariant(id)) {
                summary = c.getString(R.string.module_row_fullres_shots, FullResProfiles.size(id));
                if (!storedProfile(id)) summary += c.getString(R.string.module_row_fullres_unset, ModuleRegistry.label(slot));
            } else {
                summary = c.getString(on ? R.string.module_row_profile_on : R.string.module_row_profile_off);
                // the module's own profile beside its FULLRES ones; while all modules share one, there is nothing to tell apart
                if (variants && on) summary += c.getString(R.string.module_row_profile_regular);
                row.setValue(c.getString(on ? R.string.module_row_profile_value_on : R.string.module_row_profile_value_off));
            }
            if (on && id.equals(inCamera)) summary += c.getString(R.string.module_row_profile_in_camera);
            row.setSummary(summary);
            row.setDimmed(!on);
        }
        for (int i = 0; i < card.getPreferenceCount(); i++) {
            Preference p = card.getPreference(i);
            if (p.getKey() != null && p.getKey().startsWith(TRIALS)) {
                String status = FullResTrials.status(p.getKey().substring(TRIALS.length()));
                if (status != null) p.setSummary(status);
            }
        }
    }

    private static boolean storedProfile(String id) {
        try {
            return PreferenceKeys.profiles().stored(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void syncIds(Context c, List<String> slots) {
        for (String slot : new ArrayList<>(idRows.keySet())) {
            if (slots.contains(slot)) continue;
            ModuleRowPreference gone = idRows.remove(slot);
            if (gone != null) ids.removePreference(gone);
        }
        for (int i = 0; i < slots.size(); i++) {
            String slot = slots.get(i);
            ModuleRowPreference row = idRows.get(slot);
            if (row == null) {
                row = row(c, ID + slot, R.drawable.settings_ic_id, "", null);
                row.setOnPreferenceClickListener(p -> { CameraIdSheet.show(host.context(), slot, this::sync); return true; });
                add(ids, row);
                idRows.put(slot, row);
            }
            row.setOrder(i);
            row.setTexts(badge(slot) + " · " + cardTitle(c, slot), idState(c, slot));
            row.setValue(c.getString(R.string.module_id_value, ModuleRegistry.camera(slot)));
        }
    }

    /** The sensor categories as the modules are now (a new copy's added, a deleted one's removed, one on another camera rebuilt); the chips. */
    private void syncSensors(Context c) {
        if (sensorGroup == null || sensors == null) return;
        if (profile == null) {
            List<String> slots = SensorConfigPreferenceGenerator.moduleSlots(null);
            if (!slots.isEmpty()) {
                for (String slot : new ArrayList<>(sensors.slots())) {
                    if (slots.contains(slot) && Objects.equals(sensors.camera(slot), ModuleRegistry.camera(slot))) continue;
                    SensorConfigPreferenceGenerator.removeModuleCategory(sensorGroup, slot);
                    sensors.remove(slot);
                }
                for (String slot : slots) {
                    if (sensors.category(slot) != null) continue;
                    PreferenceCategory category = SensorConfigPreferenceGenerator.addModuleCategory(c, sensorGroup, slot,
                            this::sync, host::resetSensor);
                    SettingsStyle.apply(category);
                    category.setLayoutResource(R.layout.preference_subcategory_layout);
                    sensors.put(slot, category, slots);
                }
                sensors.order(slots);
                // with one module there is nothing to choose: its rows are shown
                if (slots.size() == 1 && sensors.selected() == null) sensors.select(slots.get(0));
            }
        }
        if (chips != null) {
            Map<String, ?> all = prefs().getAll();
            chips.setModules(sensors.slots(), slot -> chipLabel(c, slot), slot -> ModuleSensorSettings.customised(all, slot));
            chips.setSelected(sensors.selected());
            hide(chips, profile != null || sensors.slots().size() <= 1);
        }
    }

    // ───── open / close, reveal ─────

    /** One card open at a time: this one opens (the other closes in the same diff) or folds; kept in the page's arguments. */
    void toggle(ModuleSection section) {
        boolean open = !section.isExpanded();
        for (ModuleSection other : cards.values()) if (other != section && other.isExpanded()) other.setExpanded(false);
        section.setExpanded(open);
        host.args().putString(ARG_OPEN_MODULE, open ? section.slot() : "");
        if (open) revealLater(section);
    }

    /** After the rows came in (the diff, ~260 ms): the opened card scrolled into view when its rows end below the list. */
    private void revealLater(ModuleSection section) {
        RecyclerView list = host.list();
        if (list == null) return;
        list.postDelayed(() -> {
            RecyclerView l = host.list();
            if (l == null || !(l.getAdapter() instanceof PreferenceGroupAdapter) || !section.isExpanded()) return;
            PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) l.getAdapter();
            int pos = adapter.getPreferenceAdapterPosition(section);
            int last = pos;
            for (int i = 0; i < section.getPreferenceCount(); i++)
                last = Math.max(last, adapter.getPreferenceAdapterPosition(section.getPreference(i)));
            SettingsMotion.revealSection(l, pos, last);
        }, 260);
    }

    /** A copy was made: its card is added, opened and shown. */
    private void created(String copy) {
        sync();
        ModuleSection card = cards.get(copy);
        if (card != null && !card.isExpanded()) toggle(card);
        host.headerChanged();
    }

    /**
     * The search's target (or a link's): the card holding it opened, the sensor module holding it chosen. True when that changed
     * what the page shows.
     */
    boolean reveal(@Nullable Preference target) {
        boolean changed = false;
        for (Preference p = target; p != null; p = p.getParent()) {
            if (p instanceof ModuleSection && !((ModuleSection) p).isExpanded()) {
                ModuleSection section = (ModuleSection) p;
                for (ModuleSection other : cards.values()) if (other != section && other.isExpanded()) other.setExpanded(false);
                section.setExpanded(true);
                host.args().putString(ARG_OPEN_MODULE, section.slot());
                changed = true;
            }
            if (SensorConfigPreferenceGenerator.isModuleCategory(p) && sensors != null) {
                String slot = p.getKey().substring(SensorConfigPreferenceGenerator.CATEGORY_PREFIX.length());
                if (!slot.equals(sensors.selected())) {
                    sensors.select(slot);
                    if (chips != null) chips.setSelected(slot);
                    host.args().putString(SettingsActivity.SettingsFragment.ARG_SENSOR_SLOT, slot);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** The cards (tests, the page reset: a folded card's rows are not the page's). */
    List<ModuleSection> cards() {
        return new ArrayList<>(cards.values());
    }

    /** Every key a card row may hold, for the tests. */
    static List<String> rowPrefixes() {
        return Arrays.asList(NAME, ZOOM, CARD_ID, BUTTON, CROP, COPY, DELETE, LENS_ROW, PROFILE, TRIALS);
    }
}
