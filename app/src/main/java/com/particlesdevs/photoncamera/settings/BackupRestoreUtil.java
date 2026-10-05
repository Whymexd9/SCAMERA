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
 * settings, the module (per-lens) profiles and the old per-lens file. Module profiles are applied only on the phone that
 * saved them (same manufacturer/device); on another phone only the main settings are applied. Import also reads the old
 * JSON backups and plain shared_prefs XML files.
 */
public class BackupRestoreUtil {
    private static final String TAG = "BackupRestoreUtil";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final String META = "module_profiles_meta";
    static final String PROFILE_PREFIX = "module_profile_v2_";
    static final String PER_LENS = "per_lens";

    public static String backupSettings(Context context, String fileName) {
        try {
            String base = fileName == null ? "" : fileName.trim().replaceAll("(?i)\\.(xml|json)$", "");
            if (base.isEmpty()) throw new IOException(context.getString(R.string.empty_file_name_error));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ConfigXml.write(out, header(context), collect(context));
            return "Сохранено: " + ConfigFolder.save(context, base + ".xml", out.toByteArray());
        } catch (Exception e) {
            Log.e(TAG, "Config save failed", e);
            return "Ошибка сохранения: " + e.getLocalizedMessage();
        }
    }

    /** Imports a config listed in Download/SCAMERA/XML. */
    public static String restorePreferences(Context context, String fileName) {
        try (InputStream in = ConfigFolder.open(context, fileName)) {
            return restore(context, readAll(in), fileName);
        } catch (Exception e) {
            Log.e(TAG, "Config import failed", e);
            return "Ошибка загрузки: " + e.getLocalizedMessage();
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
            return "Ошибка загрузки: " + e.getLocalizedMessage();
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
            result = "Загружено (старый JSON): " + label;
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
        return files;
    }

    private static SharedPreferences perLens(Context context) {
        return context.getSharedPreferences(context.getPackageName() + context.getString(R.string._per_lens), Context.MODE_PRIVATE);
    }

    /** Writes the config into the prefs files; returns the message prefix (the caller appends the file name). */
    static String apply(Context context, ConfigXml.Config config) {
        Map<String, Object> main = config.files.get(ConfigXml.MAIN);
        if (main == null) throw new IllegalArgumentException("the config has no main settings");
        String from = config.attributes.get("device");
        boolean sameDevice = from == null || from.equals(device());
        replace(PreferenceManager.getDefaultSharedPreferences(context), main);
        int skipped = 0;
        for (Map.Entry<String, Map<String, Object>> file : config.files.entrySet()) {
            String name = file.getKey();
            if (name.equals(ConfigXml.MAIN)) continue;
            boolean lens = name.equals(META) || name.startsWith(PROFILE_PREFIX) || name.equals(PER_LENS);
            if (!lens) { Log.w(TAG, "Unknown prefs file in config: " + name); continue; }
            if (!sameDevice) { skipped++; continue; }
            replace(name.equals(PER_LENS) ? perLens(context) : context.getSharedPreferences(name, Context.MODE_PRIVATE), file.getValue());
        }
        Log.d(TAG, "Config applied: main=" + main.size() + " keys, files=" + config.files.size() + " from=" + from + " skipped=" + skipped);
        return skipped > 0 ? "Загружены общие настройки (профили модулей не перенесены: конфиг с " + from + "): " : "Загружено: ";
    }

    private static void replace(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        values.forEach((k, v) -> ModuleProfiles.put(editor, k, v));
        editor.commit();
    }

    /** Old JSON backups (metadata version 2.x). */
    private static void applyRestoredJson(Context context, JsonObject root) {
        String packageName = context.getPackageName();
        if (root.has("main_preferences")) {
            SharedPreferences mainPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = mainPrefs.edit();
            editor.clear();
            JsonObject mainPrefsObj = root.getAsJsonObject("main_preferences");
            for (String key : mainPrefsObj.keySet()) {
                putJsonValueToEditor(editor, key, mainPrefsObj.get(key));
            }
            editor.commit();
        }
        if (root.has("per_lens_settings")) {
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
        if (root.has("cameras_preferences")) {
            String camerasFileName = context.getString(R.string._cameras);
            SharedPreferences camerasPrefs = context.getSharedPreferences(packageName + camerasFileName, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = camerasPrefs.edit();
            JsonObject camerasPrefsObj = root.getAsJsonObject("cameras_preferences");
            for (String key : camerasPrefsObj.keySet()) {
                putJsonValueToEditor(editor, key, camerasPrefsObj.get(key));
            }
            editor.commit();
        }
        if (root.has("devices_preferences")) {
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
