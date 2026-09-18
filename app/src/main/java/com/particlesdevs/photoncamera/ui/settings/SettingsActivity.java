package com.particlesdevs.photoncamera.ui.settings;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.material.snackbar.Snackbar;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.app.base.BaseActivity;
import com.particlesdevs.photoncamera.pro.SupportedDevice;
import com.particlesdevs.photoncamera.settings.BackupRestoreUtil;
import com.particlesdevs.photoncamera.processing.render.NoiseModelProfile;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.TunablePreferenceGenerator;
import com.particlesdevs.photoncamera.ui.camera.LensDiscoveryActivity;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ResetPreferences;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunablePngPreference;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.util.log.FragmentLifeCycleMonitor;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

import static com.particlesdevs.photoncamera.settings.PreferenceKeys.Key.ALL_DEVICES_NAMES_KEY;
import static com.particlesdevs.photoncamera.settings.PreferenceKeys.SCOPE_GLOBAL;

public class SettingsActivity extends BaseActivity implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    private static int sCameraMode = -1;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(PreferenceKeys.getThemeValue());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        // Get camera mode from intent
        sCameraMode = getIntent().getIntExtra("camera_mode", -1);
        
        // Setup window insets to handle navigation bar
        setupWindowInsets();
        
        if (savedInstanceState == null) getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentLifeCycleMonitor(), true);

    }
    
    private void setupWindowInsets() {
        View settingsContainer = findViewById(R.id.settings_container);
        if (settingsContainer != null) {
            ViewCompat.setOnApplyWindowInsetsListener(settingsContainer, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                int navbarBottom = insets.bottom;
                
                // Apply margin bottom if navigation bar is present
                MarginLayoutParams layoutParams = (MarginLayoutParams) v.getLayoutParams();
                if (layoutParams != null) {
                    layoutParams.bottomMargin = navbarBottom;
                    v.setLayoutParams(layoutParams);
                }
                
                // Return consumed insets to prevent default behavior
                return windowInsets;
            });
            
            // Request insets to be applied
            ViewCompat.requestApplyInsets(settingsContainer);
        }
    }

    public void back(View view) {
        onBackPressed();
    }
    @Override
    public boolean onPreferenceStartScreen(@NonNull PreferenceFragmentCompat preferenceFragmentCompat,
                                           PreferenceScreen preferenceScreen) {
        Log.d("SettingsActivity", "onPreferenceStartScreen called for key: " + preferenceScreen.getKey());
        
        // Note: Tunable preferences are already generated in onPreferenceTreeClick before reaching here
        
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.animate_slide_left_enter, R.anim.animate_slide_left_exit
                        , R.anim.animate_card_enter, R.anim.animate_slide_right_exit);
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, preferenceScreen.getKey());
        fragment.setArguments(args);
        ft.replace(R.id.settings_container, fragment, preferenceScreen.getKey());
        ft.addToBackStack(preferenceScreen.getKey());
        ft.commit();
        return true;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener, PreferenceManager.OnPreferenceTreeClickListener {
        private static final String KEY_MAIN_PARENT_SCREEN = "prefscreen";
        private Activity activity;
        private SettingsManager mSettingsManager;
        private Context mContext;
        private View mRootView;
        private SupportedDevice supportedDevice;
        private boolean tunablePreferencesGenerated = false;
        private boolean sensorConfigPreferencesGenerated = false;
        private ActivityResultLauncher<String[]> lutImportLauncher;
        private ActivityResultLauncher<String[]> noiseModelImportLauncher;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mContext = requireContext();
            mSettingsManager = PhotonCamera.getSettingsManagerStatic();
            com.particlesdevs.photoncamera.settings.SettingsMigration.prepare(requireContext(), mSettingsManager.getDefaultPreferences());
            setPreferencesFromResource(R.xml.preferences, null);
            generateTunablePreferences();
            if (rootKey != null) {
                PreferenceScreen selected = findPreference(rootKey);
                if (selected == null) throw new IllegalArgumentException("Unknown settings page: " + rootKey);
                setPreferenceScreen(selected);
            }
            seedMissingListValues(getPreferenceScreen());
            setupScalarInputs(getPreferenceScreen());
            setupRemosaicBackend();
            setupOriginalNoiseReduction();
            updateHexQuadDenoiseControls(PreferenceKeys.getRemosaicBackend());
        }

        private void setupScalarInputs(PreferenceGroup group) {
            for (int i=0; i<group.getPreferenceCount(); i++) {
                Preference p=group.getPreference(i);
                if (p instanceof PreferenceGroup) { setupScalarInputs((PreferenceGroup)p); continue; }
                if (!(p instanceof androidx.preference.EditTextPreference) || p.getKey()==null) continue;
                double[] bounds=com.particlesdevs.photoncamera.settings.SettingsNumericRules.bounds(p.getKey());
                if (bounds==null) continue;
                androidx.preference.EditTextPreference edit=(androidx.preference.EditTextPreference)p;
                edit.setOnBindEditTextListener(input -> input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        | (bounds[2]==1 ? 0 : android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)));
                edit.setOnPreferenceChangeListener((preference,value) -> {
                    String error=com.particlesdevs.photoncamera.settings.SettingsNumericRules.error(p.getKey(),value);
                    if(error!=null) PhotonCamera.showToast(error);
                    return error==null;
                });
            }
        }

        private void updateHexQuadDenoiseControls(String backend) {
            boolean active=PreferenceKeys.isRemosaicEnabled() && "hp9_hexquad".equals(backend);
            boolean auto=PreferenceKeys.isHexQuadAutoIso();
            for(String key:new String[]{"hexquad_compute","hexquad_exposure_ev","hexquad_model","hexquad_full_resolution","hexquad_noise_overall",
                    "hexquad_noise_photon","hexquad_noise_readout","hexquad_auto_iso","hexquad_luma","hexquad_chroma",
                    "hexquad_iso_low_luma","hexquad_iso_low_chroma","hexquad_iso_high_luma","hexquad_iso_high_chroma",
                    "hexquad_texture","hexquad_post_denoise"}){
                Preference p=findPreference(key);if(p==null)continue;
                boolean enabled=active;
                if(key.equals("hexquad_full_resolution"))enabled &= PreferenceKeys.getHexQuadModelScale()==2;
                if(key.equals("hexquad_luma")||key.equals("hexquad_chroma"))enabled &= !auto;
                if(key.startsWith("hexquad_iso_"))enabled &= auto;
                p.setEnabled(enabled);
            }
        }

        private void setupOriginalNoiseReduction() {
            ListPreference backend = findPreference("pref_rt_denoise_backend");
            if (backend != null) backend.setOnPreferenceChangeListener((pref, value) -> {
                updateOriginalNoiseControls("rt512".equals(String.valueOf(value)));
                return true;
            });
            updateOriginalNoiseControls(com.particlesdevs.photoncamera.settings.RawTherapeeSettings.original());
            String[] curves = {"rt512_lcurve", "rt512_ccurve"};
            for (String key : curves) {
                Preference pref = findPreference(key);
                if (pref != null) pref.setOnPreferenceChangeListener((p, value) -> {
                    try {
                        com.particlesdevs.photoncamera.settings.RawTherapeeSettings.curve(String.valueOf(value));
                        updateOriginalNoiseDependencies(key, String.valueOf(value));
                        return true;
                    } catch (IllegalArgumentException e) {
                        PhotonCamera.showToast(e.getMessage()); return false;
                    }
                });
            }
            for (String key : new String[]{"rt512_auto", "rt512_median", "rt512_gain"}) {
                Preference pref = findPreference(key);
                if (pref != null) pref.setOnPreferenceChangeListener((p, value) -> {
                    updateOriginalNoiseDependencies(key, String.valueOf(value)); return true;
                });
            }
            Preference kernel = findPreference("rt512_kernel");
            if (kernel != null) kernel.setOnPreferenceChangeListener((p, value) -> {
                if ("5".equals(com.particlesdevs.photoncamera.settings.RawTherapeeSettings.text("rt512_median", "0"))
                        && Integer.parseInt(String.valueOf(value)) > 3) {
                    PhotonCamera.showToast("Для RGB доступны медианные фильтры 3×3 и 5×5"); return false;
                }
                return true;
            });
            updateOriginalNoiseDependencies("", "");
        }

        private void updateOriginalNoiseControls(boolean original) {
            Preference controls = findPreference("rt_original_controls");
            if (controls != null) controls.setEnabled(original);
            for (String key : new String[]{"pref_rt_nr_luma_key", "pref_rt_nr_chroma_key", "pref_rt_nr_detail_key", "pref_rt_nr_moire_key"}) {
                Preference pref = findPreference(key);
                if (pref != null) pref.setEnabled(!original && !PreferenceKeys.isHdrPlusMergeEnabled());
            }
        }

        private void updateOriginalNoiseDependencies(String changed, String value) {
            String auto = changed.equals("rt512_auto") ? value : com.particlesdevs.photoncamera.settings.RawTherapeeSettings.text("rt512_auto", "0");
            String median = changed.equals("rt512_median") ? value : com.particlesdevs.photoncamera.settings.RawTherapeeSettings.text("rt512_median", "0");
            String gain = changed.equals("rt512_gain") ? value : com.particlesdevs.photoncamera.settings.RawTherapeeSettings.text("rt512_gain", "1");
            for (String key : new String[]{"rt512_chroma", "rt512_red", "rt512_blue"}) {
                Preference p = findPreference(key); if(p!=null)p.setEnabled("0".equals(auto));
            }
            for (String key : new String[]{"rt512_kernel", "rt512_passes"}) {
                Preference p = findPreference(key); if(p!=null)p.setEnabled(!"0".equals(median));
            }
            ListPreference kernel = findPreference("rt512_kernel");
            if (kernel != null && "5".equals(median) && com.particlesdevs.photoncamera.settings.RawTherapeeSettings.number("rt512_kernel",0,0,5)>3) kernel.setValue("0");
            Preference exposure = findPreference("rt512_exposure");
            if(exposure!=null)exposure.setEnabled("1".equals(gain));
            Preference luma = findPreference("rt512_luma");
            if(luma!=null) {
                String curve = changed.equals("rt512_lcurve") ? value : com.particlesdevs.photoncamera.settings.RawTherapeeSettings.text("rt512_lcurve", "0");
                boolean active=false;
                try {
                    double[] points=com.particlesdevs.photoncamera.settings.RawTherapeeSettings.curve(curve);
                    if(points!=null)for(int i=2;i<points.length;i+=4)active |= points[i]!=0;
                } catch(IllegalArgumentException ignored) { }
                luma.setEnabled(!active);
            }
        }

        private void setupRemosaicBackend() {
            Preference neural = findPreference("vivo_neural_probe");
            if (neural != null) neural.setOnPreferenceClickListener(pref -> {
                startActivity(new android.content.Intent(requireContext(), VivoNeuralActivity.class));
                return true;
            });
            ListPreference backend = findPreference(getString(R.string.pref_remosaic_backend_key));
            if (backend != null) {
                backend.setOnPreferenceChangeListener((pref, value) -> {
                    // The displayed entryValues define the selectable backends.
                    // A second hard-coded list previously rejected HP9 HexQuad
                    // even though it was offered in this very dialog.
                    String selected = String.valueOf(value);
                    if (backend.findIndexOfValue(selected) < 0) return false;
                    updateRemosaicControls(selected);
                    return true;
                });
            }
            if (backend != null) updateRemosaicControls(backend.getValue());
            Preference probe = findPreference("remosaic_vivo_probe");
            if (probe == null) return;
            probe.setOnPreferenceClickListener(pref -> {
                pref.setEnabled(false);
                pref.setSummary(R.string.remosaic_vivo_checking);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                new Thread(() -> {
                    String report = com.particlesdevs.photoncamera.processing.opengl.postpipeline
                            .VivoRemosaicAvailability.probe();
                    com.particlesdevs.photoncamera.util.ScameraDebugLog.log("vivo-remosaic", report);
                    main.post(() -> {
                        if (!isAdded()) return;
                        pref.setEnabled(true);
                        pref.setSummary(R.string.remosaic_vivo_probe_desc);
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle(R.string.remosaic_vivo_result)
                                .setMessage(report)
                                .setPositiveButton(android.R.string.ok, null)
                                .setNeutralButton(android.R.string.copy, (dialog, which) -> {
                                    android.content.ClipboardManager clipboard =
                                            (android.content.ClipboardManager) requireContext()
                                                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                    if (clipboard != null) clipboard.setPrimaryClip(
                                            android.content.ClipData.newPlainText("Vivo remosaic", report));
                                }).show();
                    });
                }, "VivoRemosaicProbe").start();
                return true;
            });
        }

        private void updateRemosaicControls(String backend) {
            updateHexQuadDenoiseControls(backend);
            boolean detail = "tetra_detail".equals(backend) || "vivo_neural".equals(backend)
                    || "hp9_hexquad".equals(backend);
            int[] legacy = {R.string.pref_remosaic_profile_key, R.string.pref_remosaic_steered_key,
                    R.string.pref_remosaic_clamp_key, R.string.pref_remosaic_flatfield_key};
            for (int key : legacy) {
                Preference p = findPreference(getString(key));
                if (p != null) p.setEnabled(!detail);
            }
            Preference response = findPreference("pref_tetra_response_key");
            if (response != null) response.setEnabled(detail);
        }

        /**
         * ListPreference.SimpleSummaryProvider calls getEntry() while binding the row, and
         * getEntry() throws NullPointerException when no value has been persisted yet.
         * Defaults declared in XML are only written by PreferenceManager.setDefaultValues(),
         * which is skipped for keys added after the first run, so a freshly introduced
         * ListPreference binds with a null value and takes the whole settings screen down.
         * Seed those entries from their first entryValue before the adapter ever sees them.
         */
        private void seedMissingListValues(PreferenceGroup group) {
            if (group == null) {
                return;
            }
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference preference = group.getPreference(i);
                if (preference instanceof PreferenceGroup) {
                    seedMissingListValues((PreferenceGroup) preference);
                } else if (preference instanceof ListPreference) {
                    ListPreference list = (ListPreference) preference;
                    CharSequence[] values = list.getEntryValues();
                    if (list.getValue() == null && values != null && values.length > 0) {
                        Log.w("SettingsActivity", "No stored value for " + list.getKey()
                                + ", falling back to " + values[0]);
                        list.setValueIndex(0);
                    }
                }
            }
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            activity = getActivity();
            mContext = getContext();
            mSettingsManager = Objects.requireNonNull(PhotonCamera.getInstance(activity)).getSettingsManager();
            supportedDevice = Objects.requireNonNull(PhotonCamera.getInstance(activity)).getSupportedDevice();

            // Register PNG import launcher for TunablePngPreference
            // Uses OpenDocument to show the system file picker instead of gallery
            lutImportLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            String error = TunablePngPreference.handleImportResult(mContext, uri);
                            if (error != null) {
                                PhotonCamera.showToast("PNG import failed: " + error);
                            } else {
                                PhotonCamera.showToast("PNG imported successfully");
                            }
                            TunablePngPreference.refreshActivePreference();
                        }
                    }
            );
            TunablePngPreference.setImportLauncher(lutImportLauncher);

            // Noise-model calibration files are read as data: the coefficient arrays are
            // matched textually, nothing in the file is compiled or executed.
            noiseModelImportLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) {
                            return;
                        }
                        String result = importNoiseModel(uri);
                        PhotonCamera.showToast(result);
                    }
            );
            
            // Check if we're opening the tunable submenu specifically
            String rootKey = getArguments() != null ? getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) : null;
            Log.d("SettingsFragment", "onCreate with rootKey: " + rootKey);
            
            if ("pref_tunable_submenu".equals(rootKey)) {
                Log.d("SettingsFragment", "This is the tunable submenu fragment, generating preferences now");
                generateTunablePreferences();
            }

            if ("pref_sensor_config_submenu".equals(rootKey)) {
                Log.d("SettingsFragment", "This is the sensor config submenu fragment, generating preferences now");
                generateSensorConfigPreferences();
            }
            
            // Generators add ListPreferences after onCreatePreferences() ran, so re-run the
            // guard over whatever the tree looks like now.
            seedMissingListValues(getPreferenceScreen());

            // Keep every category reachable regardless of the last camera mode.
            setFramesSummary();
            setVersionDetails();
            setHdrxTitle();
            checkEszdTheme();
            setTelegramPref();
            setGithubPref();
            setBackupPref();
            setRestorePref();
            setSupportedDevices();
            setProTitle();
            setThisDevice();
            setFetchConfigurationsPref();
            updateSettingsAvailability();
        }
        
        private void generateTunablePreferences() {
            // Only generate once per fragment instance
            if (tunablePreferencesGenerated) {
                Log.d("SettingsActivity", "Tunable preferences already generated, skipping");
                return;
            }
            tunablePreferencesGenerated = true;
            Log.d("SettingsActivity", "=== generateTunablePreferences called ===");
            Log.d("SettingsActivity", "Context: " + (mContext != null ? "OK" : "NULL"));
            Log.d("SettingsActivity", "PreferenceScreen: " + (getPreferenceScreen() != null ? "OK" : "NULL"));
            
            try {
                // Ensure tunable classes are registered
                com.particlesdevs.photoncamera.settings.TunableSettingsManager.ensureTunableClassesRegistered();
                
                // Register with TunablePreferenceGenerator for UI generation
                for (Class<?> clazz : com.particlesdevs.photoncamera.settings.TunableRegistry.TUNABLE_CLASSES) {
                    TunablePreferenceGenerator.registerTunableClass(clazz);
                }
                
                Log.d("SettingsActivity", "Registered classes, now generating preferences...");
                
                PreferenceScreen screen = getPreferenceScreen();
                Log.d("SettingsActivity", "Target PreferenceScreen: " + screen.getKey() + " (count before: " + screen.getPreferenceCount() + ")");
                
                // Generate preferences and add to screen
                TunablePreferenceGenerator.generatePreferences(mContext, screen);
                
                Log.d("SettingsActivity", "Generated preferences (count after: " + screen.getPreferenceCount() + ")");
                
                // Add reset button for tunable preferences
                addTunableResetButton();
                
                Log.d("SettingsActivity", "=== generateTunablePreferences completed (final count: " + screen.getPreferenceCount() + ") ===");
            } catch (Exception e) {
                Log.e("SettingsActivity", "ERROR in generateTunablePreferences", e);
                e.printStackTrace();
            }
        }
        
        private void generateSensorConfigPreferences() {
            // Only generate once per fragment instance
            if (sensorConfigPreferencesGenerated) {
                Log.d("SettingsActivity", "Sensor config preferences already generated, skipping");
                return;
            }
            sensorConfigPreferencesGenerated = true;
            Log.d("SettingsActivity", "=== generateSensorConfigPreferences called ===");
            Log.d("SettingsActivity", "Context: " + (mContext != null ? "OK" : "NULL"));
            Log.d("SettingsActivity", "PreferenceScreen: " + (getPreferenceScreen() != null ? "OK" : "NULL"));

            try {
                PreferenceScreen screen = getPreferenceScreen();
                if (screen == null) {
                    Log.w("SettingsActivity", "PreferenceScreen is null, cannot generate sensor config preferences");
                    return;
                }
                Log.d("SettingsActivity", "Target PreferenceScreen: " + screen.getKey() + " (count before: " + screen.getPreferenceCount() + ")");

                com.particlesdevs.photoncamera.settings.SensorConfigPreferenceGenerator.generatePreferences(mContext, screen);

                Log.d("SettingsActivity", "Generated sensor config preferences (count after: " + screen.getPreferenceCount() + ")");
                addSensorConfigResetButton();
                Log.d("SettingsActivity", "=== generateSensorConfigPreferences completed (final count: " + screen.getPreferenceCount() + ") ===");
            } catch (Exception e) {
                Log.e("SettingsActivity", "ERROR in generateSensorConfigPreferences", e);
                e.printStackTrace();
            }
        }

        private void addSensorConfigResetButton() {
            try {
                PreferenceScreen submenu = getPreferenceScreen();
                if (submenu == null) {
                    Log.w("SettingsActivity", "PreferenceScreen is null, cannot add sensor config reset button");
                    return;
                }

                Preference resetButton = new Preference(mContext);
                resetButton.setKey("pref_reset_sensor_config_settings");
                resetButton.setTitle("Reset All to Defaults");
                resetButton.setSummary("Reset all sensor configuration parameters to their default values");
                resetButton.setIcon(android.R.drawable.ic_menu_revert);
                resetButton.setOrder(9999); // Force to the end

                resetButton.setOnPreferenceClickListener(preference -> {
                    SharedPreferences prefs = mSettingsManager.getDefaultPreferences();
                    SharedPreferences.Editor editor = prefs.edit();
                    int resetCount = 0;
                    for (String key : prefs.getAll().keySet()) {
                        if (key != null && key.startsWith("pref_sensorconfig_")) {
                            editor.remove(key);
                            resetCount++;
                        }
                    }
                    editor.apply();
                    if (getActivity() != null) {
                        getActivity().recreate();
                    }
                    PhotonCamera.showToast("Sensor config settings reset to defaults (" + resetCount + ")");
                    return true;
                });

                submenu.addPreference(resetButton);
                Log.d("SettingsActivity", "Added sensor config reset button (preferenceCount after: " + submenu.getPreferenceCount() + ")");
            } catch (Exception e) {
                Log.e("SettingsActivity", "Error adding sensor config reset button", e);
            }
        }

        private void addTunableResetButton() {
            try {
                // When we're inside the tunable submenu fragment, getPreferenceScreen() IS the tunable submenu
                androidx.preference.PreferenceScreen tunableSubmenu = findPreference("pref_tunable_submenu");
                
                if (tunableSubmenu != null) {
                    Log.d("SettingsActivity", "Adding reset button to tunable submenu (preferenceCount before: " + tunableSubmenu.getPreferenceCount() + ")");
                    
                    // Create reset button preference
                    androidx.preference.Preference resetButton = new androidx.preference.Preference(mContext);
                    resetButton.setKey("pref_reset_tunable_settings");
                    resetButton.setTitle("Reset All to Defaults");
                    resetButton.setSummary("Reset all tunable parameters to their default values");
                    resetButton.setIcon(android.R.drawable.ic_menu_revert);
                    resetButton.setOrder(9999); // Force to the end
                    
                    resetButton.setOnPreferenceClickListener(preference -> {
                        // Reset all tunable settings
                        com.particlesdevs.photoncamera.settings.TunableSettingsManager.resetAllToDefaults(mContext);
                        
                        // Restart the settings activity to refresh UI
                        if (getActivity() != null) {
                            getActivity().recreate();
                        }
                        
                        com.particlesdevs.photoncamera.app.PhotonCamera.showToast("Tunable settings reset to defaults");
                        return true;
                    });
                    
                    tunableSubmenu.addPreference(resetButton);
                    Log.d("SettingsActivity", "Added reset button (preferenceCount after: " + tunableSubmenu.getPreferenceCount() + ")");
                } else {
                    Log.w("SettingsActivity", "PreferenceScreen is null, cannot add reset button");
                }
            } catch (Exception e) {
                Log.e("SettingsActivity", "Error adding reset button", e);
            }
        }

        private void filterPreferencesByMode() {
            // Get the camera mode from the activity
            if (sCameraMode == -1) {
                // If no mode is passed, get from preferences
                sCameraMode = PreferenceKeys.getCameraModeOrdinal();
            }
            
            CameraMode cameraMode = CameraMode.valueOf(sCameraMode);
            
            // Show/hide categories based on camera mode
            if (cameraMode == CameraMode.RAWVIDEO) {
                // Raw video mode: show raw video settings only
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_photo_key));
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_jpg_key));
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_hdrx_key));
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_video_key));
            } else if (cameraMode == CameraMode.VIDEO) {
                // Regular video mode: show video settings, hide raw video settings
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_photo_key));
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_jpg_key));
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_hdrx_key));
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_rawvideo_key));
            } else {
                // Photo modes: hide all video-specific settings
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_video_key));
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_rawvideo_key));
            }
        }

        private void showHideHdrxSettings() {
            if (PreferenceKeys.isHdrXOn())
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_jpg_key));
            else
                removePreferenceFromScreen(mContext.getString(R.string.pref_category_hdrx_key));
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            mRootView = view;
            setupToolbar();
        }

        private void setupToolbar() {
            if (activity != null) {
                Toolbar toolbar = activity.findViewById(R.id.settings_toolbar);
                if (toolbar != null) {
                    CharSequence title = getPreferenceScreen().getTitle();
                    // Default to "Settings" if title is null
                    if (title == null || title.toString().isEmpty()) {
                        title = "Settings";
                    }
                    toolbar.setTitle(title);
                }
            }
        }
        
        @Override
        public void onResume() {
            super.onResume();
            // Update toolbar title when fragment resumes (e.g., after navigating back)
            setupToolbar();
            updateSettingsAvailability();
            mSettingsManager.getDefaultPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        @Override public void onPause() {
            mSettingsManager.getDefaultPreferences().unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }

        @Override public void onDestroy() {
            if (mSettingsManager != null) mSettingsManager.getDefaultPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
            super.onDestroy();
        }

        private final java.util.Map<String, Preference.SummaryProvider> originalProviders = new java.util.HashMap<>();
        private final java.util.Map<String, CharSequence> originalSummaries = new java.util.HashMap<>();
        private void updateSettingsAvailability() {
            if (!isAdded() || mSettingsManager == null) return;
            com.particlesdevs.photoncamera.settings.SettingsAvailability state =
                    new com.particlesdevs.photoncamera.settings.SettingsAvailability(mSettingsManager.getDefaultPreferences().getAll(),
                            PhotonCamera.getSpecificSensor() != null && PhotonCamera.getSpecificSensor().selectedSensorSpecifics != null
                                    && PhotonCamera.getSpecificSensor().selectedSensorSpecifics.ModelerExists);
            applyAvailability(getPreferenceScreen(), state);
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void applyAvailability(PreferenceGroup group, com.particlesdevs.photoncamera.settings.SettingsAvailability state) {
            if (group == null) return;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference p = group.getPreference(i);
                if (p instanceof PreferenceGroup) {
                    if (p instanceof PreferenceScreen) p.setEnabled(true); // the page explains inactive controls
                    applyAvailability((PreferenceGroup) p, state);
                    continue;
                }
                String key = p.getKey();
                if (key == null) continue;
                String reason = state.reason(key);
                if (p.getClass() != Preference.class) p.setEnabled(reason == null);
                if (reason != null) {
                    if (!originalSummaries.containsKey(key)) {
                        originalSummaries.put(key, p.getSummary());
                        originalProviders.put(key, p.getSummaryProvider());
                    }
                    p.setEnabled(false);
                    Preference.SummaryProvider provider = originalProviders.get(key);
                    p.setSummaryProvider(pref -> {
                        CharSequence base = provider == null ? originalSummaries.get(key) : provider.provideSummary(pref);
                        return base == null || base.length() == 0 ? reason : base + "\n" + reason;
                    });
                } else if (originalSummaries.containsKey(key)) {
                    p.setEnabled(true);
                    p.setSummaryProvider(originalProviders.remove(key));
                    CharSequence summary = originalSummaries.remove(key);
                    if (p.getSummaryProvider() == null) p.setSummary(summary);
                }
            }
        }

        private void setTelegramPref() {
            activity.runOnUiThread(()-> {
                Preference myPref = findPreference(PreferenceKeys.Key.KEY_TELEGRAM.mValue);
                if (myPref != null)
                    myPref.setOnPreferenceClickListener(preference -> {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/photon_camera_channel"));
                        startActivity(browserIntent);
                        return true;
                    });
            });
        }

        private void setGithubPref() {
            activity.runOnUiThread(()-> {
            Preference github = findPreference(PreferenceKeys.Key.KEY_CONTRIBUTORS.mValue);
            if (github != null)
                github.setOnPreferenceClickListener(preference -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eszdman/PhotonCamera"));
                    startActivity(browserIntent);
                    return true;
                });
            });
        }

        private void setRestorePref() {
                activity.runOnUiThread(()-> {
            Preference restorePref = findPreference(mContext.getString(R.string.pref_restore_preferences_key));
            if (restorePref != null) {
                restorePref.setSummary(mContext.getString(R.string.restore_summary_json));
                restorePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String restoreResult = BackupRestoreUtil.restorePreferences(mContext, newValue.toString());
                    Snackbar.make(mRootView, restoreResult, Snackbar.LENGTH_LONG).show();
                    return true;
                });
            }
          });
        }

        private void setBackupPref() {
            activity.runOnUiThread(()-> {
                Preference backupPref = findPreference(mContext.getString(R.string.pref_backup_preferences_key));
                if (backupPref != null) {
                    backupPref.setSummary(mContext.getString(R.string.backup_summary_json));
                    backupPref.setOnPreferenceChangeListener((preference, newValue) -> {
                        String backupResult = BackupRestoreUtil.backupSettings(mContext, newValue.toString());
                        Snackbar.make(mRootView, backupResult, Snackbar.LENGTH_LONG).show();
                        return true;
                    });
                }
           });
        }
        private void setSupportedDevices() {
            activity.runOnUiThread(()-> {
                Preference preference = findPreference(PreferenceKeys.Key.ALL_DEVICES_NAMES_KEY.mValue);
                if (preference != null) {
                    preference.setSummary((mSettingsManager.getStringSet(PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue,
                            ALL_DEVICES_NAMES_KEY, Collections.singleton(mContext.getString(R.string.list_not_loaded)))
                            .stream().sorted().map(s -> s + "\n").reduce("\n", String::concat)));
                }
           });
        }

        private void setProTitle() {
            activity.runOnUiThread(()-> {
                    Preference preference = findPreference(mContext.getString(R.string.pref_about_key));
                    if (preference != null && supportedDevice.isSupportedDevice()) {
                        preference.setTitle(R.string.device_support);
                    }
            });
        }

        private void setThisDevice() {
            Preference preference = findPreference(mContext.getString(R.string.pref_this_device_key));
            if (preference != null) {
                preference.setSummary(mContext.getString(R.string.this_device, SupportedDevice.THIS_DEVICE));
            }
        }

        private void setFetchConfigurationsPref() {
            Preference fetchPref = findPreference(mContext.getString(R.string.pref_fetch_configurations_key));
            if (fetchPref != null) {
                fetchPref.setOnPreferenceClickListener(preference -> {
                    preference.setSummary(mContext.getString(R.string.fetch_configurations_summary) + " (fetching…)");
                    new Thread(() -> {
                        supportedDevice.fetchFromNetwork();
                    if (activity != null) {
                        activity.runOnUiThread(() -> {
                            preference.setSummary(mContext.getString(R.string.fetch_configurations_summary));
                            com.google.android.material.snackbar.Snackbar.make(
                                    activity.findViewById(android.R.id.content),
                                    "Device configurations updated. Restart to apply camera changes.",
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).show();
                        });
                    }
                    }).start();
                    return true;
                });
            }
        }

        private void removePreferenceFromScreen(String preferenceKey) {
            Preference preference = findPreference(preferenceKey);
            if (preference != null && preference.getParent() != null) preference.getParent().removePreference(preference);
    }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            // Guard against null key (can happen during preference restore)
            if (key == null || !isResumed()) {
                return;
            }
            
            Log.d("SettingsFragment", "onSharedPreferenceChanged: key=" + key);
            if (key.equals(com.particlesdevs.photoncamera.util.ScameraDebugLog.PREF_KEY)) {
                boolean on = sharedPreferences.getBoolean(key, false);
                // Only bind the context here. Calling init() would itself call
                // setEnabled() with the freshly stored value, and the setEnabled()
                // below would then hit its own "value == enabled" early return -
                // so the writer was never opened and no file appeared.
                com.particlesdevs.photoncamera.util.ScameraDebugLog.attach(mContext);
                com.particlesdevs.photoncamera.util.ScameraDebugLog.setEnabled(on);
                if (on) {
                    PhotonCamera.showToast(
                            com.particlesdevs.photoncamera.util.ScameraDebugLog.getPath());
                }
            }
            
            if (key.equals(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue)) {
                setHdrxTitle();
                if (PreferenceKeys.isPerLensSettingsOn()) {
                    PreferenceKeys.loadSettingsForCamera(PreferenceKeys.getCameraID());
                    restartActivity();
                }
            }
            if (key.equalsIgnoreCase(PreferenceKeys.Key.KEY_THEME.mValue)) {
                restartActivity();
            }
            if (key.equalsIgnoreCase(PreferenceKeys.Key.KEY_THEME_ACCENT.mValue)) {
                checkEszdTheme();
                restartActivity();

            }
            if (key.equalsIgnoreCase(PreferenceKeys.Key.KEY_SHOW_GRADIENT.mValue)) {
                restartActivity();
            }
            if (key.equalsIgnoreCase(PreferenceKeys.Key.KEY_FRAME_COUNT.mValue)) {
                setFramesSummary();
            }
            if (key.equalsIgnoreCase(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue)) {
                Log.d("SettingsFragment", "Hide gallery icon changed, expected key: " + PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue);
                try {
                    boolean hideIcon = mSettingsManager.getBoolean(SettingsManager.SCOPE_GLOBAL, PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON);
                    Log.d("SettingsFragment", "Hide gallery icon value: " + hideIcon);
                    toggleGalleryIconVisibility(hideIcon);
                } catch (Exception e) {
                    Log.e("SettingsFragment", "Error toggling gallery icon: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            updateSettingsAvailability();
        }

        private void checkEszdTheme() {
            Preference p = findPreference(PreferenceKeys.Key.KEY_SHOW_GRADIENT.mValue);
            if (p != null)
                p.setEnabled(!mSettingsManager.getString(SCOPE_GLOBAL, PreferenceKeys.Key.KEY_THEME_ACCENT).equalsIgnoreCase("eszdman"));
        }

        private void setHdrxTitle() {
            Preference p = findPreference("settings_scope_info");
            if (p != null) {
                p.setTitle("Активная камера: " + PreferenceKeys.getCameraID());
                p.setSummary(PreferenceKeys.isPerLensSettingsOn()
                        ? "Обычные настройки — для этой линзы. Дополнительные — общие. Параметры сенсора — по физическому ID."
                        : "Общие настройки обработки. Отдельные профили включаются в разделе «Камеры и сенсоры»." );
            }
    }

        private void setBaseSummary(Preference preference, CharSequence summary) {
            if (originalSummaries.containsKey(preference.getKey())) originalSummaries.put(preference.getKey(), summary);
            else preference.setSummary(summary);
        }

        private void setFramesSummary() {
            Preference frameCountPreference = findPreference(PreferenceKeys.Key.KEY_FRAME_COUNT.mValue);
            if (frameCountPreference != null) {
                if (mSettingsManager.getInteger(PreferenceKeys.SCOPE_GLOBAL, PreferenceKeys.Key.KEY_FRAME_COUNT) == 1) {
                    setBaseSummary(frameCountPreference, mContext.getString(R.string.unprocessed_raw));
                } else {
                    setBaseSummary(frameCountPreference, mContext.getString(R.string.frame_count_summary));
                }
            }
        }

        private void toggleGalleryIconVisibility(boolean hideIcon) {
            try {
                // Get the ComponentName for the activity-alias using explicit package name
                String packageName = mContext.getPackageName();
                ComponentName galleryLauncher = new ComponentName(
                        packageName,
                        "com.particlesdevs.photoncamera.gallery.ui.GalleryActivityLauncher"
                );
                
                // Get the package manager
                PackageManager pm = mContext.getPackageManager();
                
                // Set the component enabled state based on hideIcon preference
                // If hideIcon is true, disable the launcher icon; otherwise enable it
                int newState = hideIcon ? 
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED : 
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                
                Log.d("SettingsFragment", "Toggling gallery icon visibility:");
                Log.d("SettingsFragment", "  hideIcon=" + hideIcon);
                Log.d("SettingsFragment", "  newState=" + newState);
                Log.d("SettingsFragment", "  component=" + galleryLauncher);
                
                int currentState = pm.getComponentEnabledSetting(galleryLauncher);
                if (currentState == newState || (currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !hideIcon)) return;
                pm.setComponentEnabledSetting(
                        galleryLauncher,
                        newState,
                        PackageManager.DONT_KILL_APP
                );
                
                Log.d("SettingsFragment", "Component state changed successfully");
                
                // Show a message to user
                if (activity != null) {
                    String message = hideIcon ? 
                            "Gallery icon will be hidden from launcher" : 
                            "Gallery icon will be visible in launcher";
                    activity.runOnUiThread(() -> 
                            com.google.android.material.snackbar.Snackbar.make(
                                    activity.findViewById(android.R.id.content),
                                    message,
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).show()
                    );
                }
            } catch (Exception e) {
                Log.e("SettingsFragment", "Error in toggleGalleryIconVisibility: " + e.getMessage());
                e.printStackTrace();
                // Show error message to user
                if (activity != null) {
                    activity.runOnUiThread(() -> 
                            com.google.android.material.snackbar.Snackbar.make(
                                    activity.findViewById(android.R.id.content),
                                    "Error toggling gallery icon: " + e.getMessage(),
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).show()
                    );
                }
            }
        }

        private void restartActivity() {
            if (getActivity() != null) {
                // Recreate this activity only for a real theme/scope change.
                // FragmentManager restores the nested page and its back stack.
                getActivity().recreate();
            }
        }

        private void setVersionDetails() {
            activity.runOnUiThread(() -> {
                Preference about = findPreference(mContext.getString(R.string.pref_version_key));
                if (about != null) {
                    try {
                        PackageInfo packageInfo = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0);
                        String versionName = packageInfo.versionName;
                        long versionCode = packageInfo.versionCode;

                        Date date = new Date(packageInfo.lastUpdateTime);
                        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy HH:mm:ss z", Locale.US);
                        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

                        about.setSummary(mContext.getString(R.string.version_summary, versionName + "." + versionCode, sdf.format(date)));

                    } catch (PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }

                }
            });

        }

        /**
         * Read a calibration file and store it as the active profile. Anything that does not
         * contain all four coefficient arrays is rejected rather than half-applied.
         */
        private String importNoiseModel(android.net.Uri uri) {
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return "Не удалось открыть файл";
                }
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                String source = buffer.toString("UTF-8");
                NoiseModelProfile profile = NoiseModelProfile.parse(source, "imported", "Импортированный");
                if (profile == null) {
                    return "Файл не содержит noise_model_A/B/C/D";
                }
                NoiseModelProfile.setImported(profile);
                mSettingsManager.set(PreferenceKeys.SCOPE_GLOBAL,
                        "pref_noise_model_profile_key", NoiseModelProfile.IMPORTED_ID);
                return "Модель шума импортирована";
            } catch (Exception e) {
                Log.e("SettingsFragment", "Noise model import failed", e);
                return "Ошибка импорта: " + e;
            }
        }

        /** Write the active profile back out in the calibration-file format. */
        private String exportNoiseModel() {
            NoiseModelProfile profile =
                    NoiseModelProfile.byId(PreferenceKeys.getNoiseModelProfileId());
            if (profile == null) {
                return "Активен автоматический профиль, экспортировать нечего";
            }
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "SCAMERA");
            java.io.File written = profile.exportTo(dir);
            return written != null
                    ? "Сохранено: " + written.getName()
                    : "Не удалось записать файл";
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            // Log which preference was clicked
            Log.d("SettingsFragment", "onPreferenceTreeClick: " + preference.getKey());
            if ("lens_discovery".equals(preference.getKey())) {
                startActivity(new Intent(requireContext(), LensDiscoveryActivity.class));
                return true;
            }
            if ("pref_noise_model_import_key".equals(preference.getKey())) {
                if (noiseModelImportLauncher != null) {
                    noiseModelImportLauncher.launch(new String[]{"*/*"});
                }
                return true;
            }
            if ("pref_noise_model_export_key".equals(preference.getKey())) {
                PhotonCamera.showToast(exportNoiseModel());
                return true;
            }
            
            // Keep the exact navigation behaviour from SCAMERA-26788:
            // only dynamically generated pages are handled here. Static pages
            // such as ACES must fall through to PreferenceFragmentCompat,
            // otherwise some AndroidX/vendor combinations dispatch them twice.
            if ("pref_tunable_submenu".equals(preference.getKey())
                    || "pref_sensor_config_submenu".equals(preference.getKey())) {
                Log.d("SettingsFragment", "Submenu clicked, navigating: " + preference.getKey());
                if (preference instanceof PreferenceScreen && activity instanceof SettingsActivity) {
                    ((SettingsActivity) activity).onPreferenceStartScreen(this, (PreferenceScreen) preference);
                    return true;
                }
            }
            
            // Return false to allow default handling (like opening other subscreens)
            return super.onPreferenceTreeClick(preference);
        }

        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if (preference instanceof ResetPreferences) {
                DialogFragment dialogFragment = ResetPreferences.Dialog.newInstance(preference);
                dialogFragment.setTargetFragment(this, 0);
                dialogFragment.show(getParentFragmentManager(), null);
            } else {
                super.onDisplayPreferenceDialog(preference);
            }
        }

    }
}
