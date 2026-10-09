package com.particlesdevs.photoncamera.api;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import com.particlesdevs.photoncamera.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.pro.SpecificSetting;
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

        //Spinlock waiting for specific manager
        for(int i =0; i<100; i++){
            if(PhotonCamera.getSpecific().isLoaded) break;
            try {
                Thread.sleep(1);
            } catch (InterruptedException ignored) {}
        }
        SpecificSetting sp = PhotonCamera.getSpecific().specificSetting;
        String[] ids = sp.cameraIDS;
        Log.d("CameraManager2", "Loaded ids:"+ Arrays.toString(ids));
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
            // P66: a Pixel scanned before the sensor-crop modules were told apart (knownLens) scans once more.
            if (Build.BRAND.equalsIgnoreCase("google") && !"p66".equals(mSettingsManager.getString(_CAMERAS, "scan_revision", ""))) {
                mSettingsManager.set(_CAMERAS, "scan_revision", "p66");
                mSettingsManager.remove(_CAMERAS, ALL_CAMERA_IDS_KEY);
                mSettingsManager.remove(_CAMERAS, ALL_CAMERA_LENS_KEY);
                mSettingsManager.remove(_CAMERAS, CAMERA_COUNT_KEY);
                Log.d("CameraManager2", "Pixel: camera scan cache dropped (P66 sensor-crop modules)");
            }
            if (!isLoaded()) {
                if(ids == null)
                    scanAllCameras(cameraManager);
                else {
                    for (String id : ids) {
                        try {
                            String physicalID = id;
                            if(id.contains("-")){
                                physicalID = id.split("-")[1];
                            }
                            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(physicalID);
                            CameraLensData cameraLensData = createNewCameraLensData(id, cameraCharacteristics);
                            mAllCameraIDsSet.add(id);
                            mCameraLensDataMap.put(id, cameraLensData);
                        } catch (Exception ignored) {
                        }
                    }
                    findLensZoomFactor(mCameraLensDataMap);
                }
                //Override ID detection
                save();
            } else {
                loadFromSave(cameraManager,ids);
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
                CameraLensData data = createNewCameraLensData(id, characteristics);
                mAllCameraIDsSet.add(id);
                mCameraLensDataMap.put(id, data);
                added = true;
            } catch (Exception ignored) {
            }
        }
        if (added) {
            findLensZoomFactor(mCameraLensDataMap);
            save();
        }
    }
    private void initExt(CameraManager cameraManager, String[] ids) {
        for (String id : ids) {
            try {
                int num = Integer.parseInt(id);
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(String.valueOf(num));
                log("BitAnalyser:" + num + ":" + intToReverseBinary(num));
                CameraLensData cameraLensData = createNewCameraLensData(String.valueOf(num), cameraCharacteristics);
                mAllCameraIDsSet.add(String.valueOf(num));
                mCameraLensDataMap.put(String.valueOf(num), cameraLensData);
                findLensZoomFactor(mCameraLensDataMap);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isLoaded(){
        return mSettingsManager.isSet(_CAMERAS, ALL_CAMERA_IDS_KEY);
    }

    private void loadFromSave(CameraManager cameraManager,String ids[]){
        mAllCameraIDsSet = mSettingsManager.getStringSet(_CAMERAS, ALL_CAMERA_IDS_KEY, null);
        //Retrieve the saved Set of CameraLensData JSON strings from SharedPreferences
        mCameraLensDataJSONSet = mSettingsManager.getStringSet(_CAMERAS, ALL_CAMERA_LENS_KEY, null);
        //Deserialize JSON and store CameraLensData objects into mCameraLensDataMap
        mCameraLensDataJSONSet.forEach(jsonString -> {
            CameraLensData cameraLensData = GSON.fromJson(jsonString, CameraLensData.class);
            mCameraLensDataMap.put(cameraLensData.getCameraId(), cameraLensData);
        });
        if(ids != null && mCameraLensDataJSONSet.size() < ids.length){
            initExt(cameraManager,ids);
            save();
        }
    }

    /**
     * MONO / NIR sensors are auxiliary streams, not photo lenses: Hybrid and SCAM HDR merge Bayer RAW only, and configuring the
     * vivo X100 Ultra's MONO id 6 with the RAW reader killed its camera HAL ('Error configuring streams: Broken pipe', every id
     * 'unknown device' for 4.5 s; owner's log 2026-10-05).
     */
    public static boolean isAuxiliarySensor(CameraCharacteristics characteristics) {
        Integer cfa = characteristics == null ? null : characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        return cfa != null && (cfa == 5 || cfa == 6); // SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO / _NIR (API 29)
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
