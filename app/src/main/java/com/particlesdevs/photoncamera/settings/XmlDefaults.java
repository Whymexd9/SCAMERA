package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.res.XmlResourceParser;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The android:defaultValue of every row of res/xml/preferences.xml, read once: the fallback of the {@link PreferenceKeys.Key}
 * getters that pass no default of their own (SettingsManager.getStringDefault and the typed variants). Without it such a
 * getter fell back to 0 / false whenever its key was not stored (after restoring a config or a module snapshot that lacked
 * it): AF mode 0 = AF off, «Фильтр Байера» 0 = forced RGGB, sounds / watermark / rounded corners off, every RawTherapee
 * sharpening value 0 (settings audit S2). Switches are stored as "1" / "0", the form DefaultsStore parses.
 */
final class XmlDefaults {
    private static final String TAG = "XmlDefaults";
    private static volatile Map<String, String> cache;

    private XmlDefaults() {}

    /** The XML default of the key, or null when no row of preferences.xml declares one. */
    static String get(Context context, String key) {
        Map<String, String> values = cache;
        if (values == null) {
            synchronized (XmlDefaults.class) {
                if (cache == null) cache = read(context);
                values = cache;
            }
        }
        return values.get(key);
    }

    /** key -> default of preferences.xml, references resolved, switches as "1" / "0". */
    static Map<String, String> read(Context context) {
        Map<String, String> out = new HashMap<>();
        if (context == null) return out;
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.preferences)) {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                String key = SettingsMigration.attribute(context, parser, "key");
                String value = SettingsMigration.attribute(context, parser, "defaultValue");
                if (key == null || value == null) continue;
                String type = parser.getName();
                if (type.contains("Switch") || type.contains("CheckBox"))
                    value = PreferenceNumber.bool(value, false) ? "1" : "0";
                out.put(key, value);
            }
        } catch (Exception e) {
            Log.e(TAG, "preferences.xml defaults not read: " + e);
        }
        return Collections.unmodifiableMap(out);
    }
}
