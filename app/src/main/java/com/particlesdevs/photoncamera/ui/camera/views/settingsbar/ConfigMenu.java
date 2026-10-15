package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.ConfigState;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * P143: the config menu under the shade's config button - save over the active config, save as a new one, load or delete a
 * saved one, delete the active one's file, import a file, reset to the defaults. A card in the shade's style that unfolds from the button (scale, fade, a short drop,
 * the rows following one after another) and folds back; «Сохранить как…», a saved config and the reset open their inline
 * step in place (one at a time, the card resizes smoothly). Loading, importing and the reset restart the camera.
 */
final class ConfigMenu {
    /** What the menu does; the shade wires it to BackupRestoreUtil / ConfigFolder, tests to a fake. */
    interface Actions {
        /** Saves the settings as {@code name} (no extension), returns the message. */
        String save(String name);
        /** File names in Download/SCAMERA/XML, oldest first. */
        String[] saved();
        /** Loads a saved config (the app restarts), returns the message. */
        String load(String fileName);
        /** The system file picker for a config from anywhere. */
        void importFile();
        /** Back to the defaults (the app restarts), returns the message. */
        String reset();
        /** Deletes a saved config's file (the settings stay), true when it is gone. */
        boolean delete(String fileName);

        /** Whether Download/SCAMERA is open to the app (every file there listed); true hides «Открыть папку». */
        default boolean folderOpen() { return true; }

        /** «Открыть папку»: the system folder picker for the lasting access (the menu closes). */
        default void openFolder() { }
    }

    private final Context c;
    private final ConfigChip anchor;
    private final Actions actions;
    private final Consumer<CharSequence> message;
    private final Runnable onClosed;
    private final PopupWindow window;
    private final FrameLayout root;
    private final LinearLayout card;
    /**
     * Everything in the card, in one scroll (owner, 2026-10-16: the menu could not scroll and «Сбросить настройки» stayed
     * under the screen's edge): the scroll is never taller than the room under the button ({@link #limitHeight}).
     */
    private final LinearLayout body;
    private final MaxHeightScroll scroll;
    private final int accent;
    private boolean closing;
    @Nullable private View openPanel;
    /** The header's name and the rows that belong to the active config (gone when its file is deleted). */
    private TextView title;
    private final java.util.List<View> activeRows = new java.util.ArrayList<>();
    @Nullable private LinearLayout list;
    @Nullable private View listSection, listScroll;
    /** Every saved config's row and its inline panel, by file name. */
    private final java.util.Map<String, View[]> items = new java.util.HashMap<>();

