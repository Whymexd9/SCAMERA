package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLabOrient;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLabRun;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference;
import com.particlesdevs.photoncamera.util.Lang;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P277 «Гайд и лаборатория» (owner, 2026-10-19: «гайд как небольшое приложение в котором всё будет полноценно показано ... чтобы в
 * приложение можно было подгрузить например свою Рав серию и и посмотреть на ней как и что работает»; «нам нужно чтобы было
 * наглядно видно что и как работает, те же настройки склейки и тд чтобы можно было понять разницу»): the lab shots
 * (ScamLab, «Сохранять снимки для лаборатории»), a shot's fragment, the settings of the merge and of the processing grouped as
 * in the settings, and for one setting the shot as taken (A) beside the shot with another value (B) - merged and rendered
 * again by the camera's own worker and pipeline (ScamLabRun): before / after with a draggable border and a magnifier, the
 * difference, and the merge's frame map.
 */
public class ScamLabFragment extends ModuleConceptFragment {
    static final String SCREEN = "scam_lab_screen";
    private static final String PAGE = "page", DIR = "dir", GROUP = "group", KEY = "key", VALUE = "value", TYPE = "type";

    /** The settings pages the lab offers (the capture page is the burst's: fixed at the shot). */
    static final String[] GROUPS = {"scam_hybrid_photo_screen", "scam_hybrid_ark_sharp_screen", "scam_hybrid_ark_tone_screen",
            "scam_hybrid_merge_screen", "photo_style_screen", "film_lut_screen", "lens_filter_screen", "photo_processing_screen"};
    /** Settings the burst fixed at the shot (its frames, its noise model, its grid): offered, said so. */
    static final java.util.Set<String> AT_SHOT = new java.util.HashSet<>(java.util.Arrays.asList("pref_scam_hybrid_output",
            "pref_scam_hybrid_noise_photon", "pref_scam_hybrid_noise_readout", "pref_scam_hybrid_noise_source",
            "pref_scam_hybrid_mosaic_remosaic", "pref_scam_hybrid_highlight_recovery"));

    /** The fragment of each shot ({x, y, w, h} of the sensor frame, null: whole) and its share (3: a ninth, 2: a quarter, 1: whole). */
    private static final Map<String, int[]> crops = new HashMap<>();
    private static final Map<String, Integer> shares = new HashMap<>();
    private static PreferenceScreen tree;
    private static final android.util.LruCache<String, Bitmap> thumbs = new android.util.LruCache<>(24);
    private static Bitmap shotPhoto;
    private static String shotPhotoPath;

    private static String runKey;
    private static ScamLabRun.Result runResult;
    private static String runError, runStage;
    private static Thread runThread;
    private int showMode;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static ScamLabFragment list() {
        return new ScamLabFragment();
    }

    private static ScamLabFragment of(Bundle b) {
        ScamLabFragment f = new ScamLabFragment();
        f.setArguments(b);
        return f;
    }

    private String arg(String k) {
        return getArguments() == null ? null : getArguments().getString(k);
    }

    @Override protected void render() {
        final String page = arg(PAGE);
        if ("shot".equals(page)) renderShot();
        else if ("group".equals(page)) renderGroup();
        else if ("result".equals(page)) renderResult();
        else renderList();
    }

    // ───── The shots ─────

