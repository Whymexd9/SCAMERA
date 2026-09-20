package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.text.InputType;
import android.util.AttributeSet;
import com.particlesdevs.photoncamera.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.control.Vibration;

import java.util.Locale;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;

/**
 * Created by vibhorSrv on 12/09/2020
 *
 * Value <-> progress conversion notes:
 *  - The persisted representation is a String. All arithmetic that maps that String onto a
 *    SeekBar index is done in double precision and closed with Math.round(), never with a
 *    (int) truncation. A float32 truncation loses a whole step whenever the value cannot be
 *    represented exactly, e.g. (-0.15f - -0.2f) * 20 == 0.99999994 -> 0 instead of 1.
 *  - Binding the view never rewrites the stored value. Only explicit user interaction
 *    (dragging the bar, or confirming the precise-input dialog) persists anything, so a value
 *    that sits between two steps survives reopening the settings screen.
 */
public class UniversalSeekBarPreference extends Preference implements SeekBar.OnSeekBarChangeListener {
    private static final String TAG = "UnivSeekBarPref";
    private static final boolean isLoggingOn = false;
    /** Sentinel used to detect "nothing persisted yet" without touching the store. */
    private static final String NOT_PERSISTED = "\u0000__np__";
    private final Vibration vibration;
    private final float mMin, mMax;
    private final boolean isFloat, showSeekBarValue;
    private float mStepPerUnit;
    private final int mSeekBarMax;
    private int seekBarProgress;
    private TextView seekBarValue;
    private SeekBar seekBar;
    private String fallback_value;

