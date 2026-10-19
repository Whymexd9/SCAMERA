package com.particlesdevs.photoncamera.api;

import android.annotation.SuppressLint;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.util.Log;

import java.lang.reflect.Array;
import java.util.List;

public class VendorTagUtils {
    private static final String TAG = "VendorTagUtils";
    private static volatile boolean clientNameSkipLogged;
    private static boolean isSupported(CaptureRequest.Builder builder,
                                       CaptureRequest.Key<?> key) {
        boolean supported = true;
        try {
            builder.get(key);
        }catch(IllegalArgumentException exception){
            supported = false;
            Log.w(TAG,"vendor tag " + key.getName() + " is not supported");
        }
        if ( supported ) {
            Log.d(TAG,"vendor tag " + key.getName() + " is supported");
        }
        return supported;
    }

    @SuppressLint("NewApi")
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setPhysical(CaptureRequest.Builder builder, CaptureRequest.Key key, Object value, String physicalId) {
        try {
            builder.setPhysicalCameraKey(key, value, physicalId);
        } catch (Exception e) {
            Log.w(TAG, "Error setting physical camera key: " + key.getName() + " for camera: " + physicalId, e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setKeyValue(CaptureRequest.Builder builder, CaptureRequest.Key key, Object value) {
        builder.set(key, value);
    }

    /**
     * A user-defined vendor tag that can be created per physical sensor in the
     * Sensor Configurations settings. Supports both {@link CaptureRequest} and
     * {@link CaptureResult} keys, though only request keys are applied to the
     * {@link CaptureRequest.Builder} during capture.
     */
    public static class TunableKey {
        /** P122: a key set in the session parameters only, never in a request (vivo X300 Ultra's forceSensorMode). */
        public static final String TYPE_SESSION = "Session";
        /** "CaptureRequest", {@link #TYPE_SESSION} or "CaptureResult" */
        public String type = "CaptureRequest";
        /** Vendor tag string, e.g. "com.qti.sensorbps.mode_index" */
        public String name = "";
        /** "Integer", "Long", "Float", "Byte", "int[]", ... */
        public String valueType = "Integer";
        /** Value as a string (comma separated for arrays) */
        public String value = "0";
        /** Whether the key was tested against the camera and found supported */
        public boolean supported = false;
        /** Whether the key has been tested yet (only possible via VendorTagUtils at capture) */
        public boolean tested = false;
        /** P122: off = kept in the list but not sent (module settings switch); older lists have no field: on. */
        public boolean enabled = true;

        public TunableKey() {}

        public TunableKey(String type, String name, Class<?> valueClass, Object value) {
            this.type = type;
            this.name = name;
            this.valueType = valueTypeForClass(valueClass);
            this.value = valueToString(valueClass, value);
        }

        public Object parseValue() {
            return parseValue(valueType, value);
        }

        public CaptureRequest.Key<?> toCaptureRequestKey() {
            return new CaptureRequest.Key<>(name, classForValueType(valueType));
        }

        public CaptureResult.Key<?> toCaptureResultKey() {
            return new CaptureResult.Key<>(name, classForValueType(valueType));
        }

        public static String valueTypeForClass(Class<?> valueClass) {
            if (valueClass == null) return "Integer";
            if (valueClass == Integer.class) return "Integer";
            if (valueClass == Long.class) return "Long";
            if (valueClass == Float.class) return "Float";
            if (valueClass == Double.class) return "Double";
            if (valueClass == Byte.class) return "Byte";
            if (valueClass == Short.class) return "Short";
            if (valueClass == int[].class) return "int[]";
            if (valueClass == byte[].class) return "byte[]";
            if (valueClass == long[].class) return "long[]";
            if (valueClass == float[].class) return "float[]";
            if (valueClass == String.class) return "String";
            return "Integer";
        }

        public static Class<?> classForValueType(String valueType) {
            if (valueType == null) return Integer.class;
            switch (valueType) {
                case "Long": return Long.class;
                case "Float": return Float.class;
                case "Double": return Double.class;
                case "Byte": return Byte.class;
                case "Short": return Short.class;
                case "int[]": return int[].class;
                case "byte[]": return byte[].class;
                case "long[]": return long[].class;
                case "float[]": return float[].class;
                case "String": return String.class;
                case "Integer":
                default: return Integer.class;
            }
        }

        public static String valueToString(Class<?> valueClass, Object value) {
            if (value == null) return "0";
            if (valueClass != null && valueClass.isArray()) {
                StringBuilder sb = new StringBuilder();
                int len = Array.getLength(value);
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(Array.get(value, i));
                }
                return sb.toString();
            }
            return String.valueOf(value);
        }

        public static Object parseValue(String valueType, String value) {
            if (value == null) value = "";
            String vt = valueType == null ? "Integer" : valueType;
            switch (vt) {
                case "Long": return Long.parseLong(value.trim());
                case "Float": return Float.parseFloat(value.trim());
                case "Double": return Double.parseDouble(value.trim());
                case "Byte": return Byte.parseByte(value.trim());
                case "Short": return Short.parseShort(value.trim());
                case "int[]": {
                    String[] parts = value.split(",");
                    int[] arr = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) arr[i] = Integer.parseInt(parts[i].trim());
                    return arr;
                }
                case "byte[]": {
                    String[] parts = value.split(",");
                    byte[] arr = new byte[parts.length];
                    for (int i = 0; i < parts.length; i++) arr[i] = Byte.parseByte(parts[i].trim());
                    return arr;
                }
                case "long[]": {
                    String[] parts = value.split(",");
                    long[] arr = new long[parts.length];
                    for (int i = 0; i < parts.length; i++) arr[i] = Long.parseLong(parts[i].trim());
                    return arr;
                }
                case "float[]": {
                    String[] parts = value.split(",");
                    float[] arr = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) arr[i] = Float.parseFloat(parts[i].trim());
                    return arr;
                }
                case "String": return value;
                case "Integer":
                default: return Integer.parseInt(value.trim());
            }
        }
    }