    private void renderList() {
        page(Lang.t(getContext(), "Гайд и лаборатория", "Guide and lab"), Lang.t(getContext(), "Настройки на ваших RAW-сериях", "The settings on your own RAW bursts"));
        note(Lang.t(getContext(), "Снимите серию с переключателем ниже, откройте её здесь и выберите настройку. Лаборатория склеит и обработает ту же серию дважды: "
                        + "A — как снято, B — с другим значением. Результаты можно сравнить шторкой и лупой, посмотреть разницу и карту кадров склейки.",
                "Take a burst with the switch below, open it here and pick a setting. The lab merges and processes the same burst twice: "
                        + "A as shot, B with another value. Compare them with a border and a magnifier, see the difference and the merge's frame map."));
        labSwitch();
        final List<File> shots = ScamLab.shots();
        caption(Lang.t(getContext(), "Снимки · Download/SCAMERA/Lab", "Shots · Download/SCAMERA/Lab"));
        if (shots.isEmpty()) {
            note(Lang.t(getContext(), "Пока нет снимков. Включите переключатель выше и сделайте снимок SHDR.", "No shots yet. Turn on the switch above and take an SHDR shot."));
            return;
        }
        LinearLayout card = card();
        boolean first = true;
        for (File dir : shots) {
            if (!first) divider(card);
            first = false;
            card.addView(shotRow(dir));
        }
        note(Lang.t(getContext(), "Удерживайте снимок, чтобы удалить его (серия, параметры и превью).", "Hold a shot to delete it (burst, parameters and preview)."));
    }

