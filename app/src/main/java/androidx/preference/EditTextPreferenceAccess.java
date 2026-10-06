package androidx.preference;

/**
 * The input setup a generator or the settings screen gave an EditTextPreference (input type, filters, text). AndroidX
 * keeps the getter package-private for its own dialog; SCAMERA's bottom-sheet editor (SettingsStyle) applies the same
 * listener, so the field validation stays exactly as in the stock dialog.
 */
public final class EditTextPreferenceAccess {
    private EditTextPreferenceAccess() {}

    public static EditTextPreference.OnBindEditTextListener listener(EditTextPreference preference) {
        return preference.getOnBindEditTextListener();
    }
}
