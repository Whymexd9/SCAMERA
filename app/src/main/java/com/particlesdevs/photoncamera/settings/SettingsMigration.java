package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import com.particlesdevs.photoncamera.R;
import org.xmlpull.v1.XmlPullParser;
import java.util.Map;

/** Only storage representation changes. Keys and valid user values survive menu moves/imports. */
public final class SettingsMigration {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private SettingsMigration() {}
    public static void prepare(Context context, SharedPreferences preferences) {
        Map<String, ?> values = preferences.getAll();
        SharedPreferences.Editor editor = preferences.edit();
        Object old = values.get("pref_tunable_esd4d_enableadaptivenoise");
        boolean migrateDisabledNoise = !values.containsKey("settings_audit_schema") && old != null && !PreferenceNumber.bool(old,true);
        if (!values.containsKey("settings_audit_schema")) {
            if (!values.containsKey("pref_tunable_esd4d_usencnnflow")
                    && PreferenceNumber.read(values.get("pref_processing_backend_key"),0)!=0)
                editor.putInt("pref_tunable_esd4d_usencnnflow",1); // preserve the old implicit default
            editor.putInt("settings_audit_schema",1);
        }
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.preferences)) {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                String key = attribute(context,parser,"key");
                if (key == null || !values.containsKey(key)) continue;
                String type = parser.getName(); Object value = values.get(key);
                if (type.contains("Switch") || type.contains("CheckBox")) {
                    if (!(value instanceof Boolean)) editor.putBoolean(key,PreferenceNumber.bool(value,
                            Boolean.parseBoolean(attribute(context,parser,"defaultValue"))));
                } else if (type.contains("ListPreference") && !type.contains("MultiSelect")
                        || type.contains("EditText") || type.contains("UniversalSeekBar")) {
                    String text = String.valueOf(value);
                    if ("pref_remosaic_block_key".equals(key)) text = PreferenceNumber.read(value,4)==2 ? "2" : "4";
                    int options = parser.getAttributeResourceValue(ANDROID,"entryValues",0);
                    if (options != 0) {
                        String[] entries = context.getResources().getStringArray(options);
                        for (String entry : entries) {
                            if (entry.equals(text)) break;
                            double candidate = PreferenceNumber.read(entry,Double.NaN);
                            if (Double.isFinite(candidate) && candidate == PreferenceNumber.read(text,Double.NaN)) { text=entry; break; }
                        }
                    }
                    if (!(value instanceof String) || !text.equals(value)) editor.putString(key,text);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot normalize settings schema",e);
        }
        // Apply semantic migration after type normalization, which uses the original snapshot.
        if (migrateDisabledNoise) editor.putBoolean("pref_noise_dynamic_enabled_key",false);
        editor.apply();
    }
    /** Upgrade obsolete MFSR controls once for each restored module snapshot. */
    public static void migrateMultiFrame(SharedPreferences preferences) {
        Map<String, ?> values=preferences.getAll();
        SharedPreferences.Editor e=preferences.edit();
        if(!values.containsKey("settings_zsl_capacity_v2")) {
            e.putString("pref_zsl_buffer_count_key","50");
            e.putBoolean("settings_zsl_capacity_v2",true);
        }
        if(!values.containsKey("pref_mfsr_source_key")) {
            boolean clustered=PreferenceNumber.bool(values.get("pref_remosaic_enabled_key"),false);
            int block=clustered ? (PreferenceNumber.read(values.get("pref_remosaic_block_key"),4)==2 ? 2 : 4) : 1;
            e.putString("pref_mfsr_source_key",String.valueOf(block));
        }
        for(String old:new String[]{"k_detail","k_denoise","k_stretch","k_shrink","dth","dtr","tensor_stride","grad_k"})
            e.remove("pref_mfsr_"+old+"_key");
        // Calibration is a one-shot action, never a saved or copied camera profile.
        e.remove("pref_mfsr_calibrate_key");e.commit();
    }
    private static String attribute(Context context, XmlResourceParser parser, String name) {
        int id=parser.getAttributeResourceValue(ANDROID,name,0);
        if(id==0) return parser.getAttributeValue(ANDROID,name);
        android.util.TypedValue value=new android.util.TypedValue();
        context.getResources().getValue(id,value,true);
        CharSequence text=value.coerceToString();return text==null ? null : text.toString();
    }
}
