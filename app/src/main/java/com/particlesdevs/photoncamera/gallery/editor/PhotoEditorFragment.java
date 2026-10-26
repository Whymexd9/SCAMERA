package com.particlesdevs.photoncamera.gallery.editor;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.ui.GallerySheets;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P351 (owner, 2026-10-26: «нам нужен редактор изображения в галерее с базовыми функциями как в мобильном приложении
 * лайтрума»): the gallery's photo editor - «Изменить» in the viewer opens it (a long press, or «Открыть в другом приложении» in
 * its menu, still hands the photo to another app's editor). Tabs: Кадр (ratio, rotate, flip, straighten, the crop frame),
 * Стили, Свет (+ Кривая), Цвет (basic, the 8-band mixer, grading, B&W), Эффекты, Детали; undo / redo, «Сбросить всё», copy /
 * paste of settings, a live histogram, a long press on the picture shows the photo without the tone edits. «Сохранить копию»
 * writes a new JPEG next to the original (EditExporter); back with unsaved edits asks first. Opened by another app through
 * ACTION_EDIT it returns the copy's URI.
 */
public class PhotoEditorFragment extends Fragment implements EditPanels.Host {
    private static final String KEY_STATE = "edit_state", KEY_TAB = "edit_tab";
    static final int TAB_CROP = 0, TAB_STYLES = 1, TAB_LIGHT = 2, TAB_COLOUR = 3, TAB_EFFECTS = 4, TAB_DETAIL = 5;
    private static final String PREFS = "scam_editor", PREF_CLIPBOARD = "clipboard";
    /** Copied settings («Копировать настройки»), kept for the app's lifetime and in prefs. */
    @Nullable private static EditState clipboard;

    private EditorViewModel vm;
    private GalleryViewModel gallery;
    private Uri uri;
    private String name, path, mime;
    private boolean external;
    private int tab = TAB_LIGHT;
    @Nullable private EditState restore;

