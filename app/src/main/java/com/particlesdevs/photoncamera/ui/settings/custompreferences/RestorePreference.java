package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

import com.particlesdevs.photoncamera.util.ConfigFolder;

/**
 * Configs in Download/SCAMERA/XML, plus {@link #PICK}: the system picker, for a config this app did not save there
 * (copied from another phone), which MediaStore does not list.
 */
public class RestorePreference extends ListPreference {
    public static final String PICK = "__pick_config_file__";

    public RestorePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setOnPreferenceClickListener(preference -> {
            String[] names = ConfigFolder.list(context);
            String[] entries = new String[names.length + 1];
            String[] values = new String[names.length + 1];
            entries[0] = "Выбрать файл…";
            values[0] = PICK;
            for (int i = 0; i < names.length; i++) {
                entries[i + 1] = names[names.length - 1 - i]; // newest names (dated) first
                values[i + 1] = entries[i + 1];
            }
            setEntries(entries);
            setEntryValues(values);
            return true;
        });
    }
}
