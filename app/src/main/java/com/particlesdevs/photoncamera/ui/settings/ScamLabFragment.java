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
import android.view.MotionEvent;
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
            "scam_hybrid_merge_screen", "photo_style_screen", "film_sim_screen", "film_lut_screen", "lens_filter_screen", "photo_processing_screen"};
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

    /** The comparison being rendered, the one asked for meanwhile (rendered next), and the last one rendered. */
    private static Job running, next, shown;
    private static ScamLabRun.Result shownResult;
    private static volatile String runError, runStage;
    private int showMode;
    /** P307: the side the single view shows (0 = A, 1 = B). */
    private int showSide;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** P294: a render is running; a slider is under a finger; a stage redraw waits for its release. */
    private boolean rendering, dragging, redrawWaiting;

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
        // P294 (owner, 2026-10-20: lab crash): page() empties body with removeAllViews, which sends ACTION_CANCEL to the
        // slider being dragged; its onStopTrackingTouch set the value and rendered again inside that removeAllViews. The
        // nested render added the new page to body while the outer loop was still nulling body's child slots, so body kept
        // null children under a non-zero count and the next traversal threw in dispatchCollectViewAttributes. A render asked
        // for during a render runs after it.
        if (rendering) {
            main.post(() -> { if (root != null && isAdded()) render(); });
            return;
        }
        rendering = true;
        // the page's sliders are replaced: none is under a finger any more, a waiting redraw is this one
        dragging = false;
        redrawWaiting = false;
        try {
            final String page = arg(PAGE);
            if ("shot".equals(page)) renderShot();
            else if ("group".equals(page)) renderGroup();
            else if ("result".equals(page)) renderResult();
            else renderList();
        } finally {
            rendering = false;
        }
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
            });
            body.addView(pick, space(-1, -2, 8));
            pick.setOnTouchListener((v, e) -> {
                v.onTouchEvent(e);
                final int[] cur = crops.get(id);
                if (cur != null) pick.setRect(ScamLabOrient.photoRect(s.orient, cur, s.width, s.height));
                return true;
            });
        }
        // owner, 2026-10-19: «дай возможность ещё большего кропа, добавь 4ый вариант»
        caption(Lang.t(getContext(), "Область сравнения", "Area compared"));
        LinearLayout r = row();
        String[] labels = {"1/16", "1/9", "1/4", Lang.t(getContext(), "Весь кадр", "Whole frame")};
        int[] values = {4, 3, 2, 1};
        for (int i = 0; i < values.length; i++) {
            final int v = values[i];
            TextView b = button(labels[i], share == v, () -> {
                shares.put(id, v);
                if (v > 1) {
                    final int[] cur = crops.get(id);
                    final int cx = cur == null ? s.width / 2 : cur[0] + cur[2] / 2, cy = cur == null ? s.height / 2 : cur[1] + cur[3] / 2;
                    crops.put(id, aligned(new int[]{cx - s.width / v / 2, cy - s.height / v / 2, s.width / v, s.height / v}, s));
                }
                ScamLabRun.forget();
                render();
            });
            b.setTag("lab_share_" + v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, v == 1 ? 1.6f : 1f);
            if (i < values.length - 1) lp.rightMargin = dp(8);
            r.addView(b, lp);
        }
        body.addView(r, space(-1, -2, 8));
        note(share == 1 ? Lang.t(getContext(), "Весь кадр склеивается так же долго, как при съёмке (50 Мп — минуты на каждую сторону).", "The whole frame merges as long as at the shot (50 MP: minutes a side).")
                : Lang.t(getContext(), "Нажмите на фото, чтобы передвинуть фрагмент. Экспозиция, тон и засветы считаются по всему кадру, как на снимке; для снимков без сохранённой статистики она считается один раз при первом сравнении.",
                "Tap the photo to move the fragment. Exposure, tone and highlights are measured on the whole frame, as in the shot; a shot without saved statistics measures them once at its first comparison."));
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
            } else if (p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmSimPreference) {
                // P281: the film simulation's settings, one row each
                for (String k : com.particlesdevs.photoncamera.processing.color.FilmSim.KEYS) {
                    final Spec sp = spec(k);
                    if (sp == null) continue;
                    if (card == null) card = card(); else divider(card);
                    card.addView(settingRow(k, sp.title, sp.summary, s));
                }
            } else if (comparable(p)) {
                if (card == null) card = card(); else divider(card);
                card.addView(settingRow(p, s));
            }
        }
    }

    // ───── What a setting is in the lab (Spec) ─────

    /** A tone curve, a LUT, a lens filter: settings of their own kind, compared too (owner, 2026-10-19). */
    static final String CURVE_ON = "pref_scam_hybrid_ark_curve_on",
            CURVE = com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference.KEY,
            LUT = com.particlesdevs.photoncamera.processing.color.FilmLut.KEY,
            LUT_STRENGTH = com.particlesdevs.photoncamera.processing.color.FilmLut.STRENGTH_KEY,
            FILTER = com.particlesdevs.photoncamera.processing.color.LensFilters.KEY,
            FILTER_STRENGTH = com.particlesdevs.photoncamera.processing.color.LensFilters.STRENGTH_KEY;
    /** The settings shown with a setting on its page (each with its own A and B): the curve and its switch, a LUT and its strength. */
    static final Map<String, String[]> LINKS = new HashMap<>();
    static {
        LINKS.put(CURVE_ON, new String[]{CURVE});
        LINKS.put(CURVE, new String[]{CURVE_ON});
        LINKS.put(LUT, new String[]{LUT_STRENGTH});
        LINKS.put(LUT_STRENGTH, new String[]{LUT});
        LINKS.put(FILTER, new String[]{FILTER_STRENGTH});
    }
    /** A value of a {@link Spec#CUSTOM} setting: the shot's own, or the one in the settings now (edited on its settings page). */
    static final String AS_SHOT = "\u0001shot", IN_SETTINGS = "\u0001now";

    static final class Spec {
        static final int SWITCH = 0, LIST = 1, SLIDER = 2, CUSTOM = 3;
        final String key;
        final int kind;
        String title = "", summary;
        CharSequence[] labels;
        String[] values;
        float min, max, step = 1f;
        boolean decimal;
        Object def;
        /** The settings page of a CUSTOM setting (its editor). */
        String page;

        Spec(String key, int kind) {
            this.key = key;
            this.kind = kind;
        }

        String number(float v) {
            if (!decimal) return String.valueOf(Math.round(v));
            final int decimals = Math.max(0, Math.min(4, (int) Math.ceil(Math.log10(Math.max(1, 1f / step)))));
            return String.format(Locale.ROOT, "%." + decimals + "f", v);
        }
    }

    static boolean comparable(Preference p) {
        if (p.getKey() == null) return false;
        if (p instanceof TwoStatePreference || p instanceof ListPreference || p instanceof UniversalSeekBarPreference) return true;
        return CURVE.equals(p.getKey()) || LUT.equals(p.getKey()) || FILTER.equals(p.getKey());
    }

    @Nullable
    Spec spec(String key) {
        final Preference p = tree().findPreference(key);
        final Context c = requireContext();
        Spec s;
        if (LUT.equals(key)) {
            s = new Spec(key, Spec.LIST);
            s.values = com.particlesdevs.photoncamera.settings.ItemOrder.luts(c).toArray(new String[0]);
            s.labels = new CharSequence[s.values.length];
            for (int i = 0; i < s.values.length; i++)
                s.labels[i] = s.values[i].isEmpty() ? Lang.t(c, "Без LUT", "No LUT")
                        : com.particlesdevs.photoncamera.processing.color.FilmLooks.isBuiltIn(s.values[i])
                        ? com.particlesdevs.photoncamera.processing.color.FilmLooks.label(c, s.values[i]) : s.values[i];
            s.def = "";
        } else if (FILTER.equals(key)) {
            s = new Spec(key, Spec.LIST);
            s.values = com.particlesdevs.photoncamera.settings.ItemOrder.filters().toArray(new String[0]);
            s.labels = new CharSequence[s.values.length];
            for (int i = 0; i < s.values.length; i++)
                s.labels[i] = s.values[i].isEmpty() ? Lang.t(c, "Без фильтра", "No filter")
                        : com.particlesdevs.photoncamera.processing.color.LensFilters.name(c, s.values[i]);
            s.def = "";
        } else if (FILTER_STRENGTH.equals(key)) {
            s = new Spec(key, Spec.SLIDER);
            s.title = Lang.t(c, "Сила фильтра", "Filter strength");
            s.min = 0;
            s.max = 100;
            s.def = "50";
            return s;
        } else if (filmSpec(key, c) != null) {
            return filmSpec(key, c);
        } else if (CURVE.equals(key)) {
            s = new Spec(key, Spec.CUSTOM);
            s.page = "scam_hybrid_ark_curves_screen";
            s.def = AS_SHOT;
        } else if (p instanceof TwoStatePreference) {
            s = new Spec(key, Spec.SWITCH);
            s.def = ((TwoStatePreference) p).isChecked();
        } else if (p instanceof ListPreference) {
            s = new Spec(key, Spec.LIST);
            final ListPreference l = (ListPreference) p;
            s.labels = l.getEntries() == null ? new CharSequence[0] : l.getEntries();
            final CharSequence[] v = l.getEntryValues() == null ? new CharSequence[0] : l.getEntryValues();
            s.values = new String[v.length];
            for (int i = 0; i < v.length; i++) s.values[i] = v[i].toString();
            s.def = l.getValue();
        } else if (p instanceof UniversalSeekBarPreference) {
            final UniversalSeekBarPreference u = (UniversalSeekBarPreference) p;
            s = new Spec(key, Spec.SLIDER);
            s.min = u.minimum();
            s.max = u.maximum();
            s.decimal = u.decimal();
            s.step = u.decimal() ? 1f / Math.max(1, u.stepPerUnit()) : 1f;
            s.def = s.number(u.defaultNumber());
        } else return null;
        if (p != null) {
            s.title = String.valueOf(p.getTitle());
            s.summary = p.getSummary() == null ? null : String.valueOf(p.getSummary());
        }
        return s;
    }

    /** P281: the film simulation's settings in the lab (FilmSim.KEYS). */
    @Nullable
    static Spec filmSpec(String key, Context c) {
        Spec s;
        switch (key) {
            case com.particlesdevs.photoncamera.processing.color.FilmSim.STOCK_KEY: {
                s = new Spec(key, Spec.LIST);
                final com.particlesdevs.photoncamera.processing.color.FilmSim.Stock[] st = com.particlesdevs.photoncamera.processing.color.FilmSim.STOCKS;
                s.values = new String[st.length + 1];
                s.labels = new CharSequence[st.length + 1];
                s.values[0] = "";
                s.labels[0] = Lang.t(c, "Нет", "None");
                for (int i = 0; i < st.length; i++) { s.values[i + 1] = st[i].id; s.labels[i + 1] = st[i].brand + " " + st[i].name; }
                s.def = "";
                s.title = Lang.t(c, "Плёнка", "Film stock");
                s.summary = Lang.t(c, "Плёночная симуляция: слои, проявка и печать плёнки", "Film simulation: the film's layers, development and print");
                return s;
            }
            case com.particlesdevs.photoncamera.processing.color.FilmSim.PRINT_KEY: {
                s = new Spec(key, Spec.LIST);
                final com.particlesdevs.photoncamera.processing.color.FilmSim.Print[] pr = com.particlesdevs.photoncamera.processing.color.FilmSim.PRINTS;
                s.values = new String[pr.length];
                s.labels = new CharSequence[pr.length];
                for (int i = 0; i < pr.length; i++) { s.values[i] = pr[i].id; s.labels[i] = pr[i].brand + " " + pr[i].name; }
                s.def = pr[0].id;
                s.title = Lang.t(c, "Печать", "Print");
                s.summary = Lang.t(c, "Бумага или кинопечать, на которую печатается негатив", "The paper or print film the negative is printed on");
                return s;
            }
            case com.particlesdevs.photoncamera.processing.color.FilmSim.FORMAT_KEY: {
                s = new Spec(key, Spec.LIST);
                final com.particlesdevs.photoncamera.processing.color.FilmSim.Format[] fm = com.particlesdevs.photoncamera.processing.color.FilmSim.FORMATS;
                s.values = new String[fm.length];
                s.labels = new CharSequence[fm.length];
                for (int i = 0; i < fm.length; i++) { s.values[i] = fm[i].id; s.labels[i] = com.particlesdevs.photoncamera.processing.color.FilmSim.formatName(c, fm[i]); }
                s.def = "135";
                s.title = Lang.t(c, "Формат кадра", "Frame format");
                s.summary = Lang.t(c, "Размер кадра плёнки: зерно, ореол и мягкость", "The film frame's size: grain, halation and softness");
                return s;
            }
            case com.particlesdevs.photoncamera.processing.color.FilmSim.GRAIN_KEY:
                return slider(key, Lang.t(c, "Зерно плёнки", "Film grain"), 0, 200, 1, "100");
            case com.particlesdevs.photoncamera.processing.color.FilmSim.HALATION_KEY:
                return slider(key, Lang.t(c, "Ореол плёнки", "Film halation"), 0, 400, 1, "100");
            case com.particlesdevs.photoncamera.processing.color.FilmSim.SOFTNESS_KEY:
                return slider(key, Lang.t(c, "Мягкость плёнки", "Film softness"), 0, 300, 1, "100");
            case com.particlesdevs.photoncamera.processing.color.FilmSim.EXPOSURE_KEY:
                return slider(key, Lang.t(c, "Экспозиция плёнки, EV", "Film exposure, EV"), -2, 2, .1f, "0");
            case com.particlesdevs.photoncamera.processing.color.FilmSim.PRINT_EV_KEY:
                return slider(key, Lang.t(c, "Экспозиция печати, EV", "Print exposure, EV"), -1, 1, .05f, "0");
            case com.particlesdevs.photoncamera.processing.color.FilmSim.TONE_KEY:
                return slider(key, Lang.t(c, "Контраст плёнки", "Film contrast"), -100, 100, 1, "0");
            case com.particlesdevs.photoncamera.processing.color.FilmSim.PRINT_M_KEY:
                return slider(key, Lang.t(c, "Фильтр M увеличителя, CC", "Enlarger M filter, CC"), -30, 30, 1, "0");
            case com.particlesdevs.photoncamera.processing.color.FilmSim.PRINT_Y_KEY:
                return slider(key, Lang.t(c, "Фильтр Y увеличителя, CC", "Enlarger Y filter, CC"), -30, 30, 1, "0");
            case com.particlesdevs.photoncamera.processing.color.FilmSim.STRENGTH_KEY:
                return slider(key, Lang.t(c, "Сила симуляции", "Simulation strength"), 0, 100, 1, "100");
            default:
                return null;
        }
    }

    private static Spec slider(String key, String title, float min, float max, float step, String def) {
        final Spec s = new Spec(key, Spec.SLIDER);
        s.title = title;
        s.min = min;
        s.max = max;
        s.step = step;
        s.decimal = step < 1f;
        s.def = def;
        return s;
    }

    /** The setting's value at the shot (its default when it was not set; a CUSTOM one: {@link #AS_SHOT}). */
    static Object shotValue(Spec spec, ScamLabRun.Shot s) {
        if (spec.kind == Spec.CUSTOM) return AS_SHOT;
        final Object v = s.pref(spec.key);
        if (v == null) return spec.def;
        if (spec.kind == Spec.SWITCH && !(v instanceof Boolean)) return "1".equals(String.valueOf(v)) || "true".equalsIgnoreCase(String.valueOf(v));
        return v;
    }

    /** A value as the settings show it. */
    String label(Spec spec, Object v) {
        if (v == null) return Lang.t(getContext(), "по умолчанию", "default");
        switch (spec.kind) {
            case Spec.SWITCH:
                return (v instanceof Boolean ? (Boolean) v : "1".equals(v.toString())) ? Lang.t(getContext(), "Вкл", "On") : Lang.t(getContext(), "Выкл", "Off");
            case Spec.LIST:
                for (int i = 0; i < spec.values.length; i++) if (spec.values[i].equals(v.toString())) return String.valueOf(spec.labels[i]);
                return v.toString();
            case Spec.CUSTOM:
                if (AS_SHOT.equals(v)) return Lang.t(getContext(), "как снято", "as shot");
                if (IN_SETTINGS.equals(v)) return Lang.t(getContext(), "как сейчас в настройках", "as in the settings now");
                return Lang.t(getContext(), "своя кривая", "own curve"); // P307: edited on the lab page
            default:
                return v.toString();
        }
    }

    private View settingRow(Preference p, ScamLabRun.Shot s) {
        return settingRow(p.getKey(), String.valueOf(p.getTitle()), p.getSummary() == null ? null : String.valueOf(p.getSummary()), s);
    }

    private View settingRow(String key, String title, @Nullable String summary, ScamLabRun.Shot s) {
        LinearLayout r = row();
        r.setPadding(dp(12), dp(14), dp(12), dp(14));
        r.setMinimumHeight(dp(56));
        LinearLayout labels = column();
        TextView head = text(title, 15, TEXT);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);
        labels.addView(head);
        if (summary != null) {
            TextView sub = text(summary, 12, SettingsStyle.SUMMARY);
            sub.setLineSpacing(dp(3), 1);
            sub.setMaxLines(3);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(sub, space(-1, -2, 6));
        }
        final Spec spec = spec(key);
        if (spec != null && spec.kind != Spec.CUSTOM) {
            String shotValue = Lang.t(getContext(), "При съёмке: ", "At the shot: ") + label(spec, shotValue(spec, s));
            if (AT_SHOT.contains(key)) shotValue += Lang.t(getContext(), " · задаётся при съёмке", " · fixed at the shot");
            labels.addView(text(shotValue, 12, accent), space(-1, -2, 6));
        }
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(text("›", 25, MUTED));
        r.setTag("lab_setting_" + key);
        r.setFocusable(true);
        r.setOnClickListener(x -> openResult(key, s));
        return r;
    }

    // ───── The comparison: A and B, each setting of the page with its own controls ─────

    /** A comparison: the shot, the fragment and the settings of each side. */
    private static final class Job {
        final String id, page;
        final ScamLabRun.Shot shot;
        @Nullable final int[] crop;
        final java.util.TreeMap<String, Object> a, b;

        Job(ScamLabRun.Shot shot, @Nullable int[] crop, String key, java.util.TreeMap<String, Object> a, java.util.TreeMap<String, Object> b) {
            this.shot = shot;
            this.crop = crop;
            this.a = a;
            this.b = b;
            page = shot.dir.getAbsolutePath() + "|" + (crop == null ? "whole" : java.util.Arrays.toString(crop)) + "|" + key;
            id = page + "|" + a + "|" + b;
        }
    }

    /** The keys of a setting's page: the setting and its {@link #LINKS}. */
    static String[] keys(String key) {
        final String[] linked = LINKS.get(key);
        if (linked == null) return new String[]{key};
        final String[] all = new String[linked.length + 1];
        all[0] = key;
        System.arraycopy(linked, 0, all, 1, linked.length);
        return all;
    }

    /** The page of one setting: A as shot; B the setting at another value ({@link #suggested}), its linked ones as shot. */
    private void openResult(String key, ScamLabRun.Shot s) {
        Bundle args = new Bundle();
        args.putString(PAGE, "result");
        args.putString(DIR, arg(DIR));
        args.putString(GROUP, arg(GROUP));
        args.putString(KEY, key);
        for (String k : keys(key)) {
            final Spec spec = spec(k);
            if (spec == null) continue;
            final Object at = shotValue(spec, s);
            put(args, "a", k, at);
            put(args, "b", k, k.equals(key) ? suggested(spec, at) : at);
        }
        // the curve compared is the one in the settings, switched on
        if (CURVE.equals(key)) put(args, "b", CURVE_ON, true);
        open(of(args));
    }

    private static void put(Bundle b, String side, String key, Object v) {
        b.putString(side + "t:" + key, v == null ? "n" : v instanceof Boolean ? "b" : "s");
        b.putString(side + ":" + key, String.valueOf(v));
    }

    @Nullable
    private Object get(String side, String key) {
        final String type = arg(side + "t:" + key), v = arg(side + ":" + key);
        if (type == null || "n".equals(type)) return null;
        return "b".equals(type) ? (Object) Boolean.parseBoolean(v) : v;
    }

    /** A value to compare with {@code at}: a switch's other state, a list's next entry, a slider a quarter of its range away. */
    static Object suggested(Spec spec, Object at) {
        switch (spec.kind) {
            case Spec.SWITCH:
                return !(at instanceof Boolean ? (Boolean) at : "1".equals(String.valueOf(at)));
            case Spec.LIST: {
                if (spec.values.length == 0) return at;
                int i = 0;
                for (int k = 0; k < spec.values.length; k++) if (spec.values[k].equals(String.valueOf(at))) i = k;
                return spec.values[(i + 1) % spec.values.length];
            }
            case Spec.CUSTOM:
                return IN_SETTINGS;
            default: {
                float v;
                try { v = Float.parseFloat(String.valueOf(at)); } catch (NumberFormatException e) { v = Float.parseFloat(String.valueOf(spec.def)); }
                final float quarter = (spec.max - spec.min) / 4f;
                return spec.number(v + quarter <= spec.max ? v + quarter : Math.max(spec.min, v - quarter));
            }
        }
    }

    /** The overrides of a side: a CUSTOM setting "as shot" is the shot's value, "in the settings" the stored one now. */
    private java.util.TreeMap<String, Object> overrides(String side, String key, ScamLabRun.Shot s) {
        final java.util.TreeMap<String, Object> m = new java.util.TreeMap<>();
        final Map<String, ?> stored = prefs().getAll();
        for (String k : keys(key)) {
            Object v = get(side, k);
            if (AS_SHOT.equals(v)) v = s.pref(k);
            else if (IN_SETTINGS.equals(v)) v = stored.get(k);
            else if (v == null && !arg2(side, k)) continue;
            m.put(k, v);
        }
        return m;
    }

    private boolean arg2(String side, String key) {
        return arg(side + "t:" + key) != null;
    }

    private void renderResult() {
        final String key = arg(KEY);
        final ScamLabRun.Shot s = shot();
        final Spec main = s == null ? null : spec(key);
        page(main == null ? key : main.title, null);
        if (s == null || main == null) return;
        if (main.summary != null) {
            subtitle.setText(main.summary);
            subtitle.setMaxLines(3);
            subtitle.setVisibility(View.VISIBLE);
        }
        final String id = s.dir.getAbsolutePath();
        final int[] crop = shares.containsKey(id) && shares.get(id) == 1 ? null : crops.containsKey(id) ? crops.get(id) : s.middleFragment();
        final Job job = new Job(s, crop, key, overrides("a", key, s), overrides("b", key, s));
        start(job);
        final boolean busy;
        final ScamLabRun.Result r;
        final Job seen;
        synchronized (ScamLabFragment.class) {
            busy = running != null || next != null;
            seen = shown;
            // the last result of this page (shot, fragment, setting) stays on screen while the new values render
            r = seen != null && seen.page.equals(job.page) ? shownResult : null;
        }
        if (r == null) {
            LinearLayout box = column();
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, dp(40), 0, dp(40));
            if (runError == null) {
                ProgressBar bar = new ProgressBar(requireContext());
                bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
                box.addView(bar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            TextView st = text(runError != null ? runError : runStage == null ? Lang.t(getContext(), "Подготовка…", "Preparing…") : runStage, 14,
                    runError != null ? SettingsStyle.WARN : MUTED);
            st.setGravity(Gravity.CENTER);
            st.setTag("lab_status");
            box.addView(st, space(-2, -2, 14));
            body.addView(box, new LinearLayout.LayoutParams(-1, -2));
        } else {
            final String aText = "A · " + label(main, seenValue(seen, "a", main)), bText = "B · " + label(main, seenValue(seen, "b", main));
            LabCompareView compare = new LabCompareView(requireContext());
            compare.setTag("lab_compare_view");
            compare.setAccent(accent);
            compare.setSpace(r.a.photo.getWidth(), r.a.photo.getHeight());
            final int height = Math.round(getResources().getDisplayMetrics().heightPixels * .48f);
            FrameLayout frame = new FrameLayout(requireContext());
            frame.addView(compare, new FrameLayout.LayoutParams(-1, height));
            if (busy || runError != null || !seen.id.equals(job.id)) {
                TextView st = text(runError != null ? runError : runStage == null ? Lang.t(getContext(), "Пересчёт…", "Updating…") : runStage, 13,
                        runError != null ? SettingsStyle.WARN : TEXT);
                st.setTag("lab_status");
                st.setPadding(dp(10), dp(6), dp(10), dp(6));
                st.setBackground(shape(0xCC000000, 0, 10));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                lp.bottomMargin = dp(10);
                frame.addView(st, lp);
            }
            body.addView(frame, space(-1, height, 6));
            modes(r, compare, aText, bText);
        }
        for (String k : keys(key)) {
            final Spec spec = spec(k);
            if (spec != null) controls(spec, s, !k.equals(key));
        }
        if (r != null) info(main, r);
        TextView keep = button(Lang.t(getContext(), "Сохранить B в настройки", "Keep B in the settings"), false, () -> {
            final SharedPreferences.Editor e = prefs().edit();
            final java.util.TreeMap<String, Object> b = overrides("b", key, s);
            for (Map.Entry<String, Object> en : b.entrySet()) {
                final Object v = en.getValue();
                if (v == null) e.remove(en.getKey());
                else if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
                else if (v instanceof java.util.Set) {
                    final java.util.Set<String> set = new java.util.HashSet<>();
                    for (Object x : (java.util.Set<?>) v) set.add(String.valueOf(x));
                    e.putStringSet(en.getKey(), set);
                } else e.putString(en.getKey(), String.valueOf(v));
            }
            e.apply();
            Toast.makeText(requireContext(), "«" + main.title + "»: " + label(main, get("b", key)), Toast.LENGTH_SHORT).show();
        });
        keep.setTag("lab_keep_value");
        body.addView(keep, space(-1, -2, 12));
    }

    /** The main setting's value a rendered side had (for its label on the picture). */
    private Object seenValue(Job seen, String side, Spec main) {
        final Object v = ("a".equals(side) ? seen.a : seen.b).get(main.key);
        if (main.kind == Spec.CUSTOM) return get(side, main.key);
        return v;
    }

    private void modes(ScamLabRun.Result r, LabCompareView compare, String aText, String bText) {
        LinearLayout modes = row();
        // P307 (owner, 2026-10-20: «дай возможность переключать режим на одиночный без сравнения»): «Одиночный» shows one side over
        // the whole frame (A or B below the modes), no split
        String[] names = {Lang.t(getContext(), "До / после", "Before / after"), Lang.t(getContext(), "Разница ×4", "Difference ×4"),
                Lang.t(getContext(), "Карта кадров", "Frame map"), Lang.t(getContext(), "Одиночный", "Single")};
        final TextView[] buttons = new TextView[names.length];
        final TextView explain = text("", 12, MUTED);
        explain.setLineSpacing(dp(3), 1);
        final Bitmap[] diff = {null};
        final Runnable[] apply = {null};
        for (int i = 0; i < names.length; i++) {
            final int m = i;
            buttons[i] = button(names[i], showMode == i, () -> { showMode = m; apply[0].run(); });
            buttons[i].setTag("lab_mode_" + i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < names.length - 1) lp.rightMargin = dp(6);
            modes.addView(buttons[i], lp);
        }
        final LinearLayout sides = row();
        final TextView[] sideButtons = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final int side = i;
            sideButtons[i] = button(i == 0 ? aText : bText, showSide == i, () -> { showSide = side; apply[0].run(); });
            sideButtons[i].setTag(i == 0 ? "lab_single_a" : "lab_single_b");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i == 0) lp.rightMargin = dp(8);
            sides.addView(sideButtons[i], lp);
        }
        apply[0] = () -> {
            for (int i = 0; i < buttons.length; i++) {
                buttons[i].setTextColor(showMode == i ? 0xFF15131D : accent);
                buttons[i].setBackground(shape(showMode == i ? accent : BG, showMode == i ? 0 : 0xFF626974, 10));
            }
            for (int i = 0; i < 2; i++) {
                sideButtons[i].setTextColor(showSide == i ? 0xFF15131D : accent);
                sideButtons[i].setBackground(shape(showSide == i ? accent : BG, showSide == i ? 0 : 0xFF626974, 10));
            }
            sides.setVisibility(showMode == 3 ? View.VISIBLE : View.GONE);
            if (showMode == 3) {
                compare.setSingle(showSide == 0 ? r.a.photo : r.b.photo, showSide == 0 ? aText : bText);
                explain.setText(Lang.t(getContext(), "Одна сторона на весь кадр, без сравнения. Двойное касание — увеличение ×3, удержание — лупа.",
                        "One side over the whole frame, no comparison. Double tap zooms ×3, hold for a magnifier."));
            } else if (showMode == 1) {
                if (diff[0] == null) diff[0] = ScamLabRun.difference(r.a.photo, r.b.photo, 4f);
                compare.setSingle(diff[0], "|A − B| × 4");
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
        body.addView(sides, space(-1, -2, 8));
        body.addView(explain, space(-1, -2, 8));
    }

    /** A setting's A and B, each with its own control: a change renders that side again. */
    private void controls(Spec spec, ScamLabRun.Shot s, boolean linked) {
        if (linked) caption(spec.title);
        final Object atShot = shotValue(spec, s);
        LinearLayout card = card();
        card.addView(control(spec, "A", "a", atShot, s));
        divider(card);
        card.addView(control(spec, "B", "b", atShot, s));
        if (spec.kind == Spec.CUSTOM) {
            TextView edit = button(Lang.t(getContext(), "Изменить в настройках", "Edit in the settings"), false, () -> {
                if (getActivity() instanceof SettingsActivity) ((SettingsActivity) getActivity()).openPageAt(spec.page, spec.key);
            });
            edit.setTag("lab_edit_" + spec.key);
            LinearLayout.LayoutParams lp = space(-1, -2, 4);
            lp.leftMargin = lp.rightMargin = dp(12);
            lp.bottomMargin = dp(12);
            card.addView(edit, lp);
            return;
        }
        String note = Lang.t(getContext(), "При съёмке: ", "At the shot: ") + label(spec, atShot);
        if (AT_SHOT.contains(spec.key)) note += Lang.t(getContext(), " · задаётся при съёмке: на сохранённой серии не меняется", " · fixed at the shot: no change on a saved burst");
        TextView t = text(note, 12, MUTED);
        t.setPadding(dp(12), 0, dp(12), dp(12));
        card.addView(t);
    }

    private View control(Spec spec, String name, String side, Object atShot, ScamLabRun.Shot s) {
        final Object value = get(side, spec.key);
        LinearLayout r = column();
        r.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout head = row();
        TextView letter = text(name, 16, accent);
        letter.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(letter, new LinearLayout.LayoutParams(dp(28), -2));
        final TextView shown = text(label(spec, value), 15, TEXT);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(shown);
        head.addView(shown, new LinearLayout.LayoutParams(0, -2, 1));
        final java.util.function.Consumer<Object> set = v -> {
            if (String.valueOf(v).equals(arg(side + ":" + spec.key))) return;
            put(getArguments(), side, spec.key, v);
            render();
        };
        // P283: the side at the shot's value says so (a muted tag); the other side's link names the value it goes back to (a bare
        // «as shot» beside B's value read as «B is the shot's value»)
        final boolean isShot = String.valueOf(atShot).equals(String.valueOf(value));
        TextView reset = isShot ? text(Lang.t(getContext(), "как снято", "as shot"), 13, MUTED)
                : text("↺ " + (spec.kind == Spec.CUSTOM ? label(spec, atShot) : Lang.t(getContext(), "снято: ", "shot: ") + label(spec, atShot)), 13, accent);
        reset.setPadding(dp(10), dp(6), dp(4), dp(6));
        reset.setTag("lab_reset_" + name + "_" + spec.key);
        if (!isShot) reset.setOnClickListener(x -> set.accept(atShot));
        r.addView(head);
        switch (spec.kind) {
            case Spec.SWITCH: {
                head.addView(reset);
                SwitchCompat toggle = new SwitchCompat(requireContext());
                toggle.setTag("lab_value_" + name + "_" + spec.key);
                toggle.setChecked(value instanceof Boolean ? (Boolean) value : "1".equals(String.valueOf(value)));
                toggle.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
                toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{accent, 0xFF606570}));
                toggle.setOnCheckedChangeListener((v, on) -> set.accept(on));
                head.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(40)));
                break;
            }
            case Spec.LIST:
            case Spec.CUSTOM: {
                head.addView(reset);
                final CharSequence[] labels = spec.kind == Spec.LIST ? spec.labels
                        : new CharSequence[]{label(spec, AS_SHOT), label(spec, IN_SETTINGS)};
                final String[] values = spec.kind == Spec.LIST ? spec.values : new String[]{AS_SHOT, IN_SETTINGS};
                int selected = -1;
                for (int i = 0; i < values.length; i++) if (values[i].equals(String.valueOf(value))) selected = i;
                final int current = selected;
                TextView pick = button(Lang.t(getContext(), "Выбрать…", "Choose…"), false, () ->
                        SettingsStyle.optionSheet(requireContext(), spec.title + " · " + name, labels, values, current, accent,
                                i -> set.accept(values[i])).show());
                pick.setTag("lab_value_" + name + "_" + spec.key);
                r.addView(pick, space(-1, -2, 8));
                if (CURVE.equals(spec.key)) r.addView(curveEditor(name, value, s, set), space(-1, -2, 8));
                break;
            }
            default: {
                head.addView(reset);
                final float min = spec.min, max = spec.max, step = spec.step;
                float start;
                try { start = Float.parseFloat(String.valueOf(value)); } catch (NumberFormatException e) { start = Float.parseFloat(String.valueOf(spec.def)); }
                final SeekBar bar = new SeekBar(requireContext());
                bar.setTag("lab_value_" + name + "_" + spec.key);
                bar.setMax(Math.max(1, Math.round((max - min) / step)));
                bar.setProgress(Math.round((start - min) / step));
                bar.setProgressTintList(ColorStateList.valueOf(accent));
                bar.setThumbTintList(ColorStateList.valueOf(accent));
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) { shown.setText(spec.number(min + progress * step)); }
                    @Override public void onStartTrackingTouch(SeekBar sb) {
                        dragging = true;
                        sb.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    @Override public void onStopTrackingTouch(SeekBar sb) {
                        dragging = false;
                        set.accept(spec.number(min + sb.getProgress() * step));
                        // P294: the redraw a stage asked for during the drag (set renders only when the value changed)
                        if (redrawWaiting) render();
                    }
                });
                r.addView(bar, space(-1, dp(36), 6));
                LinearLayout ends = row();
                ends.addView(text(spec.number(min), 11, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
                ends.addView(text(spec.number(max), 11, MUTED));
                r.addView(ends);
            }
        }
        return r;
    }

    /**
     * P307 (owner, 2026-10-20: «Настройки кривой тут должны быть полноценные, чтобы можно было прямо тут и настраивать»): the
     * side's curve in the settings' own editor (tap adds a point, drag moves it, hold deletes it; «S-кривая», «Прямая»); the end
     * of a gesture makes the edited curve the side's value and renders that side again. The value goes in after the touch (a
     * post: the page is rebuilt, never inside the graph's own event, P294), and no stage redraw rebuilds the page while a finger
     * is on the graph.
     */
    private View curveEditor(String name, Object value, ScamLabRun.Shot s, java.util.function.Consumer<Object> set) {
        final Context c = requireContext();
        String text;
        if (AS_SHOT.equals(value)) text = s.pref(CURVE) == null ? null : String.valueOf(s.pref(CURVE));
        else if (IN_SETTINGS.equals(value)) text = prefs().getString(CURVE, null);
        else text = value == null ? null : String.valueOf(value);
        if (text == null) text = com.particlesdevs.photoncamera.processing.color.ToneCurve.DEFAULT_POINTS;
        LinearLayout box = column();
        final com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveView graph =
                new com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveView(c);
        graph.setTag("lab_curve_" + name);
        graph.setCurve(com.particlesdevs.photoncamera.processing.color.ToneCurve.parse(text));
        graph.setOnCommit(curve -> {
            final String edited = curve.serialize();
            main.post(() -> set.accept(edited));
        });
        graph.setOnTouchListener((v, e) -> {
            final int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) dragging = true;
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                if (redrawWaiting) main.post(() -> { if (root != null && isAdded()) render(); });
            }
            return false; // the graph handles the gesture itself
        });
        box.addView(graph, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout chips = row();
        TextView sCurve = button(Lang.t(c, "S-кривая", "S-curve"), false,
                () -> set.accept(com.particlesdevs.photoncamera.processing.color.ToneCurve.defaults().serialize()));
        sCurve.setTag("lab_curve_s_" + name);
        TextView straight = button(Lang.t(c, "Прямая", "Straight"), false,
                () -> set.accept(com.particlesdevs.photoncamera.processing.color.ToneCurve.identity().serialize()));
        straight.setTag("lab_curve_straight_" + name);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        first.rightMargin = dp(8);
        chips.addView(sCurve, first);
        chips.addView(straight, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(chips, space(-1, -2, 8));
        TextView hint = text(Lang.t(c, "Тап — добавить точку, перетаскивание — двигать, удержание — удалить.",
                "Tap adds a point, drag moves it, hold deletes it."), 12, MUTED);
        box.addView(hint, space(-1, -2, 6));
        return box;
    }

    private void info(Spec main, ScamLabRun.Result r) {
        String info = r.same ? Lang.t(getContext(), "На этой серии A и B совпадают до пикселя. ", "On this burst A and B are the same pixels. ")
                + (AT_SHOT.contains(main.key) ? Lang.t(getContext(), "Настройка задаётся при съёмке (уже вшита в серию).", "The setting is fixed at the shot (already in the burst).")
                : Lang.t(getContext(), "Её условие не наступило (сцена, шум, яркость) или она действует при съёмке.", "Its condition did not occur (scene, noise, brightness) or it acts at the shot."))
                : (r.mergedAgain ? Lang.t(getContext(), "Настройка склейки: A и B склеены каждая со своим значением. ", "A merge setting: A and B were merged each with its value. ")
                : Lang.t(getContext(), "Настройка обработки: склейка одна, обработка разная. ", "A processing setting: one merge, different processing. "));
        info += String.format(Locale.ROOT, Lang.t(getContext(), "A: склейка %.1f с, обработка %.1f с; B: %.1f с + %.1f с.", "A: merge %.1f s, processing %.1f s; B: %.1f s + %.1f s."),
                r.a.mergeMs / 1000f, r.a.postMs / 1000f, r.b.mergeMs / 1000f, r.b.postMs / 1000f);
        note(info);
    }

    /**
     * Renders {@code job} (the page is drawn again at each stage and with the result). A job asked for while another one runs
     * waits and replaces any earlier waiting one: turning a slider several times renders only its last value.
     */
    private void start(Job job) {
        synchronized (ScamLabFragment.class) {
            if (shown != null && shown.id.equals(job.id)) return;
            if (running != null) {
                next = running.id.equals(job.id) ? null : job;
                return;
            }
            runError = null;
            runStage = null;
            running = job;
        }
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            Job j = job;
            while (j != null) {
                try {
                    final ScamLabRun.Result r = ScamLabRun.run(app, j.shot, j.crop, j.a, j.b, text -> {
                        runStage = text;
                        redraw();
                    });
                    synchronized (ScamLabFragment.class) { shown = j; shownResult = r; runError = null; }
                } catch (Throwable e) {
                    runError = Lang.t(app, "Не получилось: ", "Failed: ") + e.getMessage();
                    synchronized (ScamLabFragment.class) { shown = j; }
                }
                runStage = null;
                synchronized (ScamLabFragment.class) {
                    j = next;
                    next = null;
                    running = j;
                }
                redraw();
            }
        }, "scam-lab").start();
    }

    private void redraw() {
        main.post(() -> {
            if (root == null || !isAdded() || !"result".equals(arg(PAGE))) return;
            // P294: a stage or a result never rebuilds the page under a finger on a slider (that ends the drag, and its
            // cancel set the value in the middle of the rebuild); it waits for the release
            if (dragging) {
                redrawWaiting = true;
                return;
            }
            render();
        });
    }

    @Override public void onDestroyView() {
        main.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
