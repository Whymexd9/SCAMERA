package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.ui.controls.PrecisionEditor;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.ScameraFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * P144: the own tone curve of «Кривая AgX и ACES» - its points ("x:y,..." in pref_scam_hybrid_ark_curve_points) edited on a
 * graph with a finger (ToneCurveView), plus «S-кривая» (ArkCam's default) and «Прямая» (the straight line). Under them the
 * selected point: ‹ › to step through the points, «Удалить», and two sliders - «Экспозиция» (x, -5..+5 EV around middle grey)
 * and «Яркость» (y, 0..100 %) - whose values tap to type them (PrecisionEditor). Stored when a gesture ends; the next shot
 * reads it (ArkTone.curveSamples). At the bottom «Сохранить» (a named .txt in Download/SCAMERA/ToneCurves) and «Загрузить»
 * (the saved curves with a preview and a bin, and «Из файла…»: the system picker, through the settings screen's launcher).
 */
public class ToneCurvePreference extends Preference {
    public static final String KEY = "pref_scam_hybrid_ark_curve_points";
    private static final int STEPS = 1000;
    /** Larger files are no curve. */
    private static final int MAX_FILE = 256 * 1024;
    /** The selected point, kept over rebinds (-1: the middle one on the next bind). */
    private int selected = -1;
    @Nullable private ToneCurveView shown;
    @Nullable private Runnable importer, folderOpener;
    @Nullable private Files files;

    /** The curve files (Download/SCAMERA/ToneCurves; the tests give their own). */
    public interface Files {
        String[] list();

        String read(String name) throws IOException;

        /** Returns the path shown to the user. */
        String save(String name, String text) throws IOException;

        boolean delete(String name);

        String folder();
    }

    public ToneCurvePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_tone_curve);
        setPersistent(true);
        setSelectable(false);
    }

    public ToneCurvePreference(Context context) {
        this(context, null);
    }

    @Override
    protected Object onGetDefaultValue(android.content.res.TypedArray a, int index) {
        String v = a.getString(index);
        return v != null ? v : ToneCurve.DEFAULT_POINTS;
    }

    /** The stored points (the default S when none). */
    public String points() {
        return getPersistedString(ToneCurve.DEFAULT_POINTS);
    }

    /** «Из файла…»: the settings screen opens the system picker and hands the result to {@link #load}. */
    public void setImporter(@Nullable Runnable importer) {
        this.importer = importer;
    }

    /** «Открыть папку»: the lasting access to Download/SCAMERA, so curves put there by a PC or a file manager are listed. */
    public void setFolderOpener(@Nullable Runnable folderOpener) {
        this.folderOpener = folderOpener;
    }

    public void setFiles(@Nullable Files files) {
        this.files = files;
    }

    Files files() {
        if (files == null) files = folderFiles(getContext());
        return files;
    }

    /** A curve loaded from a file: on the graph (the middle point selected) and stored. */
    public void load(ToneCurve curve) {
        int middle = curve.size() / 2;
        selected = middle;
        if (shown != null) {
            shown.setCurve(curve); // its change callback copies the graph's old selection back: select after it
            shown.select(middle);
        }
        store(curve);
        if (shown == null) notifyChanged();
    }

    /** A picked file's text (at most 256 KB) as a curve; null when it holds none. */
    @Nullable
    public static ToneCurve readCurve(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_FILE) return null;
        }
        return ToneCurve.fromFileText(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    void store(ToneCurve curve) {
        String text = curve.serialize();
        if (text.equals(points()) || !callChangeListener(text)) return;
        // no notifyChanged: the row on screen already shows it, and a rebind would drop the finger's point
        persistString(text);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.findViewById(R.id.tone_curve_host);
        ToneCurveView view = host.getChildCount() > 0 && host.getChildAt(0) instanceof ToneCurveView
                ? (ToneCurveView) host.getChildAt(0) : null;
        if (view == null) {
            view = new ToneCurveView(c);
            view.setTag("tone_curve_view");
            host.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final ToneCurveView graph = view;
        shown = graph;
        graph.setOnChange(null);
        graph.setCurve(ToneCurve.parse(points()));
        graph.setOnCommit(this::store);
        graph.setEnabled(isEnabled());
        LinearLayout actions = (LinearLayout) holder.findViewById(R.id.tone_curve_actions);
        actions.removeAllViews();
        actions.addView(chip(c, Lang.t(c, "S-кривая", "S-curve"), "tone_curve_default", () -> {
            graph.setCurve(ToneCurve.defaults());
            store(graph.curve());
        }), weight());
        LinearLayout.LayoutParams second = weight();
        second.setMarginStart(SettingsStyle.dp(c, 8));
        actions.addView(chip(c, Lang.t(c, "Прямая", "Straight"), "tone_curve_identity", () -> {
            graph.setCurve(ToneCurve.identity());
            store(graph.curve());
        }), second);
        // P177: «Сравнить:» AgX / ACES / Uchimura drawn on the graph (once, below the shortcuts)
        ViewGroup card = (ViewGroup) actions.getParent();
        View oldCompare = card.findViewWithTag("tone_curve_compare");
        if (oldCompare != null) card.removeView(oldCompare);
        LinearLayout compare = ToneCurveCompare.chips(c, graph);
        LinearLayout.LayoutParams cmp = new LinearLayout.LayoutParams(-1, -2);
        cmp.topMargin = SettingsStyle.dp(c, 8);
        card.addView(compare, card.indexOfChild(actions) + 1, cmp);
        // the row dims as a whole when the curve is off (SettingsStyle.bind); the graph and its controls only stop reacting
        for (int i = 0; i < actions.getChildCount(); i++) actions.getChildAt(i).setEnabled(isEnabled());
        PointPanel panel = new PointPanel(c, graph, (LinearLayout) holder.findViewById(R.id.tone_curve_point), isEnabled());
        graph.setOnChange(() -> {
            selected = graph.selected();
            panel.refresh();
        });
        graph.select(selected >= 0 && selected < graph.curve().size() ? selected : graph.curve().size() / 2);
        LinearLayout fileRow = (LinearLayout) holder.findViewById(R.id.tone_curve_files);
        fileRow.removeAllViews();
        fileRow.addView(chip(c, Lang.t(c, "Сохранить", "Save"), "tone_curve_save", () -> saveSheet(c, graph)), weight());
        LinearLayout.LayoutParams load = weight();
        load.setMarginStart(SettingsStyle.dp(c, 8));
        fileRow.addView(chip(c, Lang.t(c, "Загрузить", "Load"), "tone_curve_load", () -> loadSheet(c)), load);
        for (int i = 0; i < fileRow.getChildCount(); i++) fileRow.getChildAt(i).setEnabled(isEnabled());
    }

    // ───── files

    /** «Сохранить»: the name (a free «Кривая_N» offered), then a .txt in the folder; the same name replaces that file. */
    private void saveSheet(Context c, ToneCurveView graph) {
        LinearLayout body = SettingsStyle.sheetBody(c, Lang.t(c, "Сохранить кривую", "Save the curve"), null);
        TextView where = SettingsStyle.text(c, Lang.t(c, "Папка: ", "Folder: ") + files().folder(), 13, SettingsStyle.MUTED);
        where.setPadding(SettingsStyle.dp(c, 4), 0, SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 10));
        where.setTag("curve_folder");
        body.addView(where);
        EditText field = new EditText(c);
        field.setSingleLine(true);
        field.setTextSize(17);
        field.setTextColor(SettingsStyle.TEXT);
        field.setHintTextColor(SettingsStyle.MUTED);
        field.setHint(Lang.t(c, "Название", "Name"));
        field.setBackground(SettingsStyle.shape(c, SettingsStyle.FIELD, SettingsStyle.LINE, 14));
        int p16 = SettingsStyle.dp(c, 16), p14 = SettingsStyle.dp(c, 14);
        field.setPadding(p16, p14, p16, p14);
        field.setText(freeName(c));
        field.selectAll();
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTag("curve_name");
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.bottomMargin = p14;
        body.addView(field, fp);
        TextView save = accentButton(c, Lang.t(c, "Сохранить", "Save"));
        body.addView(save, new LinearLayout.LayoutParams(-1, -2));
        BottomSheetDialog d = SettingsStyle.sheet(c, body);
        Runnable commit = () -> {
            String base = ScameraFolder.CURVES.baseName(field.getText().toString());
            if (base.isEmpty()) {
                field.setError(Lang.t(c, "Введите название", "Enter a name"));
                return;
            }
            try {
                String path = files().save(base + ".txt", graph.curve().toFileText());
                Toast.makeText(c, Lang.t(c, "Кривая сохранена: ", "Curve saved: ") + path, Toast.LENGTH_SHORT).show();
                d.dismiss();
            } catch (IOException | RuntimeException e) {
                field.setError(Lang.t(c, "Не сохранилось: ", "Not saved: ") + e.getLocalizedMessage());
            }
        };
        save.setOnClickListener(v -> commit.run());
        field.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                commit.run();
                return true;
            }
            return false;
        });
        d.show();
        field.requestFocus();
    }

    /** The first «Кривая_N» not taken in the folder. */
    private String freeName(Context c) {
        Set<String> taken = new HashSet<>();
        for (String n : files().list()) taken.add(n.toLowerCase(Locale.ROOT));
        String stem = Lang.t(c, "Кривая_", "Curve_");
        int i = 1;
        while (taken.contains((stem + i + ".txt").toLowerCase(Locale.ROOT))) i++;
        return stem + i;
    }

    /**
     * «Загрузить»: the saved curves (a preview of each, a tap loads it; the bin asks once more, then deletes the file) and
     * «Из файла…» for a curve from anywhere (another phone, ArkCam's "x:y,..." text).
     */
    private void loadSheet(Context c) {
        LinearLayout body = SettingsStyle.sheetBody(c, Lang.t(c, "Загрузить кривую", "Load a curve"), null);
        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setTag("curve_list");
        NestedScrollView scroll = new NestedScrollView(c);
        scroll.addView(rows);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        BottomSheetDialog d = SettingsStyle.sheet(c, body);
        // P177: the operators as own curves to edit (AgX / ACES / Uchimura with today's settings, the S-curve, the straight line)
        TextView templates = SettingsStyle.text(c, Lang.t(c, "Шаблоны: AgX, ACES, Uchimura, S-кривая, прямая",
                "Templates: AgX, ACES, Uchimura, S-curve, straight"), 15, SettingsStyle.accent(c));
        templates.setTag("curve_templates");
        templates.setGravity(Gravity.CENTER);
        int tp = SettingsStyle.dp(c, 12);
        templates.setPadding(tp, tp, tp, tp);
        templates.setBackground(SettingsStyle.shape(c, SettingsStyle.tint(SettingsStyle.accent(c), .12f), 0, 16));
        templates.setOnClickListener(v -> {
            d.dismiss();
            ToneCurveCompare.pickTemplate(c, SettingsStyle.accent(c), this::load);
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.bottomMargin = SettingsStyle.dp(c, 10);
        body.addView(templates, body.indexOfChild(scroll), tlp);
        TextView empty = SettingsStyle.text(c, Lang.t(c, "Сохранённых кривых пока нет. Папка: ", "No saved curves yet. Folder: ")
                + files().folder(), 14, SettingsStyle.MUTED);
        empty.setPadding(SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 12));
        empty.setTag("curve_empty");
        for (String name : files().list()) {
            ToneCurve curve;
            try {
                curve = ToneCurve.fromFileText(files().read(name));
            } catch (IOException | RuntimeException e) {
                curve = null;
            }
            if (curve == null) continue; // not a curve: not offered
            rows.addView(curveRow(c, d, rows, empty, name, curve));
        }
        if (rows.getChildCount() == 0) rows.addView(empty);
        if (folderOpener != null && ScameraFolder.CURVES.grantedTree(c) == null) {
            // curves put into Download/SCAMERA/ToneCurves by others: listed once the folder is open to the app
            TextView open = SettingsStyle.text(c, Lang.t(c, "Открыть папку: закинутые в неё кривые появятся здесь",
                    "Open the folder: curves put into it show up here"), 14, SettingsStyle.accent(c));
            open.setGravity(Gravity.CENTER);
            open.setPadding(SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 12));
            open.setBackground(SettingsStyle.shape(c, 0, SettingsStyle.LINE, 16));
            open.setTag("curve_open_folder");
            open.setOnClickListener(v -> {
                d.dismiss();
                folderOpener.run();
            });
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
            op.topMargin = SettingsStyle.dp(c, 10);
            body.addView(open, op);
        }
        TextView pick = SettingsStyle.text(c, Lang.t(c, "Из файла…", "From a file…"), 16, SettingsStyle.accent(c));
        pick.setTypeface(null, Typeface.BOLD);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(0, SettingsStyle.dp(c, 14), 0, SettingsStyle.dp(c, 14));
        pick.setBackground(SettingsStyle.shape(c, SettingsStyle.tint(SettingsStyle.accent(c), .12f), 0, 16));
        pick.setTag("curve_import");
        pick.setOnClickListener(v -> {
            d.dismiss();
            if (importer != null) importer.run();
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = SettingsStyle.dp(c, 10);
        body.addView(pick, pp);
        int cap = (int) (c.getResources().getDisplayMetrics().heightPixels * .5f);
        scroll.post(() -> {
            if (scroll.getHeight() > cap) {
                scroll.getLayoutParams().height = cap;
                scroll.requestLayout();
            }
        });
        d.show();
    }

    private View curveRow(Context c, BottomSheetDialog d, LinearLayout rows, TextView empty, String name, ToneCurve curve) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 8));
        row.setMinimumHeight(SettingsStyle.dp(c, 60));
        row.setBackground(SettingsStyle.shape(c, 0, 0, 14));
        row.setTag("curve_row_" + name);
        ToneCurveThumb thumb = new ToneCurveThumb(c, curve);
        row.addView(thumb, new LinearLayout.LayoutParams(SettingsStyle.dp(c, 58), SettingsStyle.dp(c, 44)));
        String shownName = name.toLowerCase(Locale.ROOT).endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
        TextView label = SettingsStyle.text(c, shownName, 16, SettingsStyle.TEXT);
        label.setPadding(SettingsStyle.dp(c, 14), 0, SettingsStyle.dp(c, 8), 0);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        // the bin: one tap turns it into «Удалить», the second deletes the file and folds the row out
        FrameLayout binSlot = new FrameLayout(c);
        ImageView bin = new ImageView(c);
        bin.setImageResource(R.drawable.settings_ic_delete);
        bin.setColorFilter(SettingsStyle.WARN, PorterDuff.Mode.SRC_IN);
        int p10 = SettingsStyle.dp(c, 10);
        bin.setPadding(p10, p10, p10, p10);
        bin.setContentDescription(Lang.t(c, "Удалить кривую", "Delete the curve"));
        bin.setTag("curve_bin_" + name);
        binSlot.addView(bin, new FrameLayout.LayoutParams(SettingsStyle.dp(c, 44), SettingsStyle.dp(c, 44), Gravity.CENTER));
        TextView confirm = SettingsStyle.text(c, Lang.t(c, "Удалить", "Delete"), 13.5f, SettingsStyle.WARN);
        confirm.setTypeface(null, Typeface.BOLD);
        confirm.setGravity(Gravity.CENTER);
        confirm.setPadding(SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 8), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 8));
        confirm.setBackground(SettingsStyle.shape(c, 0x26FFB4A8, 0, 12));
        confirm.setVisibility(View.GONE);
        confirm.setTag("curve_delete_" + name);
        binSlot.addView(confirm, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        row.addView(binSlot, new LinearLayout.LayoutParams(-2, -2));
        bin.setOnClickListener(v -> {
            bin.setVisibility(View.GONE);
            confirm.setVisibility(View.VISIBLE);
        });
        confirm.setOnClickListener(v -> {
            if (files().delete(name)) {
                rows.removeView(row);
                if (rows.getChildCount() == 0) rows.addView(empty);
            } else {
                Toast.makeText(c, Lang.t(c, "Не удалось удалить ", "Could not delete ") + name, Toast.LENGTH_SHORT).show();
            }
        });
        row.setOnClickListener(v -> {
            load(curve);
            Toast.makeText(c, Lang.t(c, "Кривая загружена: ", "Curve loaded: ") + shownName, Toast.LENGTH_SHORT).show();
            d.dismiss();
        });
        return row;
    }

    private static TextView accentButton(Context c, CharSequence label) {
        TextView t = SettingsStyle.text(c, label, 16, SettingsStyle.INK);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, SettingsStyle.dp(c, 15), 0, SettingsStyle.dp(c, 15));
        t.setBackground(SettingsStyle.shape(c, SettingsStyle.accent(c), 0, 16));
        t.setTag("sheet_save");
        return t;
    }

    /** Download/SCAMERA/ToneCurves through MediaStore (the File API below Android 10). */
    static Files folderFiles(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return new Files() {
            @Override
            public String[] list() {
                return ScameraFolder.CURVES.list(app);
            }

            @Override
            public String read(String name) throws IOException {
                try (InputStream in = ScameraFolder.CURVES.open(app, name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        if (out.size() > MAX_FILE) throw new IOException("too large: " + name);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }

            @Override
            public String save(String name, String text) throws IOException {
                return ScameraFolder.CURVES.save(app, name, text.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public boolean delete(String name) {
                return ScameraFolder.CURVES.delete(app, name);
            }

            @Override
            public String folder() {
                return ScameraFolder.CURVES.relative;
            }
        };
    }

    /** -5..+5 EV around middle grey on the graph's x. */
    static float ev(float x) {
        return (x - 0.5f) * 10f;
    }

    static String evText(float x) {
        float v = Math.round(ev(x) * 100f) / 100f;
        return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.ROOT, "%.2f EV", Math.abs(v));
    }

    static String percentText(float y) {
        return String.format(Locale.ROOT, "%.1f %%", y * 100f);
    }

    /** The selected point's controls. */
    private final class PointPanel {
        private final Context c;
        private final ToneCurveView graph;
        private final boolean on;
        private final TextView label, prev, next, delete, xValue, yValue;
        private final SeekBar xBar, yBar;

        PointPanel(Context c, ToneCurveView graph, LinearLayout panel, boolean on) {
            this.c = c;
            this.graph = graph;
            this.on = on;
            int accent = SettingsStyle.accent(c);
            panel.removeAllViews();
            panel.setBackground(SettingsStyle.shape(c, SettingsStyle.FIELD, 0, 14));
            panel.setClipChildren(false);
            panel.setClipToPadding(false);
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            prev = arrow(c, "‹", "tone_curve_prev", Lang.t(c, "Предыдущая точка", "Previous point"));
            next = arrow(c, "›", "tone_curve_next", Lang.t(c, "Следующая точка", "Next point"));
            label = SettingsStyle.text(c, "", 14, SettingsStyle.TEXT);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setGravity(Gravity.CENTER);
            label.setTag("tone_curve_point_label");
            delete = SettingsStyle.text(c, Lang.t(c, "Удалить", "Delete"), 13.5f, SettingsStyle.WARN);
            delete.setTypeface(null, Typeface.BOLD);
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(SettingsStyle.dp(c, 12), 0, SettingsStyle.dp(c, 12), 0);
            delete.setBackground(SettingsStyle.shape(c, 0x26FFB4A8, 0, 12));
            delete.setTag("tone_curve_delete");
            int h = SettingsStyle.dp(c, 34);
            head.addView(prev, new LinearLayout.LayoutParams(h, h));
            head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(next, new LinearLayout.LayoutParams(h, h));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
            dp.setMarginStart(SettingsStyle.dp(c, 10));
            head.addView(delete, dp);
            panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            xValue = pill(c, accent, "tone_curve_x_value");
            yValue = pill(c, accent, "tone_curve_y_value");
            xBar = bar(c, accent, "tone_curve_x");
            yBar = bar(c, accent, "tone_curve_y");
            addSlider(panel, Lang.t(c, "Экспозиция", "Exposure"), xValue, xBar);
            addSlider(panel, Lang.t(c, "Яркость", "Brightness"), yValue, yBar);
            prev.setOnClickListener(v -> graph.select(graph.selected() - 1));
            next.setOnClickListener(v -> graph.select(graph.selected() + 1));
            delete.setOnClickListener(v -> graph.deleteSelected());
            xBar.setOnSeekBarChangeListener(slide(true));
            yBar.setOnSeekBarChangeListener(slide(false));
            xValue.setOnClickListener(v -> typeX());
            yValue.setOnClickListener(v -> typeY());
        }

        void refresh() {
            ToneCurve curve = graph.curve();
            int i = graph.selected(), n = curve.size();
            boolean has = i >= 0 && i < n, end = has && (i == 0 || i == n - 1);
            if (has) {
                ToneCurve.Point p = curve.get(i);
                label.setText(Lang.t(c, "Точка " + (i + 1) + " из " + n, "Point " + (i + 1) + " of " + n));
                xValue.setText(evText(p.x));
                yValue.setText(percentText(p.y));
                xBar.setProgress(Math.round(p.x * STEPS));
                yBar.setProgress(Math.round(p.y * STEPS));
            } else {
                label.setText(Lang.t(c, "Нажмите на точку", "Tap a point"));
                xValue.setText("—");
                yValue.setText("—");
            }
            enable(prev, has && i > 0);
            enable(next, has && i < n - 1);
            enable(delete, has && !end);
            // the ends keep their exposure (-5 and +5 EV); only their brightness moves
            enable(xBar, has && !end);
            enable(xValue, has && !end);
            enable(yBar, has);
            enable(yValue, has);
        }

        private void enable(View v, boolean e) {
            v.setEnabled(on && e);
            v.setAlpha(on && e ? 1f : 0.4f);
        }

        private SeekBar.OnSeekBarChangeListener slide(boolean x) {
            return new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    int i = graph.selected();
                    if (!fromUser || i < 0) return;
                    ToneCurve.Point p = graph.curve().get(i);
                    float v = progress / (float) STEPS;
                    graph.moveSelected(x ? v : p.x, x ? p.y : v);
                    refresh(); // a point stops at its neighbours: the bar shows where it stayed
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    store(graph.curve());
                }
            };
        }

        /** The exposure typed in: within the neighbours' range (the ends keep theirs). */
        private void typeX() {
            ToneCurve curve = graph.curve();
            int i = graph.selected();
            if (i <= 0 || i >= curve.size() - 1) return;
            float lo = curve.get(i - 1).x + ToneCurve.MIN_GAP, hi = curve.get(i + 1).x - ToneCurve.MIN_GAP, now = curve.get(i).x;
            PrecisionEditor.show(c, Lang.t(c, "Экспозиция точки " + (i + 1) + ", EV", "Point " + (i + 1) + " exposure, EV"),
                    Math.ceil(ev(lo) * 100.0 - 1e-3) / 100.0, Math.floor(ev(hi) * 100.0 + 1e-3) / 100.0, round2(ev(now)), round2(ev(now)), true, v -> {
                        graph.moveSelected(0.5f + v / 10f, graph.curve().get(graph.selected()).y);
                        store(graph.curve());
                        refresh();
                    });
        }

        /** The brightness typed in, 0..100 %; «По умолчанию» puts the point on the straight line. */
        private void typeY() {
            ToneCurve curve = graph.curve();
            int i = graph.selected();
            if (i < 0 || i >= curve.size()) return;
            ToneCurve.Point p = curve.get(i);
            PrecisionEditor.show(c, Lang.t(c, "Яркость точки " + (i + 1) + ", %", "Point " + (i + 1) + " brightness, %"),
                    0, 100, round2(p.y * 100f), round2(p.x * 100f), true, v -> {
                        graph.moveSelected(graph.curve().get(graph.selected()).x, v / 100f);
                        store(graph.curve());
                        refresh();
                    });
        }

        private void addSlider(LinearLayout panel, CharSequence title, TextView value, SeekBar bar) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = SettingsStyle.text(c, title, 14.5f, SettingsStyle.TEXT);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = SettingsStyle.dp(c, 12);
            panel.addView(row, rp);
            // as the slider rows of the settings (preference_seekbar): the track lines up with the text
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SettingsStyle.dp(c, 36));
            bp.setMarginStart(-SettingsStyle.dp(c, 14));
            bp.setMarginEnd(-SettingsStyle.dp(c, 10));
            panel.addView(bar, bp);
        }
    }

    private static double round2(float v) {
        return Math.round(v * 100f) / 100.0;
    }

    private static TextView pill(Context c, int accent, String tag) {
        TextView t = SettingsStyle.text(c, "", 13.5f, accent);
        t.setBackgroundResource(R.drawable.settings_pill);
        t.setBackgroundTintList(ColorStateList.valueOf(SettingsStyle.tint(accent, .18f)));
        t.setFontFeatureSettings("tnum");
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(SettingsStyle.dp(c, 44));
        t.setPadding(SettingsStyle.dp(c, 11), SettingsStyle.dp(c, 4), SettingsStyle.dp(c, 11), SettingsStyle.dp(c, 4));
        t.setTag(tag);
        t.setContentDescription(Lang.t(c, "Значение — нажмите для ввода", "Value, tap to type it"));
        return t;
    }

    private static SeekBar bar(Context c, int accent, String tag) {
        SeekBar s = new SeekBar(c);
        s.setMax(STEPS);
        s.setProgressTintList(ColorStateList.valueOf(accent));
        s.setThumbTintList(ColorStateList.valueOf(accent));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF4A5058));
        s.setTag(tag);
        return s;
    }

    private static TextView arrow(Context c, String glyph, String tag, CharSequence description) {
        int accent = SettingsStyle.accent(c);
        TextView t = SettingsStyle.text(c, glyph, 22, accent);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(SettingsStyle.shape(c, SettingsStyle.tint(accent, .12f), 0, 12));
        t.setTag(tag);
        t.setContentDescription(description);
        return t;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static TextView chip(Context c, CharSequence label, String tag, Runnable click) {
        int accent = SettingsStyle.accent(c);
        TextView t = SettingsStyle.text(c, label, 14, accent);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, SettingsStyle.dp(c, 10), 0, SettingsStyle.dp(c, 10));
        t.setBackground(SettingsStyle.shape(c, SettingsStyle.tint(accent, .12f), 0, 12));
        t.setTag(tag);
        t.setOnClickListener(v -> click.run());
        return t;
    }
}
