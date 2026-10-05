package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manager for tunable settings - provides reset functionality
 */
public class TunableSettingsManager {
    private static final String TAG = "TunableSettingsMgr";
    private static final List<Class<?>> REGISTERED_CLASSES = new ArrayList<>();
    private static boolean autoRegistered = false;
    
    /**
     * Register a class for tunable management
     */
    public static void registerClass(Class<?> clazz) {
        if (!REGISTERED_CLASSES.contains(clazz)) {
            REGISTERED_CLASSES.add(clazz);
        }
    }
    
    /**
     * Automatically register all tunable classes.
     * This ensures classes are registered even if the tunable settings screen is never opened.
     */
    public static void ensureTunableClassesRegistered() {
        if (autoRegistered) {
            return; // Already registered
        }
        
        Log.d(TAG, "Auto-registering tunable classes...");
        
        for (Class<?> clazz : TunableRegistry.TUNABLE_CLASSES) {
            registerClass(clazz);
        }
        
        autoRegistered = true;
        Log.d(TAG, "Auto-registered " + REGISTERED_CLASSES.size() + " tunable classes");
    }
    
    /**
     * Get count of registered tunable classes
     */
    public static int getRegisteredClassCount() {
        return REGISTERED_CLASSES.size();
    }
    
    /**
     * Import tunable settings from a map (using native types)
     * @param context Context for SharedPreferences
     * @param tunableSettings Map of tunable settings
     */
    public static void importTunableSettings(Context context, Map<String, Object> tunableSettings) {
        if (tunableSettings == null || tunableSettings.isEmpty()) {
            Log.d(TAG, "No tunable settings to import");
            return;
        }
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        
        int importedCount = 0;
        
        for (Map.Entry<String, Object> entry : tunableSettings.entrySet()) {
            String settingKey = entry.getKey(); // Format: "ClassName.fieldName"
            Object value = entry.getValue();
            
            // Parse the key
            String[] parts = settingKey.split("\\.");
            if (parts.length != 2) {
                Log.w(TAG, "Invalid tunable setting key: " + settingKey);
                continue;
            }
            
            String className = parts[0];
            String fieldName = parts[1];
            String prefKey = "pref_tunable_" + className.toLowerCase(java.util.Locale.ROOT) + "_" + fieldName.toLowerCase(java.util.Locale.ROOT);
            
            // Find the field to determine if it's float or int
            Class<?> targetClass = findRegisteredClass(className);
            if (targetClass != null) {
                try {
                    Field field = targetClass.getDeclaredField(fieldName);
                    if (field.isAnnotationPresent(Tunable.class)) {
                        Tunable annotation = field.getAnnotation(Tunable.class);
                        Class<?> fieldType = field.getType();

                        if (fieldType == File.class) {
                            // File type: store filename string
                            String fileName = value != null ? value.toString() : "";
                            if (!fileName.isEmpty()) {
                                editor.putString(prefKey, fileName);
                            }
                        } else if (fieldType == String.class) {
                            // String type (list selector / free text)
                            String strValue = value != null ? value.toString() : "";
                            if (!strValue.isEmpty()) {
                                editor.putString(prefKey, strValue);
                            }
                        } else {
                            float step = annotation.step();
                            boolean isFloat = PreferenceNumber.floating(fieldType);

                            // Store as native type
                            if (isFloat) {
                                editor.putFloat(prefKey, (float) PreferenceNumber.read(value, annotation.defaultValue()));
                            } else {
                                editor.putInt(prefKey, (int) PreferenceNumber.read(value, annotation.defaultValue()));
                            }
                        }
                        importedCount++;
                        Log.d(TAG, "Imported tunable: " + settingKey + " = " + value);
                    }
                } catch (NoSuchFieldException e) {
                    Log.w(TAG, "Field not found: " + settingKey, e);
                }
            }
        }
        
        editor.apply();
        Log.d(TAG, "Imported " + importedCount + " tunable settings");
    }
    
    private static Class<?> findRegisteredClass(String className) {
        for (Class<?> clazz : REGISTERED_CLASSES) {
            if (clazz.getSimpleName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }
}

