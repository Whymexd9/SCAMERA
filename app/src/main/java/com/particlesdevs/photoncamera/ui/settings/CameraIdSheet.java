package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.Range;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.settings.CameraIdCheck;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P384 (owner, 2026-10-28: «Предлагаю сделать отдельную группу где можно будет вписывать айди модулей»): a module's Camera ID as a
 * bottom sheet of «Camera ID модулей» - Auto (or, for a copy, its source's camera: a copy's automatic camera is the unrelated one
 * its slot was filled with), every camera the phone lists with its facing, focal length and ISO (the logical cameras' physical ones
 * too), and a typed id checked while it is typed (CameraIdCheck): an id the phone does not offer is saved only by «Сохранить всё
 * равно». The write is ModuleActions.assign, as the module page's Camera ID dialog wrote it (ModuleLensFragment.choose, P21).
 */
final class CameraIdSheet {
    private CameraIdSheet() {}

    /** One camera of the list: its id (a bare one, or "logical-physical") and what the phone says of it. */
    private static final class Camera {
        final String id, text, sub;

        Camera(String id, String text, String sub) {
            this.id = id;
            this.text = text;
            this.sub = sub;
        }
    }

    static BottomSheetDialog show(Context c, String slot, Runnable done) {
        final SharedPreferences prefs = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        final int accent = SettingsStyle.accent(c);
        final String label = ModuleRegistry.label(slot), manual = prefs.getString("module_id_" + slot, "").trim();
        final String source = ModuleRegistry.duplicateOf(slot);
        final LinearLayout body = SettingsStyle.sheetBody(c, c.getString(R.string.camera_id_title, label), null);
        body.setTag("camera_id_sheet");
        final TextView subtitle = SettingsStyle.text(c, c.getString(R.string.camera_id_subtitle, label), 13.5f, SettingsStyle.MUTED);
        subtitle.setPadding(SettingsStyle.dp(c, 4), 0, SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 12));
        body.addView(subtitle);
        final BottomSheetDialog sheet = SettingsStyle.sheet(c, body);
        final Runnable chosen = () -> { sheet.dismiss(); done.run(); };

