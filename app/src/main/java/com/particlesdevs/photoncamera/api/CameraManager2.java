package com.particlesdevs.photoncamera.api;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import com.particlesdevs.photoncamera.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.particlesdevs.photoncamera.settings.PreferenceKeys.Key.ALL_CAMERA_IDS_KEY;
import static com.particlesdevs.photoncamera.settings.PreferenceKeys.Key.ALL_CAMERA_LENS_KEY;
import static com.particlesdevs.photoncamera.settings.PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME;
import static com.particlesdevs.photoncamera.settings.PreferenceKeys.Key.CAMERA_COUNT_KEY;

/**
 * Responsible for Scanning all Camera IDs on a Device and Storing them in SharedPreferences as a {@link Set<String>}
 */
public final class CameraManager2 {
    private static final String _CAMERAS = CAMERAS_PREFERENCE_FILE_NAME.mValue;
    private static final String TAG = "CameraManager2";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, CameraLensData> mCameraLensDataMap = new LinkedHashMap<>();
    private final SettingsManager mSettingsManager;
    /**
     * This Set stores the set of all valid camera IDs as String,
     * to be saved in SharedPreferences
     */
    private Set<String> mAllCameraIDsSet = new LinkedHashSet<>();
    /**
     * This Set stores the set of {@link CameraLensData} objects as JSON strings,
     * to be saved in SharedPreferences
     */
    private Set<String> mCameraLensDataJSONSet = new LinkedHashSet<>();

    /**
     * Initialise this class.
     *
     * @param cameraManager   {@link CameraManager} instance from {@link android.content.Context#CAMERA_SERVICE}
     * @param settingsManager {@link SettingsManager}
     */
    public CameraManager2(CameraManager cameraManager, SettingsManager settingsManager) {
        this.mSettingsManager = settingsManager;

            // The scan is cached; a changed package spoof changes which cameras the service
            // exposes (hidden lenses appear), so the cached list would keep hiding them.
            String spoofSignature = com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof
                    .signature(mSettingsManager.getContext());
            if (!spoofSignature.equals(mSettingsManager.getString(_CAMERAS, "spoof_signature", "off"))) {
                mSettingsManager.set(_CAMERAS, "spoof_signature", spoofSignature);
                mSettingsManager.remove(_CAMERAS, ALL_CAMERA_IDS_KEY);
                mSettingsManager.remove(_CAMERAS, ALL_CAMERA_LENS_KEY);
                mSettingsManager.remove(_CAMERAS, CAMERA_COUNT_KEY);
                Log.d("CameraManager2", "Package spoof changed (" + spoofSignature + "): camera scan cache dropped");
            }
            // A scan from before the auxiliary sensors without lens / gain / exposure were left out (isAuxiliarySensor) runs once more.
            if (!"aux4".equals(mSettingsManager.getString(_CAMERAS, "aux_revision", ""))) {
                // aux3: vivo teleconverter / dummy profiles; aux4 (P120): vivo multispectral / pre-ISP sensors (the X300 Ultra's
                // camera 7 took every camera down when opened) and depth-only cameras
                mSettingsManager.set(_CAMERAS, "aux_revision", "aux4");
                mSettingsManager.remove(_CAMERAS, ALL_CAMERA_IDS_KEY);
                mSettingsManager.remove(_CAMERAS, ALL_CAMERA_LENS_KEY);
                mSettingsManager.remove(_CAMERAS, CAMERA_COUNT_KEY);
                Log.d("CameraManager2", "camera scan cache dropped (auxiliary sensors without lens / gain / exposure)");
            }
            // P66: a Pixel scanned before the sensor-crop modules were told apart (knownLens) scans once more.
            if (Build.BRAND.equalsIgnoreCase("google") && !"p66".equals(mSettingsManager.getString(_CAMERAS, "scan_revision", ""))) {
                mSettingsManager.set(_CAMERAS, "scan_revision", "p66");
                mSettingsManager.remove(_CAMERAS, ALL_CAMERA_IDS_KEY);
                mSettingsManager.remove(_CAMERAS, ALL_CAMERA_LENS_KEY);
                mSettingsManager.remove(_CAMERAS, CAMERA_COUNT_KEY);
                Log.d("CameraManager2", "Pixel: camera scan cache dropped (P66 sensor-crop modules)");
            }
            if (!isLoaded()) {
                scanAllCameras(cameraManager);
                save();
            } else {
                loadFromSave();
            }
        // Lens discovery may expose vendor/physical IDs which are absent from
        // the normal CameraManager enumeration.  Materialize the user's
        // selections so they appear in the actual switcher after restart.
        Set<String> userIds = mSettingsManager.getStringSet(
                "default_scope", "user_camera_ids", new HashSet<>());
        boolean added = false;
        for (String id : userIds) {
            if (mCameraLensDataMap.containsKey(id)) continue;
            try {
                String physicalId = id;
                if (id.contains("/")) physicalId = id.split("/", 2)[1];
                else if (id.contains("-")) physicalId = id.split("-", 2)[1];
                CameraCharacteristics characteristics;
                try {
                    characteristics = cameraManager.getCameraCharacteristics(id);
                } catch (Exception ignored) {
                    characteristics = cameraManager.getCameraCharacteristics(physicalId);
                }
                // a service stream too (a vivo teleconverter profile, MONO / NIR): the user chose it (owner, 2026-10-28: the
                // X300 Ultra's camera 9 turns the picture for the telephoto extender)
                CameraLensData data = createNewCameraLensData(id, characteristics);
                mAllCameraIDsSet.add(id);
                mCameraLensDataMap.put(id, data);
                // against the scan's main lens of its side: findLensZoomFactor over the whole map took the first lens of a map
                // loaded from a saved set (any order) as the main one - the 0.4x ultra-wide made the main 2.5x and the tele 6.1x
                // (owner's log 2026-10-28, vivo V2562)
                data.setZoomFactor(zoomAgainstMain(data));
                added = true;
            } catch (Exception ignored) {
            }
        }
        if (added) save();
    }

