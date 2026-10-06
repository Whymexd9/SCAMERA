package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

/**
 * P6b «Склейка»: the merge route as its own card with a two-segment control (Hybrid | SCAM HDR). Still a ListPreference
 * (key pref_merge_route, entries, search, availability and backups unchanged); the segments only call setValue.
 */
public class RouteSelectorPreference extends ListPreference {
    public RouteSelectorPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_route);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        MaterialButtonToggleGroup group = (MaterialButtonToggleGroup) holder.findViewById(R.id.route_segments);
        if (group == null) return;
        Context context = group.getContext();
        int accent = AccentPalette.color(context);
        group.clearOnButtonCheckedListeners();
        group.removeAllViews();
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        CharSequence[] entries = getEntries(), values = getEntryValues();
        int checked = findIndexOfValue(getValue());
        int[][] states = {{android.R.attr.state_checked}, {}};
        for (int i = 0; entries != null && i < entries.length; i++) {
            MaterialButton b = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(android.view.View.generateViewId());
            b.setText(entries[i]);
            b.setTextSize(15);
            b.setAllCaps(false);
            b.setCheckable(true);
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setMinHeight(SettingsStyle.dp(context, 46));
            b.setCornerRadius(SettingsStyle.dp(context, 14));
            b.setStrokeColor(ColorStateList.valueOf(SettingsStyle.LINE));
            b.setBackgroundTintList(new ColorStateList(states, new int[]{accent, SettingsStyle.FIELD}));
            b.setTextColor(new ColorStateList(states, new int[]{SettingsStyle.INK, SettingsStyle.MUTED}));
            b.setRippleColor(ColorStateList.valueOf(0x22FFFFFF));
            b.setTag("route_" + values[i]);
            b.setEnabled(isEnabled());
            group.addView(b, new MaterialButtonToggleGroup.LayoutParams(0, -2, 1f));
            if (i == checked) group.check(b.getId());
        }
        group.addOnButtonCheckedListener((g, id, isChecked) -> {
            if (!isChecked) return;
            int index = g.indexOfChild(g.findViewById(id));
            if (index < 0 || values == null || index >= values.length) return;
            String value = values[index].toString();
            if (value.equals(getValue())) return;
            if (callChangeListener(value)) setValue(value);
            else g.post(this::notifyChanged);
        });
    }

    /** The route has its own control: tapping the card does not open the list dialog. */
    @Override
    protected void onClick() {}
}