    private EditorView gl;
    private CropOverlayView overlay;
    private EditHistogramView histogram;
    private TextView beforePill, notePill, exportText;
    private ProgressBar spinner, exportBar;
    private FrameLayout imageArea, exportOverlay;
    private ScrollView panelScroll;
    private LinearLayout tabBar;
    private View undo, redo, save;
    private EditPanels panels;
    private boolean before;
    @Nullable private float[] straightenAnchor;
    private boolean applyingStyle;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
        Bundle a = requireArguments();
        uri = Uri.parse(a.getString(ExternalEdit.ARG_URI, ""));
        name = a.getString(ExternalEdit.ARG_NAME, "");
        path = a.getString(ExternalEdit.ARG_PATH);
        mime = a.getString(ExternalEdit.ARG_MIME);
        external = a.getBoolean(ExternalEdit.ARG_EXTERNAL, false);
        vm = new ViewModelProvider(this).get(EditorViewModel.class);
        gallery = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        if (saved != null) {
            tab = saved.getInt(KEY_TAB, TAB_LIGHT);
            if (vm.history == null) restore = EditState.fromJson(saved.getString(KEY_STATE));
        }
        Context c = requireContext();
        panels = new EditPanels(c, this);
        View root = build(c);
        vm.source.observe(getViewLifecycleOwner(), this::onSource);
        vm.error.observe(getViewLifecycleOwner(), e -> {
            if (e == null) return;
            Toast.makeText(c, Lang.t("Не удалось открыть снимок: ", "Cannot open the photo: ") + e, Toast.LENGTH_LONG).show();
            leave(false);
        });
        vm.exportProgress.observe(getViewLifecycleOwner(), p -> {
            if (p == null || !vm.exporting()) return;
            exportOverlay.setVisibility(View.VISIBLE);
            exportBar.setProgress(Math.round(p * 1000));
            exportText.setText(Lang.t("Сохраняю копию… ", "Saving a copy… ") + Math.round(p * 100) + " %");
        });
        vm.exported.observe(getViewLifecycleOwner(), r -> {
            if (r == null) return;
            vm.exported.setValue(null);
            onExported(r);
        });
        vm.exportError.observe(getViewLifecycleOwner(), e -> {
            if (e == null) return;
            vm.exportError.setValue(null);
            exportOverlay.setVisibility(View.GONE);
            Toast.makeText(c, Lang.t("Не удалось сохранить: ", "Saving failed: ") + e, Toast.LENGTH_LONG).show();
        });
        vm.load(uri, name, mime, path);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        });
        if (clipboard == null) {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            clipboard = EditState.fromJson(p.getString(PREF_CLIPBOARD, null));
        }
        return root;
    }

    // ---------------------------------------------------------------- layout

    private View build(Context c) {
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(GalleryUi.BG);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 14), GalleryUi.dp(c, 12), GalleryUi.dp(c, 6));
        View close = iconButton(c, R.drawable.ui_ic_close, Lang.t("Закрыть редактор", "Close the editor"), v -> onBack());
        GalleryUi.setEnabledLook(close, true);
        top.addView(close, new LinearLayout.LayoutParams(GalleryUi.dp(c, 44), GalleryUi.dp(c, 44)));
        TextView title = GalleryUi.fitText(c, name, 13, GalleryUi.MUTED);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = tp.rightMargin = GalleryUi.dp(c, 8);
        top.addView(title, tp);
        undo = iconButton(c, R.drawable.ui_ic_undo, Lang.t("Отменить", "Undo"), v -> undo());
        redo = iconButton(c, R.drawable.ui_ic_redo, Lang.t("Повторить", "Redo"), v -> redo());
        View more = iconButton(c, R.drawable.ui_ic_more, Lang.t("Ещё", "More"), v -> menu());
        for (View b : new View[]{undo, redo, more}) top.addView(b, new LinearLayout.LayoutParams(GalleryUi.dp(c, 44), GalleryUi.dp(c, 44)));
        TextView saveBtn = GalleryUi.text(c, Lang.t("Сохранить копию", "Save a copy"), 13.5f, GalleryUi.INK);
        saveBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setMinHeight(GalleryUi.dp(c, 38));
        saveBtn.setPadding(GalleryUi.dp(c, 14), 0, GalleryUi.dp(c, 14), 0);
        saveBtn.setBackground(GalleryUi.pressable(GalleryUi.round(c, GalleryUi.accent(c), GalleryUi.accent(c), 999)));
        saveBtn.setClickable(true);
        saveBtn.setFocusable(true);
        saveBtn.setOnClickListener(v -> save());
        save = saveBtn;
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = GalleryUi.dp(c, 6);
        top.addView(saveBtn, sp);
        col.addView(top);

        imageArea = new FrameLayout(c);
        gl = new EditorView(c);
        imageArea.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new CropOverlayView(c);
        overlay.setListener(this::onCrop);
        imageArea.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        histogram = new EditHistogramView(c);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 132), GalleryUi.dp(c, 60), Gravity.TOP | Gravity.END);
        hp.topMargin = hp.rightMargin = GalleryUi.dp(c, 10);
        imageArea.addView(histogram, hp);
        beforePill = GalleryUi.pill(c, Lang.t("Оригинал", "Original"), true);
        beforePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        bp.topMargin = bp.leftMargin = GalleryUi.dp(c, 12);
        imageArea.addView(beforePill, bp);
        notePill = GalleryUi.pill(c, "", false);
        notePill.setVisibility(View.GONE);
        notePill.setMaxLines(3);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        np.bottomMargin = np.leftMargin = np.rightMargin = GalleryUi.dp(c, 12);
        imageArea.addView(notePill, np);
        spinner = new ProgressBar(c);
        imageArea.addView(spinner, new FrameLayout.LayoutParams(GalleryUi.dp(c, 48), GalleryUi.dp(c, 48), Gravity.CENTER));
        col.addView(imageArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        GestureDetector press = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (tab == TAB_CROP) return;
                before = true;
                beforePill.setVisibility(View.VISIBLE);
                render();
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });
        gl.setOnTouchListener((v, e) -> {
            press.onTouchEvent(e);
            int action = e.getActionMasked();
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && before) {
                before = false;
                beforePill.setVisibility(View.GONE);
                render();
            }
            return true;
        });
        imageArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateOverlay());

        panelScroll = new ScrollView(c);
        panelScroll.setFillViewport(true);
        panelScroll.setBackgroundColor(GalleryUi.SHEET);
        int screenH = c.getResources().getDisplayMetrics().heightPixels;
        col.addView(panelScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(GalleryUi.dp(c, 300), (int) (screenH * 0.36f))));

        tabBar = new LinearLayout(c);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(GalleryUi.SHEET);
        tabBar.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 14));
        col.addView(tabBar);
        buildTabs(c);

        exportOverlay = new FrameLayout(c);
        exportOverlay.setBackgroundColor(0xCC000000);
        exportOverlay.setClickable(true);
        exportOverlay.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GalleryUi.card(c, GalleryUi.CARD, 20));
        card.setPadding(GalleryUi.dp(c, 20), GalleryUi.dp(c, 18), GalleryUi.dp(c, 20), GalleryUi.dp(c, 18));
        exportText = GalleryUi.text(c, Lang.t("Сохраняю копию…", "Saving a copy…"), 15, GalleryUi.TEXT);
        card.addView(exportText);
        exportBar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        exportBar.setMax(1000);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.topMargin = GalleryUi.dp(c, 12);
        card.addView(exportBar, ep);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        exportOverlay.addView(card, cp);
        root.addView(exportOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (vm.exporting()) exportOverlay.setVisibility(View.VISIBLE);
        gl.setCallbacks(new EditorView.Callbacks() {
            @Override
            public void histogram(int[][] counts) {
                if (histogram != null) histogram.set(counts);
            }

            @Override
            public void failed(String why) {
                if (getContext() == null) return;
                Toast.makeText(getContext(), Lang.t("Редактор не запустился на GPU: ", "The editor could not start on the GPU: ") + why,
                        Toast.LENGTH_LONG).show();
            }
        });
        updateHistoryButtons();
        return root;
    }

    private static View iconButton(Context c, int icon, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(GalleryUi.CARD);
        b.setBackground(GalleryUi.pressable(d));
        b.setContentDescription(description);
        b.setTooltipText(description);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        ImageView i = GalleryUi.icon(c, icon, GalleryUi.TEXT, 22);
        b.addView(i, new FrameLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22), Gravity.CENTER));
        return b;
    }

    private void buildTabs(Context c) {
        tabBar.removeAllViews();
        int[] icons = {R.drawable.ui_ic_crop, R.drawable.ui_ic_styles, R.drawable.ui_ic_sun, R.drawable.ui_ic_palette,
                R.drawable.ui_ic_effects, R.drawable.ui_ic_detail};
        String[] names = {Lang.t("Кадр", "Crop"), Lang.t("Стили", "Styles"), Lang.t("Свет", "Light"), Lang.t("Цвет", "Colour"),
                Lang.t("Эффекты", "Effects"), Lang.t("Детали", "Detail")};
        for (int i = 0; i < icons.length; i++) {
            final int k = i;
            boolean on = i == tab;
            int colour = on ? GalleryUi.accent(c) : GalleryUi.TEXT;
            LinearLayout b = new LinearLayout(c);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 6));
            b.setBackground(GalleryUi.pressable(GalleryUi.round(c, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, 14)));
            b.setClickable(true);
            b.setFocusable(true);
            b.setSelected(on);
            b.setContentDescription(names[i]);
            b.setOnClickListener(v -> selectTab(k));
            b.addView(GalleryUi.icon(c, icons[i], colour, 22));
            TextView t = GalleryUi.fitText(c, names[i], 10, colour);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = GalleryUi.dp(c, 3);
            b.addView(t, lp);
            tabBar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }

    private void selectTab(int t) {
        tab = t;
        buildTabs(requireContext());
        showPanel();
        updateOverlay();
        render();
    }

    private void showPanel() {
        panelScroll.removeAllViews();
        if (vm.source.getValue() == null) return;
        View p = panels.forTab(tab);
        if (p != null) panelScroll.addView(p);
        panelScroll.scrollTo(0, 0);
    }

    // ---------------------------------------------------------------- state

    private void onSource(@Nullable EditSource s) {
        if (s == null) return;
        spinner.setVisibility(View.GONE);
        if (vm.history == null) {
            vm.history = new EditHistory(new EditState());
            if (restore != null) {
                vm.state = restore;
                vm.history.commit(restore);
            }
        }
        if (s.note != null) {
            notePill.setText(s.note);
            notePill.setVisibility(View.VISIBLE);
            notePill.postDelayed(() -> {
                if (notePill != null) notePill.setVisibility(View.GONE);
            }, 6000);
        }
        gl.setSource(s);
        showPanel();
        updateHistoryButtons();
        updateOverlay();
        render();
    }

    private void render() {
        if (gl == null) return;
        gl.setState(vm.state, tab == TAB_CROP, before);
        histogram.setVisibility(tab == TAB_CROP ? View.GONE : View.VISIBLE);
    }

    @Override
    public EditState state() {
        return vm.state;
    }

    @Override
    public EditSource source() {
        return vm.source.getValue();
    }

    @Override
    public void changed(boolean commit) {
        if (!applyingStyle) vm.styleBase = null;
        render();
        if (commit && vm.history != null) {
            vm.history.commit(vm.state);
            updateHistoryButtons();
        }
        if (tab == TAB_CROP) updateOverlay();
        if (commit) panels.refresh();
    }

    private void updateHistoryButtons() {
        boolean u = vm.history != null && vm.history.canUndo(), r = vm.history != null && vm.history.canRedo();
        GalleryUi.setEnabledLook(undo, u);
        GalleryUi.setEnabledLook(redo, r);
    }

    private void undo() {
        if (vm.history == null || !vm.history.canUndo()) return;
        vm.state = vm.history.undo();
        afterJump();
    }

    private void redo() {
        if (vm.history == null || !vm.history.canRedo()) return;
        vm.state = vm.history.redo();
        afterJump();
    }

    private void afterJump() {
        vm.styleBase = null;
        panels.styleIndex = -1;
        panels.refresh();
        updateHistoryButtons();
        updateOverlay();
        render();
    }

    private double[] oriented() {
        EditSource s = vm.source.getValue();
        return s != null ? EditorView.oriented(s) : new double[]{1, 1};
    }

    private void updateOverlay() {
        if (overlay == null) return;
        boolean crop = tab == TAB_CROP && vm.source.getValue() != null;
        overlay.setVisibility(crop ? View.VISIBLE : View.GONE);
        if (!crop || imageArea.getWidth() == 0) return;
        double[] o = oriented();
        RectF canvas = EditorView.canvas(imageArea.getWidth(), imageArea.getHeight(), EditorView.margin(requireContext(), true), vm.state, o[0], o[1], true);
        double[] b = CropMath.baseSize(o[0], o[1], vm.state.quarterTurns);
        double ratio = CropMath.ratioOf(vm.state.aspect, b[0], b[1]);
        overlay.bind(canvas, b[0], b[1], vm.state.straighten, ratio,
                new float[]{vm.state.cropL, vm.state.cropT, vm.state.cropR, vm.state.cropB});
    }

    private void onCrop(float[] r, boolean done) {
        vm.state.cropL = r[0];
        vm.state.cropT = r[1];
        vm.state.cropR = r[2];
        vm.state.cropB = r[3];
        render();
        if (done) changed(true);
    }

    @Override
    public void rotate() {
        CropMath.rotateClockwise(vm.state);
        changed(true);
    }

    @Override
    public void flip() {
        CropMath.flipHorizontal(vm.state);
        changed(true);
    }

    @Override
    public void resetCrop() {
        vm.state.resetGeometry();
        changed(true);
    }

    @Override
    public void aspect(float ratio) {
        vm.state.aspect = ratio;
        double[] o = oriented();
        CropMath.applyAspect(vm.state, o[0], o[1]);
        changed(true);
    }

    @Override
    public void straighten(float degrees, boolean commit) {
        if (straightenAnchor == null) straightenAnchor = new float[]{vm.state.cropL, vm.state.cropT, vm.state.cropR, vm.state.cropB};
        vm.state.straighten = degrees;
        double[] o = oriented();
        double[] b = CropMath.baseSize(o[0], o[1], vm.state.quarterTurns);
        float[] r = CropMath.inset(straightenAnchor[0], straightenAnchor[1], straightenAnchor[2], straightenAnchor[3], b[0], b[1], degrees);
        vm.state.cropL = r[0];
        vm.state.cropT = r[1];
        vm.state.cropR = r[2];
        vm.state.cropB = r[3];
        if (commit) straightenAnchor = null;
        updateOverlay();
        if (commit) changed(true);
        else render();
    }

    @Override
    public void style(int index, float amount, boolean commit) {
        if (vm.styleBase == null) vm.styleBase = vm.state.copy();
        EditState next = index < 0 ? vm.styleBase.copy() : EditPresets.apply(vm.styleBase, index, amount);
        vm.state.copyTone(next);
        applyingStyle = true;
        changed(commit);
        applyingStyle = false;
        if (index < 0) vm.styleBase = null;
    }

    // ---------------------------------------------------------------- menu, saving, leaving

    private void menu() {
        Context c = requireContext();
        LinearLayout sheet = EditPanels.column(c);
        sheet.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 14), GalleryUi.dp(c, 12), GalleryUi.dp(c, 18));
        sheet.setBackground(GalleryUi.round(c, GalleryUi.SHEET, GalleryUi.LINE, 26));
        final BottomSheetDialog[] d = new BottomSheetDialog[1];
        sheet.addView(menuRow(c, R.drawable.ui_ic_backup, Lang.t("Сбросить всё", "Reset all"), () -> {
            d[0].dismiss();
            vm.state = new EditState();
            changed(true);
            afterJump();
        }));
        sheet.addView(menuRow(c, R.drawable.ui_ic_layers, Lang.t("Копировать настройки", "Copy settings"), () -> {
            d[0].dismiss();
            clipboard = vm.state.copy();
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_CLIPBOARD, clipboard.toJson()).apply();
            Toast.makeText(c, Lang.t("Настройки скопированы (без кадрирования)", "Settings copied (crop not included)"), Toast.LENGTH_SHORT).show();
        }));
        View paste = menuRow(c, R.drawable.ui_ic_plus, Lang.t("Вставить настройки", "Paste settings"), () -> {
            d[0].dismiss();
            if (clipboard == null) return;
            vm.state.copyTone(clipboard);
            changed(true);
            afterJump();
        });
        GalleryUi.setEnabledLook(paste, clipboard != null);
        sheet.addView(paste);
        sheet.addView(menuRow(c, R.drawable.ui_ic_share, Lang.t("Открыть в другом приложении", "Open in another app"), () -> {
            d[0].dismiss();
            ExternalEdit.openInOtherApp(this, uri, name);
        }));
        d[0] = GallerySheets.show(c, sheet);
    }

    private static View menuRow(Context c, int icon, String text, Runnable run) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(GalleryUi.dp(c, 52));
        row.setPadding(GalleryUi.dp(c, 12), 0, GalleryUi.dp(c, 12), 0);
        row.setBackground(GalleryUi.pressable(GalleryUi.round(c, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, 14)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> run.run());
        row.addView(GalleryUi.icon(c, icon, GalleryUi.accent(c), 22));
        TextView t = GalleryUi.text(c, text, 15, GalleryUi.TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = GalleryUi.dp(c, 14);
        row.addView(t, lp);
        return row;
    }

    private void save() {
        if (vm.source.getValue() == null || vm.exporting()) return;
        exportOverlay.setVisibility(View.VISIBLE);
        exportBar.setProgress(0);
        vm.export(vm.state);
    }

    private void onExported(EditExporter.Result r) {
        exportOverlay.setVisibility(View.GONE);
        Context c = getContext();
        if (c == null) return;
        String where = r.folder != null ? " · " + r.folder : "";
        String msg = Lang.t("Копия сохранена: ", "Copy saved: ") + r.displayName + where + (r.note != null ? " (" + r.note + ")" : "");
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
        vm.history = new EditHistory(vm.state); // saved: nothing unsaved any more
        if (external) {
            Activity a = requireActivity();
            Intent data = new Intent();
            data.setData(r.uri);
            data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.setResult(Activity.RESULT_OK, data);
            a.finish();
            return;
        }
        long id = -1;
        try {
            id = ContentUris.parseId(r.uri);
        } catch (RuntimeException ignored) {
        }
        gallery.showNewFile(new ImageFile(id, r.uri, r.displayName, System.currentTimeMillis(), 0, null));
        leave(true);
    }

    private void onBack() {
        if (vm.exporting()) return;
        if (vm.history != null && vm.history.changed()) {
            Context c = requireContext();
            final BottomSheetDialog[] d = new BottomSheetDialog[1];
            LinearLayout s = EditPanels.column(c);
            s.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 18), GalleryUi.dp(c, 18), GalleryUi.dp(c, 18));
            s.setBackground(GalleryUi.round(c, GalleryUi.SHEET, GalleryUi.LINE, 26));
            TextView t = GalleryUi.text(c, Lang.t("Выйти без сохранения?", "Leave without saving?"), 17, GalleryUi.TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            s.addView(t);
            TextView sub = GalleryUi.text(c, Lang.t("Изменения пропадут. Оригинал не меняется в любом случае.",
                    "The edits are lost. The original is never changed either way."), 13, GalleryUi.MUTED);
            sub.setPadding(0, GalleryUi.dp(c, 6), 0, GalleryUi.dp(c, 14));
            s.addView(sub);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView stay = bigButton(c, Lang.t("Продолжить", "Keep editing"), false);
            stay.setOnClickListener(v -> d[0].dismiss());
            TextView discard = bigButton(c, Lang.t("Выйти", "Leave"), true);
            discard.setOnClickListener(v -> {
                d[0].dismiss();
                leave(false);
            });
            row.addView(stay, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            dp.leftMargin = GalleryUi.dp(c, 8);
            row.addView(discard, dp);
            s.addView(row);
            d[0] = GallerySheets.show(c, s);
            return;
        }
        leave(false);
    }

    private static TextView bigButton(Context c, String label, boolean warn) {
        TextView b = GalleryUi.text(c, label, 16, warn ? 0xFF2A1210 : GalleryUi.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(GalleryUi.dp(c, 50));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(GalleryUi.pressable(warn ? GalleryUi.round(c, GalleryUi.WARN, GalleryUi.WARN, 16) : GalleryUi.round(c, 0xFF232A2E, 0xFF232A2E, 16)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private void leave(boolean saved) {
        if (external) {
            if (!saved) requireActivity().setResult(Activity.RESULT_CANCELED);
            requireActivity().finish();
            return;
        }
        if (!NavHostFragment.findNavController(this).navigateUp()) requireActivity().finish();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Constants.REQUEST_EDIT_IMAGE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Toast.makeText(getContext(), Lang.t("Сохранено: ", "Saved: ") + data.getData().getPath(), Toast.LENGTH_LONG).show();
            gallery.fetchAllMedia();
            gallery.setUpdatePending(true);
            leave(true);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onResume() {
        super.onResume();
        if (gl != null) gl.onResume();
    }

    @Override
    public void onPause() {
        if (gl != null) gl.onPause();
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (vm != null) out.putString(KEY_STATE, vm.state.toJson());
        out.putInt(KEY_TAB, tab);
    }

    @Override
    public void onDestroyView() {
        if (gl != null) {
            gl.setCallbacks(null);
            gl.release();
        }
        gl = null;
        overlay = null;
        histogram = null;
        super.onDestroyView();
    }
}
