package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.InputType;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import com.google.android.material.color.MaterialColors;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;

/** The bar snaps; numeric entry preserves the exact value. Binding is strictly read-only. */
public class TunableSeekBarPreference extends Preference implements SeekBar.OnSeekBarChangeListener {
    private float mMin = 0f, mMax = 100f, mStepPerUnit = 1f, mDefaultValue = 0f;
    private boolean isFloat;
    private SeekBar seekBar;
    private TextView seekBarValue;

    public TunableSeekBarPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.preference_tunable_seekbar);
        setIconSpaceReserved(false);
    }
    public void setMinValue(float value) { mMin = value; }
    public void setMaxValue(float value) { mMax = value; }
    public void setIsFloat(boolean value) { isFloat = value; }
    public void setStepPerUnit(float value) { mStepPerUnit = value > 0 ? value : 1f; }
    public void setDefaultValue(float value) { mDefaultValue = value; super.setDefaultValue(value); }
    private int maxProgress() { return Math.max(1, Math.round((mMax - mMin) * mStepPerUnit)); }

    public float getFloatValue() {
        SharedPreferences prefs = getSharedPreferences();
        return PreferenceNumber.bounded(prefs == null ? null : prefs.getAll().get(getKey()),
                mDefaultValue, mMin, mMax);
    }
    @Override protected void onSetInitialValue(Object defaultValue) { refresh(); }
    @Override public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        seekBar = (SeekBar) holder.findViewById(R.id.seekbar);
        seekBarValue = (TextView) holder.findViewById(R.id.seekbar_value);
        seekBar.setOnSeekBarChangeListener(null);
        seekBar.setMax(maxProgress());
        refresh();
        seekBar.setOnSeekBarChangeListener(this);
        holder.itemView.setOnClickListener(v -> showPreciseValueDialog());
        if (seekBarValue != null) seekBarValue.setOnClickListener(v -> showPreciseValueDialog());
    }
    private void refresh() {
        float value = getFloatValue();
        if (seekBar != null) seekBar.setProgress(PreferenceNumber.progress(value, mMin, mStepPerUnit, maxProgress()));
        if (seekBarValue != null) {
            seekBarValue.setText(PreferenceNumber.format(value, isFloat));
            SharedPreferences prefs = getSharedPreferences();
            boolean custom = prefs != null && prefs.contains(getKey());
            seekBarValue.setTextColor(custom ? Color.parseColor("#4CAF50")
                    : MaterialColors.getColor(getContext(), android.R.attr.textColorPrimary, Color.WHITE));
        }
    }
    private void save(float requested, boolean reset) {
        if (!isEnabled() || !Float.isFinite(requested)) return;
        float value = PreferenceNumber.bounded(requested, mDefaultValue, mMin, mMax);
        if (!isFloat) value = Math.round(value);
        Object candidate = isFloat ? (Object) Float.valueOf(value) : Integer.valueOf(Math.round(value));
        if (!callChangeListener(candidate)) { refresh(); return; }
        SharedPreferences prefs = getSharedPreferences();
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (reset) editor.remove(getKey());
        else if (isFloat) editor.putFloat(getKey(), value);
        else editor.putInt(getKey(), Math.round(value));
        editor.apply();
        refresh();
    }
    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (!fromUser || !isEnabled()) return;
        if (PhotonCamera.getVibration() != null) PhotonCamera.getVibration().Tick();
        save((float) (progress / (double) mStepPerUnit + mMin), false);
    }
    @Override public void onStartTrackingTouch(SeekBar bar) {}
    @Override public void onStopTrackingTouch(SeekBar bar) {}

    public float minimum(){return mMin;}
    public float maximum(){return mMax;}
    public boolean decimal(){return isFloat;}
    public float defaultNumber(){return mDefaultValue;}
    private void showPreciseValueDialog() {
        if (!isEnabled()) return;
        com.particlesdevs.photoncamera.ui.controls.PrecisionEditor.show(getContext(),String.valueOf(getTitle()),
            mMin,mMax,getFloatValue(),mDefaultValue,isFloat,value->save(value,false));
    }
}
