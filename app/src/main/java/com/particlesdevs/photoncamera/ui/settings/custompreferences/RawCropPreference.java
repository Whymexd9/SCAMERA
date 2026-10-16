package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.RawCrop;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.Arrays;

/**
 * P162 «Обрезка кадра»: the picture of the crop (RawCropView) at the top of the page. It follows the switch «Убирать чёрные
 * полосы», the four own margins and the last shot's detection as they change (gliding), and a dragged edge of the kept frame
 * sets that side's margin: stored through its slider when the finger lifts.
 */
public class RawCropPreference extends Preference implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Nullable private RawCropView shown;
    @Nullable private SharedPreferences listening;

    public RawCropPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_raw_crop);
        setPersistent(false);
        setSelectable(false);
    }

    public RawCropPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        FrameLayout host = (FrameLayout) holder.findViewById(R.id.raw_crop_host);
        RawCropView view = host.getChildCount() > 0 && host.getChildAt(0) instanceof RawCropView ? (RawCropView) host.getChildAt(0) : null;
        if (view == null) {
            view = new RawCropView(getContext());
            view.setTag("raw_crop_view");
            host.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        shown = view;
        view.setOnMarginDrag(this::dragged);
        view.setEnabled(isEnabled());
        view.show(state(), view.state() != null);
        listen();
    }

    /** The crop the next shot makes with what is stored now. */
    RawCropView.State state() {
        final SharedPreferences prefs = getSharedPreferences();
        final boolean auto = prefs == null || prefs.getBoolean(PreferenceKeys.RAW_CROP_AUTO, true);
        final int[] margins = new int[4];
        for (int i = 0; i < 4; i++) margins[i] = margin(prefs, PreferenceKeys.RAW_CROP_SIDES[i]);
        final String detected = prefs == null ? "" : String.valueOf(com.particlesdevs.photoncamera.settings.PreferenceValue.get(prefs,
                PreferenceKeys.RAW_CROP_DETECTED));
        return RawCropView.compute(auto, margins, detected);
    }

    private static int margin(@Nullable SharedPreferences prefs, String key) {
        if (prefs == null) return 0;
        final Object value = com.particlesdevs.photoncamera.settings.PreferenceValue.get(prefs, key);
        if (value == null) return 0;
        try {
            return Math.max(0, Math.min(RawCrop.MAX_MARGIN, (int) Math.round(Double.parseDouble(value.toString()))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A dragged edge, when the finger lifts: the side's slider takes the value (and stores it). */
    private void dragged(int side, int px, boolean done) {
        if (!done) return;
        final Preference row = findPreferenceInHierarchy(PreferenceKeys.RAW_CROP_SIDES[side]);
        if (row instanceof UniversalSeekBarPreference) ((UniversalSeekBarPreference) row).setNumber(px);
        else if (getSharedPreferences() != null)
            getSharedPreferences().edit().putString(PreferenceKeys.RAW_CROP_SIDES[side], String.valueOf(px)).apply();
    }

    private void listen() {
        final SharedPreferences prefs = getSharedPreferences();
        if (prefs == listening) return;
        if (listening != null) listening.unregisterOnSharedPreferenceChangeListener(this);
        listening = prefs;
        if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDetached() {
        if (listening != null) listening.unregisterOnSharedPreferenceChangeListener(this);
        listening = null;
        super.onDetached();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, @Nullable String key) {
        if (shown == null || key == null) return;
        if (!key.equals(PreferenceKeys.RAW_CROP_AUTO) && !key.equals(PreferenceKeys.RAW_CROP_DETECTED)
                && !Arrays.asList(PreferenceKeys.RAW_CROP_SIDES).contains(key)) return;
        shown.show(state(), true);
    }
}
