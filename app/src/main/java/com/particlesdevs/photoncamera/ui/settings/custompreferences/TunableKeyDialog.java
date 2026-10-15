package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Creating, editing and deleting a {@link VendorTagUtils.TunableKey}: a bottom sheet in the style of the other value editors
 * (P137). The key's type is an explicit choice: CaptureRequest (every request) or Session (the session parameters only - the
 * vivo X300 Ultra's vivo.control.forceSensorMode, P122). Pass editIndex = -1 to create a new key.
 */
public class TunableKeyDialog {
    static final String[] VALUE_TYPES = {"Integer", "Long", "Float", "Double", "Byte", "Short", "int[]", "byte[]", "long[]", "float[]", "String"};
    static final String TYPE_REQUEST = "CaptureRequest";

    private TunableKeyDialog() {}

    public static BottomSheetDialog show(Context c, String sensorId, int editIndex, Runnable onDone) {
        if (c == null || sensorId == null) return null;
        List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(c, sensorId);
        VendorTagUtils.TunableKey existing = (editIndex >= 0 && editIndex < keys.size()) ? keys.get(editIndex) : null;
        boolean isNew = existing == null;
        int accent = SettingsStyle.accent(c);

        LinearLayout body = SettingsStyle.sheetBody(c, isNew ? Lang.t(c, "Новый vendor tag", "New vendor tag") : "Vendor tag", null);
        body.addView(label(c, Lang.t(c, "Имя ключа", "Key name")));
        EditText name = field(c, existing != null ? existing.name : "", "vendor_name");
        name.setHint("vivo.control.forceSensorMode");
        body.addView(name, fieldParams(c));

        // the key's type: where it goes; an old CaptureResult key keeps its type as a third choice
        List<String> types = new ArrayList<>();
        types.add(TYPE_REQUEST);
        types.add(VendorTagUtils.TunableKey.TYPE_SESSION);
        String type = existing != null && existing.type != null ? existing.type : TYPE_REQUEST;
        if (!types.contains(type)) types.add(type);
        final String[] chosenType = {type};
        body.addView(label(c, Lang.t(c, "Тип ключа", "Key type")));
        LinearLayout typeRow = new LinearLayout(c);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView typeHint = SettingsStyle.text(c, "", 13, SettingsStyle.MUTED);
        typeHint.setPadding(SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 4), 0);
        List<TextView> pills = new ArrayList<>();
        for (String t : types) {
            TextView pill = SettingsStyle.text(c, t, 15, SettingsStyle.TEXT);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 13), SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 13));
            pill.setTag("vendor_type_" + t);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -2, 1);
            if (!pills.isEmpty()) pp.leftMargin = SettingsStyle.dp(c, 8);
            typeRow.addView(pill, pp);
            pills.add(pill);
        }
        Runnable paintTypes = () -> {
            for (TextView pill : pills) {
                boolean on = pill.getText().toString().equals(chosenType[0]);
                pill.setSelected(on);
                pill.setTextColor(on ? accent : SettingsStyle.TEXT);
                pill.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                pill.setBackground(SettingsStyle.shape(c, on ? SettingsStyle.tint(accent, .16f) : SettingsStyle.FIELD,
                        on ? 0 : SettingsStyle.LINE, 14));
            }
            typeHint.setText(typeHint(c, chosenType[0]));
        };
        for (TextView pill : pills) pill.setOnClickListener(v -> { chosenType[0] = pill.getText().toString(); paintTypes.run(); });
        paintTypes.run();
        body.addView(typeRow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = SettingsStyle.dp(c, 6);
        body.addView(typeHint, hp);

        body.addView(label(c, Lang.t(c, "Тип значения", "Value type")));
        final String[] valueType = {existing != null && existing.valueType != null ? existing.valueType : "Integer"};
        TextView valueTypeRow = SettingsStyle.text(c, valueType[0], 17, SettingsStyle.TEXT);
        valueTypeRow.setBackground(SettingsStyle.shape(c, SettingsStyle.FIELD, SettingsStyle.LINE, 14));
        valueTypeRow.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 14), SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 14));
        valueTypeRow.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        valueTypeRow.setTag("vendor_value_type");
        valueTypeRow.setOnClickListener(v -> {
            int selected = java.util.Arrays.asList(VALUE_TYPES).indexOf(valueType[0]);
            SettingsStyle.optionSheet(c, Lang.t(c, "Тип значения", "Value type"), VALUE_TYPES, VALUE_TYPES, selected, accent, i -> {
                valueType[0] = VALUE_TYPES[i];
                valueTypeRow.setText(valueType[0]);
            });
        });
        body.addView(valueTypeRow, fieldParams(c));

        body.addView(label(c, Lang.t(c, "Значение", "Value")));
        EditText value = field(c, existing != null ? existing.value : "0", "vendor_value");
        value.setHint(Lang.t(c, "массив — через запятую: 1,0,2", "an array: comma separated, 1,0,2"));
        body.addView(value, fieldParams(c));

        TextView save = button(c, Lang.t(c, "Сохранить", "Save"), SettingsStyle.INK, accent, 0);
        save.setTag("sheet_save");
        body.addView(save, new LinearLayout.LayoutParams(-1, -2));
        TextView delete = null;
        if (!isNew) {
            delete = button(c, Lang.t(c, "Удалить", "Delete"), SettingsStyle.WARN, SettingsStyle.FIELD, SettingsStyle.LINE);
            delete.setTag("vendor_delete");
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
            dp.topMargin = SettingsStyle.dp(c, 10);
            body.addView(delete, dp);
        }

        BottomSheetDialog d = SettingsStyle.sheet(c, body);
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) {
                ScameraApp.showToast(Lang.t(c, "Имя ключа не может быть пустым", "The key name can't be empty"));
                return;
            }
            String text = value.getText().toString().trim();
            try {
                VendorTagUtils.TunableKey.parseValue(valueType[0], text);
            } catch (RuntimeException wrong) {
                ScameraApp.showToast(Lang.t(c, "Значение не подходит к типу " + valueType[0], "The value does not fit " + valueType[0]));
                return;
            }
            VendorTagUtils.TunableKey key = new VendorTagUtils.TunableKey();
            key.type = chosenType[0];
            key.name = n;
            key.valueType = valueType[0];
            key.value = text;
            key.tested = existing != null && existing.tested;
            key.enabled = existing == null || existing.enabled;
            key.supported = existing != null && existing.supported;
            List<VendorTagUtils.TunableKey> list = TunableKeyManager.loadKeys(c, sensorId);
            if (isNew) list.add(key);
            else if (editIndex < list.size()) list.set(editIndex, key);
            TunableKeyManager.saveKeys(c, sensorId, list);
            d.dismiss();
            if (onDone != null) onDone.run();
        });
        if (delete != null) delete.setOnClickListener(v -> {
            List<VendorTagUtils.TunableKey> list = TunableKeyManager.loadKeys(c, sensorId);
            if (editIndex >= 0 && editIndex < list.size()) {
                list.remove(editIndex);
                TunableKeyManager.saveKeys(c, sensorId, list);
            }
            d.dismiss();
            if (onDone != null) onDone.run();
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        if (isNew) name.requestFocus();
        return d;
    }

    static String typeHint(Context c, String type) {
        if (VendorTagUtils.TunableKey.TYPE_SESSION.equals(type))
            return Lang.t(c, "Только в параметрах сессии, при её открытии (как forceSensorMode на X300 Ultra)",
                    "Only in the session parameters, when the session opens (like forceSensorMode on the X300 Ultra)");
        if (TYPE_REQUEST.equals(type))
            return Lang.t(c, "В каждом запросе кадра", "In every frame request");
        return Lang.t(c, "Читается из результата кадра", "Read from the frame result");
    }

    private static TextView label(Context c, String text) {
        TextView t = SettingsStyle.text(c, text, 13, SettingsStyle.MUTED);
        t.setPadding(SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 6), 0, SettingsStyle.dp(c, 8));
        return t;
    }

    private static EditText field(Context c, String value, String tag) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setTextSize(17);
        e.setTextColor(SettingsStyle.TEXT);
        e.setHintTextColor(SettingsStyle.MUTED);
        e.setBackground(SettingsStyle.shape(c, SettingsStyle.FIELD, SettingsStyle.LINE, 14));
        e.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 14), SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 14));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value);
        e.setTag(tag);
        return e;
    }

    private static LinearLayout.LayoutParams fieldParams(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = SettingsStyle.dp(c, 12);
        return p;
    }

    private static TextView button(Context c, String text, int color, int fill, int stroke) {
        TextView b = SettingsStyle.text(c, text, 16, color);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, SettingsStyle.dp(c, 15), 0, SettingsStyle.dp(c, 15));
        b.setBackground(SettingsStyle.shape(c, fill, stroke, 16));
        b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return b;
    }
}
