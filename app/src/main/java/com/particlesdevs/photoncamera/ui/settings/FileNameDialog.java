package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.particlesdevs.photoncamera.processing.FileNamePattern;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * «Название фото» (P119, like ArkCam's «Формат названия фото»): the template, the name it gives now, the date / time variables to
 * insert at the cursor, ready templates; «Сбросить» (the default SCA_yyyyMMdd_HHmmss), «Отмена», «Сохранить».
 */
final class FileNameDialog {
    private FileNameDialog() {}

    static void show(Context context, Runnable onSaved) {
        final float dp = context.getResources().getDisplayMetrics().density;
        final int pad = Math.round(20 * dp), gap = Math.round(8 * dp);
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, gap, pad, 0);

        final EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setHint(Lang.t(context, "Шаблон, например SCA_yyyyMMdd_HHmmss", "Template, e.g. SCA_yyyyMMdd_HHmmss"));
        field.setText(PreferenceKeys.getFileNamePattern());
        field.setSelection(field.getText().length());
        box.addView(field);

        final TextView preview = new TextView(context);
        preview.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
        preview.setBackground(rounded(0x33808080, 14 * dp));
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        final LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        previewLp.topMargin = gap;
        box.addView(preview, previewLp);
        final Runnable update = () -> preview.setText(Lang.t(context, "Предпросмотр\n", "Preview\n")
                + FileNamePattern.format(field.getText().toString(), System.currentTimeMillis()) + ".jpg");
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { update.run(); }
        });
        update.run();

        box.addView(label(context, Lang.t(context, "Дата и время (нажмите, чтобы вставить):", "Date and time (tap to insert):"), gap));
        final LinearLayout variables = row(context, box, gap);
        for (String v : FileNamePattern.VARIABLES) {
            variables.addView(chip(context, v, dp), chipLp(gap));
            variables.getChildAt(variables.getChildCount() - 1).setOnClickListener(x -> {
                final int a = Math.max(0, field.getSelectionStart()), b = Math.max(0, field.getSelectionEnd());
                field.getText().replace(Math.min(a, b), Math.max(a, b), v);
            });
        }
        box.addView(label(context, Lang.t(context, "Готовые шаблоны:", "Ready templates:"), gap));
        final LinearLayout presets = row(context, box, gap);
        for (String p : FileNamePattern.PRESETS) {
            presets.addView(chip(context, p, dp), chipLp(gap));
            presets.getChildAt(presets.getChildCount() - 1).setOnClickListener(x -> {
                field.setText(p);
                field.setSelection(field.getText().length());
            });
        }

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(Lang.t(context, "Название фото", "Photo file name"))
                .setView(box)
                .setNeutralButton(Lang.t(context, "Сбросить", "Reset"), null)
                .setNegativeButton(Lang.t(context, "Отмена", "Cancel"), null)
                .setPositiveButton(Lang.t(context, "Сохранить", "Save"), (d, w) -> {
                    PreferenceKeys.setFileNamePattern(FileNamePattern.clean(field.getText().toString()));
                    if (onSaved != null) onSaved.run();
                })
                .create();
        // «Сбросить» puts the default in the field and leaves the dialog open (saved with «Сохранить»)
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x -> {
            field.setText(FileNamePattern.DEFAULT);
            field.setSelection(field.getText().length());
        }));
        dialog.show();
    }

    private static TextView label(Context context, String text, int gap) {
        final TextView t = new TextView(context);
        t.setText(text);
        t.setPadding(0, 2 * gap, 0, gap / 2);
        return t;
    }

    private static LinearLayout row(Context context, LinearLayout box, int gap) {
        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        box.addView(scroll);
        return row;
    }

    private static TextView chip(Context context, String text, float dp) {
        final TextView t = new TextView(context);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Math.round(48 * dp));
        t.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
        t.setBackground(rounded(0x1F808080, 12 * dp));
        t.setClickable(true);
        t.setFocusable(true);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return t;
    }

    private static LinearLayout.LayoutParams chipLp(int gap) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = gap;
        return lp;
    }

    private static GradientDrawable rounded(int color, float radius) {
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }
}