    /** A lens added after the scan: its zoom against the lens of its side the scan made 1x (else the first of its side). */
    private float zoomAgainstMain(CameraLensData lens) {
        CameraLensData main = null;
        for (CameraLensData c : mCameraLensDataMap.values()) {
            if (c == lens || c.getFacing() != lens.getFacing()) continue;
            if (Math.abs(c.getZoomFactor() - 1f) < 1e-3f) {
                main = c;
                break;
            }
            if (main == null) main = c;
        }
        return main == null ? 1f : lens.getCamera35mmFocalLength() / main.getCamera35mmFocalLength();
    }

    private boolean isLoaded(){
        return mSettingsManager.isSet(_CAMERAS, ALL_CAMERA_IDS_KEY);
    }

    private void loadFromSave(){
        mAllCameraIDsSet = mSettingsManager.getStringSet(_CAMERAS, ALL_CAMERA_IDS_KEY, null);
        //Retrieve the saved Set of CameraLensData JSON strings from SharedPreferences
        mCameraLensDataJSONSet = mSettingsManager.getStringSet(_CAMERAS, ALL_CAMERA_LENS_KEY, null);
        //Deserialize JSON and store CameraLensData objects into mCameraLensDataMap
        mCameraLensDataJSONSet.forEach(jsonString -> {
            CameraLensData cameraLensData = GSON.fromJson(jsonString, CameraLensData.class);
            mCameraLensDataMap.put(cameraLensData.getCameraId(), cameraLensData);
        });
    }