    public UniversalSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.UniversalSeekBarPreference, defStyleAttr, defStyleRes);
        vibration = PhotonCamera.getVibration();
        mMax = a.getFloat(R.styleable.UniversalSeekBarPreference_maxValue, 100.0f);
        mMin = a.getFloat(R.styleable.UniversalSeekBarPreference_minValue, 0.0f);
        mStepPerUnit = a.getFloat(R.styleable.UniversalSeekBarPreference_stepPerUnit, 1.0f);
        showSeekBarValue = a.getBoolean(R.styleable.UniversalSeekBarPreference_showSeekBarValue, true);
        isFloat = a.getBoolean(R.styleable.UniversalSeekBarPreference_isFloat, false);
        if (!isFloat && mStepPerUnit > 1)
            mStepPerUnit = 1.0f;
        a.recycle();
        // Rounded, not truncated: (1.0f - -0.2f) * 20 evaluates to 24.00000095 in float32,
        // and a range like 0.3f * 10 would truncate to 2 instead of 3.
        mSeekBarMax = Math.max(1, (int) Math.round(((double) mMax - (double) mMin) * (double) mStepPerUnit));
    }

    public UniversalSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public UniversalSeekBarPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UniversalSeekBarPreference(Context context) {
        this(context, null);
    }

    private void log(String msg) {
        if (isLoggingOn)
            Log.d(TAG + getKey(), msg);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        seekBar = (SeekBar) holder.findViewById(R.id.seekbar);
        seekBarValue = (TextView) holder.findViewById(R.id.seekbar_value);
        seekBar.setMax(mSeekBarMax);
        seekBar.setOnSeekBarChangeListener(this);
        // Read-only refresh: must not quantize or rewrite what is already stored.
        showStoredValue();

        // Add click listener for precise value input on the value text
        if (seekBarValue != null) {
            seekBarValue.setOnClickListener(v -> showPreciseValueDialog());
        }
        // Match the reference UI: tapping anywhere on the parameter row opens
        // direct numeric entry; the slider remains available for quick tuning.
        holder.itemView.setOnClickListener(v -> showPreciseValueDialog());
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser && isEnabled() && vibration != null) vibration.Tick();
        if (fromUser && isEnabled()) {
            set(progress);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        if (defaultValue == null) {
            defaultValue = fallback_value;
        }
        // First run only: seed the store so backups/exports contain the key.
        // Afterwards this is a pure refresh, so an off-grid value is preserved.
        if (NOT_PERSISTED.equals(readStoredString(NOT_PERSISTED))) {
            float seed = clamp(parseValue((defaultValue == null ? Float.toString(mMin) : defaultValue.toString()), mMin));
            String seedText = isFloat ? formatGridValue(seed) : formatExactValue(seed);
            seekBarProgress = valueToProgress(seed);
            updateLabel(seedText);
            updateSeekbar(seekBarProgress);
            persistString(seedText);
            log("onSetInitialValue (seed) : " + seedText);
        } else {
            showStoredValue();
        }
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        fallback_value = a.getString(index);
        log("onGetDefaultValue : " + fallback_value);
        return a.getString(index);
    }

    /**
     * Refreshes bar + label from the persisted String without writing anything back.
     * Out-of-range leftovers are the one exception: those are clamped and rewritten.
     */
    private void showStoredValue() {
        String stored = readStoredString(fallback_value == null ? "0" : fallback_value);
        float raw = parseValue(stored, parseValue(fallback_value, mMin));
        float clamped = clamp(raw);
        seekBarProgress = valueToProgress(clamped);
        updateSeekbar(seekBarProgress);
        if (clamped != raw || !Double.isFinite(PreferenceNumber.read(stored, Double.NaN))) {
            String fixed = formatExactValue(clamped);
            updateLabel(fixed);
            persistString(fixed);
            log("showStoredValue (clamped) : " + fixed);
        } else {
            // Show exactly what is stored, so a precise off-grid value stays visible.
            updateLabel(stored);
            log("showStoredValue : " + stored);
        }
    }

    /** User dragged the bar: the value snaps to the step grid and is persisted. */
    private void set(int progress) {
        if (!isEnabled()) return;
        seekBarProgress = clampProgress(progress);
        String valueToPersist = convertToValue(seekBarProgress);
        if (!callChangeListener(valueToPersist)) { showStoredValue(); return; }
        updateLabel(valueToPersist);
        updateSeekbar(seekBarProgress);
        persistString(valueToPersist);
        log("set : " + valueToPersist);
    }

    /** Manual input: keeps the exact value, the bar only shows the nearest step. */
    private void setDirectValue(float value) {
        if (!isEnabled() || !Float.isFinite(value)) return;
        float clamped = clamp(value);
        String valueToPersist = formatExactValue(clamped);
        if (!callChangeListener(valueToPersist)) { showStoredValue(); return; }
        seekBarProgress = valueToProgress(clamped);

        updateLabel(valueToPersist);
        updateSeekbar(seekBarProgress);
        persistString(valueToPersist);
        log("setDirectValue : " + valueToPersist);
    }

    private void updateLabel(String valueToPersist) {
        if (seekBarValue != null) {
            if (showSeekBarValue) {
                seekBarValue.setVisibility(View.VISIBLE);
                seekBarValue.setText(valueToPersist);
            } else
                seekBarValue.setVisibility(View.GONE);
        }
    }

    private void updateSeekbar(int progress) {
        if (seekBar != null)
            seekBar.setProgress(clampProgress(progress));
    }

    private float clamp(float value) {
        if (value < mMin) return mMin;
        if (value > mMax) return mMax;
        return value;
    }

    private int clampProgress(int progress) {
        if (progress < 0) return 0;
        return Math.min(progress, mSeekBarMax);
    }

    private float parseValue(String text, float fallback) {
        if (text == null) return fallback;
        try {
            return (float) PreferenceNumber.read(text, fallback);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Unparsable stored value '" + text + "' for " + getKey());
            return fallback;
        }
    }

    /**
     * Maps a value onto the nearest step index. Double precision plus Math.round, because
     * float32 subtraction of mMin lands just below the exact multiple for most decimals.
     */
    private int valueToProgress(float value) {
        double steps = ((double) value - (double) mMin) * (double) mStepPerUnit;
        return clampProgress((int) Math.round(steps));
    }

    private String convertToValue(int progress) {
        double value = (double) progress / (double) mStepPerUnit + (double) mMin;
        if (isFloat)
            return String.format(Locale.ROOT, "%.2f", value);
        else
            return String.valueOf((int) Math.round(value));
    }

    /** Trimmed representation used for off-grid values and for dialog hints. */
    private String formatExactValue(float value) {
        return PreferenceNumber.format(value, isFloat);
    }

    /** Grid representation, identical to what dragging the bar produces. */
    private String formatGridValue(float value) {
        return isFloat ? String.format(Locale.ROOT, "%.2f", value) : String.valueOf(Math.round(value));
    }

    private String readStoredString(String fallback) {
        android.content.SharedPreferences prefs = getSharedPreferences();
        Object value = prefs == null ? null : prefs.getAll().get(getKey());
        return value == null ? fallback : value.toString();
    }

    /** Reset through the Preference store, preserving module/profile scoping. */
    public void resetToDefault() {
        persistString(formatExactValue(clamp(defaultNumber())));
        showStoredValue();
        notifyChanged();
    }

    public String getValue() {
        return readStoredString(fallback_value);
    }

    public int getSeekBarProgress() {
        return seekBarProgress;
    }

    public SeekBar getSeekBar() {
        return seekBar;
    }

    public float minimum(){return mMin;}
    public float maximum(){return mMax;}
    public boolean decimal(){return isFloat;}
    public float defaultNumber(){return parseValue(fallback_value,mMin);}
    private void showPreciseValueDialog() {
        if (!isEnabled()) return;
        com.particlesdevs.photoncamera.ui.controls.PrecisionEditor.show(getContext(),String.valueOf(getTitle()),
            mMin,mMax,clamp(parseValue(getValue(),defaultNumber())),defaultNumber(),isFloat,this::setDirectValue);
    }
}
