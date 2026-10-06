package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.util.ConfigFolder;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import org.apache.commons.io.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Config save / import: an XML file ({@link ConfigXml}) in Download/SCAMERA/XML ({@link ConfigFolder}) with the main
 * settings, the module (per-lens) profiles, the old per-lens file and a passport of every module slot (lens facing, 35 mm
 * focal length, zoom, sensor crop). On the phone that saved it everything is applied verbatim; on another phone that
 * phone keeps its own module slots and sensor configs, and every slot gets the profile of the matching saved lens
 * ({@link LensProfileMatcher}). Import also reads the old JSON backups and plain shared_prefs XML files.
 */
public class BackupRestoreUtil {
    private static final String TAG = "BackupRestoreUtil";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final String META = "module_profiles_meta";
    static final String PROFILE_PREFIX = "module_profile_v2_";
    static final String PER_LENS = "per_lens";
    /** Lens passports of the saving phone's module slots ("slot.facing", ".focal", ".zoom", ".crop", ".mode", ".label", ".camera"). */
    static final String LENSES = "lenses";
    /** Lens part of a config imported before this phone had module slots (fresh install), applied in ModuleRegistry.initialize. */
    static final String PENDING = "config_pending_lenses";

    public static String backupSettings(Context context, String fileName) {
        try {
            String base = fileName == null ? "" : fileName.trim().replaceAll("(?i)\\.(xml|json)$", "");
            if (base.isEmpty()) throw new IOException(context.getString(R.string.empty_file_name_error));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ConfigXml.write(out, header(context), collect(context));
            return Lang.t(context, "Сохранено: ", "Saved: ") + ConfigFolder.save(context, base + ".xml", out.toByteArray());
        } catch (Exception e) {
            Log.e(TAG, "Config save failed", e);
            return Lang.t(context, "Ошибка сохранения: ", "Save failed: ") + e.getLocalizedMessage();
        }
    }

    /** Imports a config listed in Download/SCAMERA/XML. */
    public static String restorePreferences(Context context, String fileName) {
        try (InputStream in = ConfigFolder.open(context, fileName)) {
            return restore(context, readAll(in), fileName);
        } catch (Exception e) {
            Log.e(TAG, "Config import failed", e);
            return Lang.t(context, "Ошибка загрузки: ", "Import failed: ") + e.getLocalizedMessage();
        }
    }

