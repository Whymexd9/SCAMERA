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
import android.widget.ScrollView;
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
 * P143: the config menu under the shade's config button - save over the active config, save as a new one, load a saved one,
 * import a file, reset to the defaults. A card in the shade's style that unfolds from the button (scale, fade, a short drop,
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
    }

    static final int MAX_LIST_DP = 196;
    private final Context c;
    private final ConfigChip anchor;
    private final Actions actions;
    private final Consumer<CharSequence> message;
    private final Runnable onClosed;
    private final PopupWindow window;
    private final FrameLayout root;
    private final LinearLayout card;
    private final int accent;
    private boolean closing;
    @Nullable private View openPanel;

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
        menu.window.showAsDropDown(anchor, -pad, dp(anchor.getContext(), 6) - pad, Gravity.START);
        anchor.setOpen(true);
        menu.enter();
        return menu;
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
        card.addView(eyebrow);
        TextView title = text(ConfigState.label(c), 17, ShadeStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setPadding(dp(10), 0, dp(10), dp(10));
        title.setTag("config_menu_name");
        card.addView(title);
        divider();

        if (active != null) {
            card.addView(row(R.drawable.settings_ic_save, Lang.t(c, "Сохранить", "Save"),
                    Lang.t(c, "Перезаписать «" + active + "»", "Overwrite «" + active + "»"), accent, "config_save",
                    () -> done(actions.save(active))));
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
        card.addView(row(R.drawable.settings_ic_plus, Lang.t(c, "Сохранить как…", "Save as…"), null, accent, "config_save_as",
                () -> toggle(saveAs, field)));
        card.addView(saveAs);

        String[] files = actions.saved();
        if (files != null && files.length > 0) {
            divider();
            TextView section = new TextView(c);
            section.setText(Lang.t(c, "Сохранённые", "Saved"));
            ShadeStyle.label(section, accent);
            section.setPadding(dp(10), dp(4), dp(10), dp(4));
            card.addView(section);
            LinearLayout list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setLayoutTransition(transition());
            for (int i = files.length - 1; i >= 0; i--) { // newest (dated names) first
                final String file = files[i];
                final String base = ConfigState.base(file);
                boolean current = base.equals(active);
                LinearLayout confirm = panel("config_load_panel_" + base);
                TextView warn = text(Lang.t(c, "Текущие настройки заменятся, камера перезапустится",
                        "The current settings are replaced, the camera restarts"), 13, ShadeStyle.MUTED);
                confirm.addView(warn);
                confirm.addView(button(Lang.t(c, "Загрузить «" + base + "»", "Load «" + base + "»"), ShadeStyle.INK, accent,
                        "config_load_confirm", () -> done(actions.load(file))), buttonParams());
                list.addView(row(current ? R.drawable.settings_ic_config : R.drawable.settings_ic_list, base,
                        current ? Lang.t(c, "Сейчас", "Now") : null, current ? accent : ShadeStyle.MUTED, "config_item_" + base,
                        () -> toggle(confirm, null)));
                list.addView(confirm);
            }
            ScrollView scroll = new ScrollView(c) {
                @Override protected void onMeasure(int w, int h) {
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(MAX_LIST_DP), MeasureSpec.AT_MOST));
                }
            };
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setTag("config_list");
            scroll.addView(list);
            card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        }
        divider();
        card.addView(row(R.drawable.settings_ic_restore, Lang.t(c, "Импорт из файла…", "Import a file…"),
                Lang.t(c, "Конфиг с другого телефона или из другой папки", "A config from another phone or folder"), accent,
                "config_import", () -> { actions.importFile(); close(); }));
        LinearLayout reset = panel("config_reset_panel");
        reset.addView(text(Lang.t(c, "Все настройки и модули вернутся к стандартным, камера перезапустится",
                "Every setting and module goes back to the defaults, the camera restarts"), 13, ShadeStyle.MUTED));
        reset.addView(button(Lang.t(c, "Сбросить", "Reset"), ShadeStyle.INK, SettingsStyle.WARN, "config_reset_confirm",
                () -> done(actions.reset())), buttonParams());
        card.addView(row(R.drawable.settings_ic_reset, Lang.t(c, "Сбросить настройки", "Reset settings"), null,
                SettingsStyle.WARN, "config_reset", () -> toggle(reset, null)));
        card.addView(reset);
    }

    private void done(String result) {
        if (result != null) message.accept(result);
        close();
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
        card.addView(d, p);
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
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
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
