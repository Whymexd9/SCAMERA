package com.particlesdevs.photoncamera.ui.settings;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import com.particlesdevs.photoncamera.util.Lang;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

import static com.particlesdevs.photoncamera.settings.PreferenceKeys.SCOPE_GLOBAL;

public class SettingsActivity extends BaseActivity implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    private static int sCameraMode = -1;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
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
        
        if("camera_settings_screen".equals(preferenceScreen.getKey())){
            getSupportFragmentManager().beginTransaction().replace(R.id.settings_container,new ModuleSettingsFragment()).addToBackStack("modules").commit();return true;
        }
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

    void openSearchResult(SettingsSearchFragment.Entry entry) {
        SettingsFragment page = new SettingsFragment();
        Bundle args = new Bundle();
        if (!"prefscreen".equals(entry.page)) args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, entry.page);
        args.putString("search_target", entry.key);
        page.setArguments(args);
        getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, page)
                .addToBackStack("search_result").commit();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener, PreferenceManager.OnPreferenceTreeClickListener {
        private static final String KEY_MAIN_PARENT_SCREEN = "prefscreen";
        private static final String SENSOR_PAGE = "pref_sensor_config_submenu";
        /** Argument of the sensor page: the module it shows (a module page opens its own; the chip changes it). */
        static final String ARG_SENSOR_SLOT = "sensor_slot";
        private Activity activity;
        private com.particlesdevs.photoncamera.settings.SensorConfigPreferenceGenerator.ModuleSelection sensorSelection;

        /** The sensor settings and vendor keys of one module. */
        public static SettingsFragment sensorPage(String slot) {
            SettingsFragment page = new SettingsFragment();
            Bundle args = new Bundle();
            args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, SENSOR_PAGE);
            args.putString(ARG_SENSOR_SLOT, slot);
            page.setArguments(args);
            return page;
        }
        private SettingsManager mSettingsManager;
        private Context mContext;
        private View mRootView;
        private PreferenceScreen fullPreferenceScreen;
        private boolean tunablePreferencesGenerated = false;
        private boolean sensorConfigPreferencesGenerated = false;
        private ActivityResultLauncher<String[]> lutImportLauncher;
        private ActivityResultLauncher<String[]> noiseModelImportLauncher;
        private ActivityResultLauncher<String[]> configImportLauncher;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mContext = requireContext();
            mSettingsManager = PhotonCamera.getSettingsManagerStatic();
            com.particlesdevs.photoncamera.settings.SettingsMigration.prepare(requireContext(), mSettingsManager.getDefaultPreferences());
            setPreferencesFromResource(R.xml.preferences, null);
            generateTunablePreferences();
            generateSensorConfigPreferences();
            fullPreferenceScreen = getPreferenceScreen();
            if (rootKey != null) {
                PreferenceScreen selected = findPreference(rootKey);
                if (selected == null) throw new IllegalArgumentException("Unknown settings page: " + rootKey);
                setPreferenceScreen(selected);
                // The XML page of «Камеры и сенсоры» opens from that page's «Сенсоры и вендорные ключи» row: same name in its header
                if ("camera_settings_screen".equals(rootKey))
                    selected.setTitle(Lang.t(getContext(),"Сенсоры и вендорные ключи","Sensors and vendor keys"));
            }
            seedMissingListValues(fullPreferenceScreen);
            setupScalarInputs(getPreferenceScreen());
            ListPreference route = findPreference(PreferenceKeys.ROUTE_KEY);
            if (route != null && !PreferenceKeys.isScamHdrSupported()) {
                // SCAM HDR needs the 8 Elite NPU: the hybrid is the only route here (PreferenceKeys.mergeRoute).
                route.setEntries(new CharSequence[]{"Hybrid"});
                route.setEntryValues(new CharSequence[]{"hybrid"});
                route.setValue("hybrid");
                route.setEnabled(false);
                route.setSummary(Lang.t(getContext(),"Только Hybrid: SCAM HDR и нейроремозаик работают только на Snapdragon 8 Elite","Hybrid only: SCAM HDR and the neural remosaic run on the Snapdragon 8 Elite only"));
            }
            // No 8 Elite: no SCAM HDR, so neither its screen (mosaic and neural remosaic tuning included).
            Preference scamHdr = findPreference("vivo_hdr_screen");
            if (scamHdr != null && !PreferenceKeys.isScamHdrSupported()) scamHdr.setVisible(false);
            // P17: the tele's smooth optical zoom exists on the Xiaomi 17 Ultra only
            Preference xiaomiZoom = findPreference(com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.PREF);
            if (xiaomiZoom != null && !com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.phone()) xiaomiZoom.setVisible(false);
            setupPhotoFormat();
            setupRemosaicBackend();
            updateHexQuadDenoiseControls();
            SettingsStyle.apply(getPreferenceScreen());
        }

        /**
         * «Формат фото» is the one format choice of the top bar and the shade (FormatChoice): JPEG, HEIC, WebP, RAW,
         * RAW + JPEG / HEIC / WebP, stored as the save mode and the codec, never as a value of its own (the list is not
         * persistent). HEIC needs Android 9 (PhotoFormat.HEIC_MIN_SDK): below it the HEIC options are not offered.
         */
        private void setupPhotoFormat() {
            ListPreference format = findPreference(com.particlesdevs.photoncamera.processing.PhotoFormat.KEY);
            if (format == null) return;
            java.util.List<com.particlesdevs.photoncamera.settings.FormatChoice> offered =
                    com.particlesdevs.photoncamera.settings.FormatChoice.offered(android.os.Build.VERSION.SDK_INT);
            CharSequence[] entries = new CharSequence[offered.size()], values = new CharSequence[offered.size()];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = offered.get(i).longLabel();
                values[i] = offered.get(i).name();
            }
            format.setPersistent(false);
            format.setEntries(entries);
            format.setEntryValues(values);
            format.setValue(com.particlesdevs.photoncamera.settings.FormatChoice.current().name());
            format.setOnPreferenceChangeListener((preference, value) -> {
                com.particlesdevs.photoncamera.settings.FormatChoice.store(
                        com.particlesdevs.photoncamera.settings.FormatChoice.valueOf(value.toString()));
                return true;
            });
        }

        // ───── P6b: the card look of «Камеры и сенсоры» on every page ─────
        private SettingsStyle.Header header;
        private android.widget.LinearLayout chipBox;

        @NonNull @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
            View list = super.onCreateView(inflater, container, state);
            Context c = requireContext();
            android.widget.LinearLayout page = new android.widget.LinearLayout(c);
            page.setOrientation(android.widget.LinearLayout.VERTICAL);
            page.setBackgroundColor(SettingsStyle.BG);
            page.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 16), 0);
            header = SettingsStyle.header(c, () -> requireActivity().getOnBackPressedDispatcher().onBackPressed(), this::openSearch);
            page.addView(header.view, new android.widget.LinearLayout.LayoutParams(-1, -2));
            chipBox = new android.widget.LinearLayout(c);
            page.addView(chipBox, new android.widget.LinearLayout.LayoutParams(-1, -2));
            page.addView(list, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
            return page;
        }

        @NonNull @Override
        public androidx.recyclerview.widget.RecyclerView onCreateRecyclerView(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent, @Nullable Bundle state) {
            androidx.recyclerview.widget.RecyclerView list = super.onCreateRecyclerView(inflater, parent, state);
            list.setClipToPadding(false);
            list.setPadding(0, 0, 0, SettingsStyle.dp(requireContext(), 28));
            list.setVerticalScrollBarEnabled(false);
            return list;
        }

        /** Three columns only so that the «Конфиг» tiles share a row; every other row spans all three. */
        @NonNull @Override
        public androidx.recyclerview.widget.RecyclerView.LayoutManager onCreateLayoutManager() {
            androidx.recyclerview.widget.GridLayoutManager grid = new androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3);
            grid.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                @Override public int getSpanSize(int position) {
                    androidx.recyclerview.widget.RecyclerView list = getListView();
                    if (list == null || !(list.getAdapter() instanceof androidx.preference.PreferenceGroupAdapter)) return 3;
                    Preference p = ((androidx.preference.PreferenceGroupAdapter) list.getAdapter()).getItem(position);
                    return p != null && p.getKey() != null && SettingsStyle.TILES.contains(p.getKey()) ? 1 : 3;
                }
            });
            return grid;
        }

        @NonNull @Override
        protected androidx.recyclerview.widget.RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen screen) {
            return new androidx.preference.PreferenceGroupAdapter(screen) {
                @Override public void onBindViewHolder(@NonNull androidx.preference.PreferenceViewHolder holder, int position) {
                    super.onBindViewHolder(holder, position);
                    Preference p = getItem(position);
                    if (p != null) SettingsStyle.bind(holder, p);
                }
            };
        }

        /** Title, and the chip: the active route on the root page, the module being edited on pages with per-module values. */
        private void updateHeader() {
            if (header == null || !isAdded()) return;
            PreferenceScreen screen = getPreferenceScreen();
            CharSequence title = screen == null ? null : screen.getTitle();
            header.heading.setText(title == null || title.length() == 0 ? Lang.t(getContext(),"Настройки камеры","Camera settings") : title);
            header.subtitle.setVisibility(View.GONE);
            chipBox.removeAllViews();
            String chip = null;
            boolean picker = false;
            if (screen != null && KEY_MAIN_PARENT_SCREEN.equals(screen.getKey()))
                chip = Lang.t(getContext(),"Активна: ","Active: ") + ("hybrid".equals(PreferenceKeys.mergeRoute()) ? "Hybrid" : "SCAM HDR") + Lang.t(getContext()," · по умолчанию Hybrid"," · Hybrid by default");
            else if (screen != null && SENSOR_PAGE.equals(screen.getKey()) && sensorSelection != null) {
                // Sensor settings always belong to one module, per-module profiles on or off. The chip names it and,
                // with more than one module, is the module picker (the page has no picker row of its own).
                chip = Lang.t(getContext(),"Настраивается: ","Editing: ") + sensorSelection.title(sensorSelection.selected());
                picker = sensorSelection.slots().size() > 1;
                if (picker) chip += "  ▾";
            }
            else if (screen != null && PreferenceKeys.isPerLensSettingsOn() && SettingsStyle.hasModuleSettings(screen)) {
                String slot = com.particlesdevs.photoncamera.settings.ModuleRegistry.active();
                chip = Lang.t(getContext(),"Настраивается: ","Editing: ") + com.particlesdevs.photoncamera.settings.ModuleRegistry.label(slot)
                        + " · ID " + com.particlesdevs.photoncamera.settings.ModuleRegistry.camera(slot);
            }
            if (chip != null) {
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = SettingsStyle.dp(requireContext(), 4);
                android.widget.TextView view = SettingsStyle.chip(requireContext(), chip);
                if (picker) {
                    view.setOnClickListener(v -> pickSensorModule());
                    view.setFocusable(true);
                    view.setContentDescription(chip.replace("  ▾", "") + Lang.t(getContext(),". Выбрать другой модуль",". Choose another module"));
                }
                chipBox.addView(view, lp);
            }
        }

        /** The module picker of the sensor page: shows another module's sensor settings; the camera stays as it is. */
        private void pickSensorModule() {
            if (sensorSelection == null) return;
            java.util.List<String> slots = sensorSelection.slots();
            CharSequence[] labels = new CharSequence[slots.size()], tags = new CharSequence[slots.size()];
            for (int i = 0; i < slots.size(); i++) { labels[i] = sensorSelection.title(slots.get(i)); tags[i] = slots.get(i); }
            SettingsStyle.optionSheet(requireContext(), Lang.t(getContext(),"Модуль камеры","Camera module"), labels, tags,
                    slots.indexOf(sensorSelection.selected()), SettingsStyle.accent(requireContext()), i -> {
                        sensorSelection.select(slots.get(i));
                        // kept in the arguments, so a reset (it recreates the activity) comes back to this module
                        if (getArguments() != null) getArguments().putString(ARG_SENSOR_SLOT, slots.get(i));
                        updateHeader();
                    });
        }

        private void openSearch() {
            SettingsSearchFragment fragment = SettingsSearchFragment.create(SettingsSearchFragment.index(fullPreferenceScreen));
            getParentFragmentManager().beginTransaction().replace(R.id.settings_container, fragment).addToBackStack("settings_search").commit();
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

        /** SCAM HDR mosaic «neural» (Quad 2x2 model): manual Luma / Chroma or the ISO table. */
        private void updateQuadDenoiseControls() {
            boolean active=true;
            boolean auto=com.particlesdevs.photoncamera.app.PhotonCamera.getSettingsManagerStatic()!=null
                    && com.particlesdevs.photoncamera.app.PhotonCamera.getSettingsManagerStatic().getBoolean("default_scope","quad2x2_auto_iso",false);
            for(String key:new String[]{"quad2x2_noise_overall","quad2x2_noise_photon","quad2x2_noise_readout",
                    "quad2x2_auto_iso","quad2x2_luma","quad2x2_chroma","quad2x2_iso_low_luma","quad2x2_iso_low_chroma",
                    "quad2x2_iso_high_luma","quad2x2_iso_high_chroma"}){
                Preference p=findPreference(key);if(p==null)continue;
                boolean enabled=active;
                if(key.equals("quad2x2_luma")||key.equals("quad2x2_chroma"))enabled &= !auto;
                if(key.startsWith("quad2x2_iso_"))enabled &= auto;
                p.setEnabled(enabled);
            }
        }

        /** SCAM HDR mosaic «neural» (HexQuad model on Tetra 4x4): manual Luma / Chroma or the ISO table. */
        private void updateHexQuadDenoiseControls() {
            updateQuadDenoiseControls();
            boolean active=true;
            boolean auto=PreferenceKeys.isHexQuadAutoIso();
            for(String key:new String[]{"hexquad_compute","hexquad_model","hexquad_noise_overall",
                    "hexquad_noise_photon","hexquad_noise_readout","hexquad_auto_iso","hexquad_luma","hexquad_chroma",
                    "hexquad_iso_low_luma","hexquad_iso_low_chroma","hexquad_iso_high_luma","hexquad_iso_high_chroma",
                    "hexquad_texture"}){
                Preference p=findPreference(key);if(p==null)continue;
                boolean enabled=active;
                if(key.equals("hexquad_luma")||key.equals("hexquad_chroma"))enabled &= !auto;
                if(key.startsWith("hexquad_iso_"))enabled &= auto;
                p.setEnabled(enabled);
            }
        }




        private void setupRemosaicBackend() {
            Preference nice = findPreference("vivo_nice_probe");
            if (nice != null) nice.setOnPreferenceClickListener(pref -> {
                startActivity(new android.content.Intent(requireContext(), VivoNiceActivity.class));
                return true;
            });
            Preference neural = findPreference("vivo_neural_probe");
            if (neural != null) neural.setOnPreferenceClickListener(pref -> {
                startActivity(new android.content.Intent(requireContext(), VivoNeuralActivity.class));
                return true;
            });
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

            // Config import from any folder (a file copied from another phone is not listed by MediaStore);
            // the picker starts in Download/SCAMERA/XML.
            configImportLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument() {
                        @NonNull @Override
                        public Intent createIntent(@NonNull android.content.Context context, @NonNull String[] input) {
                            Intent intent = super.createIntent(context, input);
                            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                                    com.particlesdevs.photoncamera.util.ConfigFolder.initialPickerUri());
                            return intent;
                        }
                    },
                    uri -> {
                        if (uri == null) return;
                        String result = BackupRestoreUtil.restoreFromUri(mContext, uri);
                        if (mRootView != null) Snackbar.make(mRootView, result, Snackbar.LENGTH_LONG).show();
                        else PhotonCamera.showToast(result);
                    }
            );
            
            // Check if we're opening the tunable submenu specifically
            String rootKey = getArguments() != null ? getArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT) : null;
            Log.d("SettingsFragment", "onCreate with rootKey: " + rootKey);
            
            if ("pref_sensor_config_submenu".equals(rootKey)) {
                Log.d("SettingsFragment", "This is the sensor config submenu fragment, generating preferences now");
                generateSensorConfigPreferences();
                SettingsStyle.apply(getPreferenceScreen());
            }
            
            // Generators add ListPreferences after onCreatePreferences() ran, so re-run the
            // guard over whatever the tree looks like now.
            seedMissingListValues(getPreferenceScreen());

            // Keep every category reachable regardless of the last camera mode.
            setVersionDetails();
            checkEszdTheme();
            setBackupPref();
            setRestorePref();
            setThisDevice();
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

                String preferred = getArguments() == null ? null : getArguments().getString(ARG_SENSOR_SLOT);
                sensorSelection = com.particlesdevs.photoncamera.settings.SensorConfigPreferenceGenerator.generatePreferences(mContext, screen, preferred);

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
                PreferenceScreen submenu = findPreference("pref_sensor_config_submenu");
                if (submenu == null) {
                    Log.w("SettingsActivity", "PreferenceScreen is null, cannot add sensor config reset button");
                    return;
                }

                Preference resetButton = new Preference(mContext);
                resetButton.setKey("pref_reset_sensor_config_settings");
                resetButton.setTitle(Lang.t(getContext(),"Сбросить настройки выбранного модуля","Reset the selected module settings"));
                resetButton.setSummary(Lang.t(getContext(),"Остальные модули сохранят свои значения","Other modules keep their values"));
                resetButton.setIcon(android.R.drawable.ic_menu_revert);
                resetButton.setOrder(9999); // Force to the end

                resetButton.setOnPreferenceClickListener(preference -> {
                    String slot=sensorSelection!=null?sensorSelection.selected():com.particlesdevs.photoncamera.settings.ModuleRegistry.active();
                    new androidx.appcompat.app.AlertDialog.Builder(mContext)
                        .setTitle(Lang.t(getContext(),"Сбросить настройки модуля?","Reset the module settings?"))
                        .setMessage(com.particlesdevs.photoncamera.settings.ModuleRegistry.label(slot)+" · ID "+com.particlesdevs.photoncamera.settings.ModuleRegistry.camera(slot))
                        .setNegativeButton(Lang.t(getContext(),"Отмена","Cancel"),null)
                        .setPositiveButton(Lang.t(getContext(),"Сбросить","Reset"),(d,w)->{
                            com.particlesdevs.photoncamera.settings.ModuleSensorSettings.reset(slot);
                            if(getActivity()!=null)getActivity().recreate();
                        }).show();
                    return true;
                });

                submenu.addPreference(resetButton);
                Log.d("SettingsActivity", "Added sensor config reset button (preferenceCount after: " + submenu.getPreferenceCount() + ")");
            } catch (Exception e) {
                Log.e("SettingsActivity", "Error adding sensor config reset button", e);
            }
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            mRootView = view;
            setupToolbar();
            setDivider(null);
            String target = getArguments() == null ? null : getArguments().getString("search_target");
            if (target != null && findPreference(target) != null) {
                Preference found = findPreference(target);
                android.text.SpannableString highlighted = new android.text.SpannableString(found.getTitle());
                highlighted.setSpan(new android.text.style.ForegroundColorSpan(com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.color(requireContext())), 0,
                        highlighted.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                found.setTitle(highlighted);
                scrollToPreference(target);
            }
        }

        /** The activity toolbar stays hidden: every page has the shared header (SettingsStyle.header). */
        private void setupToolbar() {
            if (activity != null) {
                View toolbar = activity.findViewById(R.id.settings_toolbar);
                if (toolbar != null) toolbar.setVisibility(View.GONE);
            }
            updateHeader();
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
            state.heic10Unavailable(com.particlesdevs.photoncamera.processing.heif.Heic10Support.unavailableReason());
            applyAvailability(getPreferenceScreen(), state);
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void applyAvailability(PreferenceGroup group, com.particlesdevs.photoncamera.settings.SettingsAvailability state) {
            if (group == null) return;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference p = group.getPreference(i);
                if (p instanceof PreferenceGroup) {
                    if (p instanceof PreferenceScreen) p.setEnabled(true);
                    applyAvailability((PreferenceGroup) p, state);
                    if (!(p instanceof PreferenceScreen)) continue;
                }
                String key = p.getKey();
                if (key == null) continue;
                // Rows of a photo format that is not chosen are hidden, not explained (SettingsAvailability.hidden).
                boolean hidden = state.hidden(key);
                if (p.isVisible() == hidden) p.setVisible(!hidden);
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

        private void setRestorePref() {
                activity.runOnUiThread(()-> {
            Preference restorePref = findPreference(mContext.getString(R.string.pref_restore_preferences_key));
            if (restorePref != null) {
                restorePref.setSummary(mContext.getString(R.string.restore_summary_json));
                restorePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (com.particlesdevs.photoncamera.ui.settings.custompreferences.RestorePreference.PICK.equals(newValue.toString())) {
                        if (configImportLauncher != null) configImportLauncher.launch(new String[]{"*/*"});
                        return false;
                    }
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
        private void setThisDevice() {
            Preference preference = findPreference(mContext.getString(R.string.pref_this_device_key));
            if (preference != null) {
                preference.setSummary(mContext.getString(R.string.this_device, SupportedDevice.THIS_DEVICE));
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            // Guard against null key (can happen during preference restore)
            if (key == null || !isResumed()) {
                return;
            }
            
            Log.d("SettingsFragment", "onSharedPreferenceChanged: key=" + key);
            if (key.equals(PreferenceKeys.ROUTE_KEY)) {
                // the chip and the dimmed root row of the other route follow the selection
                updateHeader();
                if (getListView() != null && getListView().getAdapter() != null) getListView().getAdapter().notifyDataSetChanged();
            }
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
                if (PreferenceKeys.isPerLensSettingsOn()) {

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
                p.setEnabled(!"eszdman".equalsIgnoreCase(mSettingsManager.getString(SCOPE_GLOBAL, PreferenceKeys.Key.KEY_THEME_ACCENT)));
        }


        private void setBaseSummary(Preference preference, CharSequence summary) {
            if (originalSummaries.containsKey(preference.getKey())) originalSummaries.put(preference.getKey(), summary);
            else preference.setSummary(summary);
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
                    return Lang.t(getContext(),"Не удалось открыть файл","Couldn't open the file");
                }
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                String source = buffer.toString("UTF-8");
                NoiseModelProfile profile = NoiseModelProfile.parse(source, "imported", Lang.t(getContext(),"Импортированный","Imported"));
                if (profile == null) {
                    return Lang.t(getContext(),"Файл не содержит noise_model_A/B/C/D","The file has no noise_model_A/B/C/D");
                }
                NoiseModelProfile.setImported(profile);
                mSettingsManager.set(PreferenceKeys.SCOPE_GLOBAL,
                        "pref_noise_model_profile_key", NoiseModelProfile.IMPORTED_ID);
                return Lang.t(getContext(),"Модель шума импортирована","Noise model imported");
            } catch (Exception e) {
                Log.e("SettingsFragment", "Noise model import failed", e);
                return Lang.t(getContext(),"Ошибка импорта: ","Import error: ") + e;
            }
        }

        /** Write the active profile back out in the calibration-file format. */
        private String exportNoiseModel() {
            NoiseModelProfile profile =
                    NoiseModelProfile.byId(PreferenceKeys.getNoiseModelProfileId());
            if (profile == null) {
                return Lang.t(getContext(),"Активен автоматический профиль, экспортировать нечего","The automatic profile is active, nothing to export");
            }
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "SCAMERA");
            java.io.File written = profile.exportTo(dir);
            return written != null
                    ? Lang.t(getContext(),"Сохранено: ","Saved: ") + written.getName()
                    : Lang.t(getContext(),"Не удалось записать файл","Couldn't write the file");
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference preference) {
            // Log which preference was clicked
            Log.d("SettingsFragment", "onPreferenceTreeClick: " + preference.getKey());
            if (PreferenceKeys.Key.KEY_THEME_ACCENT.mValue.equals(preference.getKey())) {
                getParentFragmentManager().beginTransaction().replace(R.id.settings_container,new AccentSettingsFragment()).addToBackStack("accent").commit();return true;
            }
            if ("pref_dcp_profile_key".equals(preference.getKey())) {
                getParentFragmentManager().beginTransaction().replace(R.id.settings_container,new DcpSettingsFragment()).addToBackStack("dcp").commit();return true;
            }
            if ("vivo_hdr_ark_link".equals(preference.getKey())) {
                // SCAM HDR shares the ArkCore finish of the Hybrid: one page, opened from both routes
                PreferenceScreen ark = fullPreferenceScreen.findPreference("lmc_hybrid_arkcore_screen");
                if (ark != null && activity instanceof SettingsActivity) ((SettingsActivity) activity).onPreferenceStartScreen(this, ark);
                return true;
            }
            if ("module_copy_settings".equals(preference.getKey())) {
                getParentFragmentManager().beginTransaction().replace(R.id.settings_container, new ModuleCopyFragment()).addToBackStack("module_copy").commit();
                return true;
            }
            if ("lens_discovery".equals(preference.getKey())) {
                getParentFragmentManager().beginTransaction().replace(R.id.settings_container, new ModuleLensFragment()).addToBackStack("modules").commit();
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
            if ("pref_sensor_config_submenu".equals(preference.getKey())) {
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
            if (SettingsStyle.showDialog(requireContext(), preference)) return;
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
