/*
 *
 *  PhotonCamera
 *  SettingsBarEntryView.java
 *  Copyright (C) 2020 - 2021  Vibhor Srivastava
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 * /
 */

package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarButtonModel;
import com.particlesdevs.photoncamera.ui.camera.model.SettingsBarEntryModel;

import java.util.ArrayList;
import java.util.List;

import static android.view.Gravity.CENTER_VERTICAL;

public class SettingsBarEntryView extends LinearLayout {
    private final TextView titleTextView;
    private final TextView stateTextView;
    private final List<ImageButton> imageButtons = new ArrayList<>();
    private final Context context;

    public SettingsBarEntryView(Context context) {
        super(context);
        this.context = context;
        setPadding(dp(10), dp(8), dp(10), dp(8));
        setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setOrientation(HORIZONTAL);

        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        textContainer.setOrientation(VERTICAL);

        titleTextView = new TextView(context);
        titleTextView.setId(android.R.id.title);
        titleTextView.setGravity(CENTER_VERTICAL);
        titleTextView.setTextAlignment(TEXT_ALIGNMENT_VIEW_END);
        titleTextView.setTextAppearance(R.style.BoldTextWithShadow);
        titleTextView.setTextSize(13);

        stateTextView = new TextView(context);
        stateTextView.setId(android.R.id.summary);
        stateTextView.setGravity(CENTER_VERTICAL);
        stateTextView.setTextAlignment(TEXT_ALIGNMENT_VIEW_END);
        stateTextView.setTextColor(getResolvedAttrData(context, android.R.attr.colorControlActivated));
        LayoutParams textViewParam = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        textViewParam.setMargins(dp(2), dp(2), dp(2), dp(2));

        textContainer.addView(titleTextView, textViewParam);
        textContainer.addView(stateTextView, textViewParam);

        addView(textContainer);
    }

    public void setSettingsBarEntryModel(SettingsBarEntryModel entryModel) {
        titleTextView.setText(entryModel.getTitleStringId());
        stateTextView.setText(entryModel.getStateTextStringId());

        imageButtons.clear();
        if (entryModel.getSettingsBarButtonModels() != null) {
            for (SettingsBarButtonModel buttonModel : entryModel.getSettingsBarButtonModels()) {
                ImageButton button = new ImageButton(context);
                button.setId(buttonModel.getId());
                if (buttonModel.getTextLabel() != null) button.setImageDrawable(textChip(context, buttonModel.getTextLabel()));
                else button.setImageResource(buttonModel.getButtonDrawableId());
                button.setImageTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_selected}, new int[]{-android.R.attr.state_selected}}, new int[]{Color.BLACK, Color.WHITE}));
                button.setBackgroundResource(R.drawable.aux_button_background);
                button.setCropToPadding(false);
                button.setOnClickListener(buttonModel.getButtonClickListener());
                button.setSelected(buttonModel.isSelected());
                imageButtons.add(button);
            }
            addToLayout(imageButtons);
        }
    }

    private void addToLayout(List<ImageButton> buttons) {
        LayoutParams buttonParam = new LayoutParams(dp(40), dp(40));
        buttonParam.setMargins(dp(5), dp(2), dp(5), dp(2));
        if (buttons != null) {
            for (ImageButton button : buttons) {
                addView(button, buttonParam);
            }
        }
    }

    /** Icon-sized bitmap with the chip text (tinted like the icons: the tint list recolours the alpha mask). */
    private static android.graphics.drawable.Drawable textChip(Context context, String text) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int size = Math.round(28 * density);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        float textSize = (text.length() <= 2 ? 13f : text.length() <= 3 ? 11f : 9f) * density;
        paint.setTextSize(textSize);
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint);
        android.graphics.drawable.BitmapDrawable drawable = new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
        drawable.setBounds(0, 0, size, size);
        return drawable;
    }

    private int dp(float f) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, f, getContext().getResources().getDisplayMetrics());
    }

    private int getResolvedAttrData(Context context, int attrId) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(attrId, outValue, true);
        return outValue.data;
    }
}
