from pathlib import Path

path = Path("app/src/main/java/com/particlesdevs/photoncamera/ui/settings/SettingsActivity.java")
source = path.read_text(encoding="utf-8")

anchor = '''            Log.d("SettingsFragment", "onCreate with rootKey: " + rootKey);
            
            if ("pref_tunable_submenu".equals(rootKey)) {'''
replacement = '''            Log.d("SettingsFragment", "onCreate with rootKey: " + rootKey);

            setupAcesNavigation();
            
            if ("pref_tunable_submenu".equals(rootKey)) {'''
if source.count(anchor) != 1:
    raise SystemExit(f"ACES call anchor count is {source.count(anchor)}, expected 1")
source = source.replace(anchor, replacement, 1)

anchor = '''            setThisDevice();
            setFetchConfigurationsPref();
        }
        
        private void generateTunablePreferences() {'''
replacement = '''            setThisDevice();
            setFetchConfigurationsPref();
        }

        /**
         * Some vendor AndroidX builds do not dispatch the ACES PreferenceScreen
         * through OnPreferenceStartScreenCallback. Consume the row click and
         * open the existing nested screen explicitly exactly once.
         */
        private void setupAcesNavigation() {
            Preference acesPreference = findPreference("aces_group_screen");
            if (!(acesPreference instanceof PreferenceScreen)) {
                Log.e("SettingsFragment", "ACES PreferenceScreen is missing");
                return;
            }

            acesPreference.setOnPreferenceClickListener(preference -> {
                Log.d("SettingsFragment", "Opening ACES settings explicitly");
                if (activity instanceof SettingsActivity) {
                    return ((SettingsActivity) activity).onPreferenceStartScreen(
                            this, (PreferenceScreen) preference);
                }
                return false;
            });
        }
        
        private void generateTunablePreferences() {'''
if source.count(anchor) != 1:
    raise SystemExit(f"ACES method anchor count is {source.count(anchor)}, expected 1")
source = source.replace(anchor, replacement, 1)
path.write_text(source, encoding="utf-8")