    /**
     * Apply a list of user-defined tunable keys to a request builder.
     * Tests each key for support (via {@code builder.get}) and only sets it when
     * supported. Updates the {@code tested}/{@code supported} flags in place so the
     * settings UI can show green/red feedback afterwards.
     */
    public static void applyTunableKeys(CaptureRequest.Builder builder, List<TunableKey> keys, String physicalId) {
        applyTunableKeys(builder, keys, physicalId, false);
    }

    /**
     * {@code session}: the builder of the session parameters, which takes the {@link TunableKey#TYPE_SESSION} keys only (the
     * request keys reach it through builderSessionApply); otherwise the request keys, the session keys skipped.
     */
    public static void applyTunableKeys(CaptureRequest.Builder builder, List<TunableKey> keys, String physicalId, boolean session) {
        if (builder == null || keys == null) return;
        for (TunableKey tunableKey : keys) {
            if (tunableKey == null) continue;
            if (!tunableKey.enabled || TunableKey.TYPE_SESSION.equals(tunableKey.type) != session) continue;
            tunableKey.tested = true;
            if (!"CaptureRequest".equals(tunableKey.type) && !TunableKey.TYPE_SESSION.equals(tunableKey.type)) {
                Log.w(TAG, "Tunable key " + tunableKey.name + " is a " + tunableKey.type + " key, cannot be applied to the request builder");
                tunableKey.supported = false;
                continue;
            }
            try {
                CaptureRequest.Key<?> key = tunableKey.toCaptureRequestKey();
                boolean supported = isSupported(builder, key);
                tunableKey.supported = supported;
                if (supported) {
                    Object parsedValue = tunableKey.parseValue();
                    setKeyValue(builder, key, parsedValue);
                    // the session parameters take the key as it is (as MotionCam sets its session keys), no physical copy
                    if (!session && physicalId != null && !physicalId.isEmpty()) {
                        setPhysical(builder, key, parsedValue, physicalId);
                    }
                    Log.d(TAG, "Applied tunable key " + tunableKey.name + " = " + tunableKey.value + " (" + tunableKey.valueType + ")");
                }
            } catch (Exception e) {
                tunableKey.supported = false;
                Log.w(TAG, "Error applying tunable key " + tunableKey.name, e);
            }
        }
    }

    @SuppressLint({"NewApi", "LocalSuppress"})
    public static void builderSessionApply(CaptureRequest.Builder builder, boolean burst, boolean useMaximumResolutionKey, String physicalId) {
        try {
            // Owner: no impersonation of the stock camera on any phone. com.xiaomi.sessionparams.clientName = com.android.camera is
            // never sent: the 17 Ultra's HAL still knew the real package (isThirdParty = 1), and on the 13 Ultra (2026-10-10 log)
            // the sessions that carried it stopped delivering frames.
            if (!clientNameSkipLogged) {
                clientNameSkipLogged = true;
                Log.i(TAG, "com.xiaomi.sessionparams.clientName not sent (no stock-camera impersonation)");
            }
        } catch (Exception e){
            Log.w(TAG, "Error applying vendor tags to CaptureRequest.Builder", e);
        }
        if(useMaximumResolutionKey) {
            builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        }
        com.particlesdevs.photoncamera.settings.TunableKeyManager.applyTunableKeys(builder, physicalId);
        // P103: the HAL keys of «Движение» / Ideal RAW, on the session and every request
        com.particlesdevs.photoncamera.capture.HiddenFeatures.apply(builder,
                com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics);
        com.particlesdevs.photoncamera.capture.VivoQcomModes.apply(builder,
                com.particlesdevs.photoncamera.capture.CaptureController.mCameraCharacteristics, physicalId);
        com.particlesdevs.photoncamera.capture.VivoPd2454Isz.mirrorRequest(builder, physicalId);
        // P274: the X300 Ultra's forceSensorMode is a session key the camera service drops when a request lacks it
        com.particlesdevs.photoncamera.capture.VivoQcomIsz.mirrorRequest(builder);
        com.particlesdevs.photoncamera.capture.VivoPd2454NativeRaw.apply(builder, physicalId);
    }
}
