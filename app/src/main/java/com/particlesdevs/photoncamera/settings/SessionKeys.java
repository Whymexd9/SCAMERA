package com.particlesdevs.photoncamera.settings;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.settings.annotations.SensorConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * P214g (speed-ux C5, P211's finding: «Тип сессии камеры» and «Оптическая стабилизация» were not on the shade's hand-written
 * restart list): the settings a camera session reads when it is built, one list for every place that has to know -
 * <ul>
 * <li>the shade restarts the camera when it writes one ({@link ShadeCatalog.Entry#sessionTime}, owner's answer 7);</li>
 * <li>the camera screen restarts it when one changed while the settings screen was open beside it (a multi-window settings
 * screen leaves the camera resumed: CaptureController.sessionSettingsChanged against the values its session was built with;
 * the usual way back from the settings closes the camera in CameraFragment.onPause and builds a new session in onResume,
 * which reads them anyway);</li>
 * <li>the rows of the settings screen and of the shade carry the «↻» mark ({@link #MARK}).</li>
 * </ul>
 * Three parts:
 * <ul>
 * <li>the {@code @SensorConfig} fields of the classes injected when the session is built ({@link #SESSION_CLASSES}:
 * CaptureController, injected at the top of createCameraPreviewSession - «Тип сессии камеры» goes into the session's
 * operation mode, «Оптическая стабилизация» into the viewfinder request), stored per module as
 * pref_sensorconfig_&lt;slot&gt;_&lt;field in lower case&gt; (ModuleSensorSettings.prefix); the classes injected for each shot
 * ({@link #PER_SHOT_CLASSES}: Parameters, the RAW levels of the processing) are not on it. Every class of
 * SensorConfigRegistry is in one of the two (SessionKeysTest), so a new one has to be placed;</li>
 * <li>the module's vendor keys (pref_sensorconfig_&lt;slot&gt;_tunablekeys, TunableKeyManager): the session parameters and the
 * viewfinder request of the session;</li>
 * <li>{@link #PLAIN}: the other preferences openCamera, setUpCameraOutputs, createCameraPreviewSession and
 * setCaptureRequestBuilder read. Not on it: what a running session reads again (face detection and tracking AF, SubjectFocus
 * every 0.5 s; the Xiaomi tele's smooth zoom, each zoom request), what a shot reads (frame counts, the merge, the tone) and the
 * camera-screen controls that restart the camera themselves (module, FULLRES, DCG 14 bit).</li>
 * </ul>
 */
public final class SessionKeys {
    private SessionKeys() {}

    /** The mark of a row whose setting takes a new camera session (the camera restarts with it). */
    public static final String MARK = "↻";
    static final String SENSOR_PREFIX = "pref_sensorconfig_";
    /** Field part of the module's vendor keys (TunableKeyManager.prefKey). */
    static final String VENDOR_FIELD = "tunablekeys";

    /**
     * The hand-written part: the shade's list of owner's answer 7 (live RAW viewfinder, 16:9, RAW stream format, ZSL ring size,
     * AF mode) and what else the session's build reads.
     */
    public static final Set<String> PLAIN = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            // the RAW viewfinder's stream and reader (setUpCameraOutputs, createCameraPreviewSession)
            "pref_live_viewfinder_raw_key",
            // the preview size (Settings.aspect169, getTextureOutputSize)
            "pref_wide169_key",
            // the RAW stream's format (resolveRawFormat)
            "pref_raw_stream_format",
            // the ZSL ring and the RAW reader's image count (zslRingCapacity)
            "pref_zsl_buffer_count_key",
            // the viewfinder request's AF mode (setCaptureRequestBuilder)
            "pref_af_mode_key",
            // P86 «Движение»: its HAL session keys (HiddenFeatures.sessionKeys) and fixed AE frame rate; the top-bar switch
            // restarts the camera for it (CameraUIController), the shade did not
            MotionMode.PREF,
            // the viewfinder request's low-light boost AE mode (HiddenFeatures.applyPreview in setCaptureRequestBuilder)
            com.particlesdevs.photoncamera.capture.HiddenFeatures.PREF_LOW_LIGHT_BOOST,
            // P182 «Аппаратный ремозаик»: the FULLRES profile the session is configured with (VivoSdkFullRes.profile)
            com.particlesdevs.photoncamera.capture.VivoSdkFullRes.PREF_HW_REMOSAIC,
            // the vivo EIS viewfinder profile and the video stabilization mode of the session (no row since P25; reset to its
            // default by SettingsMigration.resetRemovedSettings)
            "pref_eis_photo_key")));

    /** Classes whose {@code @SensorConfig} fields are injected when the session is built. */
    static final List<Class<?>> SESSION_CLASSES = Collections.unmodifiableList(Collections.singletonList(
            com.particlesdevs.photoncamera.capture.CaptureController.class));
    /** Classes whose {@code @SensorConfig} fields are injected for each shot (Parameters.FillConstParameters). */
    static final List<Class<?>> PER_SHOT_CLASSES = Collections.unmodifiableList(Collections.singletonList(
            com.particlesdevs.photoncamera.processing.render.Parameters.class));

    private static volatile Set<String> sessionFields;

    /** The {@code @SensorConfig} fields of {@link #SESSION_CLASSES}, in lower case as their preference keys end. */
    static Set<String> sessionFields() {
        Set<String> fields = sessionFields;
        if (fields != null) return fields;
        Set<String> out = new TreeSet<>();
        for (Class<?> c : SESSION_CLASSES)
            for (Field f : c.getDeclaredFields())
                if (f.isAnnotationPresent(SensorConfig.class)) out.add(f.getName().toLowerCase(Locale.ROOT));
        sessionFields = fields = Collections.unmodifiableSet(out);
        return fields;
    }

    /** The module scope and the field of a per-module sensor key (pref_sensorconfig_&lt;scope&gt;_&lt;field&gt;), or null. */
    @Nullable
    static String[] sensorKey(String key) {
        if (!key.startsWith(SENSOR_PREFIX)) return null;
        String rest = key.substring(SENSOR_PREFIX.length());
        int cut = rest.lastIndexOf('_');
        if (cut <= 0 || cut == rest.length() - 1) return null;
        return new String[]{rest.substring(0, cut), rest.substring(cut + 1)};
    }

    private static boolean sessionField(String field) {
        // P384: the module's row of the camera's sensor mode table (SensorModePick, read when the session is built: its RAW
        // stream size and the mode key) - it had no restart pill, and a camera beside the settings (multi-window) kept its old mode
        return VENDOR_FIELD.equals(field) || ModuleDuplicates.SENSOR_MODE_SUFFIX.equals(field) || sessionFields().contains(field);
    }

    /** True when a new camera session is needed for a change of this preference to reach the camera. */
    public static boolean needsNewSession(@Nullable String key) {
        if (key == null || key.isEmpty()) return false;
        if (PLAIN.contains(key)) return true;
        String[] sensor = sensorKey(key);
        return sensor != null && sessionField(sensor[1]);
    }

    /**
     * The session settings of a module scope (ModuleSensorSettings.runtimeScope of the session's camera) as they are in
     * {@code all} (SharedPreferences.getAll): {@link #PLAIN} and the scope's own sensor and vendor keys - another module's
     * do not reach this session. The vendor keys count by what is sent ({@link #vendorContent}): the camera writes the
     * supported / tested flags of the list back each time a session applies it.
     */
    public static Map<String, String> snapshot(Map<String, ?> all, @Nullable String scope) {
        Map<String, String> out = new TreeMap<>();
        if (all == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (key == null || e.getValue() == null) continue;
            if (PLAIN.contains(key)) {
                out.put(key, String.valueOf(e.getValue()));
                continue;
            }
            String[] sensor = sensorKey(key);
            if (sensor == null || scope == null || !sensor[0].equals(scope) || !sessionField(sensor[1])) continue;
            out.put(key, VENDOR_FIELD.equals(sensor[1]) ? vendorContent(String.valueOf(e.getValue())) : String.valueOf(e.getValue()));
        }
        return out;
    }

    /** The keys whose value differs between two snapshots (added and removed ones too), sorted. */
    public static List<String> changed(@Nullable Map<String, String> before, @Nullable Map<String, String> now) {
        Map<String, String> a = before == null ? Collections.<String, String>emptyMap() : before;
        Map<String, String> b = now == null ? Collections.<String, String>emptyMap() : now;
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        List<String> out = new ArrayList<>();
        for (String key : keys) if (!java.util.Objects.equals(a.get(key), b.get(key))) out.add(key);
        return out;
    }

    /**
     * What a vendor key list sends (TunableKeyManager.signature): the enabled request and session keys as
     * type|name|valueType|value, in list order; a list that does not parse counts as its text.
     */
    static String vendorContent(String json) {
        List<VendorTagUtils.TunableKey> keys;
        try {
            keys = new com.google.gson.Gson().fromJson(json,
                    new com.google.gson.reflect.TypeToken<List<VendorTagUtils.TunableKey>>() {}.getType());
        } catch (RuntimeException e) {
            return json;
        }
        if (keys == null) return "";
        StringBuilder out = new StringBuilder();
        Set<String> sent = new HashSet<>(Arrays.asList("CaptureRequest", VendorTagUtils.TunableKey.TYPE_SESSION));
        for (VendorTagUtils.TunableKey k : keys)
            if (k != null && k.enabled && sent.contains(k.type))
                out.append(k.type).append('|').append(k.name).append('|').append(k.valueType).append('|').append(k.value).append(';');
        return out.toString();
    }

    /** A row title with the {@link #MARK}. */
    public static CharSequence marked(@Nullable CharSequence title) {
        return title == null || title.length() == 0 ? MARK : title + " " + MARK;
    }
}