    private ConfigMenu(ConfigChip anchor, Actions actions, Consumer<CharSequence> message, Runnable onClosed) {
        this.c = anchor.getContext();
        this.anchor = anchor;
        this.actions = actions;
        this.message = message;
        this.onClosed = onClosed;
        this.accent = ShadeStyle.accent(c);
        root = new FrameLayout(c);
        root.setTag("config_menu");
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        root.setClipToPadding(false);
        root.setClipChildren(false);
        // a tap on the shadow margin around the card closes it like a tap outside
        root.setOnClickListener(v -> close());
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(12), dp(8), dp(8));
        card.setBackground(ShadeStyle.card(c, ShadeStyle.CARD, ShadeStyle.LINE, 20));
        card.setElevation(dp(12));
        card.setClickable(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });
        card.setClipToOutline(true);
        card.setLayoutTransition(transition());
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutTransition(transition());
        scroll = new MaxHeightScroll(c);
        scroll.setTag("config_menu_scroll");
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        // as wide as the shade's header allows, 240..300dp
        View head = anchor.getParent() instanceof View && ((View) anchor.getParent()).getParent() instanceof View
                ? (View) ((View) anchor.getParent()).getParent() : anchor;
        int width = Math.min(dp(300), Math.max(dp(240), head.getWidth() - dp(8)));
        root.addView(card, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        build();
        window = new PopupWindow(root, width + 2 * pad, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.setOutsideTouchable(true);
        window.setAnimationStyle(0); // the card animates itself
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setTouchInterceptor((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) { close(); return true; }
            return false;
        });
        window.setOnDismissListener(() -> {
            anchor.setOpen(false);
            if (onClosed != null) onClosed.run();
        });
    }

    /** Opens the menu under {@code anchor}. */
    static ConfigMenu show(ConfigChip anchor, Actions actions, Consumer<CharSequence> message, Runnable onClosed) {
        ConfigMenu menu = new ConfigMenu(anchor, actions, message, onClosed);
        int pad = menu.root.getPaddingLeft();
        menu.limitHeight(roomBelow(anchor, dp(anchor.getContext(), 6)));
        menu.window.showAsDropDown(anchor, -pad, dp(anchor.getContext(), 6) - pad, Gravity.START);
        anchor.setOpen(true);
        menu.enter();
        return menu;
    }

    /** The room from under the button (+ gap) to the bottom of the visible window, 0 when the view is not laid out. */
    static int roomBelow(View anchor, int gap) {
        android.graphics.Rect frame = new android.graphics.Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int room = frame.bottom - (at[1] + anchor.getHeight() + gap);
        return frame.height() > 0 ? room : 0;
    }

    /**
     * The scroll's height limit from the room below the button: the window's shadow margin and the card's padding come off;
     * at least 3 rows' worth; without a room (not laid out yet) 60 % of the screen.
     */
    void limitHeight(int room) {
        int chrome = 2 * root.getPaddingTop() + card.getPaddingTop() + card.getPaddingBottom() + dp(8);
        int max = room > 0 ? room - chrome : (int) (c.getResources().getDisplayMetrics().heightPixels * .6f);
        scroll.maxHeight = Math.max(dp(150), max);
        scroll.requestLayout();
    }

    /** A vertical scroll no taller than {@link #maxHeight} (its content scrolls inside). */
    static final class MaxHeightScroll extends androidx.core.widget.NestedScrollView {
        int maxHeight = Integer.MAX_VALUE;

        MaxHeightScroll(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int limit = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(maxHeight, MeasureSpec.getSize(h));
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    boolean isShowing() {
        return window.isShowing() && !closing;
    }

    View content() {
        return root;
    }

    /** Folds the card back into the button, then the window goes. */
    void close() {
        if (closing || !window.isShowing()) return;
        closing = true;
        anchor.setOpen(false);
        card.animate().alpha(0f).scaleX(.94f).scaleY(.94f).translationY(-dp(8)).setDuration(150)
                .setInterpolator(ConfigChip.EASE).withEndAction(this::dismissNow).start();
    }

    /** At once (the shade hides, a test). */
    void dismissNow() {
        if (window.isShowing()) window.dismiss();
    }

    // ───────────────────────────── content

    private void build() {
        String active = ConfigState.active(c);
        TextView eyebrow = new TextView(c);
        eyebrow.setText(Lang.t(c, "Конфиг", "Config"));
        ShadeStyle.label(eyebrow, accent);
        eyebrow.setPadding(dp(10), 0, dp(10), dp(6));
        body.addView(eyebrow);
        title = text(ConfigState.label(c), 17, ShadeStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setPadding(dp(10), 0, dp(10), dp(10));
        title.setTag("config_menu_name");
        body.addView(title);
        divider();

        final String[] files = actions.saved();
        // the active config's own file in the folder (an imported file from elsewhere has none to delete)
        String activeFile = null;
        if (active != null && files != null)
            for (String f : files) if (ConfigState.base(f).equals(active)) activeFile = f;
        if (active != null) {
            View save = row(R.drawable.settings_ic_save, Lang.t(c, "Сохранить", "Save"),
                    Lang.t(c, "Перезаписать «" + active + "»", "Overwrite «" + active + "»"), accent, "config_save",
                    () -> done(actions.save(active)));
            body.addView(save);
            activeRows.add(save);
        }
        final LinearLayout saveAs = panel("config_save_panel");
        EditText field = new EditText(c);
        field.setTag("config_name_field");
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setTextColor(ShadeStyle.TEXT);
        field.setHintTextColor(ShadeStyle.MUTED);
        field.setHint(Lang.t(c, "Имя конфига", "Config name"));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setBackground(ShadeStyle.card(c, ShadeStyle.FIELD, ShadeStyle.LINE, 14));
        field.setPadding(dp(14), dp(11), dp(14), dp(11));
        field.setText(active != null ? active + "_2"
                : "config_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()));
        saveAs.addView(field, new LinearLayout.LayoutParams(-1, -2));
        Runnable saveNamed = () -> {
            String name = ConfigState.fileSafe(field.getText().toString());
            if (name.isEmpty()) { message.accept(Lang.t(c, "Введите имя конфига", "Enter a config name")); return; }
            done(actions.save(name));
        };
        field.setOnEditorActionListener((v, id, e) -> { if (id == EditorInfo.IME_ACTION_DONE) { saveNamed.run(); return true; } return false; });
        saveAs.addView(button(Lang.t(c, "Сохранить", "Save"), ShadeStyle.INK, accent, "config_save_confirm", saveNamed), buttonParams());
        body.addView(row(R.drawable.settings_ic_plus, Lang.t(c, "Сохранить как…", "Save as…"), null, accent, "config_save_as",
                () -> toggle(saveAs, field)));
        body.addView(saveAs);
        if (activeFile != null) {
            final String file = activeFile;
            LinearLayout ask = panel("config_delete_panel");
            ask.addView(text(Lang.t(c, "Файл «" + file + "» удалится из Download/SCAMERA/XML. Настройки останутся как есть",
                    "«" + file + "» is deleted from Download/SCAMERA/XML. The settings stay as they are"), 13, ShadeStyle.MUTED));
            ask.addView(button(Lang.t(c, "Удалить «" + active + "»", "Delete «" + active + "»"), ShadeStyle.INK, SettingsStyle.WARN,
                    "config_delete_confirm", () -> deleteFile(file)), buttonParams());
            View delete = row(R.drawable.settings_ic_delete, Lang.t(c, "Удалить конфиг", "Delete the config"),
                    Lang.t(c, "Файл «" + file + "»", "File «" + file + "»"), SettingsStyle.WARN, "config_delete", () -> toggle(ask, null));
            body.addView(delete);
            body.addView(ask);
            activeRows.add(delete);
            activeRows.add(ask);
        }

        if (files != null && files.length > 0) {
            divider();
            TextView section = new TextView(c);
            section.setText(Lang.t(c, "Сохранённые", "Saved"));
            ShadeStyle.label(section, accent);
            section.setPadding(dp(10), dp(4), dp(10), dp(4));
            body.addView(section);
            listSection = section;
            list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setLayoutTransition(transition());
            for (int i = files.length - 1; i >= 0; i--) { // newest (dated names) first
                final String file = files[i];
                final String base = ConfigState.base(file);
                boolean current = base.equals(active);
                LinearLayout panel = panel("config_load_panel_" + base);
                panel.setLayoutTransition(transition());
                // step 1: load or delete; step 2 (after «Удалить»): delete for sure or back
                LinearLayout choose = new LinearLayout(c);
                choose.setOrientation(LinearLayout.VERTICAL);
                choose.setTag("config_choose_" + base);
                choose.addView(text(Lang.t(c, "Загрузить: текущие настройки заменятся, камера перезапустится",
                        "Load: the current settings are replaced, the camera restarts"), 13, ShadeStyle.MUTED));
                LinearLayout sure = new LinearLayout(c);
                sure.setOrientation(LinearLayout.VERTICAL);
                sure.setTag("config_sure_" + base);
                sure.setVisibility(View.GONE);
                sure.addView(text(Lang.t(c, "Удалить «" + file + "»? Файл пропадёт из Download/SCAMERA/XML",
                        "Delete «" + file + "»? The file goes from Download/SCAMERA/XML"), 13, ShadeStyle.MUTED));
                choose.addView(pair(
                        button(Lang.t(c, "Загрузить", "Load"), ShadeStyle.INK, accent, "config_load_confirm", () -> done(actions.load(file))),
                        outlined(Lang.t(c, "Удалить", "Delete"), SettingsStyle.WARN, "config_item_delete", () -> {
                            choose.setVisibility(View.GONE);
                            sure.setVisibility(View.VISIBLE);
                        })), buttonParams());
                sure.addView(pair(
                        outlined(Lang.t(c, "Отмена", "Cancel"), ShadeStyle.TEXT, "config_item_keep", () -> {
                            sure.setVisibility(View.GONE);
                            choose.setVisibility(View.VISIBLE);
                        }),
                        button(Lang.t(c, "Удалить", "Delete"), ShadeStyle.INK, SettingsStyle.WARN, "config_item_delete_confirm",
                                () -> deleteFile(file))), buttonParams());
                panel.addView(choose);
                panel.addView(sure);
                View item = row(current ? R.drawable.settings_ic_config : R.drawable.settings_ic_list, base,
                        current ? Lang.t(c, "Сейчас", "Now") : null, current ? accent : ShadeStyle.MUTED, "config_item_" + base,
                        () -> {
                            sure.setVisibility(View.GONE);
                            choose.setVisibility(View.VISIBLE);
                            toggle(panel, null);
                        });
                list.addView(item);
                list.addView(panel);
                items.put(file, new View[]{item, panel});
            }
            list.setTag("config_list");
            body.addView(list, new LinearLayout.LayoutParams(-1, -2));
            listScroll = list;
        }
        divider();
        if (!actions.folderOpen()) {
            // files put into Download/SCAMERA/XML by a PC or a file manager: listed once the folder is open to the app
            body.addView(row(R.drawable.settings_ic_list, Lang.t(c, "Открыть папку конфигов", "Open the config folder"),
                    Lang.t(c, "Один раз: конфиги, закинутые в Download/SCAMERA/XML, появятся здесь",
                            "Once: configs put into Download/SCAMERA/XML show up here"), accent,
                    "config_open_folder", () -> { actions.openFolder(); close(); }));
        }
        body.addView(row(R.drawable.settings_ic_restore, Lang.t(c, "Импорт из файла…", "Import a file…"),
                Lang.t(c, "Конфиг с другого телефона или из другой папки", "A config from another phone or folder"), accent,
                "config_import", () -> { actions.importFile(); close(); }));
        LinearLayout reset = panel("config_reset_panel");
        reset.addView(text(Lang.t(c, "Все настройки и модули вернутся к стандартным, камера перезапустится. Сохранённые конфиги и кривые не удаляются",
                "Every setting and module goes back to the defaults, the camera restarts. Saved configs and curves stay"), 13, ShadeStyle.MUTED));
        reset.addView(button(Lang.t(c, "Сбросить", "Reset"), ShadeStyle.INK, SettingsStyle.WARN, "config_reset_confirm",
                () -> done(actions.reset())), buttonParams());
        body.addView(row(R.drawable.settings_ic_reset, Lang.t(c, "Сбросить настройки", "Reset settings"), null,
                SettingsStyle.WARN, "config_reset", () -> toggle(reset, null)));
        body.addView(reset);
    }

    private void done(String result) {
        if (result != null) message.accept(result);
        close();
    }

    /**
     * Deletes a saved config's file; the menu stays open: its row folds out of the list, and when it was the active config the
     * settings stay as they are under «Без конфига» (no «Сохранить» / «Удалить конфиг» for it any more).
     */
    private void deleteFile(String file) {
        String base = ConfigState.base(file);
        if (!actions.delete(file)) {
            message.accept(Lang.t(c, "Не удалось удалить «" + file + "»", "Could not delete «" + file + "»"));
            return;
        }
        View[] views = items.remove(file);
        if (views != null && list != null) {
            if (openPanel == views[1]) openPanel = null;
            list.removeView(views[1]);
            list.removeView(views[0]);
        }
        if (list != null && list.getChildCount() == 0) {
            if (listSection != null) listSection.setVisibility(View.GONE);
            if (listScroll != null) listScroll.setVisibility(View.GONE);
        }
        if (base.equals(ConfigState.active(c))) {
            ConfigState.detach(c);
            for (View v : activeRows) {
                if (openPanel == v) openPanel = null;
                v.setVisibility(View.GONE);
            }
            title.setText(ConfigState.label(c));
        }
        message.accept(Lang.t(c, "Удалён «" + file + "»", "Deleted «" + file + "»"));
    }

    /** One inline step at a time: the open one folds, this one unfolds (the layout transition animates both). */
    private void toggle(View panel, @Nullable EditText focus) {
        if (openPanel != null && openPanel != panel) openPanel.setVisibility(View.GONE);
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        openPanel = show ? panel : null;
        if (show && focus != null) {
            focus.requestFocus();
            focus.setSelection(focus.getText().length());
        }
    }

    private LinearLayout panel(String tag) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(10), dp(2), dp(10), dp(10));
        p.setTag(tag);
        p.setVisibility(View.GONE);
        return p;
    }

    private View row(int icon, CharSequence title, @Nullable CharSequence summary, int iconColor, String tag, Runnable click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(10), dp(10), dp(10));
        r.setMinimumHeight(dp(48));
        r.setBackground(ShadeStyle.pressable(c, 0, 0, 14));
        r.setTag(tag);
        r.setOnClickListener(v -> click.run());
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(iconColor));
        r.addView(i, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, iconColor == SettingsStyle.WARN ? SettingsStyle.WARN : ShadeStyle.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(t);
        if (summary != null) {
            TextView s = text(summary, 12, ShadeStyle.MUTED);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(dp(12));
        r.addView(texts, tp);
        return r;
    }

    private TextView button(CharSequence label, int textColor, int fill, String tag, Runnable click) {
        TextView b = text(label, 15, textColor);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setBackground(ShadeStyle.pressable(c, fill, 0, 14));
        b.setTag(tag);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    /** A secondary button: a coloured label in a thin frame of that colour. */
    private TextView outlined(CharSequence label, int color, String tag, Runnable click) {
        TextView b = button(label, color, 0, tag, click);
        b.setBackground(ShadeStyle.pressable(c, ShadeStyle.tint(color, .08f), (color & 0x00FFFFFF) | 0x66000000, 14));
        return b;
    }

    /** Two buttons side by side, 8dp apart. */
    private LinearLayout pair(View left, View right) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.setMarginStart(dp(8));
        p.addView(right, rp);
        return p;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        return p;
    }

    private void divider() {
        View d = new View(c);
        d.setBackgroundColor(ShadeStyle.LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        p.setMargins(dp(10), dp(4), dp(10), dp(4));
        body.addView(d, p);
    }

    private TextView text(CharSequence value, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ───────────────────────────── motion

    /** The card unfolds from the button: pivot on the button, a short drop, fade and scale; its rows follow one by one. */
    private void enter() {
        card.setPivotX(Math.min(anchor.getWidth() / 2f, dp(80)));
        card.setPivotY(0f);
        card.setAlpha(0f);
        card.setScaleX(.9f);
        card.setScaleY(.9f);
        card.setTranslationY(-dp(10));
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(240)
                .setInterpolator(ConfigChip.EASE).start();
        for (int i = 0; i < body.getChildCount(); i++) {
            View child = body.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.setAlpha(0f);
            child.setTranslationY(-dp(4));
            child.animate().alpha(1f).translationY(0f).setStartDelay(40 + 18L * i).setDuration(200)
                    .setInterpolator(ConfigChip.EASE).start();
        }
    }

    /** Inline steps fade in sliding down and fold out; the card's height follows smoothly. */
    private LayoutTransition transition() {
        LayoutTransition t = new LayoutTransition();
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(220);
        t.setInterpolator(LayoutTransition.CHANGE_APPEARING, ConfigChip.EASE);
        t.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, ConfigChip.EASE);
        t.setInterpolator(LayoutTransition.CHANGING, ConfigChip.EASE);
        t.setAnimator(LayoutTransition.APPEARING, ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f), PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -dp(6), 0f)));
        t.setAnimator(LayoutTransition.DISAPPEARING, ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f)));
        t.setStartDelay(LayoutTransition.APPEARING, 60);
        return t;
    }

    private int dp(float v) {
        return dp(c, v);
    }

    private static int dp(Context c, float v) {
        return ShadeStyle.dp(c, v);
    }
}
