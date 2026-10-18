package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.settings.PhotoStyle;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SIMPLE_UI §4 «Стиль снимка» (the concept's style page): one card of three radio rows - Обычный (natural colours),
 * Чёрно-белый (monochrome), Плёнка (the film LUT's colours) - over the black-and-white filter and the film LUT (PhotoStyle).
 * The row of the colour page shows the style as its value.
 */
public class PhotoStylePreference extends Preference implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Nullable private LinearLayout card;

    public PhotoStylePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public PhotoStylePreference(Context context) {
        this(context, null);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDetached() {
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetached();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, @Nullable String key) {
        if (PhotoStyle.MONO_KEY.equals(key) || FilmLut.KEY.equals(key)) fill();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 18));
        card.setTag("photo_style");
        host.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fill();
    }

    private void fill() {
        LinearLayout box = card;
        SharedPreferences prefs = getSharedPreferences();
        if (box == null || prefs == null) return;
        Context c = getContext();
        int accent = SettingsStyle.accent(c), now = PhotoStyle.current(prefs);
        box.removeAllViews();
        String[][] rows = {{Lang.t(c, "Обычный", "Normal"), Lang.t(c, "Естественные цвета", "Natural colours")},
                {Lang.t(c, "Чёрно-белый", "Black and white"), Lang.t(c, "Монохром", "Monochrome")},
                {Lang.t(c, "Плёнка", "Film"), Lang.t(c, "Цвета плёночного LUT", "The film LUT's colours")}};
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                View line = new View(c);
                line.setBackgroundColor(UiTokens.LINE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiTokens.dp(c, .5f)));
                lp.setMarginStart(UiTokens.dp(c, 62));
                box.addView(line, lp);
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(UiTokens.dp(c, 58));
            row.setPadding(UiTokens.dp(c, 14), UiTokens.dp(c, 10), UiTokens.dp(c, 14), UiTokens.dp(c, 10));
            row.setBackgroundResource(R.drawable.settings_row);
            FrameLayout tile = new FrameLayout(c);
            tile.setBackgroundResource(R.drawable.settings_icon_tile);
            ImageView icon = new ImageView(c);
            icon.setImageResource(R.drawable.ui_ic_palette);
            icon.setImageTintList(ColorStateList.valueOf(UiTokens.TEXT));
            tile.addView(icon, new FrameLayout.LayoutParams(UiTokens.dp(c, 22), UiTokens.dp(c, 22), Gravity.CENTER));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(UiTokens.dp(c, 36), UiTokens.dp(c, 36));
            tp.setMarginEnd(UiTokens.dp(c, 12));
            row.addView(tile, tp);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = SettingsStyle.text(c, rows[i][0], 14.5f, UiTokens.TEXT);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            texts.addView(t);
            TextView s = SettingsStyle.text(c, rows[i][1], 12.5f, UiTokens.SUMMARY);
            s.setPadding(0, UiTokens.dp(c, 2), 0, 0);
            texts.addView(s);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(SettingsStyle.radio(c, i == now, accent), new LinearLayout.LayoutParams(UiTokens.dp(c, 24), UiTokens.dp(c, 24)));
            row.setTag("style_option_" + i);
            row.setSelected(i == now);
            row.setContentDescription(rows[i][0]);
            final int style = i;
            row.setOnClickListener(v -> PhotoStyle.apply(prefs, style));
            box.addView(row);
        }
    }
}
