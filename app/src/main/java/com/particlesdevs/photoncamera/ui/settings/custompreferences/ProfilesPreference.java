package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.settings.ConfigState;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SIMPLE_UI §4 «Профили и резервная копия» (the concept's profiles page; the shade's config menu moved here): the note on
 * what a profile is, «Сейчас» - the active profile (ConfigState), «Сохранённые» - the configs of Download/SCAMERA/XML, newest
 * first, each with «Загрузить»; a tap opens its step in place (load: the settings are replaced and the camera restarts, or
 * delete the file). «Действия» (save as, load from a file, reset) follow as rows of the page.
 */
public class ProfilesPreference extends Preference {
    /** What the page does with the files; the settings wire it to BackupRestoreUtil / ConfigFolder, tests to a fake. */
    public interface Actions {
        String[] saved();
        String load(String fileName);
        boolean delete(String fileName);

        /** Whether Download/SCAMERA is open to the app (every file there listed); false shows «Открыть папку конфигов». */
        default boolean folderOpen() { return true; }
    }

    private Actions actions = new Actions() {
        @Override public String[] saved() {
            return com.particlesdevs.photoncamera.util.ConfigFolder.list(getContext());
        }

        @Override public String load(String fileName) {
            return com.particlesdevs.photoncamera.settings.BackupRestoreUtil.restorePreferences(getContext(), fileName);
        }

        @Override public boolean delete(String fileName) {
            return com.particlesdevs.photoncamera.util.ConfigFolder.delete(getContext(), fileName);
        }

        @Override public boolean folderOpen() {
            return com.particlesdevs.photoncamera.util.ScameraFolder.CONFIGS.grantedTree(getContext()) != null;
        }
    };
    @Nullable private Runnable folderOpener;
    @Nullable private LinearLayout page;
    @Nullable private View openPanel;

    public ProfilesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public ProfilesPreference(Context context) {
        this(context, null);
    }

    /** Tests. */
    public void setActions(Actions actions) {
        this.actions = actions;
        fill();
    }

    /** P246: what «Открыть папку конфигов» runs (the settings' system folder picker for Download/SCAMERA). */
    public void setFolderOpener(@Nullable Runnable opener) {
        folderOpener = opener;
        fill();
    }

    /** The list read again (the settings came back to the front, the folder was opened). */
    public void refresh() {
        fill();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        page = new LinearLayout(getContext());
        page.setOrientation(LinearLayout.VERTICAL);
        page.setTag("profiles_page");
        host.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fill();
    }

    private void fill() {
        LinearLayout p = page;
        if (p == null) return;
        Context c = getContext();
        int accent = SettingsStyle.accent(c);
        p.removeAllViews();
        openPanel = null;
        p.addView(LutChangesPreference.noteView(c, Lang.t(c,
                "Профиль — это все настройки камеры в одном файле. Удобно держать отдельный для ночи или портретов.",
                "A profile is every camera setting in one file. Handy to keep one for the night or for portraits.")));
        // «Сейчас»
        p.addView(FilmLutPreference.groupTitle(c, Lang.t(c, "Сейчас", "Now"), accent), titleParams(c));
        LinearLayout now = card(c);
        TextView tick = SettingsStyle.text(c, "✓", 16, accent);
        now.addView(row(c, ConfigState.label(c), Lang.t(c, "Активный профиль", "The active profile"), tick, "profile_active", null));
        p.addView(now);
        // «Сохранённые»
        String[] files;
        try {
            files = actions.saved();
        } catch (RuntimeException e) {
            files = new String[0];
        }
        p.addView(FilmLutPreference.groupTitle(c, Lang.t(c, "Сохранённые", "Saved"), accent), titleParams(c));
        LinearLayout saved = card(c);
        saved.setTag("profiles_saved");
        if (files == null || files.length == 0) {
            TextView none = SettingsStyle.text(c, Lang.t(c, "Пока ничего не сохранено: «Сохранить текущие как…» ниже.",
                    "Nothing saved yet: «Save the current as…» below."), 13, UiTokens.MUTED);
            none.setPadding(UiTokens.dp(c, 14), UiTokens.dp(c, 14), UiTokens.dp(c, 14), UiTokens.dp(c, 14));
            saved.addView(none);
        } else {
            String active = ConfigState.active(c);
            for (int i = files.length - 1; i >= 0; i--) {   // newest (dated) first
                String file = files[i];
                String base = ConfigState.base(file);
                if (saved.getChildCount() > 0) saved.addView(divider(c));
                TextView load = SettingsStyle.text(c, base.equals(active) ? Lang.t(c, "Сейчас", "Now") : Lang.t(c, "Загрузить", "Load"), 13,
                        base.equals(active) ? UiTokens.MUTED : accent);
                LinearLayout panel = panel(c, file, accent);
                View r = row(c, base, file, load, "profile_" + base, () -> toggle(panel));
                saved.addView(r);
                saved.addView(panel);
            }
        }
        p.addView(saved);
        // P246 (owner, 2026-10-18: «если я в папку добавляю новый конфиг вручную не через наше приложение, то у нас его не
        // видно»): Android shows the app only its own files there until the folder is opened to it once (the shade's config
        // menu had this row; the page that replaced it had not)
        boolean open;
        try {
            open = actions.folderOpen();
        } catch (RuntimeException e) {
            open = true;
        }
        if (!open && folderOpener != null) {
            LinearLayout folder = card(c);
            TextView go = SettingsStyle.text(c, Lang.t(c, "Открыть", "Open"), 13, accent);
            folder.addView(row(c, Lang.t(c, "Открыть папку конфигов", "Open the config folder"),
                    Lang.t(c, "Один раз: конфиги, закинутые в Download/SCAMERA/XML вручную, появятся в списке",
                            "Once: configs put into Download/SCAMERA/XML by hand show up in the list"), go, "profiles_open_folder",
                    folderOpener));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fp.topMargin = UiTokens.dp(c, 10);
            p.addView(folder, fp);
        }
    }