    /**
     * MONO / NIR sensors are auxiliary streams, not photo lenses: Hybrid and SCAM HDR merge Bayer RAW only, and configuring the
     * vivo X100 Ultra's MONO id 6 with the RAW reader killed its camera HAL ('Error configuring streams: Broken pipe', every id
     * 'unknown device' for 4.5 s; owner's log 2026-10-05).
     */
    public static boolean isAuxiliarySensor(CameraCharacteristics characteristics) {
        if (characteristics == null) return false;
        Integer cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (cfa != null && (cfa == 5 || cfa == 6)) return true; // SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO / _NIR (API 29)
        // vivo teleconverter / dummy profiles (owner, 2026-10-13: «X300 его не поддерживает»); scam_dev vivo_teleconverter 1 keeps them
        if (AuxiliarySensors.vivoProfileOnly(vivoCameraType(characteristics))
                && !com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("vivo_teleconverter", false)) return true;
        if (AuxiliarySensors.notAPhotoCamera(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))) return true;
        float[] focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        android.util.Range<Integer> iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        android.util.Range<Long> exposure = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        return AuxiliarySensors.noLensGainOrExposure(focal != null && focal.length > 0 ? focal[0] : Float.NaN,
                iso != null ? iso.getUpper() : -1, exposure != null ? exposure.getUpper() : -1L);
    }


    /** com.vivo.chi.override.CameraType («Master», «Tele3xLong», «Tele3xLongExternal», «DUMMY»…), null when absent. */
    @androidx.annotation.Nullable
    public static String vivoCameraType(CameraCharacteristics characteristics) {
        try {
            byte[] v = characteristics.get(new CameraCharacteristics.Key<>("com.vivo.chi.override.CameraType", byte[].class));
            if (v == null) return null;
            int n = 0;
            while (n < v.length && v[n] != 0) n++;
            return new String(v, 0, n, java.nio.charset.StandardCharsets.US_ASCII);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean isAuxiliarySensor(CameraManager cameraManager, String id) {
        if (cameraManager == null || id == null) return false;
        try {
            return isAuxiliarySensor(cameraManager.getCameraCharacteristics(id));
        } catch (Exception direct) {
            String physical = id.contains("/") ? id.split("/", 2)[1] : id.contains("-") ? id.split("-", 2)[1] : null;
            try {
                return physical != null && isAuxiliarySensor(cameraManager.getCameraCharacteristics(physical));
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private void scanAllCameras(CameraManager cameraManager) {
        boolean isSamsung = Build.BRAND.equalsIgnoreCase("samsung") || Build.BRAND.equalsIgnoreCase("google");
        boolean isGoogle = Build.BRAND.equalsIgnoreCase("google");
            CameraLensData mainLensData = null;
            try {
                mainLensData = createNewCameraLensData("0", cameraManager.getCameraCharacteristics("0"));
                for (int num = 0; num < 121; num++) {
                    try {
                        String formatID = String.valueOf(num);
                        if(isSamsung){
                            formatID = "0-" + formatID;
                        }
                        CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(String.valueOf(num));
                        log("BitAnalyser:" + num + ":" + intToReverseBinary(num));
                        CameraLensData cameraLensData = createNewCameraLensData(formatID, cameraCharacteristics);
                        if (mainLensData.getCameraFocalLength() == cameraLensData.getCameraFocalLength()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                boolean isLogical = !cameraCharacteristics.getPhysicalCameraIds().isEmpty();
                                if (!getBit(6, num) && !knownLens(cameraLensData, isGoogle) && !isLogical && !isAuxiliarySensor(cameraCharacteristics)) {
                                    mAllCameraIDsSet.add(formatID);
                                    mCameraLensDataMap.put(formatID, cameraLensData);
                                    break;
                                }
                            } else {
                                if (!getBit(6, num) && !knownLens(cameraLensData, isGoogle) && !isAuxiliarySensor(cameraCharacteristics)) {
                                    mAllCameraIDsSet.add(formatID);
                                    mCameraLensDataMap.put(formatID, cameraLensData);
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {

            }
            for (int num = 0; num < 121; num++) {
                try {
                    String formatID = String.valueOf(num);
                    if(isSamsung){
                        formatID = "0-" + formatID;
                    }
                    CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(String.valueOf(num));
                    log("BitAnalyser:" + num + ":" + intToReverseBinary(num));
                    CameraLensData cameraLensData = createNewCameraLensData(formatID, cameraCharacteristics);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        boolean isLogical = !cameraCharacteristics.getPhysicalCameraIds().isEmpty();
                        if (!getBit(6, num) && !knownLens(cameraLensData, isGoogle) && !isLogical && !isAuxiliarySensor(cameraCharacteristics)) {
                            mAllCameraIDsSet.add(formatID);
                            mCameraLensDataMap.put(formatID, cameraLensData);
                        }
                    } else {
                        if (!getBit(6, num) && !knownLens(cameraLensData, isGoogle) && !isAuxiliarySensor(cameraCharacteristics)) {
                            mAllCameraIDsSet.add(formatID);
                            mCameraLensDataMap.put(formatID, cameraLensData);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if(mAllCameraIDsSet.size() == 0) {
                for(int i = 0; i<2;i++){
                    try {
                        String formatID = String.valueOf(i);
                        if(isSamsung){
                            formatID = "0-" + formatID;
                        }
                        CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(String.valueOf(i));
                        CameraLensData cameraLensData = createNewCameraLensData(formatID, cameraCharacteristics);
                        mAllCameraIDsSet.add(formatID);
                        mCameraLensDataMap.put(formatID, cameraLensData);
                        } catch (Exception ignored) {
                        }
                }
            }

        findLensZoomFactor(mCameraLensDataMap);
    }

    /**
     * Whether the scan already holds this lens. CameraLensData equals by facing, focal length, aperture and flash; P66: on a
     * Pixel (Pixel 7: camera 4 is the main sensor's 2x in-sensor crop, 6.81 mm f/1.85 like camera 2 on half the sensor width,
     * 50 mm instead of 25 mm equivalent; front 5 is a wider crop of the front sensor) an ID whose sensor area gives another
     * field of view is another module, so the 35 mm focal length must match too.
     */
    private boolean knownLens(CameraLensData lens, boolean byField) {
        if (!byField) return mCameraLensDataMap.containsValue(lens);
        for (CameraLensData known : mCameraLensDataMap.values())
            if (CameraLensData.sameLens(known, lens, true)) return true;
        return false;
    }

    private CameraLensData createNewCameraLensData(String cameraId, CameraCharacteristics characteristics) {
        CameraLensData cameraLensData = new CameraLensData(cameraId);
        cameraLensData.setFacing(characteristics.get(CameraCharacteristics.LENS_FACING));
        cameraLensData.setCameraFocalLength(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0]);
        cameraLensData.setCameraAperture(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)[0]);
        cameraLensData.setCamera35mmFocalLength((36.0f / characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE).getWidth() * cameraLensData.getCameraFocalLength()));
        cameraLensData.setFlashSupported(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        return cameraLensData;
    }

    /**
     * This method finds the optical zoom factor of multiple camera lenses with respect to the Main Camera for each facing(ie. Front and Back)
     * <p>
     * Note: Here, it is assumed that the first camera in the list for each facing is the Main Camera for that facing.
     *
     * @param mCameraLensData Map of all valid CameraLensData objects
     */
    private void findLensZoomFactor(Map<String, CameraLensData> mCameraLensData) {
        CameraLensData mainBack = null;
        CameraLensData mainFront = null;
        for (Map.Entry<String, CameraLensData> entry : mCameraLensData.entrySet()) {
            CameraLensData cameraLensData = entry.getValue();
            if (cameraLensData.getFacing() == CameraCharacteristics.LENS_FACING_FRONT) {
                if (mainFront == null) {
                    mainFront = cameraLensData;
                }
                cameraLensData.setZoomFactor(cameraLensData.getCamera35mmFocalLength() / mainFront.getCamera35mmFocalLength());
            } else if (cameraLensData.getFacing() == CameraCharacteristics.LENS_FACING_BACK) {
                if (mainBack == null) {
                    mainBack = cameraLensData;
                }
                cameraLensData.setZoomFactor(cameraLensData.getCamera35mmFocalLength() / mainBack.getCamera35mmFocalLength());
            }
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }

    private void save() {
        // the set from loadFromSave would keep each lens's old line beside its new one (two zooms of one camera)
        mCameraLensDataJSONSet = new LinkedHashSet<>();
        mSettingsManager.set(_CAMERAS, CAMERA_COUNT_KEY, mAllCameraIDsSet.size());
        mSettingsManager.set(_CAMERAS, ALL_CAMERA_IDS_KEY, mAllCameraIDsSet);

        //Serialise CameraLensData objects to JSON and store them to mCameraLensDataJSONSet
        mCameraLensDataMap.forEach((id, lensData) -> mCameraLensDataJSONSet.add(GSON.toJson(lensData)));
        //Save mCameraLensDataJSONSet to SharedPreferences
        mSettingsManager.set(_CAMERAS, ALL_CAMERA_LENS_KEY, mCameraLensDataJSONSet);
    }

    //Getters===========================================================================================================

    /**
     * @return the list of scanned Camera IDs
     */
    public String[] getCameraIdList() {
        log("CameraCount:" + mAllCameraIDsSet.size()
                + ", CameraIDs:" + Arrays.toString(mAllCameraIDsSet.toArray(new String[0])));
        return mAllCameraIDsSet.toArray(new String[0]);
    }

    /**
     * @return the map of CameraLensData
     */
    public Map<String, CameraLensData> getCameraLensDataMap() {
        Log.d(TAG,"LensData : \n" + mCameraLensDataMap);
        return mCameraLensDataMap;
    }

    //Bit analyzer for AUX number=======================================================================================

    private boolean getBit(int pos, int val) {
        return ((val >> (pos - 1)) & 1) == 1;
    }

    private String intToReverseBinary(int num) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            sb.append(getBit(i, num) ? "1" : "0");
        }
        return sb.toString();
    }
}
