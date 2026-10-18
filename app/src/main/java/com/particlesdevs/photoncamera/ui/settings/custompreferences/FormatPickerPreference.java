package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.settings.FormatChoice;
import com.particlesdevs.photoncamera.ui.camera.CameraSheets;

import java.util.List;

/**
 * SIMPLE_UI §4 «Формат файла» (the concept's format page): the choice at the top of the page as the camera's sheet draws it
 * - «Фото» (JPEG / HEIC / WebP / AVIF) and «RAW (DNG)» (none / + RAW / RAW only) as two segmented rows, what is saved, the
 * note - instead of a list row. Its value is the FormatChoice (the settings wire the change to FormatChoice.store).
 */
public class FormatPickerPreference extends ListPreference {
    public FormatPickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public FormatPickerPreference(Context context) {
        this(context, null);
    }

    FormatChoice choice(List<FormatChoice> offered) {
        try {
            if (getValue() != null) return FormatChoice.valueOf(getValue());
        } catch (IllegalArgumentException ignored) {
            // an old codec value: the current choice below
        }
        try {
            return FormatChoice.current();
        } catch (RuntimeException e) {
            return offered.isEmpty() ? FormatChoice.values()[0] : offered.get(0);
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        List<FormatChoice> offered = FormatChoice.offered(android.os.Build.VERSION.SDK_INT);
        PhotoFormat stored;
        try {
            stored = com.particlesdevs.photoncamera.settings.PreferenceKeys.getPhotoFormat();
        } catch (RuntimeException e) {
            stored = null;
        }
        android.widget.LinearLayout page = CameraSheets.formatContent(c, offered, choice(offered), stored, picked -> {
            if (callChangeListener(picked.name())) setValue(picked.name());
        });
        // the rows under it are the concept's «Качество» group
        android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.dp(c, 18);
        tp.bottomMargin = com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.dp(c, 2);
        page.addView(FilmLutPreference.groupTitle(c, com.particlesdevs.photoncamera.util.Lang.t(c, "Качество", "Quality"),
                com.particlesdevs.photoncamera.ui.settings.SettingsStyle.accent(c)), tp);
        host.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
