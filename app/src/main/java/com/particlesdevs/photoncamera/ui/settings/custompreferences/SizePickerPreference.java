package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.camera.CameraSheets;
import com.particlesdevs.photoncamera.ui.camera.PhotoSize;

/**
 * SIMPLE_UI §4 «Размер фото» (the concept's size page): «Обычный · 12 Мп», «Подробный · 50 Мп», «Максимум · 200 Мп» as radio
 * rows for the camera's current lens (PhotoSize: a size it lacks is dimmed and says why). A pick switches the size as the
 * camera's sheet does; the camera, closed while the settings are open, builds its session with it on return.
 */
public class SizePickerPreference extends Preference {
    public SizePickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public SizePickerPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        host.addView(CameraSheets.sizeCard(c, PhotoSize.current(), PhotoSize.fullAvailable(), PhotoSize.maxAvailable(), size -> {
            String message = PhotoSize.set(null, size);
            Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
            notifyChanged();
        }, refused -> Toast.makeText(c, refused, Toast.LENGTH_SHORT).show()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