    /** Imports a config picked with the system file picker (any folder, e.g. a file from another phone). */
    public static String restoreFromUri(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open " + uri);
            String name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
            return restore(context, readAll(in), name == null ? "config" : name);
        } catch (Exception e) {
            Log.e(TAG, "Config import failed", e);
            return Lang.t(context, "Ошибка загрузки: ", "Import failed: ") + e.getLocalizedMessage();
        }
    }

    private static String restore(Context context, byte[] data, String label) throws IOException {
        int i = 0;
        if (data.length >= 3 && (data[0] & 0xff) == 0xef && (data[1] & 0xff) == 0xbb && (data[2] & 0xff) == 0xbf) i = 3;
        while (i < data.length && Character.isWhitespace(data[i])) i++;
        String result;
        if (i < data.length && data[i] == '{') {
            TunableSettingsManager.ensureTunableClassesRegistered();
            try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
                applyRestoredJson(context, JsonParser.parseReader(reader).getAsJsonObject());
            }
            result = Lang.t(context, "Загружено (старый JSON): ", "Imported (old JSON): ") + label;
        } else {
            result = apply(context, ConfigXml.read(new ByteArrayInputStream(data))) + label;
        }
        PhotonCamera.restartWithDelay(context, 1000);
        return result;
    }

    static Map<String, String> header(Context context) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("device", device());
        a.put("model", Build.MODEL);
        a.put("app", appVersion(context));
        a.put("created", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
        return a;
    }

    private static String appVersion(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String device() {
        return Build.MANUFACTURER + "/" + Build.DEVICE;
    }

    /** Every prefs file a config carries: main, module profile meta + profiles, the old per-lens file. */
    static Map<String, Map<String, ?>> collect(Context context) {
        Map<String, Map<String, ?>> files = new LinkedHashMap<>();
        files.put(ConfigXml.MAIN, PreferenceManager.getDefaultSharedPreferences(context).getAll());
        Map<String, ?> meta = context.getSharedPreferences(META, Context.MODE_PRIVATE).getAll();
        if (!meta.isEmpty()) {
            files.put(META, meta);
            java.util.TreeSet<String> ids = new java.util.TreeSet<>();
            ids.add("common");
            for (Map.Entry<String, ?> e : meta.entrySet())
                if (e.getKey().startsWith("exists_") && Boolean.TRUE.equals(e.getValue())) ids.add(e.getKey().substring("exists_".length()));
            for (String id : ids) {
                Map<String, ?> profile = context.getSharedPreferences(PROFILE_PREFIX + id, Context.MODE_PRIVATE).getAll();
                if (!profile.isEmpty()) files.put(PROFILE_PREFIX + id, profile);
            }
        }
        Map<String, ?> perLens = perLens(context).getAll();
        if (!perLens.isEmpty()) files.put(PER_LENS, perLens);
        Map<String, Object> lenses = lensPassports(context);
        if (!lenses.isEmpty()) files.put(LENSES, lenses);
        return files;
    }

    /**
     * Passport of every visible module slot of this phone: facing, the 35 mm equivalent focal length of what the slot
     * shows (the lens's own, times the slot's zoom over the zoom of the uncropped slots on the same Camera ID, so a 2x /
     * ISZ slot of the tele reads as its real focal length), zoom, sensor crop, vendor sensor mode, label and Camera ID.
     */
    static Map<String, Object> lensPassports(Context context) {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.List<String> slots;
        try { slots = ModuleRegistry.slots(); } catch (RuntimeException e) { return out; }
        for (String slot : slots) {
            if (!ModuleRegistry.visible(slot)) continue;
            String camera = ModuleRegistry.camera(slot);
            float zoom = ModuleRegistry.zoom(slot);
            float base = Float.NaN;
            for (String other : slots)
                if (ModuleRegistry.visible(other) && !ModuleRegistry.sensorCrop(other) && ModuleRegistry.camera(other).equals(camera))
                    base = Float.isNaN(base) ? ModuleRegistry.zoom(other) : Math.min(base, ModuleRegistry.zoom(other));
            if (Float.isNaN(base)) base = zoom;
            float equiv = equivFocal(context, camera);
            // Without characteristics (no camera service): the zoom labels are relative to the main lens (~26 mm).
            float focal = equiv > 0f ? equiv * zoom / Math.max(base, 1e-3f) : 26f * zoom;
            out.put(slot + ".facing", slot.startsWith("front") ? "front" : "back");
            out.put(slot + ".focal", focal);
            out.put(slot + ".zoom", zoom);
            out.put(slot + ".crop", ModuleRegistry.sensorCrop(slot));
            out.put(slot + ".mode", ModuleRegistry.sensorMode(slot));
            out.put(slot + ".label", ModuleRegistry.label(slot));
            out.put(slot + ".camera", camera);
        }
        return out;
    }

    /** 35 mm equivalent focal length of a Camera ID (as CameraManager2: 36 mm over the sensor width), 0 when unknown. */
    private static float equivFocal(Context context, String cameraId) {
        try {
            android.hardware.camera2.CameraManager cm = context.getSystemService(android.hardware.camera2.CameraManager.class);
            android.hardware.camera2.CameraCharacteristics c = cm.getCameraCharacteristics(cameraId);
            float[] focal = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF size = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (focal == null || focal.length == 0 || size == null || !(size.getWidth() > 0f)) return 0f;
            return 36f / size.getWidth() * focal[0];
        } catch (Exception | LinkageError e) {
            return 0f;
        }
    }

    static java.util.List<LensProfileMatcher.Lens> lenses(Map<String, ?> passports) {
        java.util.List<LensProfileMatcher.Lens> out = new java.util.ArrayList<>();
        if (passports == null) return out;
        java.util.TreeSet<String> slots = new java.util.TreeSet<>();
        for (String k : passports.keySet()) if (k.endsWith(".facing")) slots.add(k.substring(0, k.length() - ".facing".length()));
        for (String slot : slots) {
            Object label = passports.get(slot + ".label");
            out.add(new LensProfileMatcher.Lens(slot, "front".equals(String.valueOf(passports.get(slot + ".facing"))),
                    (float) PreferenceNumber.read(passports.get(slot + ".focal"), 0), (float) PreferenceNumber.read(passports.get(slot + ".zoom"), 1),
                    PreferenceNumber.bool(passports.get(slot + ".crop"), false), label == null ? slot : String.valueOf(label)));
        }
        return out;
    }

    /** Main-prefs keys that describe this phone, never another one: module slots, sensor configs, camera ids, spoof. */
    static boolean deviceOnly(String key) {
        return key.startsWith("module_") || key.startsWith("pref_sensorconfig_") || key.startsWith("lens_")
                || key.equals(PreferenceKeys.Key.CAMERA_ID.mValue) || key.equals("user_camera_ids") || key.equals("hidden_camera_ids")
                || key.equals("pref_camera_package_spoof_enabled") || key.endsWith("_spoof_package_key")
                || key.equals("device_defaults_version");
    }

    /** Settings of a slot profile written on another phone: its local keys, minus references to files this phone lacks. */
    private static Map<String, Object> portable(Context context, Map<String, ?> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values == null) return out;
        for (Map.Entry<String, ?> e : values.entrySet()) if (ModuleProfiles.isLocal(e.getKey())) out.put(e.getKey(), e.getValue());
        sanitize(context, out);
        return out;
    }

    /** Drops an imported DCP that is not on this phone and the in-memory imported noise model. */
    private static void sanitize(Context context, Map<String, Object> values) {
        String dcpKey = com.particlesdevs.photoncamera.processing.color.DcpProfiles.KEY;
        Object dcp = values.get(dcpKey);
        if (dcp != null && !String.valueOf(dcp).isEmpty() && !new File(new File(context.getFilesDir(), "dcp"), String.valueOf(dcp)).isFile())
            values.put(dcpKey, "");
        if (com.particlesdevs.photoncamera.processing.render.NoiseModelProfile.IMPORTED_ID.equals(values.get("pref_noise_model_profile_key")))
            values.put("pref_noise_model_profile_key", "auto");
    }

    private static void migrate(SharedPreferences prefs) {
        SettingsMigration.migrateMultiFrame(prefs);
        SettingsMigration.migrateLmcHybrid(prefs, false);
        SettingsMigration.migrateShadeTiles(prefs);
        SettingsMigration.removeObsolete(prefs);
    }

    /**
     * Writes the source's module profiles onto this phone's slots, matched by lens (LensProfileMatcher): every target
     * slot gets the profile of its matched source lens (the source's active slot from its main settings), a slot with no
     * lens of its facing in the source gets the source baseline. Returns the summary, or null when the config has no lens
     * passports. Without slots on this phone yet, the lens part is kept for ModuleRegistry.initialize.
     */
    static String applyLensProfiles(Context context, ConfigXml.Config config) {
        java.util.List<LensProfileMatcher.Lens> sources = lenses(config.files.get(LENSES));
        if (sources.isEmpty()) return null;
        java.util.List<LensProfileMatcher.Lens> targets = lenses(lensPassports(context));
        if (targets.isEmpty()) {
            try {
                Map<String, Map<String, ?>> part = new LinkedHashMap<>();
                for (Map.Entry<String, Map<String, Object>> f : config.files.entrySet())
                    if (f.getKey().equals(LENSES) || f.getKey().equals(META) || f.getKey().startsWith(PROFILE_PREFIX) || f.getKey().equals(ConfigXml.MAIN))
                        part.put(f.getKey(), f.getValue());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ConfigXml.write(out, config.attributes, part);
                context.getSharedPreferences(PENDING, Context.MODE_PRIVATE).edit().putString("xml", out.toString("UTF-8")).commit();
            } catch (IOException e) {
                Log.e(TAG, "Pending lens profiles not stored", e);
            }
            return Lang.t(context, "профили модулей будут перенесены при первом открытии камеры",
                    "module profiles will be applied when the camera first opens");
        }
        Map<String, String> mapping = LensProfileMatcher.match(sources, targets);
        Map<String, Object> srcMain = config.files.get(ConfigXml.MAIN);
        Map<String, Object> srcMeta = config.files.get(META);
        Object srcActive = srcMeta == null ? null : srcMeta.get("active");
        // With per-lens settings off on the source every value is in its main settings: every module gets those.
        boolean perLens = srcMain != null && PreferenceNumber.bool(srcMain.get(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue), false);
        Map<String, Object> baseline = perLens && config.files.containsKey(PROFILE_PREFIX + "common")
                ? config.files.get(PROFILE_PREFIX + "common") : srcMain;
        SharedPreferences.Editor meta = context.getSharedPreferences(META, Context.MODE_PRIVATE).edit();
        writeProfile(context, "common", portable(context, baseline));
        meta.putBoolean("baseline", true);
        java.util.Set<String> used = new java.util.HashSet<>();
        for (LensProfileMatcher.Lens t : targets) {
            String from = mapping.get(t.slot);
            Map<String, ?> values;
            if (from == null || !perLens) values = baseline;
            else {
                used.add(from);
                values = from.equals(srcActive) && srcMain != null ? srcMain
                        : config.files.containsKey(PROFILE_PREFIX + from) ? config.files.get(PROFILE_PREFIX + from) : baseline;
            }
            writeProfile(context, t.slot, portable(context, values));
            meta.putBoolean("exists_" + t.slot, true);
        }
        // The active slot's values live in the main settings.
        String active = ModuleRegistry.active();
        SharedPreferences main = PreferenceManager.getDefaultSharedPreferences(context);
        Map<String, ?> activeProfile = context.getSharedPreferences(PROFILE_PREFIX + active, Context.MODE_PRIVATE).getAll();
        if (!activeProfile.isEmpty()) {
            SharedPreferences.Editor e = main.edit();
            for (String k : main.getAll().keySet()) if (ModuleProfiles.isLocal(k)) e.remove(k);
            activeProfile.forEach((k, v) -> ModuleProfiles.put(e, k, v));
            e.commit();
        }
        meta.putString("active", active).commit();
        StringBuilder unused = new StringBuilder();
        for (LensProfileMatcher.Lens s : sources) if (!used.contains(s.slot)) unused.append(unused.length() == 0 ? "" : ", ").append(s.label);
        Log.i(TAG, "Lens profiles mapped: " + mapping + " sources=" + sources + " targets=" + targets);
        if (!perLens) return Lang.t(context, "общие настройки на все модули (отдельные настройки модулей в конфиге выключены)",
                "shared settings for all modules (per-module settings are off in the config)");
        return LensProfileMatcher.describe(mapping, sources, targets)
                + (unused.length() > 0 ? Lang.t(context, "; не перенесены: ", "; not applied: ") + unused : "");
    }

    private static void writeProfile(Context context, String slot, Map<String, Object> values) {
        SharedPreferences p = context.getSharedPreferences(PROFILE_PREFIX + slot, Context.MODE_PRIVATE);
        replace(p, values);
        migrate(p);
    }

    /** Called by ModuleRegistry.initialize: maps a config imported before this phone had module slots. */
    public static void applyPending(Context context) {
        SharedPreferences pending = context.getSharedPreferences(PENDING, Context.MODE_PRIVATE);
        String xml = pending.getString("xml", null);
        if (xml == null || lenses(lensPassports(context)).isEmpty()) return;
        try {
            ConfigXml.Config config = ConfigXml.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Log.i(TAG, "Pending lens profiles applied: " + applyLensProfiles(context, config));
        } catch (Exception e) {
            Log.e(TAG, "Pending lens profiles failed", e);
        }
        pending.edit().clear().commit();
    }

    private static SharedPreferences perLens(Context context) {
        return context.getSharedPreferences(context.getPackageName() + context.getString(R.string._per_lens), Context.MODE_PRIVATE);
    }

    /**
     * Writes the config into the prefs files; returns the message prefix (the caller appends the file name). On the phone
     * that saved it everything comes back verbatim. On another phone this phone's own slots, sensor configs and camera
     * ids stay, the main settings are the source's, and the module profiles are mapped onto this phone's lenses.
     */
    static String apply(Context context, ConfigXml.Config config) {
        Map<String, Object> main = config.files.get(ConfigXml.MAIN);
        if (main == null) throw new IllegalArgumentException("the config has no main settings");
        String from = config.attributes.get("device");
        boolean sameDevice = from == null || from.equals(device());
        SharedPreferences mainPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (sameDevice) {
            replace(mainPrefs, main);
            for (Map.Entry<String, Map<String, Object>> file : config.files.entrySet()) {
                String name = file.getKey();
                if (name.equals(ConfigXml.MAIN) || name.equals(LENSES)) continue;
                boolean lens = name.equals(META) || name.startsWith(PROFILE_PREFIX) || name.equals(PER_LENS);
                if (!lens) { Log.w(TAG, "Unknown prefs file in config: " + name); continue; }
                replace(name.equals(PER_LENS) ? perLens(context) : context.getSharedPreferences(name, Context.MODE_PRIVATE), file.getValue());
            }
            Log.d(TAG, "Config applied on the same phone: main=" + main.size() + " keys, files=" + config.files.size());
            return Lang.t(context, "Загружено: ", "Imported: ");
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : main.entrySet()) if (!deviceOnly(e.getKey())) merged.put(e.getKey(), e.getValue());
        for (Map.Entry<String, ?> e : mainPrefs.getAll().entrySet()) if (deviceOnly(e.getKey())) merged.put(e.getKey(), e.getValue());
        // DeviceDefaults must not overwrite the restored config on the next start.
        if (!merged.containsKey("device_defaults_version") && DeviceDefaults.forDevice(context) != null)
            merged.put("device_defaults_version", DeviceDefaults.VERSION);
        sanitize(context, merged);
        replace(mainPrefs, merged);
        migrate(mainPrefs);
        String lenses = applyLensProfiles(context, config);
        Log.d(TAG, "Config from " + from + " applied: main=" + merged.size() + " keys, lenses: " + lenses);
        return lenses == null
                ? Lang.t(context, "Загружены общие настройки (профили модулей не перенесены: в конфиге с " + from + " нет описания объективов): ",
                        "Imported the shared settings (module profiles not applied: the config from " + from + " has no lens descriptions): ")
                : Lang.t(context, "Загружено с " + from + ". Модули: " + lenses + ". Файл: ",
                        "Imported from " + from + ". Modules: " + lenses + ". File: ");
    }

    private static void replace(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        values.forEach((k, v) -> ModuleProfiles.put(editor, k, v));
        editor.commit();
    }

    /**
     * Old JSON backups (metadata version 2.x, no lens passports). The backup counts as this phone's when every Camera ID
     * its module slots point at exists here; otherwise this phone keeps its own slots, sensor configs and camera ids, and
     * the old per-lens file (keyed by Camera ID) is not applied.
     */
    private static void applyRestoredJson(Context context, JsonObject root) {
        String packageName = context.getPackageName();
        // Camera IDs alone do not identify a phone (an X200 Ultra backup passed on an X100 Ultra, whose ids 0-6 exist too, and
        // put PD2454 slots and forced sensor modes on its main sensor): the lenses recorded in the backup must match as well.
        final boolean lensesHere = lensesMatchHere(context, root);
        boolean sameDevice = lensesHere;
        if (root.has("main_preferences")) {
            SharedPreferences mainPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            Map<String, Object> own = new LinkedHashMap<>();
            for (Map.Entry<String, ?> e : mainPrefs.getAll().entrySet()) if (deviceOnly(e.getKey())) own.put(e.getKey(), e.getValue());
            JsonObject mainPrefsObj = root.getAsJsonObject("main_preferences");
            sameDevice = lensesHere && slotsExistHere(context, mainPrefsObj);
            SharedPreferences.Editor editor = mainPrefs.edit();
            editor.clear();
            for (String key : mainPrefsObj.keySet()) {
                if (!sameDevice && deviceOnly(key)) continue;
                putJsonValueToEditor(editor, key, mainPrefsObj.get(key));
            }
            if (!sameDevice) own.forEach((k, v) -> ModuleProfiles.put(editor, k, v));
            editor.commit();
            migrate(mainPrefs);
        }
        if (root.has("per_lens_settings") && sameDevice) {
            SharedPreferences perLensPrefs = perLens(context);
            SharedPreferences.Editor editor = perLensPrefs.edit();
            editor.clear();
            com.google.gson.JsonElement perLensElement = root.get("per_lens_settings");
            if (perLensElement.isJsonArray()) {
                com.google.gson.JsonArray perLensArray = perLensElement.getAsJsonArray();
                for (com.google.gson.JsonElement element : perLensArray) {
                    JsonObject cameraObj = element.getAsJsonObject();
                    String cameraId = cameraObj.get("id").getAsString();
                    JsonObject settings = cameraObj.getAsJsonObject("settings");
                    editor.putString("settings_for_camera_" + cameraId, GSON.toJson(settings));
                }
            } else if (perLensElement.isJsonObject()) {
                JsonObject perLensPrefsObj = perLensElement.getAsJsonObject();
                for (String key : perLensPrefsObj.keySet()) {
                    putJsonValueToEditor(editor, key, perLensPrefsObj.get(key));
                }
            }
            editor.commit();
        }
        if (root.has("tunable_settings")) {
            JsonObject tunableSettingsObj = root.getAsJsonObject("tunable_settings");
            Map<String, Object> tunableSettingsMap = GSON.fromJson(tunableSettingsObj,
                new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType());
            TunableSettingsManager.importTunableSettings(context, tunableSettingsMap);
        }
        // The camera scan cache and the device specifics describe the phone that wrote the backup.
        if (root.has("cameras_preferences") && sameDevice) {
            String camerasFileName = context.getString(R.string._cameras);
            SharedPreferences camerasPrefs = context.getSharedPreferences(packageName + camerasFileName, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = camerasPrefs.edit();
            JsonObject camerasPrefsObj = root.getAsJsonObject("cameras_preferences");
            for (String key : camerasPrefsObj.keySet()) {
                putJsonValueToEditor(editor, key, camerasPrefsObj.get(key));
            }
            editor.commit();
        }
        if (root.has("devices_preferences") && sameDevice) {
            String devicesFileName = context.getString(R.string._devices);
            SharedPreferences devicesPrefs = context.getSharedPreferences(packageName + devicesFileName, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = devicesPrefs.edit();
            JsonObject devicesPrefsObj = root.getAsJsonObject("devices_preferences");
            for (String key : devicesPrefsObj.keySet()) {
                putJsonValueToEditor(editor, key, devicesPrefsObj.get(key));
            }
            editor.commit();
        }
    }

    /**
     * False when the backup's camera scan (cameras_preferences / all_camera_lens: id, facing, focal length) names a Camera ID
     * that this phone has with another facing or a focal length more than 3 % apart. A backup without that list passes.
     */
    static boolean lensesMatchHere(Context context, JsonObject root) {
        java.util.Map<String, float[]> recorded = recordedLenses(root);
        if (recorded.isEmpty()) return true;
        android.hardware.camera2.CameraManager cm;
        try {
            cm = context.getSystemService(android.hardware.camera2.CameraManager.class);
        } catch (Exception | LinkageError e) {
            return false;
        }
        for (java.util.Map.Entry<String, float[]> lens : recorded.entrySet()) {
            String id = lens.getKey();
            if (id.contains("-")) id = id.split("-", 2)[1];
            android.hardware.camera2.CameraCharacteristics c;
            try {
                c = cm.getCameraCharacteristics(id);
            } catch (Exception e) {
                continue; // a hidden lens of that phone: the slot check decides
            }
            Integer facing = c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
            float[] focal = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (!lensMatches(lens.getValue(), facing, focal)) return false;
        }
        return true;
    }

    /** {facing, focal length} per Camera ID from the backup's all_camera_lens list (CameraLensData JSON). */
    static java.util.Map<String, float[]> recordedLenses(JsonObject root) {
        java.util.Map<String, float[]> out = new LinkedHashMap<>();
        if (root == null || !root.has("cameras_preferences") || !root.get("cameras_preferences").isJsonObject()) return out;
        com.google.gson.JsonElement list = root.getAsJsonObject("cameras_preferences").get("all_camera_lens");
        if (list == null || !list.isJsonArray()) return out;
        for (com.google.gson.JsonElement item : list.getAsJsonArray()) {
            try {
                JsonObject lens = item.isJsonPrimitive() ? JsonParser.parseString(item.getAsString()).getAsJsonObject() : item.getAsJsonObject();
                if (!lens.has("id") || !lens.has("fl")) continue;
                out.put(lens.get("id").getAsString(), new float[]{lens.has("face") ? lens.get("face").getAsFloat() : -1, lens.get("fl").getAsFloat()});
            } catch (RuntimeException ignored) {
                // an unreadable entry says nothing about the phone
            }
        }
        return out;
    }

    static boolean lensMatches(float[] recorded, Integer facing, float[] focal) {
        if (recorded[0] >= 0 && facing != null && Math.round(recorded[0]) != facing) return false;
        if (recorded[1] <= 0 || focal == null || focal.length == 0) return true;
        for (float f : focal) if (Math.abs(f - recorded[1]) <= 0.03f * Math.max(f, recorded[1])) return true;
        return false;
    }

    /** True when every Camera ID of the backup's module slots (module_auto_*) exists on this phone. */
    private static boolean slotsExistHere(Context context, JsonObject main) {
        java.util.Set<String> here = new java.util.HashSet<>();
        try {
            android.hardware.camera2.CameraManager cm = context.getSystemService(android.hardware.camera2.CameraManager.class);
            here.addAll(java.util.Arrays.asList(cm.getCameraIdList()));
        } catch (Exception | LinkageError e) {
            return false;
        }
        boolean any = false;
        for (String key : main.keySet()) {
            if (!key.startsWith("module_auto_")) continue;
            any = true;
            if (!here.contains(main.get(key).getAsString())) return false;
        }
        return any;
    }

    /**
     * Helper method to put JSON values into SharedPreferences Editor with type safety
     */
    private static void putJsonValueToEditor(SharedPreferences.Editor editor, String key, com.google.gson.JsonElement value) {
        if (value.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                editor.putBoolean(key, primitive.getAsBoolean());
            } else if (primitive.isNumber()) {
                // Try to determine if it's int, long, or float
                String strValue = primitive.getAsString();
                if (strValue.contains(".")) {
                    editor.putFloat(key, primitive.getAsFloat());
                } else {
                    try {
                        editor.putInt(key, primitive.getAsInt());
                    } catch (Exception e) {
                        editor.putLong(key, primitive.getAsLong());
                    }
                }
            } else {
                String strValue = primitive.getAsString();
                // Sanitize legacy/corrupted string numbers for tunable and sensor seekbars
                if (key.startsWith("pref_tunable_") || key.startsWith("pref_sensorconfig_")) {
                    try {
                        if (strValue.contains(".")) {
                            editor.putFloat(key, Float.parseFloat(strValue));
                            return;
                        } else {
                            editor.putInt(key, Integer.parseInt(strValue));
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                        // Fallback to string if it is a non-numeric string or free text
                    }
                }
                editor.putString(key, strValue);
            }
        } else if (value.isJsonArray()) {
            // Handle string sets
            java.util.Set<String> stringSet = new java.util.HashSet<>();
            for (com.google.gson.JsonElement element : value.getAsJsonArray()) {
                stringSet.add(element.getAsString());
            }
            editor.putStringSet(key, stringSet);
        } else {
            // For complex objects, store as string
            editor.putString(key, value.toString());
        }
    }

    public static boolean resetPreferences(Context context) {
        File data_dir = context.getDataDir();
        File shared_prefs_dir = Paths.get(data_dir.toPath() + File.separator + "shared_prefs").toFile();
        try {
            FileUtils.deleteDirectory(shared_prefs_dir);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