        // Auto (an original) or the source's camera (a copy)
        final List<Camera> cameras = cameras(c);
        boolean known = false;
        if (source == null) {
            final boolean on = manual.isEmpty();
            known = on;
            body.addView(option(c, "camera_id_auto", c.getString(R.string.camera_id_auto, prefs.getString("module_auto_" + slot, slot)),
                    c.getString(R.string.camera_id_auto_sub), on, accent, () -> { ModuleActions.assign(c, slot, ""); chosen.run(); }));
        } else {
            final String id = ModuleRegistry.camera(source);
            final boolean on = manual.equals(id);
            known = on;
            body.addView(option(c, "camera_id_source", c.getString(R.string.camera_id_source, ModuleRegistry.label(source), id),
                    c.getString(R.string.camera_id_source_sub), on, accent, () -> { ModuleActions.assign(c, slot, id); chosen.run(); }));
        }
        for (Camera cam : cameras) {
            final boolean on = !known && !manual.isEmpty() && manual.equals(cam.id);
            known |= on;
            body.addView(option(c, "camera_id_" + cam.id, cam.text, on ? cam.sub + "\n" + c.getString(R.string.module_id_chosen) : cam.sub,
                    on, accent, () -> { ModuleActions.assign(c, slot, cam.id); chosen.run(); }));
        }
        // a typed id: the field, its check as it is typed, and the save
        final boolean typed = !known && !manual.isEmpty();
        final LinearLayout block = manualBlock(c, slot, typed ? manual : "", accent, chosen);
        final View row = option(c, "camera_id_manual", c.getString(R.string.camera_id_manual),
                typed ? c.getString(R.string.module_id_chosen) + " · " + c.getString(R.string.module_id_value, manual) : null, typed, accent, null);
        row.setOnClickListener(v -> {
            if (block.getVisibility() == View.VISIBLE) return;
            if (SettingsMotion.enabled(body)) {
                AutoTransition t = new AutoTransition();
                t.setDuration(220);
                t.setInterpolator(SettingsMotion.EASE_OUT);
                TransitionManager.beginDelayedTransition(body, t);
            }
            block.setVisibility(View.VISIBLE);
            block.findViewWithTag("camera_id_field").requestFocus();
        });
        body.addView(row);
        body.addView(block, new LinearLayout.LayoutParams(-1, -2));
        // an original's own ID: «Авто» brings SCAMERA's choice back (a copy has no Auto: its camera is its source's)
        if (source == null && !manual.isEmpty()) {
            final TextView note = SettingsStyle.text(c, c.getString(R.string.camera_id_chosen_note), 12.5f, SettingsStyle.MUTED);
            note.setPadding(SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 6), 0);
            body.addView(note);
        }
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
        return sheet;
    }

    /** A radio row (≥ 52 dp): the current one tinted. {@code pick} null: the row's click is set by the caller. */
    private static View option(Context c, String tag, CharSequence text, @Nullable CharSequence sub, boolean on, int accent, @Nullable Runnable pick) {
        final LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 11), SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 11));
        row.setMinimumHeight(SettingsStyle.dp(c, 52));
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1FFFFFFF),
                SettingsStyle.shape(c, on ? SettingsStyle.tint(accent, .10f) : 0, 0, 14), SettingsStyle.shape(c, 0xFFFFFFFF, 0, 14)));
        row.addView(SettingsStyle.radio(c, on, accent), new LinearLayout.LayoutParams(SettingsStyle.dp(c, 24), SettingsStyle.dp(c, 24)));
        final LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(SettingsStyle.dp(c, 14), 0, 0, 0);
        final TextView title = SettingsStyle.text(c, text, 16, SettingsStyle.TEXT);
        texts.addView(title);
        if (sub != null && sub.length() > 0) {
            final TextView s = SettingsStyle.text(c, sub, 12.5f, SettingsStyle.SUMMARY);
            s.setLineSpacing(SettingsStyle.dp(c, 2), 1);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = SettingsStyle.dp(c, 3);
            texts.addView(s, lp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        row.setTag(tag);
        row.setFocusable(true);
        row.setContentDescription(sub == null ? text : text + ". " + sub);
        if (pick != null) row.setOnClickListener(v -> pick.run());
        return row;
    }

    /** The typed id: a field, its check line and the save (off for an empty or a malformed id, «Сохранить всё равно» for an unknown one). */
    private static LinearLayout manualBlock(Context c, String slot, String start, int accent, Runnable chosen) {
        final LinearLayout block = new LinearLayout(c);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 4), 0);
        block.setVisibility(start.isEmpty() ? View.GONE : View.VISIBLE);
        block.setTag("camera_id_manual_block");
        final EditText field = DialogStyle.field(new EditText(c));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setHint(c.getString(R.string.camera_id_hint));
        field.setTag("camera_id_field");
        block.addView(field, new LinearLayout.LayoutParams(-1, -2));
        final TextView status = SettingsStyle.text(c, "", 13, SettingsStyle.MUTED);
        status.setTag("camera_id_status");
        status.setPadding(SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 12));
        block.addView(status, new LinearLayout.LayoutParams(-1, -2));
        final TextView save = SettingsStyle.text(c, c.getString(R.string.camera_id_save), 16, SettingsStyle.INK);
        save.setTypeface(null, android.graphics.Typeface.BOLD);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, SettingsStyle.dp(c, 15), 0, SettingsStyle.dp(c, 15));
        save.setBackground(SettingsStyle.shape(c, accent, 0, 16));
        save.setTag("camera_id_save");
        block.addView(save, new LinearLayout.LayoutParams(-1, -2));
        final CameraIdCheck.Result[] now = {null};
        final Runnable check = () -> {
            final CameraIdCheck.Result r = check(c, field.getText().toString());
            now[0] = r;
            final boolean saves = r.state != CameraIdCheck.State.EMPTY && r.state != CameraIdCheck.State.INVALID;
            save.setEnabled(saves);
            save.setAlpha(saves ? 1f : .4f);
            save.setText(c.getString(r.state == CameraIdCheck.State.UNKNOWN ? R.string.camera_id_save_anyway : R.string.camera_id_save));
            status.setText(statusText(c, r));
            status.setTextColor(r.state == CameraIdCheck.State.INVALID || r.state == CameraIdCheck.State.UNKNOWN ? SettingsStyle.WARN : SettingsStyle.MUTED);
        };
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { check.run(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        save.setOnClickListener(v -> {
            final CameraIdCheck.Result r = now[0];
            if (r == null || r.state == CameraIdCheck.State.EMPTY || r.state == CameraIdCheck.State.INVALID) return;
            ModuleActions.assign(c, slot, r.id);
            chosen.run();
        });
        field.setText(start);
        check.run();
        return block;
    }

    static String statusText(Context c, CameraIdCheck.Result r) {
        switch (r.state) {
            case EMPTY: return c.getString(R.string.camera_id_empty);
            case INVALID: return c.getString(R.string.camera_id_invalid);
            case PHYSICAL: {
                final int cut = r.id.indexOf('-');
                return c.getString(R.string.camera_id_physical, r.id.substring(cut + 1), r.id.substring(0, cut), r.id);
            }
            case UNKNOWN: return c.getString(R.string.camera_id_unknown, r.id);
            default: return c.getString(R.string.camera_id_ok, r.id, r.detail == null ? "" : r.detail);
        }
    }

    /** {@link CameraIdCheck#check} against this phone's cameras. */
    static CameraIdCheck.Result check(Context c, String typed) {
        final CameraManager cm = c.getSystemService(CameraManager.class);
        List<String> listed;
        try {
            listed = cm == null ? Collections.emptyList() : Arrays.asList(cm.getCameraIdList());
        } catch (Exception e) {
            listed = Collections.emptyList();
        }
        final List<String> list = listed;
        return CameraIdCheck.check(typed, list, id -> describe(c, cm, id), id -> logicalOf(cm, list, id));
    }

    @Nullable
    private static String logicalOf(@Nullable CameraManager cm, List<String> listed, String id) {
        if (cm == null || android.os.Build.VERSION.SDK_INT < 28) return null;
        for (String logical : listed) {
            try {
                if (cm.getCameraCharacteristics(logical).getPhysicalCameraIds().contains(id)) return logical;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** «задняя, 6.9 мм»; null for a camera the phone does not describe. */
    @Nullable
    static String describe(Context c, @Nullable CameraManager cm, String id) {
        if (cm == null) return null;
        try {
            final CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            final Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            String text = c.getString(facing == null ? R.string.camera_id_external
                    : facing == CameraCharacteristics.LENS_FACING_FRONT ? R.string.camera_id_front
                    : facing == CameraCharacteristics.LENS_FACING_BACK ? R.string.camera_id_back : R.string.camera_id_external);
            final float[] focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focal != null && focal.length > 0)
                text += ", " + c.getString(R.string.camera_id_focal, String.format(Locale.US, "%.1f", focal[0]).replaceAll("\\.0$", ""));
            return text;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** The phone's cameras for the list: every listed id, then (Android 9+) each logical camera's physical ones as pairs. */
    private static List<Camera> cameras(Context c) {
        final List<Camera> out = new ArrayList<>();
        final CameraManager cm = c.getSystemService(CameraManager.class);
        if (cm == null) return out;
        try {
            final Set<String> ids = new LinkedHashSet<>(Arrays.asList(cm.getCameraIdList()));
            if (android.os.Build.VERSION.SDK_INT >= 28)
                for (String id : new ArrayList<>(ids))
                    for (String physical : cm.getCameraCharacteristics(id).getPhysicalCameraIds())
                        if (!physical.equals(id)) ids.add(id + "-" + physical);
            for (String id : ids) {
                final String part = id.contains("-") ? id.substring(id.indexOf('-') + 1) : id;
                final String text = describe(c, cm, part);
                String sub = "";
                try {
                    final CameraCharacteristics ch = cm.getCameraCharacteristics(part);
                    final Range<Integer> iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    if (iso != null) sub = "ISO " + iso.getLower() + "–" + iso.getUpper();
                    if (android.os.Build.VERSION.SDK_INT >= 28 && !ch.getPhysicalCameraIds().isEmpty())
                        sub += (sub.isEmpty() ? "" : " · ") + c.getString(R.string.camera_id_logical, new ArrayList<>(ch.getPhysicalCameraIds()).toString());
                    if (id.contains("-"))
                        sub += (sub.isEmpty() ? "" : " · ") + c.getString(R.string.camera_id_physical_of, id.substring(0, id.indexOf('-')));
                    if (CameraManager2.isAuxiliarySensor(ch)) sub += (sub.isEmpty() ? "" : "\n") + c.getString(R.string.camera_id_service);
                } catch (Exception | LinkageError ignored) { }
                out.add(new Camera(id, text == null ? c.getString(R.string.module_id_value, id) : c.getString(R.string.camera_id_option, id, text), sub));
            }
        } catch (Exception ignored) { }
        return out;
    }
}