    private LinearLayout panel(Context c, String file, int accent) {
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(UiTokens.dp(c, 14), 0, UiTokens.dp(c, 14), UiTokens.dp(c, 12));
        panel.setVisibility(View.GONE);
        panel.setTag("profile_panel_" + ConfigState.base(file));
        panel.addView(SettingsStyle.text(c, Lang.t(c, "Текущие настройки заменятся, камера перезапустится.",
                "The current settings are replaced, the camera restarts."), 12.5f, UiTokens.MUTED));
        LinearLayout buttons = new LinearLayout(c);
        buttons.setPadding(0, UiTokens.dp(c, 10), 0, 0);
        buttons.addView(button(c, Lang.t(c, "Загрузить", "Load"), true, accent, "profile_load", () -> done(actions.load(file))));
        View del = button(c, Lang.t(c, "Удалить файл", "Delete the file"), false, SettingsStyle.WARN, "profile_delete", () -> {
            if (!actions.delete(file)) {
                done(Lang.t(c, "Не удалось удалить «" + file + "»", "Could not delete «" + file + "»"));
                return;
            }
            if (ConfigState.base(file).equals(ConfigState.active(c))) ConfigState.detach(c);
            done(Lang.t(c, "Удалён «" + file + "»", "Deleted «" + file + "»"));
            fill();
        });
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.setMarginStart(UiTokens.dp(c, 8));
        buttons.addView(del, dp);
        panel.addView(buttons);
        return panel;
    }

    private void done(@Nullable String message) {
        if (message != null && !message.isEmpty()) Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    }

    private void toggle(View panel) {
        if (openPanel != null && openPanel != panel) openPanel.setVisibility(View.GONE);
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        openPanel = show ? panel : null;
    }

    private static LinearLayout.LayoutParams titleParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiTokens.dp(c, 16);
        lp.bottomMargin = UiTokens.dp(c, 6);
        return lp;
    }

    private static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 18));
        return card;
    }

    private static View divider(Context c) {
        View line = new View(c);
        line.setBackgroundColor(UiTokens.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiTokens.dp(c, .5f)));
        lp.setMarginStart(UiTokens.dp(c, 62));
        line.setLayoutParams(lp);
        return line;
    }

    /** A row as the settings draw it: the 36dp icon tile, the title and its line, the value on the right. */
    private static View row(Context c, String title, @Nullable String sub, View right, String tag, @Nullable Runnable onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiTokens.dp(c, 58));
        row.setPadding(UiTokens.dp(c, 14), UiTokens.dp(c, 10), UiTokens.dp(c, 14), UiTokens.dp(c, 10));
        if (onClick != null) {
            row.setBackgroundResource(R.drawable.settings_row);
            row.setOnClickListener(v -> onClick.run());
        }
        FrameLayout tile = new FrameLayout(c);
        tile.setBackgroundResource(R.drawable.settings_icon_tile);
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ui_ic_layers);
        icon.setImageTintList(ColorStateList.valueOf(UiTokens.TEXT));
        tile.addView(icon, new FrameLayout.LayoutParams(UiTokens.dp(c, 22), UiTokens.dp(c, 22), Gravity.CENTER));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(UiTokens.dp(c, 36), UiTokens.dp(c, 36));
        tp.setMarginEnd(UiTokens.dp(c, 12));
        row.addView(tile, tp);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = SettingsStyle.text(c, title, 14.5f, UiTokens.TEXT);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        texts.addView(t);
        if (sub != null) {
            TextView s = SettingsStyle.text(c, sub, 12.5f, UiTokens.SUMMARY);
            s.setPadding(0, UiTokens.dp(c, 2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMarginStart(UiTokens.dp(c, 10));
        row.addView(right, rp);
        row.setTag(tag);
        return row;
    }

    /** A pill button: filled in {@code color} (the ink text) or outlined in it. */
    private static View button(Context c, String text, boolean filled, int color, String tag, Runnable onClick) {
        TextView b = SettingsStyle.text(c, text, 13, filled ? UiTokens.INK : color);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(UiTokens.dp(c, 36));
        b.setPadding(UiTokens.dp(c, 16), 0, UiTokens.dp(c, 16), 0);
        b.setBackground(UiTokens.shape(c, filled ? color : 0, filled ? 0 : color, 999));
        b.setTag(tag);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }
}