    private void labSwitch() {
        LinearLayout c = card(), r = row();
        r.setPadding(dp(12), dp(16), dp(10), dp(16));
        ImageView ic = icon("◉");
        ic.setImageResource(R.drawable.settings_ic_save);
        ic.setPadding(0, 0, dp(12), 0);
        r.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(28)));
        LinearLayout labels = column();
        TextView head = text(Lang.t(getContext(), "Сохранять снимки для лаборатории", "Keep shots for the lab"), 15, TEXT);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);
        labels.addView(head);
        TextView sub = text(Lang.t(getContext(), "Каждый снимок SHDR сохраняет RAW-серию и свои параметры в папку Lab/<дата_время>. Файлы большие: 12 Мп — до 1 ГБ, 50 Мп — до нескольких ГБ за снимок.",
                "Every SHDR shot keeps its RAW burst and its parameters in a folder Lab/<date_time>. The files are large: 12 MP up to 1 GB, 50 MP up to a few GB a shot."), 12, SettingsStyle.SUMMARY);
        sub.setLineSpacing(dp(3), 1);
        labels.addView(sub, space(-1, -2, 6));
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        SwitchCompat toggle = new SwitchCompat(requireContext());
        toggle.setTag(ScamLab.PREF);
        toggle.setContentDescription(head.getText());
        toggle.setChecked(ScamLab.wanted());
        toggle.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{accent, 0xFF606570}));
        r.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(48)));
        c.addView(r);
        toggle.setOnCheckedChangeListener((v, on) -> prefs().edit().putBoolean(ScamLab.PREF, on).apply());
    }

    private View shotRow(File dir) {
        LinearLayout r = row();
        r.setPadding(dp(10), dp(10), dp(12), dp(10));
        r.setMinimumHeight(dp(72));
        ImageView thumb = new ImageView(requireContext());
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(shape(0xFF000000, 0, 8));
        thumb.setClipToOutline(true);
        Bitmap b = thumb(new File(dir, ScamLab.PREVIEW));
        if (b != null) thumb.setImageBitmap(b);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(64), dp(48));
        tp.setMarginEnd(dp(12));
        r.addView(thumb, tp);
        LinearLayout labels = column();
        TextView head = text(when(dir.getName()), 15, TEXT);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);
        labels.addView(head);
        String sub;
        try {
            ScamLabRun.Shot s = ScamLabRun.Shot.load(dir);
            sub = s.meta.optString("device", "") + " · " + s.width + "×" + s.height + " · " + s.frames + Lang.t(getContext(), " кадр.", " frames") + " · " + size(s.bytes());
        } catch (Exception e) {
            sub = Lang.t(getContext(), "Не читается: ", "Unreadable: ") + e.getMessage();
        }
        TextView st = text(sub, 12, SettingsStyle.SUMMARY);
        labels.addView(st, space(-1, -2, 6));
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(text("›", 25, MUTED));
        r.setTag("lab_shot_" + dir.getName());
        r.setFocusable(true);
        r.setOnClickListener(v -> {
            Bundle a = new Bundle();
            a.putString(PAGE, "shot");
            a.putString(DIR, dir.getAbsolutePath());
            open(of(a));
        });
        r.setOnLongClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(Lang.t(getContext(), "Удалить снимок?", "Delete the shot?"))
                    .setMessage(when(dir.getName()) + "\n" + Lang.t(getContext(), "Серия, параметры и превью будут удалены из Download/SCAMERA/Lab.", "The burst, parameters and preview are deleted from Download/SCAMERA/Lab."))
                    .setNegativeButton(Lang.t(getContext(), "Отмена", "Cancel"), null)
                    .setPositiveButton(Lang.t(getContext(), "Удалить", "Delete"), (d, w) -> {
                        ScamLabRun.forget();
                        if (!ScamLab.remove(dir)) Toast.makeText(requireContext(), Lang.t(getContext(), "Не удалось удалить", "Could not delete"), Toast.LENGTH_SHORT).show();
                        if (root != null) render();
                    }).show();
            return true;
        });
        return r;
    }

    /** "2026-10-19_15-30-12" -> "19.10.2026 15:30:12". */
    static String when(String folder) {
        if (folder.length() < 19) return folder;
        return folder.substring(8, 10) + "." + folder.substring(5, 7) + "." + folder.substring(0, 4) + " "
                + folder.substring(11, 13) + ":" + folder.substring(14, 16) + ":" + folder.substring(17, 19) + folder.substring(19).replace('_', ' ');
    }

    static String size(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30)) : (bytes >> 20) + " MB";
    }

    @Nullable
    private static Bitmap thumb(File file) {
        Bitmap b = thumbs.get(file.getAbsolutePath());
        if (b != null) return b;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 8;
        b = BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        if (b != null) thumbs.put(file.getAbsolutePath(), b);
        return b;
    }

    private SharedPreferences prefs() {
        return ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
    }

    // ───── A shot: its fragment and the groups ─────

    @Nullable
    private ScamLabRun.Shot shot() {
        try {
            return ScamLabRun.Shot.load(new File(arg(DIR)));
        } catch (Exception e) {
            note(Lang.t(getContext(), "Снимок не читается: ", "The shot cannot be read: ") + e.getMessage());
            return null;
        }
    }

    private Bitmap photo(ScamLabRun.Shot s) {
        final String path = new File(s.dir, ScamLab.PREVIEW).getAbsolutePath();
        if (!path.equals(shotPhotoPath)) {
            shotPhoto = BitmapFactory.decodeFile(path);
            shotPhotoPath = path;
        }
        return shotPhoto;
    }

    private void renderShot() {
        page(when(new File(arg(DIR)).getName()), null);
        final ScamLabRun.Shot s = shot();
        if (s == null) return;
        subtitle.setText(s.width + "×" + s.height + " · " + s.frames + Lang.t(getContext(), " кадров · ", " frames · ") + size(s.bytes()));
        subtitle.setVisibility(View.VISIBLE);
        final String id = s.dir.getAbsolutePath();
        final int share = shares.containsKey(id) ? shares.get(id) : 3;
        if (!crops.containsKey(id)) crops.put(id, s.middleFragment());
        final Bitmap preview = photo(s);
        if (preview != null) {
            LabPickView pick = new LabPickView(requireContext());
            pick.setTag("lab_fragment");
            final int[] c = share == 1 ? null : crops.get(id);
            pick.set(preview, c == null ? null : ScamLabOrient.photoRect(s.orient, c, s.width, s.height), accent, (x, y) -> {
                final int[] cur = crops.get(id);
                if (cur == null) return;
                final float[] uv = ScamLabOrient.toSensor(s.orient, x, y);
                final int[] moved = new int[]{Math.round(uv[0] * s.width) - cur[2] / 2, Math.round(uv[1] * s.height) - cur[3] / 2, cur[2], cur[3]};
                final int[] a = aligned(moved, s);
                crops.put(id, a);
                ScamLabRun.forget();
                runKey = null;
            });
            body.addView(pick, space(-1, -2, 8));
            pick.setOnTouchListener((v, e) -> {
                v.onTouchEvent(e);
                final int[] cur = crops.get(id);
                if (cur != null) pick.setRect(ScamLabOrient.photoRect(s.orient, cur, s.width, s.height));
                return true;
            });
        }
        LinearLayout r = row();
        String[] labels = {Lang.t(getContext(), "Фрагмент 1/9", "Fragment 1/9"), Lang.t(getContext(), "Фрагмент 1/4", "Fragment 1/4"), Lang.t(getContext(), "Весь кадр", "Whole frame")};
        int[] values = {3, 2, 1};
        for (int i = 0; i < 3; i++) {
            final int v = values[i];
            TextView b = button(labels[i], share == v, () -> {
                shares.put(id, v);
                if (v > 1) {
                    final int[] cur = crops.get(id);
                    final int cx = cur == null ? s.width / 2 : cur[0] + cur[2] / 2, cy = cur == null ? s.height / 2 : cur[1] + cur[3] / 2;
                    crops.put(id, aligned(new int[]{cx - s.width / v / 2, cy - s.height / v / 2, s.width / v, s.height / v}, s));
                }
                ScamLabRun.forget();
                runKey = null;
                render();
            });
            b.setTag("lab_share_" + v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < 2) lp.rightMargin = dp(8);
            r.addView(b, lp);
        }
        body.addView(r, space(-1, -2, 12));
        note(share == 1 ? Lang.t(getContext(), "Весь кадр склеивается так же долго, как при съёмке (50 Мп — минуты на каждую сторону).", "The whole frame merges as long as at the shot (50 MP: minutes a side).")
                : Lang.t(getContext(), "Нажмите на фото, чтобы передвинуть фрагмент. Экспозиция и тон считаются по фрагменту, поэтому яркость может немного отличаться от снимка.",
                "Tap the photo to move the fragment. Exposure and tone are measured on the fragment, so the brightness can differ a little from the shot."));
        caption(Lang.t(getContext(), "Что сравнить", "What to compare"));
        final PreferenceScreen all = tree();
        for (String key : GROUPS) {
            final Preference g = all.findPreference(key);
            if (!(g instanceof PreferenceGroup)) continue;
            navigation("☷", String.valueOf(g.getTitle()), g.getSummary() == null ? null : String.valueOf(g.getSummary()), () -> openGroup(key));
        }
    }

    private static int[] aligned(int[] c, ScamLabRun.Shot s) {
        final int w = Math.max(32, c[2]), h = Math.max(32, c[3]);
        final int x = Math.max(0, Math.min(s.width - w, c[0])), y = Math.max(0, Math.min(s.height - h, c[1]));
        return new int[]{x / 32 * 32, y / 32 * 32, w / 32 * 32, h / 32 * 32};
    }

    private void openGroup(String key) {
        Bundle a = new Bundle();
        a.putString(PAGE, "group");
        a.putString(DIR, arg(DIR));
        a.putString(GROUP, key);
        open(of(a));
    }

    /** The settings tree with each value at its default (a store that keeps nothing: inflating writes no setting). */
    private PreferenceScreen tree() {
        if (tree == null) {
            // as ShadeCatalog.ensureTree: the settings theme, a store that answers the defaults and keeps nothing
            final Context c = new android.view.ContextThemeWrapper(requireContext().getApplicationContext(), R.style.Theme_Scamera_SettingsActivity);
            final PreferenceManager pm = new PreferenceManager(c);
            pm.setPreferenceDataStore(new PreferenceDataStore() {
                @Override public void putString(String key, String value) {}
                @Override public void putStringSet(String key, java.util.Set<String> values) {}
                @Override public void putInt(String key, int value) {}
                @Override public void putLong(String key, long value) {}
                @Override public void putFloat(String key, float value) {}
                @Override public void putBoolean(String key, boolean value) {}
            });
            tree = pm.inflateFromResource(c, R.xml.preferences, null);
            pm.setPreferences(tree);
        }
        return tree;
    }

    // ───── A group: its settings ─────

    private void renderGroup() {
        final ScamLabRun.Shot s = shot();
        final Preference g = tree().findPreference(arg(GROUP));
        page(g == null ? "" : String.valueOf(g.getTitle()), Lang.t(getContext(), "Нажмите настройку, чтобы сравнить", "Tap a setting to compare"));
        if (s == null || !(g instanceof PreferenceGroup)) return;
        addGroup((PreferenceGroup) g, s, null);
    }

    private void addGroup(PreferenceGroup group, ScamLabRun.Shot s, @Nullable LinearLayout card) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference p = group.getPreference(i);
            if (!p.isVisible()) continue;
            if (p instanceof PreferenceScreen) {
                final String key = p.getKey();
                navigation("☷", String.valueOf(p.getTitle()), p.getSummary() == null ? null : String.valueOf(p.getSummary()), () -> openGroup(key));
                card = null;
            } else if (p instanceof PreferenceCategory) {
                caption(String.valueOf(p.getTitle()));
                addGroup((PreferenceGroup) p, s, null);
                card = null;
            } else if (comparable(p)) {
                if (card == null) card = card(); else divider(card);
                card.addView(settingRow(p, s));
            }
        }
    }

    static boolean comparable(Preference p) {
        return p.getKey() != null && (p instanceof TwoStatePreference || p instanceof ListPreference || p instanceof UniversalSeekBarPreference);
    }

    private View settingRow(Preference p, ScamLabRun.Shot s) {
        LinearLayout r = row();
        r.setPadding(dp(12), dp(14), dp(12), dp(14));
        r.setMinimumHeight(dp(56));
        LinearLayout labels = column();
        TextView head = text(String.valueOf(p.getTitle()), 15, TEXT);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);
        labels.addView(head);
        if (p.getSummary() != null) {
            TextView sub = text(String.valueOf(p.getSummary()), 12, SettingsStyle.SUMMARY);
            sub.setLineSpacing(dp(3), 1);
            sub.setMaxLines(3);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(sub, space(-1, -2, 6));
        }
        String shotValue = Lang.t(getContext(), "При съёмке: ", "At the shot: ") + label(p, shotValue(p, s));
        if (AT_SHOT.contains(p.getKey())) shotValue += Lang.t(getContext(), " · задаётся при съёмке", " · fixed at the shot");
        TextView v = text(shotValue, 12, accent);
        labels.addView(v, space(-1, -2, 6));
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(text("›", 25, MUTED));
        r.setTag("lab_setting_" + p.getKey());
        r.setFocusable(true);
        r.setOnClickListener(x -> pickValue(p, s, null));
        return r;
    }

    /** The setting's value at the shot (its default when it was not set). */
    static Object shotValue(Preference p, ScamLabRun.Shot s) {
        final Object v = s.pref(p.getKey());
        return v != null ? v : defaultValue(p);
    }

    static Object defaultValue(Preference p) {
        if (p instanceof TwoStatePreference) return ((TwoStatePreference) p).isChecked();
        if (p instanceof ListPreference) return ((ListPreference) p).getValue();
        if (p instanceof UniversalSeekBarPreference) return number((UniversalSeekBarPreference) p, ((UniversalSeekBarPreference) p).defaultNumber());
        return null;
    }

    static String number(UniversalSeekBarPreference p, float v) {
        if (!p.decimal()) return String.valueOf(Math.round(v));
        final int decimals = Math.max(0, Math.min(4, (int) Math.ceil(Math.log10(Math.max(1, p.stepPerUnit())))));
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    /** A value as the settings show it. */
    String label(Preference p, Object v) {
        if (v == null) return Lang.t(getContext(), "по умолчанию", "default");
        if (p instanceof TwoStatePreference) {
            final boolean on = v instanceof Boolean ? (Boolean) v : "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
            return on ? Lang.t(getContext(), "Вкл", "On") : Lang.t(getContext(), "Выкл", "Off");
        }
        if (p instanceof ListPreference) {
            final ListPreference l = (ListPreference) p;
            final int i = l.findIndexOfValue(v.toString());
            return i >= 0 && l.getEntries() != null ? String.valueOf(l.getEntries()[i]) : v.toString();
        }
        return v.toString();
    }

    /** The sheet of the value B; {@code onPicked} null: open its result page. */
    private void pickValue(Preference p, ScamLabRun.Shot s, @Nullable java.util.function.Consumer<Object> onPicked) {
        final java.util.function.Consumer<Object> done = onPicked != null ? onPicked : v -> openResult(p, v);
        final Object at = shotValue(p, s);
        if (p instanceof TwoStatePreference) {
            final boolean on = at instanceof Boolean ? (Boolean) at : "1".equals(String.valueOf(at));
            done.accept(!on);
            return;
        }
        if (p instanceof ListPreference) {
            final ListPreference l = (ListPreference) p;
            final CharSequence[] entries = l.getEntries(), values = l.getEntryValues();
            if (entries == null || values == null) return;
            SettingsStyle.optionSheet(requireContext(), p.getTitle(), entries, values, l.findIndexOfValue(String.valueOf(at)), accent,
                    i -> done.accept(values[i].toString())).show();
            return;
        }
        final UniversalSeekBarPreference u = (UniversalSeekBarPreference) p;
        LinearLayout sheetBody = SettingsStyle.sheetBody(requireContext(), p.getTitle(), null);
        final float min = u.minimum(), max = u.maximum(), step = u.decimal() ? 1f / Math.max(1, u.stepPerUnit()) : 1f;
        final int steps = Math.max(1, Math.round((max - min) / step));
        float start;
        try { start = Float.parseFloat(String.valueOf(at)); } catch (NumberFormatException e) { start = u.defaultNumber(); }
        final TextView value = text("", 22, accent);
        value.setGravity(Gravity.CENTER);
        sheetBody.addView(value, space(-1, -2, 4));
        final TextView atShot = text(Lang.t(getContext(), "При съёмке: ", "At the shot: ") + label(p, at), 12, MUTED);
        atShot.setGravity(Gravity.CENTER);
        sheetBody.addView(atShot, space(-1, -2, 6));
        final SeekBar bar = new SeekBar(requireContext());
        bar.setMax(steps);
        bar.setProgress(Math.round((start - min) / step));
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        sheetBody.addView(bar, space(-1, dp(40), 14));
        final Runnable show = () -> value.setText(number(u, min + bar.getProgress() * step));
        show.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) { show.run(); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        final BottomSheetDialog[] dialog = new BottomSheetDialog[1];
        TextView go = button(Lang.t(getContext(), "Сравнить", "Compare"), true, () -> {
            dialog[0].dismiss();
            done.accept(number(u, min + bar.getProgress() * step));
        });
        go.setTag("lab_compare");
        sheetBody.addView(go, space(-1, -2, 18));
        dialog[0] = SettingsStyle.sheet(requireContext(), sheetBody);
        dialog[0].show();
    }

    private void openResult(Preference p, Object v) {
        Bundle a = new Bundle();
        a.putString(PAGE, "result");
        a.putString(DIR, arg(DIR));
        a.putString(GROUP, arg(GROUP));
        a.putString(KEY, p.getKey());
        a.putString(TYPE, v instanceof Boolean ? "b" : "s");
        a.putString(VALUE, String.valueOf(v));
        open(of(a));
    }

    // ───── The comparison ─────

    private Object value() {
        final String v = arg(VALUE);
        return "b".equals(arg(TYPE)) ? (Object) Boolean.parseBoolean(v) : v;
    }

    private void renderResult() {
        final Preference p = tree().findPreference(arg(KEY));
        final ScamLabRun.Shot s = shot();
        page(p == null ? arg(KEY) : String.valueOf(p.getTitle()), null);
        if (s == null || p == null) return;
        final Object b = value();
        final String aText = "A · " + label(p, shotValue(p, s)), bText = "B · " + label(p, b);
        subtitle.setText(aText + "   " + bText);
        subtitle.setVisibility(View.VISIBLE);
        final String id = s.dir.getAbsolutePath();
        final int[] crop = shares.containsKey(id) && shares.get(id) == 1 ? null : crops.containsKey(id) ? crops.get(id) : s.middleFragment();
        final String key = id + "|" + (crop == null ? "whole" : java.util.Arrays.toString(crop)) + "|" + p.getKey() + "=" + b;
        if (!key.equals(runKey)) start(key, s, crop, p.getKey(), b);
        if (runResult == null) {
            LinearLayout box = column();
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, dp(40), 0, dp(40));
            if (runError == null) {
                ProgressBar bar = new ProgressBar(requireContext());
                bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
                box.addView(bar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            TextView st = text(runError != null ? runError : runStage == null ? Lang.t(getContext(), "Подготовка…", "Preparing…") : runStage, 14, runError != null ? SettingsStyle.WARN : MUTED);
            st.setGravity(Gravity.CENTER);
            st.setTag("lab_status");
            box.addView(st, space(-2, -2, 14));
            body.addView(box, new LinearLayout.LayoutParams(-1, -2));
            if (runError == null)
                note(Lang.t(getContext(), "Склейка идёт тем же обработчиком, что и при съёмке. Можно уйти с экрана: результат дождётся.", "The merge runs on the same worker as a shot. You can leave the screen: the result waits."));
            return;
        }
        final ScamLabRun.Result r = runResult;
        LabCompareView compare = new LabCompareView(requireContext());
        compare.setTag("lab_compare_view");
        compare.setAccent(accent);
        compare.setSpace(r.a.photo.getWidth(), r.a.photo.getHeight());
        final int height = Math.round(getResources().getDisplayMetrics().heightPixels * .52f);
        body.addView(compare, space(-1, height, 6));
        LinearLayout modes = row();
        String[] names = {Lang.t(getContext(), "До / после", "Before / after"), Lang.t(getContext(), "Разница ×4", "Difference ×4"), Lang.t(getContext(), "Карта кадров", "Frame map")};
        final TextView[] buttons = new TextView[3];
        final TextView explain = text("", 12, MUTED);
        explain.setLineSpacing(dp(3), 1);
        final Bitmap[] diff = {null};
        final Runnable[] apply = {null};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            buttons[i] = button(names[i], showMode == i, () -> { showMode = m; apply[0].run(); });
            buttons[i].setTag("lab_mode_" + i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < 2) lp.rightMargin = dp(8);
            modes.addView(buttons[i], lp);
        }
        apply[0] = () -> {
            for (int i = 0; i < 3; i++) {
                buttons[i].setTextColor(showMode == i ? 0xFF15131D : accent);
                buttons[i].setBackground(shape(showMode == i ? accent : BG, showMode == i ? 0 : 0xFF626974, 10));
            }
            if (showMode == 1) {
                if (diff[0] == null) diff[0] = ScamLabRun.difference(r.a.photo, r.b.photo, 4f);
                compare.setSingle(diff[0], Lang.t(getContext(), "|A − B| × 4", "|A − B| × 4"));
                explain.setText(Lang.t(getContext(), "Светлое — где B отличается от A. Чёрное — без изменений.", "Bright: where B differs from A. Black: unchanged."));
            } else if (showMode == 2 && r.a.frameMap != null && r.b.frameMap != null) {
                compare.setPair(r.a.frameMap, r.b.frameMap, "A", "B");
                explain.setText(Lang.t(getContext(), "Сколько кадров серии склейка взяла в каждую точку: тёмно-синий — мало (движение, отбраковка), жёлтый — вдвое больше среднего и выше.",
                        "How many burst frames the merge took at each point: dark blue few (motion, rejection), yellow twice the median and more."));
            } else {
                if (showMode == 2) explain.setText(Lang.t(getContext(), "Карты кадров нет: этот размер склеивается без неё.", "No frame map: this size merges without one."));
                else explain.setText(Lang.t(getContext(), "Тяните границу. Двойное касание — увеличение ×3, удержание — лупа.", "Drag the border. Double tap zooms ×3, hold for a magnifier."));
                compare.setPair(r.a.photo, r.b.photo, aText, bText);
            }
        };
        apply[0].run();
        body.addView(modes, space(-1, -2, 10));
        body.addView(explain, space(-1, -2, 8));
        String info = r.same ? Lang.t(getContext(), "На этой серии настройка ничего не меняет: A и B совпадают до пикселя. ", "On this burst the setting changes nothing: A and B are the same pixels. ")
                + (AT_SHOT.contains(p.getKey()) ? Lang.t(getContext(), "Она задаётся при съёмке (уже вшита в серию).", "It is fixed at the shot (already in the burst).")
                : Lang.t(getContext(), "Её условие не наступило (сцена, шум, яркость) или она действует при съёмке.", "Its condition did not occur (scene, noise, brightness) or it acts at the shot."))
                : (r.mergedAgain ? Lang.t(getContext(), "Настройка склейки: серия склеена заново для B. ", "A merge setting: the burst was merged again for B. ")
                : Lang.t(getContext(), "Настройка обработки: склейка та же, обработка другая. ", "A processing setting: the same merge, other processing. "));
        info += String.format(Locale.ROOT, Lang.t(getContext(), "Склейка A %.1f с, обработка %.1f с; B %.1f с + %.1f с.", "Merge A %.1f s, processing %.1f s; B %.1f s + %.1f s."),
                r.a.mergeMs / 1000f, r.a.postMs / 1000f, r.b.mergeMs / 1000f, r.b.postMs / 1000f);
        note(info);
        LinearLayout actions = row();
        TextView other = button(Lang.t(getContext(), "Другое значение", "Another value"), false, () -> pickValue(p, s, v -> {
            getArguments().putString(TYPE, v instanceof Boolean ? "b" : "s");
            getArguments().putString(VALUE, String.valueOf(v));
            render();
        }));
        other.setTag("lab_other_value");
        TextView keep = button(Lang.t(getContext(), "Сохранить B в настройки", "Keep B in the settings"), false, () -> {
            final SharedPreferences.Editor e = prefs().edit();
            final Object v = value();
            if (v instanceof Boolean) e.putBoolean(p.getKey(), (Boolean) v); else e.putString(p.getKey(), String.valueOf(v));
            e.apply();
            Toast.makeText(requireContext(), Lang.t(getContext(), "«" + p.getTitle() + "»: " + label(p, v), "“" + p.getTitle() + "”: " + label(p, v)), Toast.LENGTH_SHORT).show();
        });
        keep.setTag("lab_keep_value");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.rightMargin = dp(8);
        actions.addView(other, lp);
        actions.addView(keep, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(actions, space(-1, -2, 12));
    }

    /** One run at a time; the page is drawn again with each stage and with the result. */
    private void start(String key, ScamLabRun.Shot s, @Nullable int[] crop, String prefKey, Object value) {
        if (runThread != null && runThread.isAlive()) {
            if (key.equals(runKey)) return;
            runError = Lang.t(getContext(), "Идёт предыдущее сравнение. Дождитесь его и откройте снова.", "The previous comparison is running. Wait for it and open again.");
            runResult = null;
            return;
        }
        runKey = key;
        runResult = null;
        runError = null;
        runStage = null;
        final Context app = requireContext().getApplicationContext();
        runThread = new Thread(() -> {
            try {
                final ScamLabRun.Result r = ScamLabRun.run(app, s, crop, prefKey, value, text -> {
                    runStage = text;
                    redraw(key);
                });
                if (key.equals(runKey)) runResult = r;
            } catch (Throwable e) {
                if (key.equals(runKey)) runError = Lang.t(getContext(), "Не получилось: ", "Failed: ") + e.getMessage();
            }
            redraw(key);
        }, "scam-lab");
        runThread.start();
    }

    private void redraw(String key) {
        main.post(() -> {
            if (root != null && isAdded() && "result".equals(arg(PAGE)) && key.equals(runKey)) render();
        });
    }

    @Override public void onDestroyView() {
        main.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
