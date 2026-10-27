package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference;
import com.particlesdevs.photoncamera.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.util.HashMap;
import java.util.Map;

/**
 * P357 (owner, 2026-10-27: «добавь кнопку сброса всех настроек. сделай это для всех»): the rows of one settings page back to their
 * defaults (the header's reset button; the root page's button is the full reset of «Сбросить настройки»). Each row is reset
 * through the preference itself, so a module page writes its module's profile as an edit there would: the sliders by their own
 * default, the switches / lists / text rows by the android:defaultValue of preferences.xml. The rows of the page's own sub-pages,
 * rows without an XML default and the non-persistent rows (they write elsewhere) stay as they are.
 */
public final class PageReset {
    private static final String TAG = "PageReset";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static Map<String, String> defaults;

    private PageReset() {}

    /** The rows of {@code page} (not of its sub-pages) back to their defaults; the number of rows that changed. */
    public static int reset(Context context, PreferenceGroup page) {
        final Map<String, String> xml = defaults(context);
        int changed = 0;
        for (int i = 0; i < page.getPreferenceCount(); i++) {
            final Preference p = page.getPreference(i);
            if (p instanceof PreferenceScreen) continue;
            if (p instanceof PreferenceGroup) {
                changed += reset(context, (PreferenceGroup) p);
                continue;
            }
            try {
                if (resetRow(p, p.getKey() == null ? null : xml.get(p.getKey()))) changed++;
            } catch (RuntimeException e) {
                Log.w(TAG, "row " + p.getKey() + " not reset: " + e);
            }
        }
        return changed;
    }

    /** One row back to its default; true when its value changed. */
    static boolean resetRow(Preference p, @Nullable String def) {
        if (p.getKey() == null || !p.isPersistent() || !p.isEnabled() || !p.isVisible()) return false;
        if (p instanceof UniversalSeekBarPreference) {
            final UniversalSeekBarPreference u = (UniversalSeekBarPreference) p;
            if (!u.isChanged()) return false;
            u.resetToDefault();
            return true;
        }
        if (p instanceof TunableSeekBarPreference) return ((TunableSeekBarPreference) p).resetToDefault();
        if (def == null) return false;
        if (p instanceof TwoStatePreference) {
            final TwoStatePreference t = (TwoStatePreference) p;
            final boolean on = Boolean.parseBoolean(def.trim());
            if (t.isChecked() == on || !t.callChangeListener(on)) return false;
            t.setChecked(on);
            return true;
        }
        if (p instanceof ListPreference) {
            final ListPreference l = (ListPreference) p;
            if (def.equals(l.getValue()) || l.findIndexOfValue(def) < 0 || !l.callChangeListener(def)) return false;
            l.setValue(def);
            return true;
        }
        if (p instanceof EditTextPreference) {
            final EditTextPreference t = (EditTextPreference) p;
            if (def.equals(t.getText()) || !t.callChangeListener(def)) return false;
            t.setText(def);
            return true;
        }
        return false;
    }

    /** android:key -> android:defaultValue (resources resolved) of every row of preferences.xml. */
    static synchronized Map<String, String> defaults(Context context) {
        if (defaults != null) return defaults;
        final Map<String, String> out = new HashMap<>();
        final Resources res = context.getResources();
        try (XmlResourceParser x = res.getXml(R.xml.preferences)) {
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev != XmlPullParser.START_TAG) continue;
                final String key = value(res, x, "key"), def = value(res, x, "defaultValue");
                if (key != null && def != null) out.put(key, def);
            }
        } catch (Exception e) {
            Log.w(TAG, "preferences.xml defaults: " + e);
        }
        defaults = out;
        return out;
    }

    @Nullable
    private static String value(Resources res, XmlResourceParser x, String name) {
        final int id = x.getAttributeResourceValue(ANDROID, name, 0);
        if (id != 0) {
            try {
                final String type = res.getResourceTypeName(id);
                if ("bool".equals(type)) return String.valueOf(res.getBoolean(id));
                if ("integer".equals(type)) return String.valueOf(res.getInteger(id));
                return res.getString(id);
            } catch (Resources.NotFoundException e) {
                return null;
            }
        }
        return x.getAttributeValue(ANDROID, name);
    }
}
