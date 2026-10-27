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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.EditTextPreference;
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
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamMergeStages;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.settings.DevKeys;
import com.particlesdevs.photoncamera.settings.FullResProfiles;
import com.particlesdevs.photoncamera.settings.MotionMode;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;
import com.particlesdevs.photoncamera.settings.ScamDevFile;
import com.particlesdevs.photoncamera.settings.SettingsAvailability;
import com.particlesdevs.photoncamera.settings.SettingsNumericRules;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference;
import com.particlesdevs.photoncamera.util.Lang;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private static final String PAGE = "page", DIR = "dir", GROUP = "group", KEY = "key", VALUE = "value", TYPE = "type", EXTRA = "extra";

    /**
     * The settings pages the lab offers, in the settings' order (owner, 2026-10-21: «в лаборатории у нас не все пункты есть»): every
     * SHDR page - «Кадры светов и теней» too: the saved burst holds its highlight and shadow frames and the worker merges them
     * again with the side's values (its rows that only plan the capture are in {@link #AT_SHOT}) -, the noise model and the
     * photo size (their rows fixed at the shot said so, the downsampler and Sabre 2x re-run), the «Движение» mode's merge
     * values and the whole «Цвет и стиль» (its warmth and saturation sit beside its pages).
     */
    static final String[] GROUPS = {"scam_hybrid_photo_screen", "scam_hybrid_why_sharp_screen", "scam_hybrid_why_tone_screen",
            "scam_hybrid_capture_screen", "scam_hybrid_merge_screen", "noise_model_screen", "photo_size_screen",
            "scam_hybrid_motion_screen", "root_colour"};
    /**
     * Settings fixed at the shot: what the capture plan took (the exposures and counts of the highlight / shadow frames, the
     * «Движение» shutter, flicker and ring frames), what the burst holds (its noise model, its grid and output size, the crop
     * kept, the remosaic) and the colour transform the shot's Parameters were built with («Цветовой метод»): offered, said so.
     */
    static final java.util.Set<String> AT_SHOT = new java.util.HashSet<>(java.util.Arrays.asList("pref_scam_hybrid_output",
            "pref_crop_upscale", "pref_scam_hybrid_noise_photon", "pref_scam_hybrid_noise_readout", "pref_noise_model_profile_key",
            "pref_scam_hybrid_mosaic_remosaic", "pref_scam_hybrid_bento_factor", "pref_scam_hybrid_bento_trigger",
            "pref_scam_hybrid_shasta_frames", "pref_scam_hybrid_shasta_ev", "pref_scam_hybrid_mm_shutter",
            "pref_scam_hybrid_mm_flicker", "pref_scam_hybrid_mm_zsl_frames", "pref_color_method_key"));
    /**
     * Settings that plan the highlight / shadow frames at the shot and are applied again in the merge (worker bento, bentoFrames,
     * shastaEnable, shastaForce, shastaMaxRatio): on a saved burst they can only drop or limit the frames it holds - a value that
     * asks for more cannot add frames the shot did not take. Said beside the row, its controls and an A = B result.
     */
    static final java.util.Set<String> BURST_FRAMES = new java.util.HashSet<>(java.util.Arrays.asList("pref_scam_hybrid_bento",
            "pref_scam_hybrid_bento_frames", "pref_scam_hybrid_shasta", "pref_scam_hybrid_shasta_mode",
            "pref_scam_hybrid_shasta_max_ratio", "pref_scam_hybrid_mm_bento", "pref_scam_hybrid_mm_shasta"));

    /**
     * The lab's «Для разработчика» page: the scam_dev.txt lines that change the photo (DevKeys.photo), each compared as
     * {@link ScamLabRun#DEV} + its line (ScamLabRun reads them as the side's lines, «Сохранить B» writes them into the file).
     */
    static final String DEV = ScamLabRun.DEV, DEV_GROUP = "lab_dev_lines";
    /**
     * Settings a fragment cannot show as the whole photo would: a fragment's exposure comes from the whole frame's statistics,
     * measured with the shot's colour of whyLow (warmth, tint, the scene light kept, the calibration: WhyTone.colourMatrix) and
     * the shot's gain clamp (ae_clamp_stack): on the whole photo the AE measures again with the side's value. The page says so.
     */
    static final java.util.Set<String> FRAGMENT_STATS = new java.util.HashSet<>(java.util.Arrays.asList(
            "pref_scam_hybrid_colour_warmth", "pref_scam_hybrid_colour_tint", "pref_scam_hybrid_colour_keep_warm",
            "pref_scam_hybrid_colour_keep_cool", "pref_scam_hybrid_colour_cal_red_hue", "pref_scam_hybrid_colour_cal_red_sat",
            "pref_scam_hybrid_colour_cal_green_hue", "pref_scam_hybrid_colour_cal_green_sat", "pref_scam_hybrid_colour_cal_blue_hue",
            "pref_scam_hybrid_colour_cal_blue_sat", DEV + "ae_clamp_stack"));

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
    /** The stage view's map (ScamMergeStages kind) and donor frame (-1: the first). */
    private String showStage = ScamMergeStages.ROBUST;
    private int showFrame = -1;
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * P363: the lasting grant of Download/SCAMERA - the shots of an earlier installation of the app are read through it (LabFiles);
     * offered where one cannot be read.
     */
    private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> folderGrant = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null || getContext() == null) return;
                try {
                    requireContext().getContentResolver().takePersistableUriPermission(uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (RuntimeException e) {
                    com.particlesdevs.photoncamera.util.Log.w("SCAM_LAB", "folder grant not kept: " + e);
                }
                final boolean ok = com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.grantPresent();
                Toast.makeText(requireContext(), ok ? Lang.t(getContext(), "Папка открыта: снимки прошлой установки читаются", "Folder open: the earlier installation's shots can be read")
                        : Lang.t(getContext(), "Это не папка Download/SCAMERA", "This is not the Download/SCAMERA folder"), Toast.LENGTH_LONG).show();
                ScamLabRun.forget();
                thumbs.evictAll();
                shotPhotoPath = null;
                if (root != null) render();
            });

    /** P363: the «open the folder» button under a note that a shot cannot be read (none once the grant is there). */
    private void grantButton() {
        if (com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.grantPresent()) return;
        TextView b = button(Lang.t(getContext(), "Открыть доступ к папке Download/SCAMERA", "Open the Download/SCAMERA folder"), true,
                () -> folderGrant.launch(com.particlesdevs.photoncamera.util.ScameraFolder.grantHint()));
        b.setTag("lab_folder_grant");
        body.addView(b, space(-1, -2, 8));
    }
    /** P294: a render is running; a slider is under a finger; a stage redraw waits for its release. */
    private boolean rendering, dragging, redrawWaiting;
    /** P363: a shot of the list could not be read without the folder grant. */
    private boolean unreadable;

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
            else if ("pipeline".equals(page)) renderPipeline();
            else if ("sandbox".equals(page)) renderSandbox();
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
        unreadable = false;
        caption(Lang.t(getContext(), "Снимки · Download/SCAMERA/Lab", "Shots · Download/SCAMERA/Lab"));
        if (shots.isEmpty()) {
            note(Lang.t(getContext(), "Пока нет снимков. Включите переключатель выше и сделайте снимок SHDR.", "No shots yet. Turn on the switch above and take an SHDR shot."));
        } else {
            LinearLayout card = card();
            boolean first = true;
            for (File dir : shots) {
                if (!first) divider(card);
                first = false;
                card.addView(shotRow(dir));
            }
            note(Lang.t(getContext(), "Удерживайте снимок, чтобы удалить его (серия, параметры и превью).", "Hold a shot to delete it (burst, parameters and preview)."));
            if (unreadable) {
                note(Lang.t(getContext(), "Часть снимков записана прошлой установкой приложения: Android не даёт их прочитать, пока приложению не открыта папка Download/SCAMERA.",
                        "Some shots were written by an earlier installation of the app: Android does not let them be read until the app has the Download/SCAMERA folder."));
                grantButton();
            }
        }
        keptSeries();
    }

    /**
     * P359 (owner, 2026-10-27: «Дай возможность в лаборатории использовать старые бурсты с новой обработкой/пайплайном»): the RAW
     * series «Сохранять RAW-серию» kept in Download/SCAMERA, opened in the lab without a copy (ScamLab.importSeries).
     */
    private void keptSeries() {
        final List<File> kept = ScamLab.keptSeries();
        if (kept.isEmpty()) return;
        caption(Lang.t(getContext(), "Сохранённые RAW-серии · Download/SCAMERA", "Kept RAW series · Download/SCAMERA"));
        LinearLayout card = card();
        boolean first = true;
        for (File f : kept) {
            if (!first) divider(card);
            first = false;
            final boolean own = ScamLab.keptMeta(f) != null;
            LinearLayout r = row();
            r.setPadding(dp(12), dp(12), dp(12), dp(12));
            r.setMinimumHeight(dp(60));
            LinearLayout labels = column();
            TextView head = text(f.getName(), 14.5f, TEXT);
            com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);
            labels.addView(head);
            TextView sub = text(size(f.length()) + " · " + (own ? Lang.t(getContext(), "с параметрами снимка", "with its shot's parameters")
                    : Lang.t(getContext(), "без параметров: цвет по снимку-образцу", "no parameters: colour from a template shot")), 12, SettingsStyle.SUMMARY);
            labels.addView(sub, space(-1, -2, 4));
            r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(text(Lang.t(getContext(), "Открыть", "Open"), 14, accent));
            r.setTag("lab_series_" + f.getName());
            r.setFocusable(true);
            r.setOnClickListener(v -> {
                final Context app = requireContext().getApplicationContext();
                Toast.makeText(app, Lang.t(app, "Открываю серию…", "Opening the series…"), Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try {
                        final File dir = ScamLab.importSeries(f);
                        main.post(() -> {
                            if (root == null || !isAdded()) return;
                            Bundle a = new Bundle();
                            a.putString(PAGE, "shot");
                            a.putString(DIR, dir.getAbsolutePath());
                            open(of(a));
                        });
                    } catch (Exception e) {
                        main.post(() -> {
                            if (getContext() == null) return;
                            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle(Lang.t(getContext(), "Серию не открыть", "The series cannot be opened"))
                                    .setMessage(String.valueOf(e.getMessage()))
                                    .setPositiveButton(android.R.string.ok, null).show();
                        });
                    }
                }, "scam-lab-import").start();
            });
            card.addView(r);
        }
        note(Lang.t(getContext(), "Серия открывается без копирования: в Lab появляется папка со ссылкой на файл. Серии, сохранённые этой версией, несут параметры своего снимка; более старые берут цвет и геометрию у последнего снимка лаборатории того же размера кадра (баланс белого — по самой серии) и обрабатываются с текущими настройками.",
                "A series opens without a copy: Lab gets a folder that names the file. Series kept by this version carry their shot's parameters; older ones take the colour and geometry of the newest lab shot of the same frame size (the white balance from the series itself) and are processed with the settings of now."));
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
            sub = s.meta.optString("device", "") + " · " + s.width + "×" + s.height + " · " + s.frames + Lang.t(getContext(), " кадр.", " frames") + " · " + size(s.bytes())
                    + (s.meta.has("source") ? " · " + s.meta.optString("source") : "")
                    + (s.now ? Lang.t(getContext(), " · текущие настройки", " · settings of now") : "");
        } catch (Exception e) {
            sub = Lang.t(getContext(), "Не читается: ", "Unreadable: ") + e.getMessage();
            if (e instanceof com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.Unreadable) unreadable = true;
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
        b = com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.decode(file, o);
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
            if (e instanceof com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.Unreadable) grantButton();
            return null;
        }
    }

    private Bitmap photo(ScamLabRun.Shot s) {
        final String path = new File(s.dir, ScamLab.PREVIEW).getAbsolutePath();
        if (!path.equals(shotPhotoPath)) {
            shotPhoto = com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.decode(new File(path), null);
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
        // P359: the settings the lab starts from (side A and every B): the shot's, or today's
        caption(Lang.t(getContext(), "Настройки обработки", "Processing settings"));
        LinearLayout base = row();
        final String[] baseNames = {Lang.t(getContext(), "Как снято", "As shot"), Lang.t(getContext(), "Текущие", "Of now")};
        for (int i = 0; i < 2; i++) {
            final boolean now = i == 1;
            TextView b = button(baseNames[i], s.now == now, () -> {
                if (s.now == now) return;
                ScamLab.settingsNow(s.dir, now);
                ScamLabRun.forget();
                render();
            });
            b.setTag(now ? "lab_settings_now" : "lab_settings_shot");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i == 0) lp.rightMargin = dp(8);
            base.addView(b, lp);
        }
        body.addView(base, space(-1, -2, 8));
        note(s.now ? Lang.t(getContext(), "A и B начинаются с настроек приложения сейчас (и строк scam_dev.txt сейчас): видно, как серия выглядит с сегодняшними настройками. Склейка и обработка — всегда этой версии приложения.",
                "A and B start from the app's settings of now (and today's scam_dev.txt lines): how the series looks with today's settings. The merge and processing are always this version's.")
                : Lang.t(getContext(), "A и B начинаются с настроек, с которыми сделан снимок. Склейка и обработка — всегда этой версии приложения, так что старая серия проходит через новый пайплайн.",
                "A and B start from the settings the shot was taken with. The merge and processing are always this version's, so an old series goes through the new pipeline."));
        if (s.templated)
            note(Lang.t(getContext(), "Серия сохранена без параметров своего снимка: цвет и геометрия взяты у снимка «" + s.meta.optString("template") + "», баланс белого — серый мир по самой серии. Цвет может отличаться от снятого.",
                    "The series was kept without its shot's parameters: the colour and geometry come from the shot «" + s.meta.optString("template") + "», the white balance from the grey world of the series. The colour may differ from the shot's."));
        if (new File(s.dir, ScamLab.PREVIEW).isFile())
            navigation("⇄", Lang.t(getContext(), "Тогда и сейчас", "Then and now"), Lang.t(getContext(),
                    "Фото снимка (обработка той версии) рядом с той же серией, склеенной и обработанной сейчас", "The shot's photo (that version's processing) beside the same series merged and processed now"),
                    () -> {
                        Bundle a = new Bundle();
                        a.putString(PAGE, "pipeline");
                        a.putString(DIR, arg(DIR));
                        open(of(a));
                    });
        sandboxCard(s);
        favouritesCard(s);
        caption(Lang.t(getContext(), "Что сравнить", "What to compare"));
        final PreferenceScreen all = tree();
        // P355: the section opened last comes first (as the reference app keeps its last category)
        final String last = labStore(requireContext()).getString(LAST_GROUP, null);
        final List<String> order = new ArrayList<>(Arrays.asList(GROUPS));
        if (last != null && order.remove(last)) order.add(0, last);
        for (String key : order) {
            final Preference g = all.findPreference(key);
            if (!(g instanceof PreferenceGroup)) continue;
            final String sub = key.equals(last) ? Lang.t(getContext(), "Открыт последним", "Opened last")
                    : g.getSummary() == null ? null : String.valueOf(g.getSummary());
            navigation(key.equals(last) ? "↻" : "☷", String.valueOf(g.getTitle()), sub, () -> openGroup(key));
        }
        // the scam_dev.txt lines that change the photo (the settings page builds its rows from the file, not from the tree)
        navigation("☷", devTitle(), Lang.t(getContext(), "Строки scam_dev.txt склейки и обработки, которые меняют фото",
                "The merge and processing lines of scam_dev.txt that change the photo"), () -> openGroup(DEV_GROUP));
    }

    /** P359: «Тогда и сейчас»: the shot's photo (A) and the series merged and processed now (B), on the shot's fragment. */
    private void renderPipeline() {
        page(Lang.t(getContext(), "Тогда и сейчас", "Then and now"), null);
        final ScamLabRun.Shot s = shot();
        if (s == null) return;
        subtitle.setText(when(s.dir.getName()) + (s.now ? Lang.t(getContext(), " · текущие настройки", " · settings of now") : Lang.t(getContext(), " · настройки снимка", " · the shot's settings")));
        subtitle.setVisibility(View.VISIBLE);
        final String id = s.dir.getAbsolutePath();
        final int[] crop = shares.containsKey(id) && shares.get(id) == 1 ? null : crops.containsKey(id) ? crops.get(id) : s.middleFragment();
        final Job job = new Job(s, crop, PIPELINE, new java.util.TreeMap<>(), new java.util.TreeMap<>());
        start(job);
        final ScamLabRun.Result r;
        final boolean busy;
        synchronized (ScamLabFragment.class) {
            busy = running != null || next != null;
            r = shown != null && shown.id.equals(job.id) ? shownResult : null;
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
            final String aText = Lang.t(getContext(), "A · тогда (фото снимка)", "A · then (the shot's photo)"), bText = Lang.t(getContext(), "B · сейчас", "B · now");
            LabCompareView compare = new LabCompareView(requireContext());
            compare.setTag("lab_compare_view");
            compare.setAccent(accent);
            compare.setSpace(r.b.photo.getWidth(), r.b.photo.getHeight());
            compare.setPair(r.a.photo, r.b.photo, aText, bText);
            final int height = Math.round(getResources().getDisplayMetrics().heightPixels * .55f);
            body.addView(compare, space(-1, height, 6));
            LinearLayout sides = row();
            final TextView[] b = new TextView[3];
            final String[] names = {Lang.t(getContext(), "До / после", "Before / after"), "A", "B"};
            for (int i = 0; i < 3; i++) {
                final int m = i;
                b[i] = button(names[i], i == 0, () -> {
                    if (m == 0) compare.setPair(r.a.photo, r.b.photo, aText, bText);
                    else compare.setSingle(m == 1 ? r.a.photo : r.b.photo, m == 1 ? aText : bText);
                    for (int k = 0; k < 3; k++) {
                        b[k].setTextColor(k == m ? 0xFF15131D : accent);
                        b[k].setBackground(shape(k == m ? accent : BG, k == m ? 0 : 0xFF626974, 10));
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, i == 0 ? 1.6f : 1f);
                if (i < 2) lp.rightMargin = dp(8);
                sides.addView(b[i], lp);
            }
            body.addView(sides, space(-1, -2, 8));
            note(Lang.t(getContext(), "A — фото, которое сохранила версия приложения при съёмке (не больше 1600 пикселей по стороне, поэтому на фрагменте оно мягче). B — та же серия сейчас: склейка " + r.b.mergeMs + " мс, обработка " + r.b.postMs + " мс.",
                    "A is the photo the app's version saved at the shot (at most 1600 px a side, so softer on a fragment). B is the same series now: merge " + r.b.mergeMs + " ms, processing " + r.b.postMs + " ms."));
            if (busy) note(runStage == null ? Lang.t(getContext(), "Пересчёт…", "Updating…") : runStage);
        }
    }

    private String devTitle() {
        final Preference g = tree().findPreference(DeveloperPreferences.SCREEN);
        return g == null || g.getTitle() == null ? Lang.t(getContext(), "Для разработчика", "For developers") : String.valueOf(g.getTitle());
    }

    private static int[] aligned(int[] c, ScamLabRun.Shot s) {
        final int w = Math.max(32, c[2]), h = Math.max(32, c[3]);
        final int x = Math.max(0, Math.min(s.width - w, c[0])), y = Math.max(0, Math.min(s.height - h, c[1]));
        return new int[]{x / 32 * 32, y / 32 * 32, w / 32 * 32, h / 32 * 32};
    }

    private void openGroup(String key) {
        labStore(requireContext()).edit().putString(LAST_GROUP, key).apply();
        Bundle a = new Bundle();
        a.putString(PAGE, "group");
        a.putString(DIR, arg(DIR));
        a.putString(GROUP, key);
        open(of(a));
    }

    /** The settings tree with each value at its default (a store that keeps nothing: inflating writes no setting). */
    private PreferenceScreen tree() {
        if (tree == null) tree = defaultsTree(requireContext().getApplicationContext());
        return tree;
    }

    /** As ShadeCatalog.ensureTree: the settings theme, a store that answers the defaults and keeps nothing. */
    static PreferenceScreen defaultsTree(Context app) {
        final Context c = new android.view.ContextThemeWrapper(app, R.style.Theme_Scamera_SettingsActivity);
        final PreferenceManager pm = new PreferenceManager(c);
        pm.setPreferenceDataStore(new PreferenceDataStore() {
            @Override public void putString(String key, String value) {}
            @Override public void putStringSet(String key, java.util.Set<String> values) {}
            @Override public void putInt(String key, int value) {}
            @Override public void putLong(String key, long value) {}
            @Override public void putFloat(String key, float value) {}
            @Override public void putBoolean(String key, boolean value) {}
        });
        final PreferenceScreen screen = pm.inflateFromResource(c, R.xml.preferences, null);
        pm.setPreferences(screen);
        return screen;
    }

    // ───── A group: its settings ─────

    private void renderGroup() {
        if (DEV_GROUP.equals(arg(GROUP))) {
            renderDevGroup();
            return;
        }
        final ScamLabRun.Shot s = shot();
        final Preference g = tree().findPreference(arg(GROUP));
        page(g == null ? "" : String.valueOf(g.getTitle()), Lang.t(getContext(), "Нажмите настройку, чтобы сравнить", "Tap a setting to compare"));
        if (s == null || !(g instanceof PreferenceGroup)) return;
        addGroup((PreferenceGroup) g, s, null);
    }

    /** «Для разработчика»: each line of DevKeys.photo, grouped as on the settings page. */
    private void renderDevGroup() {
        page(devTitle(), Lang.t(getContext(), "Нажмите строку, чтобы сравнить", "Tap a line to compare"));
        final ScamLabRun.Shot s = shot();
        if (s == null) return;
        note(Lang.t(getContext(), "Строки файла " + ScamDevFile.SHOWN + ", которые меняют склейку и обработку. «По умолчанию» — строки нет, работает значение из кода. "
                        + (s.dev == null ? "Снимок сделан до того, как лаборатория стала сохранять эти строки: A берёт файл как сейчас. " : "A — строки, с которыми сделан снимок. ")
                        + "«Сохранить B» записывает строку в файл.",
                "The lines of " + ScamDevFile.SHOWN + " that change the merge and the processing. «Default»: no line, the code's value applies. "
                        + (s.dev == null ? "The shot was taken before the lab kept these lines: A takes the file as it is now. " : "A: the lines the shot was taken with. ")
                        + "«Keep B» writes the line into the file."));
        String group = null;
        LinearLayout card = null;
        for (DevKeys.Key k : DevKeys.photo()) {
            if (!k.group.equals(group)) {
                caption(k.group);
                group = k.group;
                card = null;
            }
            if (card == null) card = card(); else divider(card);
            card.addView(settingRow(DEV + k.key, k.key, k.text, s));
        }
    }

    private void addGroup(PreferenceGroup group, ScamLabRun.Shot s, @Nullable LinearLayout card) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference p = group.getPreference(i);
            if (!p.isVisible() || hiddenFor(p.getKey(), s.prefs(), s.oppoTunedCcm())) continue;
            if (p instanceof PreferenceCategory && !hasRows((PreferenceGroup) p, s.prefs(), s.oppoTunedCcm())) continue; // no empty caption
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

    /**
     * A row the settings hide for what this shot was not taken with (SettingsAvailability.hidden): «Насыщенность при матрице
     * OPPO» on a shot rendered without the OPPO tuned matrix (WhyTone.ccmSatComp gives 1 there, A and B would match).
     */
    static boolean hiddenFor(@Nullable String key, Map<String, ?> shot, boolean oppoTunedCcm) {
        return key != null && new SettingsAvailability(shot).oppoTunedColor(oppoTunedCcm).hidden(key);
    }

    /** Whether a group shows anything in the lab: a page of its own, the film simulation or a row to compare. */
    static boolean hasRows(PreferenceGroup group, Map<String, ?> shot, boolean oppoTunedCcm) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference p = group.getPreference(i);
            if (!p.isVisible() || hiddenFor(p.getKey(), shot, oppoTunedCcm)) continue;
            if (p instanceof PreferenceScreen || p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmSimPreference
                    || comparable(p)) return true;
            if (p instanceof PreferenceGroup && hasRows((PreferenceGroup) p, shot, oppoTunedCcm)) return true;
        }
        return false;
    }

    // ───── What a setting is in the lab (Spec) ─────

    /** A tone curve, a LUT, a lens filter: settings of their own kind, compared too (owner, 2026-10-19). */
    static final String CURVE_ON = "pref_scam_hybrid_why_curve_on",
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
    static final String HYBRID = "pref_scam_hybrid_", DENOISE = "pref_scam_hybrid_denoise",
            ACES_ON = "pref_scam_hybrid_why_aces_on", UCHI_ON = "pref_scam_hybrid_why_uchi_on";
    /** A lens filter's tune control on the filter's page: LensFilters.tuneKey(id) + this + control (0: its parameter, else T_*). */
    static final String TUNE_SEP = "#";

    static final class Spec {
        /**
         * TEXT: a number list "a,b,c,d,e" (SettingsNumericRules.listBounds: {@link #count} values from min to max); NUMBER: a
         * scam_dev.txt number line (any number, null: no line).
         */
        static final int SWITCH = 0, LIST = 1, SLIDER = 2, CUSTOM = 3, TEXT = 4, NUMBER = 5;
        final String key;
        final int kind;
        String title = "", summary;
        CharSequence[] labels;
        String[] values;
        float min, max, step = 1f;
        boolean decimal;
        int count;
        Object def;
        /** The settings page of a CUSTOM setting (its editor). */
        String page;
        /** A {@link #DEV} line: the code's value without the line (DevKeys.Key.def; null: the app decides). */
        String devDefault;
        boolean dev;

        /** A developer switch's state without its line. */
        boolean devSwitchDefault() {
            return devDefault != null && !"0".equals(devDefault);
        }

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
        // the denoise tables («Таблица яркости / цвета»): a number or a number list with its bounds (SettingsNumericRules)
        if (p instanceof EditTextPreference)
            return SettingsNumericRules.bounds(p.getKey()) != null || SettingsNumericRules.listBounds(p.getKey()) != null;
        return CURVE.equals(p.getKey()) || LUT.equals(p.getKey()) || FILTER.equals(p.getKey());
    }

    @Nullable
    Spec spec(String key) {
        return spec(tree(), requireContext(), key);
    }

    @Nullable
    static Spec spec(PreferenceScreen tree, Context c, String key) {
        if (key != null && key.startsWith(DEV)) return devSpec(key);
        final Preference p = tree.findPreference(key);
        Spec s;
        if (tuneId(key) != null) {
            return tuneSpec(key, c);
        } else if (LUT.equals(key)) {
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
            s.page = "scam_hybrid_why_curves_screen";
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
        } else if (p instanceof EditTextPreference) {
            // the denoise tables: a tier's SNR (a number) and its five bands' strength / revert / outlier (a number list), each
            // read by ScamDenoise.readConfig through the lab's overlay; the default is the XML's (the defaults-only tree)
            final String text = ((EditTextPreference) p).getText();
            final double[] list = SettingsNumericRules.listBounds(key), one = SettingsNumericRules.bounds(key);
            if (list != null) {
                s = new Spec(key, Spec.TEXT);
                s.count = (int) list[0];
                s.min = (float) list[1];
                s.max = (float) list[2];
                s.decimal = list[3] != 1;
                s.def = text == null ? "" : text;
            } else if (one != null) {
                s = new Spec(key, Spec.SLIDER);
                s.min = (float) one[0];
                s.max = (float) one[1];
                s.decimal = one[2] != 1;
                s.step = s.decimal ? 0.1f : 1f;
                s.def = s.number((float) PreferenceNumber.read(text, s.min));
            } else return null;
        } else return null;
        if (p != null) {
            s.title = String.valueOf(p.getTitle());
            s.summary = p.getSummary() == null ? null : String.valueOf(p.getSummary());
        }
        return s;
    }

    /** A scam_dev.txt line of «Для разработчика» ({@link #DEV} + its line): a switch or a number, its value null without the line. */
    @Nullable
    static Spec devSpec(String key) {
        final DevKeys.Key k = DevKeys.find(key.substring(DEV.length()));
        if (k == null) return null;
        final Spec s = new Spec(key, k.kind == DevKeys.Kind.SWITCH ? Spec.SWITCH : Spec.NUMBER);
        s.title = k.key;
        s.summary = k.text;
        s.dev = true;
        s.devDefault = k.def;
        s.decimal = true;
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

    // ───── A lens filter's own parameter and fine tuning (P228, LensFilters.Tune) ─────

    /** The filter of a tune control's key, null for any other key. */
    @Nullable
    static String tuneId(String key) {
        if (key == null || !key.startsWith(LensFilters.TUNE_PREFIX)) return null;
        final int at = key.indexOf(TUNE_SEP);
        return at < 0 ? null : key.substring(LensFilters.TUNE_PREFIX.length(), at);
    }

    /** The control of a tune control's key: 0 the filter's parameter, else LensFilters.T_*. */
    static int tuneControl(String key) {
        return (int) PreferenceNumber.read(key.substring(key.indexOf(TUNE_SEP) + 1), 0);
    }

    /** The tune controls of filter {@code id} as the settings page shows them: its parameter (when it has one) and its fine controls. */
    static List<String> tuneKeys(@Nullable String id, @Nullable Context c) {
        final List<String> out = new ArrayList<>();
        if (id == null || id.isEmpty() || !LensFilters.known(id)) return out;
        if (LensFilters.paramName(c, id) != null) out.add(LensFilters.tuneKey(id) + TUNE_SEP + 0);
        for (int control : LensFilters.CONTROLS)
            if ((LensFilters.tunable(id) & control) != 0) out.add(LensFilters.tuneKey(id) + TUNE_SEP + control);
        return out;
    }

    private static Spec tuneSpec(String key, Context c) {
        final String id = tuneId(key);
        final int control = tuneControl(key);
        final String name = control == 0 ? LensFilters.paramName(c, id) : LensFilters.controlName(c, id, control);
        final Spec s = slider(key, LensFilters.name(c, id) + ": " + name, 0, 100, 1,
                String.valueOf(Math.round(LensFilters.Tune.defaults(id).get(control) * 100f)));
        s.summary = control == 0 ? null : LensFilters.controlHint(c, id, control);
        return s;
    }

    /** The setting's value at the shot (its default when it was not set; a CUSTOM one: {@link #AS_SHOT}). */
    static Object shotValue(Spec spec, ScamLabRun.Shot s) {
        if (spec.dev) return devValue(spec, ScamLabRun.devLines(s));
        return shotValue(spec, s.prefs());
    }

    /** A developer line's value in {@code lines}: a Boolean or a number text, null without the line. */
    @Nullable
    static Object devValue(Spec spec, Map<String, Float> lines) {
        final Float v = lines.get(spec.key.substring(DEV.length()));
        if (v == null) return null;
        return spec.kind == Spec.SWITCH ? (Object) (v != 0f) : ScamDevFile.number(v);
    }

    static Object shotValue(Spec spec, Map<String, ?> shot) {
        if (spec.kind == Spec.CUSTOM) return AS_SHOT;
        final String id = tuneId(spec.key);
        if (id != null) {
            final Object stored = shot.get(LensFilters.tuneKey(id));
            return String.valueOf(Math.round(LensFilters.Tune.parse(id, stored == null ? null : String.valueOf(stored)).get(tuneControl(spec.key)) * 100f));
        }
        final Object v = shot.get(spec.key);
        if (v == null) return spec.def;
        if (spec.kind == Spec.SWITCH && !(v instanceof Boolean)) return "1".equals(String.valueOf(v)) || "true".equalsIgnoreCase(String.valueOf(v));
        return v;
    }

    // ───── What a setting compares on a shot ─────

    /** The shot was taken in the «Движение» mode (its merge read the mode's own values, MotionMode). */
    static boolean motionShot(Map<String, ?> shot) {
        return PreferenceNumber.bool(shot.get(MotionMode.PREF), false);
    }

    /**
     * The key a row compares on a shot: on a «Движение» shot a hybrid key the mode replaces is the mode's own
     * pref_scam_hybrid_mm_&lt;key&gt; (PreferenceKeys.hybridValue / hybridSwitch read it there), null when the mode has no row
     * for it (a fixed value: the row does nothing on that shot); any other key is itself.
     */
    @Nullable
    static String effectiveKey(String key, boolean motionShot, PreferenceScreen tree) {
        if (!motionShot || key == null || !key.startsWith(HYBRID)) return key;
        final String k = key.substring(HYBRID.length());
        if (!MotionMode.replaces(k)) return key;
        final String mm = MotionMode.prefKey(k);
        return tree.findPreference(mm) != null ? mm : null;
    }

    /** The switch a setting acts under: its android:dependency, «Шумоподавление» for the denoise rows (SettingsAvailability). */
    @Nullable
    static String requires(String key, PreferenceScreen tree) {
        final Preference p = tree.findPreference(key);
        if (p != null && p.getDependency() != null) return p.getDependency();
        // «Порог дисперсии для усиления»: the boost switch rules it in SettingsAvailability (no android:dependency: in «Движение» the mode's own does)
        if (key.equals(HYBRID + "boost_threshold")) return HYBRID + "motion_boost";
        // «Кадры теней»'s rows: the same (no android:dependency since the mode greys the photo switch: its own rules there)
        if (Arrays.asList(HYBRID + "shasta_mode", HYBRID + "shasta_motion_max", HYBRID + "shasta_frames", HYBRID + "shasta_ev").contains(key))
            return HYBRID + "shasta";
        if ((key.startsWith(HYBRID + "dn_") && !key.equals(HYBRID + "dn_engine"))
                || key.equals(HYBRID + "why_bracket_denoise") || key.equals(HYBRID + "why_chroma_denoise")) return DENOISE;
        return null;
    }

    /** A switch at the shot (its default when the shot did not set it). */
    static boolean switchAt(String key, Map<String, ?> shot, PreferenceScreen tree) {
        final Preference p = tree.findPreference(key);
        return PreferenceNumber.bool(shot.get(key), p instanceof TwoStatePreference && ((TwoStatePreference) p).isChecked());
    }

    /**
     * What a comparison of {@code key} sets on both sides so that the setting acts on this shot (each then on the page with its
     * own A and B): the switch it depends on when the shot had it off; for ACES / Uchimura and their parameters the operator on
     * and the ones before it off (WhyTone.toneOperator: own curve, Uchimura, ACES - a parameter of an operator left off gave the
     * same pixels); for a film setting of a shot without film a stock (and a strength above 0: FilmSim.current is null else);
     * for the colour prior and the defringe of the highlights on a shot without the per-channel recovery, the recovery at 100
     * (ScamRgb runs them inside it; the clip band needs the burst's clip flags too, which such a shot does not hold).
     */
    static LinkedHashMap<String, Object> forced(String key, Map<String, ?> shot, PreferenceScreen tree) {
        final LinkedHashMap<String, Object> f = new LinkedHashMap<>();
        final List<String> own = Arrays.asList(keys(key));
        String dep = requires(key, tree);
        // on a «Движение» shot a switch the mode replaces is its own (motion_boost: mm_motion_boost), as the merge read it
        final String modeDep = dep == null ? null : effectiveKey(dep, motionShot(shot), tree);
        if (modeDep != null) dep = modeDep;
        if (dep != null && !own.contains(dep) && !switchAt(dep, shot, tree)) f.put(dep, true);
        final String operator = ACES_ON.equals(key) || UCHI_ON.equals(key) ? key : ACES_ON.equals(dep) || UCHI_ON.equals(dep) ? dep : null;
        if (operator != null) {
            if (switchAt(CURVE_ON, shot, tree)) f.put(CURVE_ON, false);
            if (ACES_ON.equals(operator) && switchAt(UCHI_ON, shot, tree)) f.put(UCHI_ON, false);
        }
        if (Arrays.asList(FilmSim.KEYS).contains(key) && !FilmSim.STOCK_KEY.equals(key)) {
            final Object stock = shot.get(FilmSim.STOCK_KEY);
            if (FilmSim.stock(stock == null ? null : String.valueOf(stock)) == null) f.put(FilmSim.STOCK_KEY, FilmSim.STOCKS[0].id);
            if (!FilmSim.STRENGTH_KEY.equals(key) && PreferenceNumber.read(shot.get(FilmSim.STRENGTH_KEY), 100) <= 0)
                f.put(FilmSim.STRENGTH_KEY, "100");
        }
        if ((key.equals(HYBRID + "highlight_chroma") || key.equals(HYBRID + "highlight_defringe")) && !recoveryAt(shot))
            f.put(RECOVERY, "100");
        return f;
    }

    static final String RECOVERY = "pref_scam_hybrid_highlight_recovery";

    /** The shot took the per-channel highlight recovery (its strength above 0; the merge then sent the clip flags). */
    static boolean recoveryAt(Map<String, ?> shot) {
        return PreferenceNumber.read(shot.get(RECOVERY), 100) > 0;
    }

    /**
     * Why a setting cannot change this shot's picture (null: it can, or it is {@link #AT_SHOT}, said apart): a «Движение» shot
     * without the mode's value of it ({@code effective} null), or the shot's other settings with the comparison's
     * {@link #forced} ones leave it out (SettingsAvailability, as the settings page explains a greyed row).
     */
    @Nullable
    static String inert(@Nullable Context c, String key, @Nullable String effective, Map<String, ?> shot, Map<String, Object> forced) {
        if (AT_SHOT.contains(key)) return null;
        if (effective == null)
            return Lang.t(c, "снимок сделан в режиме «Движение», а в нём эта настройка не действует.",
                    "the shot was taken in Motion mode, where this setting has no effect.");
        // the clip band reads the per-pixel clip flags, which the merge sent only with the recovery on at the shot
        if (effective.equals(HYBRID + "highlight_band") && !recoveryAt(shot))
            return Lang.t(c, "при съёмке «Восстановление светов по каналам» было 0: в серии нет попиксельных флагов клипа.",
                    "“Per-channel highlight recovery” was 0 at the shot: the burst holds no per-pixel clip flags.");
        final Map<String, Object> values = new HashMap<>(shot);
        values.putAll(forced);
        return new SettingsAvailability(values).reason(effective);
    }

    /** A TEXT value's numbers (its default's when it does not read as {@link Spec#count} numbers), null when neither does. */
    @Nullable
    static float[] numbers(Spec spec, @Nullable Object v) {
        return SettingsNumericRules.listValue(spec.key, v, SettingsNumericRules.listValue(spec.key, spec.def, null));
    }

    /** A TEXT value as stored: "a,b,c,d,e". */
    static String listText(Spec spec, float[] v) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) b.append(',');
            if (!spec.decimal) b.append(Math.round(v[i]));
            else b.append(String.format(Locale.ROOT, "%.4f", v[i]).replaceAll("0+$", "").replaceAll("\\.$", ""));
        }
        return b.toString();
    }

    /** A value as the settings show it. */
    String label(Spec spec, Object v) {
        if (v == null && spec.dev) {
            if (spec.devDefault == null) return Lang.t(getContext(), "по умолчанию (решает приложение)", "default (chosen by the app)");
            return Lang.t(getContext(), "по умолчанию", "default") + " (" + (spec.kind == Spec.SWITCH
                    ? (spec.devSwitchDefault() ? Lang.t(getContext(), "Вкл", "On") : Lang.t(getContext(), "Выкл", "Off")) : spec.devDefault) + ")";
        }
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
        // a «Движение» shot's merge read the mode's own value of a key it replaces: the row compares that one
        final String effective = effectiveKey(key, motionShot(s.prefs()), tree());
        final String compared = effective == null ? key : effective;
        final Spec spec = spec(compared);
        if (spec != null && spec.kind != Spec.CUSTOM) {
            String shotValue = Lang.t(getContext(), "При съёмке: ", "At the shot: ") + label(spec, shotValue(spec, s));
            if (AT_SHOT.contains(compared)) shotValue += Lang.t(getContext(), " · задаётся при съёмке", " · fixed at the shot");
            if (BURST_FRAMES.contains(compared))
                shotValue += Lang.t(getContext(), " · на серии только убирает или ограничивает снятые кадры", " · on the burst it only drops or limits the frames taken");
            if (!compared.equals(key)) shotValue += Lang.t(getContext(), " · снято в режиме «Движение»: его значение", " · shot in Motion mode: its value");
            labels.addView(text(shotValue, 12, accent), space(-1, -2, 6));
        }
        final String why = inert(getContext(), compared, effective, s.prefs(), forced(compared, s.prefs(), tree()));
        if (why != null) {
            TextView w = text(Lang.t(getContext(), "На этом снимке не действует: ", "No effect on this shot: ") + why, 12, SettingsStyle.WARN);
            w.setLineSpacing(dp(3), 1);
            labels.addView(w, space(-1, -2, 6));
        }
        final String line = ScamLabRun.burstLine(s, compared);
        if (line != null) {
            TextView w = text(burstFixed(line), 12, SettingsStyle.WARN);
            w.setLineSpacing(dp(3), 1);
            labels.addView(w, space(-1, -2, 6));
        }
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        // P355: a star puts the setting into «Избранное» at the top of every shot
        final boolean fav = favourites(requireContext()).contains(compared);
        final TextView star = text(fav ? "★" : "☆", 22, fav ? accent : MUTED);
        star.setGravity(Gravity.CENTER);
        star.setTag("lab_fav_" + compared);
        star.setContentDescription(Lang.t(getContext(), "В избранное", "Favourite"));
        star.setOnClickListener(v -> {
            final boolean on = toggleFavourite(requireContext(), compared);
            star.setText(on ? "★" : "☆");
            star.setTextColor(on ? accent : MUTED);
        });
        r.addView(star, new LinearLayout.LayoutParams(dp(40), dp(40)));
        r.addView(text("›", 25, MUTED));
        r.setTag("lab_setting_" + key);
        r.setFocusable(true);
        r.setOnClickListener(x -> openResult(compared, s));
        return r;
    }

    /** A setting the shot's burst fixed with its own worker line (ScamLabRun.burstLine): why it changes nothing there. */
    private String burstFixed(String line) {
        return Lang.t(getContext(), "На этой серии не меняется: склейка снимка задала её сама строкой «" + line + "» (повтор после сбоя или режим снимка).",
                "No change on this burst: the shot's merge set it itself with the line “" + line + "” (a retry after a failure or the shot's mode).");
    }

    // ───── The comparison: A and B, each setting of the page with its own controls ─────

    /** A comparison: the shot, the fragment and the settings of each side. */
    /** P359: the key of «Тогда и сейчас» (a Job that compares the shot's photo with the series processed now). */
    static final String PIPELINE = "lab:pipeline";
    /** P355: the key of the sandbox page's comparison. */
    static final String SANDBOX_KEY = "lab:sandbox";

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
            // P359: a shot's settings of now (their key) or as shot are another page
            page = shot.dir.getAbsolutePath() + "|" + shot.nowKey + "|" + (crop == null ? "whole" : java.util.Arrays.toString(crop)) + "|" + key;
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

    /**
     * The page of one setting: A as shot; B the setting at another value ({@link #suggested}), its linked ones as shot; what the
     * setting needs to act on this shot ({@link #forced}) set on both sides.
     */
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
        final LinkedHashMap<String, Object> forced = forced(key, s.prefs(), tree());
        for (Map.Entry<String, Object> e : forced.entrySet()) {
            put(args, "a", e.getKey(), e.getValue());
            put(args, "b", e.getKey(), e.getValue());
        }
        args.putStringArrayList(EXTRA, new ArrayList<>(forced.keySet()));
        // the curve compared is the one in the settings, switched on
        if (CURVE.equals(key)) put(args, "b", CURVE_ON, true);
        open(of(args));
    }

    /**
     * The keys of a setting's page: the setting, its {@link #LINKS}, what {@link #openResult} set on both sides and, on the lens
     * filter's page, the tune controls of the filter B shows (the shot's when B shows none).
     */
    private List<String> pageKeys(String key, ScamLabRun.Shot s) {
        final List<String> out = new ArrayList<>(Arrays.asList(keys(key)));
        final ArrayList<String> extra = getArguments() == null ? null : getArguments().getStringArrayList(EXTRA);
        if (extra != null) for (String k : extra) if (!out.contains(k)) out.add(k);
        if (FILTER.equals(key)) {
            final Object b = get("b", FILTER);
            String id = b == null ? "" : String.valueOf(b);
            if (id.isEmpty() || !LensFilters.known(id)) {
                final Object shot = s.pref(FILTER);
                id = shot == null ? "" : String.valueOf(shot);
            }
            for (String k : tuneKeys(id, getContext())) if (!out.contains(k)) out.add(k);
        }
        // a key the page has no value of yet (the tune of another filter picked for B): the shot's on both sides
        for (String k : out) {
            if (arg2("a", k) && arg2("b", k)) continue;
            final Spec spec = spec(k);
            if (spec == null) continue;
            if (!arg2("a", k)) put(getArguments(), "a", k, shotValue(spec, s));
            if (!arg2("b", k)) put(getArguments(), "b", k, shotValue(spec, s));
        }
        return out;
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
                if (at == null && spec.dev) return !spec.devSwitchDefault();
                return !(at instanceof Boolean ? (Boolean) at : "1".equals(String.valueOf(at)));
            case Spec.NUMBER: {
                // a developer number: twice its value (or the code's), 1 from 0 or without a known value
                Float v = ScamLabRun.devNumber(at);
                if (v == null) v = ScamLabRun.devNumber(spec.devDefault);
                return ScamDevFile.number(v == null || v == 0f ? 1f : v * 2f);
            }
            case Spec.LIST: {
                if (spec.values.length == 0) return at;
                int i = 0;
                for (int k = 0; k < spec.values.length; k++) if (spec.values[k].equals(String.valueOf(at))) i = k;
                return spec.values[(i + 1) % spec.values.length];
            }
            case Spec.CUSTOM:
                return IN_SETTINGS;
            case Spec.TEXT: {
                // each number a quarter of its range away, as a slider
                final float[] v = numbers(spec, at);
                if (v == null) return at;
                final float quarter = (spec.max - spec.min) / 4f;
                for (int i = 0; i < v.length; i++) v[i] = v[i] + quarter <= spec.max ? v[i] + quarter : Math.max(spec.min, v[i] - quarter);
                return listText(spec, v);
            }
            default: {
                float v;
                try { v = Float.parseFloat(String.valueOf(at)); } catch (NumberFormatException e) { v = Float.parseFloat(String.valueOf(spec.def)); }
                final float quarter = (spec.max - spec.min) / 4f;
                return spec.number(v + quarter <= spec.max ? v + quarter : Math.max(spec.min, v - quarter));
            }
        }
    }

    /**
     * The overrides of a side: a CUSTOM setting "as shot" is the shot's value, "in the settings" the stored one now; a lens
     * filter's tune controls join into the filter's stored tune (left as shot when they all are).
     */
    private java.util.TreeMap<String, Object> overrides(String side, String key, ScamLabRun.Shot s) {
        final java.util.TreeMap<String, Object> m = new java.util.TreeMap<>();
        // P355: side B carries the sandbox's pinned values (this page's own keys set below win)
        if ("b".equals(side)) m.putAll(sandbox(s));
        m.putAll(pageOverrides(side, key, s));
        return m;
    }

    /** The overrides of a side from this page's keys only (no sandbox). */
    private java.util.TreeMap<String, Object> pageOverrides(String side, String key, ScamLabRun.Shot s) {
        final java.util.TreeMap<String, Object> m = new java.util.TreeMap<>();
        final Map<String, ?> stored = prefs().getAll();
        final Map<String, LensFilters.Tune> tunes = new HashMap<>();
        for (String k : pageKeys(key, s)) {
            Object v = get(side, k);
            final String id = tuneId(k);
            if (id != null) {
                LensFilters.Tune t = tunes.get(id);
                if (t == null) tunes.put(id, t = shotTune(id, s));
                t.set(tuneControl(k), (float) PreferenceNumber.read(v, 50) / 100f);
                continue;
            }
            if (AS_SHOT.equals(v)) v = s.pref(k);
            else if (IN_SETTINGS.equals(v)) v = stored.get(k);
            else if (v == null && !arg2(side, k)) continue;
            m.put(k, v);
        }
        for (Map.Entry<String, LensFilters.Tune> e : tunes.entrySet())
            if (!e.getValue().serialize().equals(shotTune(e.getKey(), s).serialize()))
                m.put(LensFilters.tuneKey(e.getKey()), e.getValue().serialize());
        return m;
    }

    private static LensFilters.Tune shotTune(String id, ScamLabRun.Shot s) {
        final Object v = s.pref(LensFilters.tuneKey(id));
        return LensFilters.Tune.parse(id, v == null ? null : String.valueOf(v));
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
        for (String k : pageKeys(key, s)) {
            final Spec spec = spec(k);
            if (spec != null) controls(spec, s, !k.equals(key));
        }
        final LinkedHashMap<String, Object> forced = forced(key, s.prefs(), tree());
        if (!forced.isEmpty()) {
            final StringBuilder set = new StringBuilder();
            for (Map.Entry<String, Object> e : forced.entrySet()) {
                final Spec spec = spec(e.getKey());
                if (spec == null) continue;
                if (set.length() > 0) set.append("; ");
                set.append('«').append(spec.title).append("» — ").append(label(spec, e.getValue()))
                        .append(Lang.t(getContext(), " (при съёмке: ", " (at the shot: ")).append(label(spec, shotValue(spec, s))).append(')');
            }
            note(Lang.t(getContext(), "Чтобы настройка действовала на этом снимке, на обеих сторонах задано: ",
                    "For the setting to act on this shot, both sides set: ") + set
                    + Lang.t(getContext(), ". У каждого значения выше свои A и B.", ". Each has its own A and B above."));
        }
        if (key.startsWith(HYBRID + "mm_") && motionShot(s.prefs()))
            note(Lang.t(getContext(), "Снимок сделан в режиме «Движение»: его склейка читает значения этого режима, их лаборатория и сравнивает.",
                    "The shot was taken in Motion mode: its merge reads that mode's values, and the lab compares them."));
        if (crop != null && FRAGMENT_STATS.contains(key))
            note(Lang.t(getContext(), "На фрагменте экспозиция и тон берутся из статистики всего кадра, снятой с цветом и пределом усиления снимка: эту настройку она не учитывает, и разница на фрагменте может быть не такой, как на фото. Точно — «Весь кадр».",
                    "On a fragment the exposure and the tone come from the whole frame's statistics, measured with the shot's colour and gain clamp: they do not take this setting in, so the difference on a fragment may not be the photo's. Exactly: «Whole frame»."));
        devNotes(key, s, r);
        if (r != null) info(main, r, s);
        // P355: the sandbox - B of several settings at once
        final java.util.TreeMap<String, Object> box = sandbox(s);
        if (!box.isEmpty())
            note(Lang.t(getContext(), "B включает песочницу: " + box.size() + " закреплённых настроек (A — как снято).",
                    "B includes the sandbox: " + box.size() + " pinned settings (A as shot)."));
        LinearLayout tools = row();
        TextView pin = button(Lang.t(getContext(), "📌 В песочницу", "📌 Pin to sandbox"), false, () -> {
            final java.util.TreeMap<String, Object> b = pageOverrides("b", key, s);
            b.entrySet().removeIf(e -> java.util.Objects.equals(String.valueOf(e.getValue()), String.valueOf(pageOverrides("a", key, s).get(e.getKey())))
                    && pageOverrides("a", key, s).containsKey(e.getKey()));
            if (b.isEmpty()) {
                Toast.makeText(requireContext(), Lang.t(getContext(), "B совпадает с A: закреплять нечего", "B equals A: nothing to pin"), Toast.LENGTH_SHORT).show();
                return;
            }
            box.putAll(b);
            saveSandbox(s, box);
            Toast.makeText(requireContext(), Lang.t(getContext(), "В песочнице: ", "In the sandbox: ") + box.size(), Toast.LENGTH_SHORT).show();
        });
        pin.setTag("lab_pin");
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, -2, 1);
        pl.rightMargin = dp(8);
        tools.addView(pin, pl);
        TextView jpeg = button(Lang.t(getContext(), "Сохранить B (JPEG)", "Save B (JPEG)"), false, () -> saveJpeg(s, key, r));
        jpeg.setTag("lab_save_b");
        tools.addView(jpeg, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(tools, space(-1, -2, 12));
        final List<String> keys = pageKeys(key, s);
        boolean devOnly = true;
        for (String k : keys) devOnly &= k.startsWith(DEV);
        final boolean devPage = devOnly;
        // P156 / P189: with per-module settings the shot's settings are its module's profile: B goes there, not into the module
        // the settings edit now (the chip, another module's page, a shot taken with another lens)
        final String module = devPage ? null : keepModule(s);
        if (devPage) note(Lang.t(getContext(), "Это строка файла " + ScamDevFile.SHOWN + ": «Сохранить B» записывает её в файл (на все модули), «по умолчанию» — удаляет.",
                "This is a line of " + ScamDevFile.SHOWN + ": «Keep B» writes it into the file (for every module), «default» removes it."));
        final String keepText = devPage ? Lang.t(getContext(), "Сохранить B в scam_dev.txt", "Keep B in scam_dev.txt")
                : module != null ? Lang.t(getContext(), "Сохранить B в «" + FullResProfiles.label(module) + "»", "Keep B in “" + FullResProfiles.label(module) + "”")
                : Lang.t(getContext(), "Сохранить B в настройки", "Keep B in the settings");
        TextView keep = button(keepText, false, () -> {
            // P355: this page's B only (the sandbox has its own «Сохранить всё»)
            final boolean devFailed = !keepValues(pageOverrides("b", key, s), module);
            String done = "«" + main.title + "»: " + label(main, get("b", key));
            if (module != null) done += " → " + FullResProfiles.label(module);
            if (devFailed) done = Lang.t(getContext(), "Не удалось записать ", "Could not write ") + ScamDevFile.SHOWN;
            Toast.makeText(requireContext(), done, Toast.LENGTH_SHORT).show();
        });
        keep.setTag("lab_keep_value");
        body.addView(keep, space(-1, -2, 12));
    }

    /** Writes {@code b} into the settings ({@code module}'s profile) and its scam_dev.txt lines into the file; false: the file failed. */
    private boolean keepValues(Map<String, Object> b, @Nullable String module) {
        boolean prefsWritten = false, devFailed = false;
        if (module != null && !module.equals(PreferenceKeys.profiles().editing())) PreferenceKeys.profiles().activate(module);
        final SharedPreferences.Editor e = prefs().edit();
        for (Map.Entry<String, Object> en : b.entrySet()) {
            final Object v = en.getValue();
            if (en.getKey().startsWith(DEV)) {
                final Float n = ScamLabRun.devNumber(v);
                devFailed |= !ScamDevFile.set(requireContext().getApplicationContext(), en.getKey().substring(DEV.length()),
                        n == null ? null : ScamDevFile.number(n));
                continue;
            }
            prefsWritten = true;
            if (v == null) e.remove(en.getKey());
            else if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
            else if (v instanceof java.util.Set) {
                final java.util.Set<String> set = new java.util.HashSet<>();
                for (Object x : (java.util.Set<?>) v) set.add(String.valueOf(x));
                e.putStringSet(en.getKey(), set);
            } else e.putString(en.getKey(), String.valueOf(v));
        }
        if (prefsWritten) e.apply();
        return !devFailed;
    }

    // ───── P355 (owner, 2026-10-27: «Сделай лабораторию более удобной и практичной, посмотри как сделано в арк камере»): favourites,
    // the last section, a sandbox of several settings at once, B saved as a JPEG ─────

    static final String LAB_STORE = "scam_lab", FAVOURITES = "favourites", LAST_GROUP = "last_group", SANDBOX_FILE = "sandbox.json";
    /** The sandbox of each shot (folder path -> pinned B values), read from its folder once. */
    private static final Map<String, java.util.TreeMap<String, Object>> SANDBOXES = new HashMap<>();

    /** The lab's own small store (not the settings: no backup, no module profile). */
    static SharedPreferences labStore(Context c) {
        return c.getApplicationContext().getSharedPreferences(LAB_STORE, Context.MODE_PRIVATE);
    }

    /** The favourite settings, in the order they were starred. */
    static List<String> favourites(Context c) {
        final String v = labStore(c).getString(FAVOURITES, "");
        final List<String> out = new ArrayList<>();
        for (String k : v.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    /** Stars or unstars {@code key}; true when it is a favourite now. */
    static boolean toggleFavourite(Context c, String key) {
        final List<String> f = favourites(c);
        final boolean on = !f.remove(key);
        if (on) f.add(key);
        labStore(c).edit().putString(FAVOURITES, android.text.TextUtils.join("\n", f)).apply();
        return on;
    }

    /** The shot's sandbox (pinned B values: setting key -> value, null = unset). */
    static synchronized java.util.TreeMap<String, Object> sandbox(ScamLabRun.Shot s) {
        final String id = s.dir.getAbsolutePath();
        java.util.TreeMap<String, Object> box = SANDBOXES.get(id);
        if (box == null) {
            box = readSandbox(new File(s.dir, SANDBOX_FILE));
            SANDBOXES.put(id, box);
        }
        return box;
    }

    static java.util.TreeMap<String, Object> readSandbox(File f) {
        final java.util.TreeMap<String, Object> box = new java.util.TreeMap<>();
        if (!f.isFile()) return box;
        try {
            final org.json.JSONObject o = new org.json.JSONObject(new String(com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFiles.read(f), java.nio.charset.StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                final String k = it.next();
                final org.json.JSONArray e = o.getJSONArray(k);
                final String t = e.getString(0);
                box.put(k, "n".equals(t) ? null : "b".equals(t) ? (Object) e.getBoolean(1) : e.getString(1));
            }
        } catch (Exception e) {
            com.particlesdevs.photoncamera.util.Log.w("SCAM_LAB", "sandbox not read: " + e);
        }
        return box;
    }

    static synchronized void saveSandbox(ScamLabRun.Shot s, java.util.TreeMap<String, Object> box) {
        SANDBOXES.put(s.dir.getAbsolutePath(), box);
        final File f = new File(s.dir, SANDBOX_FILE);
        try {
            if (box.isEmpty()) {
                f.delete();
                return;
            }
            final org.json.JSONObject o = new org.json.JSONObject();
            for (Map.Entry<String, Object> e : box.entrySet()) {
                final Object v = e.getValue();
                o.put(e.getKey(), new org.json.JSONArray().put(v == null ? "n" : v instanceof Boolean ? "b" : "s").put(v == null ? "" : v instanceof Boolean ? v : String.valueOf(v)));
            }
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            com.particlesdevs.photoncamera.util.Log.w("SCAM_LAB", "sandbox not written: " + e);
        }
    }

    /** «Избранное»: the starred settings of every shot, at the top of a shot's page. */
    private void favouritesCard(ScamLabRun.Shot s) {
        final List<String> fav = favourites(requireContext());
        if (fav.isEmpty()) return;
        caption(Lang.t(getContext(), "Избранное", "Favourites"));
        LinearLayout card = null;
        for (String k : fav) {
            final Spec sp = spec(k);
            if (sp == null) continue;
            if (card == null) card = card(); else divider(card);
            card.addView(settingRow(k, sp.title, sp.summary, s));
        }
    }

    /** «Песочница»: the pinned values of this shot, each removable, compared all at once and kept all at once. */
    private void sandboxCard(ScamLabRun.Shot s) {
        final java.util.TreeMap<String, Object> box = sandbox(s);
        if (box.isEmpty()) return;
        caption(Lang.t(getContext(), "Песочница · B из нескольких настроек", "Sandbox · B of several settings"));
        LinearLayout card = card();
        boolean first = true;
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(box).entrySet()) {
            if (!first) divider(card);
            first = false;
            final Spec sp = spec(e.getKey());
            LinearLayout r = row();
            r.setPadding(dp(12), dp(10), dp(6), dp(10));
            LinearLayout labels = column();
            labels.addView(text(sp == null ? e.getKey() : sp.title, 14.5f, TEXT));
            labels.addView(text(sp == null ? String.valueOf(e.getValue()) : label(sp, e.getValue()), 12, accent), space(-1, -2, 4));
            r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            final TextView x = text("✕", 18, MUTED);
            x.setGravity(Gravity.CENTER);
            x.setTag("lab_unpin_" + e.getKey());
            x.setOnClickListener(v -> {
                box.remove(e.getKey());
                saveSandbox(s, box);
                ScamLabRun.forget();
                render();
            });
            r.addView(x, new LinearLayout.LayoutParams(dp(40), dp(40)));
            card.addView(r);
        }
        navigation("⇄", Lang.t(getContext(), "Сравнить песочницу", "Compare the sandbox"),
                Lang.t(getContext(), "A — как снято, B — со всеми закреплёнными значениями", "A as shot, B with every pinned value"), () -> {
                    Bundle a = new Bundle();
                    a.putString(PAGE, "sandbox");
                    a.putString(DIR, arg(DIR));
                    open(of(a));
                });
    }

    /** P355: the sandbox page - A as shot, B with every pinned value; keep them all or clear the sandbox. */
    private void renderSandbox() {
        page(Lang.t(getContext(), "Песочница", "Sandbox"), null);
        final ScamLabRun.Shot s = shot();
        if (s == null) return;
        final java.util.TreeMap<String, Object> box = sandbox(s);
        subtitle.setText(when(s.dir.getName()) + " · " + box.size() + Lang.t(getContext(), " настроек", " settings"));
        subtitle.setVisibility(View.VISIBLE);
        if (box.isEmpty()) {
            note(Lang.t(getContext(), "Песочница пуста: на странице настройки нажмите «В песочницу».", "The sandbox is empty: press «Pin to sandbox» on a setting's page."));
            return;
        }
        final String id = s.dir.getAbsolutePath();
        final int[] crop = shares.containsKey(id) && shares.get(id) == 1 ? null : crops.containsKey(id) ? crops.get(id) : s.middleFragment();
        final Job job = new Job(s, crop, SANDBOX_KEY, new java.util.TreeMap<>(), new java.util.TreeMap<>(box));
        start(job);
        final ScamLabRun.Result r;
        synchronized (ScamLabFragment.class) {
            r = shown != null && shown.id.equals(job.id) ? shownResult : null;
        }
        if (r == null) {
            LinearLayout wait = column();
            wait.setGravity(Gravity.CENTER);
            wait.setPadding(0, dp(40), 0, dp(40));
            if (runError == null) {
                ProgressBar bar = new ProgressBar(requireContext());
                bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
                wait.addView(bar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            TextView st = text(runError != null ? runError : runStage == null ? Lang.t(getContext(), "Подготовка…", "Preparing…") : runStage, 14,
                    runError != null ? SettingsStyle.WARN : MUTED);
            st.setGravity(Gravity.CENTER);
            st.setTag("lab_status");
            wait.addView(st, space(-2, -2, 14));
            body.addView(wait, new LinearLayout.LayoutParams(-1, -2));
        } else {
            final String aText = Lang.t(getContext(), "A · как снято", "A · as shot"), bText = Lang.t(getContext(), "B · песочница", "B · sandbox");
            LabCompareView compare = new LabCompareView(requireContext());
            compare.setTag("lab_compare_view");
            compare.setAccent(accent);
            compare.setSpace(r.a.photo.getWidth(), r.a.photo.getHeight());
            final int height = Math.round(getResources().getDisplayMetrics().heightPixels * .48f);
            FrameLayout frame = new FrameLayout(requireContext());
            frame.addView(compare, new FrameLayout.LayoutParams(-1, height));
            body.addView(frame, space(-1, height, 6));
            modes(r, compare, aText, bText);
        }
        final String module = keepModule(s);
        TextView keepAll = button(module != null ? Lang.t(getContext(), "Сохранить всё в «" + FullResProfiles.label(module) + "»", "Keep all in “" + FullResProfiles.label(module) + "”")
                : Lang.t(getContext(), "Сохранить всё в настройки", "Keep all in the settings"), true, () -> {
            final boolean ok = keepValues(box, module);
            Toast.makeText(requireContext(), ok ? Lang.t(getContext(), "Сохранено настроек: " + box.size(), "Settings kept: " + box.size())
                    : Lang.t(getContext(), "Не удалось записать ", "Could not write ") + ScamDevFile.SHOWN, Toast.LENGTH_SHORT).show();
        });
        keepAll.setTag("lab_keep_all");
        body.addView(keepAll, space(-1, -2, 12));
        TextView clear = button(Lang.t(getContext(), "Очистить песочницу", "Clear the sandbox"), false, () -> {
            saveSandbox(s, new java.util.TreeMap<>());
            ScamLabRun.forget();
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        });
        clear.setTag("lab_sandbox_clear");
        body.addView(clear, space(-1, -2, 8));
    }

    /** P355: side B of a result as a JPEG in the shot's folder (the screen's size, at most 4096 px a side). */
    private void saveJpeg(ScamLabRun.Shot s, String key, @Nullable ScamLabRun.Result r) {
        if (r == null || r.b == null || r.b.photo == null || r.b.photo.isRecycled()) {
            Toast.makeText(requireContext(), Lang.t(getContext(), "B ещё не готово", "B is not ready yet"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String tag = key.replaceAll("[^A-Za-z0-9_]", "_").replaceFirst("^pref_scam_hybrid_", "");
        final File out = new File(s.dir, "B_" + tag + "_" + new java.text.SimpleDateFormat("HHmmss", Locale.ROOT).format(new java.util.Date()) + ".jpg");
        final android.graphics.Bitmap photo = r.b.photo;
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String msg;
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(out)) {
                photo.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, o);
                msg = Lang.t(app, "Сохранено: ", "Saved: ") + out.getAbsolutePath();
            } catch (Exception e) {
                msg = Lang.t(app, "Не удалось сохранить: ", "Saving failed: ") + e.getMessage();
            }
            final String m = msg;
            main.post(() -> Toast.makeText(app, m, Toast.LENGTH_LONG).show());
        }, "scam-lab-jpeg").start();
    }

    /**
     * The module profile «Сохранить B» writes into while per-module settings are on: the shot's (meta "module"), else (a shot
     * taken before it was kept, or with per-module settings off) the one the settings edit now; null: the common settings.
     */
    @Nullable
    private static String keepModule(ScamLabRun.Shot s) {
        try {
            if (!PreferenceKeys.isPerLensSettingsOn()) return null;
            return s.module != null ? s.module : PreferenceKeys.profiles().editing();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The scam_dev.txt lines of the page's settings: the shot's the lab left out (ScamLabRun: A and B with their own values), one
     * in the file now (in the camera it wins over the setting and over «Сохранить B»), a shot taken before the lines were kept.
     */
    private void devNotes(String key, ScamLabRun.Shot s, @Nullable ScamLabRun.Result r) {
        final List<String> keys = pageKeys(key, s);
        final List<String> now = ScamLabRun.overriding(keys, PreferenceKeys.scamDevLines());
        final List<String> shot = r != null ? r.devMasked : ScamLabRun.overriding(keys, ScamLabRun.devLines(s));
        if (!now.isEmpty())
            note(Lang.t(getContext(), "Сейчас в scam_dev.txt есть строка " + quoted(now) + ": в камере действует она, а не эта настройка (и не «Сохранить B»). Лаборатория её не применяет: A и B — со своими значениями.",
                    "scam_dev.txt has the line " + quoted(now) + " now: in the camera it applies instead of this setting (and of «Keep B»). The lab leaves it out: A and B have their own values."));
        else if (!shot.isEmpty())
            note(Lang.t(getContext(), "При съёмке в scam_dev.txt была строка " + quoted(shot) + ": лаборатория её не применяет, A и B — со своими значениями.",
                    "At the shot scam_dev.txt had the line " + quoted(shot) + ": the lab leaves it out, A and B have their own values."));
        if (r != null && r.devNow)
            note(Lang.t(getContext(), "Снимок сделан до того, как лаборатория стала сохранять строки scam_dev.txt: обе стороны берут файл как сейчас, A может отличаться от снятого.",
                    "The shot was taken before the lab kept the scam_dev.txt lines: both sides take the file as it is now, A may differ from the shot."));
    }

    private static String quoted(List<String> lines) {
        final StringBuilder b = new StringBuilder();
        for (String l : lines) {
            if (b.length() > 0) b.append(", ");
            b.append('«').append(l).append('»');
        }
        return b.toString();
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
        // «Этапы склейки» (owner, 2026-10-25: «Дай возможность в лаборатории просмотра полностью всех этапов склейки в том числе
        // реджект карты»): the worker's stage maps of each side (ScamMergeStages), one stage and donor at a time, A against B
        String[] names = {Lang.t(getContext(), "До / после", "Before / after"), Lang.t(getContext(), "Разница ×4", "Difference ×4"),
                Lang.t(getContext(), "Карта кадров", "Frame map"), Lang.t(getContext(), "Одиночный", "Single"),
                Lang.t(getContext(), "Этапы склейки", "Merge stages")};
        final LinearLayout modes2 = row();
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
            if (i != 2 && i < names.length - 1) lp.rightMargin = dp(6);
            (i < 3 ? modes : modes2).addView(buttons[i], lp);
        }
        final LinearLayout stageKinds = row(), stageFrames = row();
        final HorizontalScrollView kindScroll = new HorizontalScrollView(requireContext()), frameScroll = new HorizontalScrollView(requireContext());
        kindScroll.setHorizontalScrollBarEnabled(false);
        frameScroll.setHorizontalScrollBarEnabled(false);
        kindScroll.addView(stageKinds);
        frameScroll.addView(stageFrames);
        final java.util.Map<String, Bitmap> stageBitmaps = new java.util.HashMap<>();
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
            kindScroll.setVisibility(showMode == 4 ? View.VISIBLE : View.GONE);
            frameScroll.setVisibility(View.GONE);
            if (showMode == 4) {
                stages(r, compare, explain, stageKinds, stageFrames, frameScroll, stageBitmaps, apply[0]);
            } else if (showMode == 3) {
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
        body.addView(modes2, space(-1, -2, 6));
        body.addView(sides, space(-1, -2, 8));
        body.addView(kindScroll, space(-1, -2, 8));
        body.addView(frameScroll, space(-1, -2, 6));
        body.addView(explain, space(-1, -2, 8));
    }

    /** The stage view: the kinds either side has, the donors of a per-donor kind, the chosen map of A against B. */
    private void stages(ScamLabRun.Result r, LabCompareView compare, TextView explain, LinearLayout kinds, LinearLayout frames,
                        View frameScroll, java.util.Map<String, Bitmap> cache, Runnable redraw) {
        final ScamMergeStages a = r.a.stages, b = r.b.stages;
        final java.util.List<String> present = new java.util.ArrayList<>();
        for (ScamMergeStages s : new ScamMergeStages[]{a, b}) if (s != null) for (String k : s.kinds()) if (!present.contains(k)) present.add(k);
        kinds.removeAllViews();
        frames.removeAllViews();
        if (present.isEmpty()) {
            explain.setText(Lang.t(getContext(), "Этапов склейки нет: в серии один кадр или склейка не дошла до отбраковки.",
                    "No merge stages: the burst has one frame or the merge did not reach the rejection."));
            compare.setPair(r.a.photo, r.b.photo, "A", "B");
            return;
        }
        // the merge order: base, rejection, weight, cutoff, F6, Bento, accepted, merged (P344: the cutoff only from a worker that
        // writes it; an older worker's stages list none)
        java.util.Collections.sort(present, (x, y) -> Integer.compare(stageRank(x), stageRank(y)));
        if (!present.contains(showStage)) showStage = present.contains(ScamMergeStages.ROBUST) ? ScamMergeStages.ROBUST : present.get(0);
        for (String k : present) {
            final TextView t = button(stageTitle(k), k.equals(showStage), () -> { showStage = k; redraw.run(); });
            t.setTag("lab_stage_" + k);
            t.setTextSize(13);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            kinds.addView(t, lp);
        }
        int frame = -1;
        if (ScamMergeStages.perDonor(showStage)) {
            final java.util.List<Integer> list = new java.util.ArrayList<>();
            final java.util.Map<Integer, Integer> roles = new java.util.HashMap<>();
            for (ScamMergeStages s : new ScamMergeStages[]{a, b}) if (s != null) for (ScamMergeStages.Map m : s.maps)
                if (m.name.equals(showStage) && m.frame >= 0 && !list.contains(m.frame)) { list.add(m.frame); roles.put(m.frame, m.role); }
            if (!list.contains(showFrame) && !list.isEmpty()) showFrame = list.get(0);
            frame = showFrame;
            for (int f : list) {
                final int role = roles.get(f);
                final String suffix = role == 5 ? Lang.t(getContext(), " · УК", " · US") : role == 3 ? Lang.t(getContext(), " · Д", " · L") : "";
                final TextView t = button(Lang.t(getContext(), "Кадр ", "Frame ") + f + suffix, f == showFrame, () -> { showFrame = f; redraw.run(); });
                t.setTag("lab_stage_frame_" + f);
                t.setTextSize(13);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(6);
                frames.addView(t, lp);
            }
            frameScroll.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        }
        final Bitmap pa = stageBitmap(a, "a", showStage, frame, cache), pb = stageBitmap(b, "b", showStage, frame, cache);
        final String title = stageTitle(showStage) + (frame >= 0 ? Lang.t(getContext(), ", кадр ", ", frame ") + frame : "");
        if (pa != null && pb != null) compare.setPair(pa, pb, "A", "B");
        else if (pa != null || pb != null) compare.setSingle(pa != null ? pa : pb, (pa != null ? "A: " : "B: ") + title);
        explain.setText(stageExplain(showStage) + (pa == null || pb == null
                ? Lang.t(getContext(), " Этот этап есть только у одной стороны.", " Only one side has this stage.") : ""));
    }

    private static int stageRank(String kind) {
        final String[] order = {ScamMergeStages.BASE, ScamMergeStages.RAW, ScamMergeStages.ROBUST, ScamMergeStages.CUTOFF,
                ScamMergeStages.FLOW, ScamMergeStages.BENTO, ScamMergeStages.BLEND, ScamMergeStages.ACCEPTED, ScamMergeStages.MERGED};
        for (int i = 0; i < order.length; i++) if (order[i].equals(kind)) return i;
        return order.length;
    }

    @Nullable
    private static Bitmap stageBitmap(@Nullable ScamMergeStages s, String side, String kind, int frame, java.util.Map<String, Bitmap> cache) {
        if (s == null) return null;
        final ScamMergeStages.Map m = s.find(kind, frame);
        if (m == null) return null;
        final String id = side + "|" + kind + "|" + m.frame;
        Bitmap bmp = cache.get(id);
        if (bmp == null) cache.put(id, bmp = ScamMergeStages.bitmap(m, s.orient));
        return bmp;
    }

    private String stageTitle(String kind) {
        switch (kind) {
            case ScamMergeStages.BASE: return Lang.t(getContext(), "База", "Base");
            case ScamMergeStages.RAW: return Lang.t(getContext(), "Реджект", "Rejection");
            case ScamMergeStages.ROBUST: return Lang.t(getContext(), "Вес кадра", "Frame weight");
            case ScamMergeStages.CUTOFF: return Lang.t(getContext(), "Отсечение", "Cutoff");
            case ScamMergeStages.FLOW: return Lang.t(getContext(), "Сдвиг F6", "F6 offset");
            case ScamMergeStages.BENTO: return Lang.t(getContext(), "Маска Bento", "Bento mask");
            case ScamMergeStages.BLEND: return Lang.t(getContext(), "Смешивание УК", "US blend");
            case ScamMergeStages.ACCEPTED: return Lang.t(getContext(), "Принято", "Accepted");
            case ScamMergeStages.MERGED: return Lang.t(getContext(), "Склейка", "Merged");
            default: return kind;
        }
    }

    private String stageExplain(String kind) {
        switch (kind) {
            case ScamMergeStages.BASE: return Lang.t(getContext(), "Опорный кадр серии до склейки: один цвет на клетку 2×2, баланс по серому.",
                    "The burst's reference frame before the merge: one colour per 2×2 cell, grey-world balance.");
            // P344: the weights on a ramp with a plateau (ScamMergeStages.weight): white from 0.97 up, the noise-level dip cream
            case ScamMergeStages.RAW: return Lang.t(getContext(), "Карта реджекта до расширения: насколько клетка кадра совпала с базой. Белый — принята (0,97 и выше), кремовый и янтарный — разница на уровне шума, красный → чёрный — отклонена (движение, ошибка выравнивания, пересвет длинного кадра).",
                    "Rejection map before the spread: how well the frame's cell matched the base. White accepted (0.97 and up), cream and amber a noise-level difference, red to black rejected (motion, misalignment, a clipped longer frame).")
                    // P358: what the worker now dumps for the ultrashort and long frames
                    + Lang.t(getContext(), " У сверхкороткого кадра («УК») — его карта годности: где он совпал с базой (движение и мерцание — чёрным). У длинного кадра его собственный пересвет не показан как отказ.",
                    " For an ultrashort frame («US») its validity: where it matched the base (motion and flicker black). A long frame's own clipping is not drawn as rejection.");
            case ScamMergeStages.ROBUST: return Lang.t(getContext(), "Итоговый вес кадра в склейке: отказ растянут на клетки вокруг (5×5), учтены маска Bento и вес кадра. Белый — полный вес (0,97 и выше), кремовый → красный — вес снижен, чёрный — кадр здесь не вошёл.",
                    "The frame's final weight in the merge: rejection spread over the 5×5 cells around, Bento and the frame weight applied. White full weight (0.97 and up), cream to red a lower weight, black not merged here.")
                    + Lang.t(getContext(), " У сверхкороткого кадра — маска Bento × годность (максимум по блоку): белое там, где он заменяет света.",
                    " For an ultrashort frame: the Bento mask × validity (block maximum): white where it replaces the lights.");
            case ScamMergeStages.CUTOFF: return Lang.t(getContext(), "Отсечение после склейки: в каждой клетке наименьший средний итоговый вес среди обычных кадров серии, без цветовой шкалы. Белый — вошли все кадры, чёрный — хотя бы один кадр здесь отсечён (движение, отказ), серый — частично. Длинные кадры брекета не учитываются: в клипе они отключены всегда.",
                    "Cutoff after the merge: per cell the smallest mean final weight over the burst's normal frames, plain grey. White every frame merged, black at least one frame cut here (motion, rejection), grey partly. The bracket's long frames are left out: they always drop out where they clip.");
            case ScamMergeStages.FLOW: return Lang.t(getContext(), "Локальное доравнивание F6 поверх гомографии, |сдвиг| в RAW-пикселях: тёмно-синий — 0, жёлтый — 8 и больше.",
                    "F6 local alignment on top of the homography, |offset| in RAW pixels: dark blue 0, yellow 8 and more.");
            case ScamMergeStages.BENTO: return Lang.t(getContext(), "Маска Bento: где склейка берёт ультракороткие кадры вместо обычных (жёлтый — пересвет, берутся они).",
                    "Bento mask: where the merge takes the ultrashort frames instead of the normal ones (yellow: clipped, they are taken).");
            case ScamMergeStages.BLEND: return Lang.t(getContext(), "Вес смешивания ультракоротких кадров после склейки (P310): жёлтый — берутся они.",
                    "The ultrashort frames' blend weight after the merge (P310): yellow, they are taken.");
            case ScamMergeStages.ACCEPTED: return Lang.t(getContext(), "Средний вес обычных кадров в каждой клетке: сколько серии получила база. Белый — взяты все (0,97 и выше), красный → чёрный — почти только база (шум выше).",
                    "Mean weight of the normal frames per cell: how much of the burst the base got. White: all of them (0.97 and up), red to black: almost the base alone (more noise).");
            case ScamMergeStages.MERGED: return Lang.t(getContext(), "Результат склейки до постобработки, то же превью, что у базы: сравните шум и детали.",
                    "The merge before the post pipeline, the same preview as the base: compare noise and detail.");
            default: return "";
        }
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
        if (BURST_FRAMES.contains(spec.key))
            note += Lang.t(getContext(), " · на сохранённой серии действуют только меньшие значения или «Выкл»: кадры сняты при съёмке, добавить их нельзя",
                    " · on a saved burst only lower values or off act: the frames were taken at the shot, none can be added");
        final String line = ScamLabRun.burstLine(s, spec.key);
        if (line != null) note += Lang.t(getContext(), " · склейка снимка задала «" + line + "»: на этой серии не меняется", " · the shot's merge set “" + line + "”: no change on this burst");
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
                toggle.setChecked(value instanceof Boolean ? (Boolean) value
                        : value == null && spec.dev ? spec.devSwitchDefault() : "1".equals(String.valueOf(value)));
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
            case Spec.TEXT: {
                // a denoise table row: its numbers as the settings take them; «Done» renders the side again (after the key event:
                // the page is rebuilt, never inside the field's own callback, P294)
                head.addView(reset);
                final android.widget.EditText field = new android.widget.EditText(requireContext());
                field.setTag("lab_value_" + name + "_" + spec.key);
                field.setText(value == null ? "" : String.valueOf(value));
                field.setTextColor(TEXT);
                field.setTextSize(15);
                field.setSingleLine(true);
                field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
                field.setBackground(shape(BG, 0xFF626974, 10));
                field.setPadding(dp(10), dp(10), dp(10), dp(10));
                field.setOnEditorActionListener((v, action, event) -> {
                    if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                            && (event == null || event.getKeyCode() != android.view.KeyEvent.KEYCODE_ENTER)) return false;
                    final float[] parsed = SettingsNumericRules.listValue(spec.key, v.getText().toString(), null);
                    if (parsed == null) {
                        Toast.makeText(requireContext(), String.format(Locale.ROOT, Lang.t(getContext(), "Нужно %d чисел через запятую.", "Enter %d numbers separated by commas."),
                                spec.count), Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    final android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager)
                            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    final String text = listText(spec, parsed);
                    main.post(() -> { if (root != null && isAdded()) set.accept(text); });
                    return true;
                });
                r.addView(field, space(-1, -2, 8));
                TextView hint = text(String.format(Locale.ROOT, Lang.t(getContext(), "%d чисел через запятую, каждое от %s до %s; «Готово» на клавиатуре — пересчитать.",
                        "%d numbers separated by commas, each from %s to %s; Done on the keyboard renders again."), spec.count,
                        listText(spec, new float[]{spec.min}), listText(spec, new float[]{spec.max})), 12, MUTED);
                r.addView(hint, space(-1, -2, 6));
                break;
            }
            case Spec.NUMBER: {
                // a developer number line: empty = no line (the code's value); «Done» renders the side again (after the key event, P294)
                head.addView(reset);
                final android.widget.EditText field = new android.widget.EditText(requireContext());
                field.setTag("lab_value_" + name + "_" + spec.key);
                field.setText(value == null ? "" : String.valueOf(value));
                field.setHint(label(spec, null));
                field.setTextColor(TEXT);
                field.setTextSize(15);
                field.setSingleLine(true);
                field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
                field.setBackground(shape(BG, 0xFF626974, 10));
                field.setPadding(dp(10), dp(10), dp(10), dp(10));
                field.setOnEditorActionListener((v, action, event) -> {
                    if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                            && (event == null || event.getKeyCode() != android.view.KeyEvent.KEYCODE_ENTER)) return false;
                    final String typed = v.getText().toString().trim();
                    final Float parsed = typed.isEmpty() ? null : ScamLabRun.devNumber(typed);
                    if (!typed.isEmpty() && parsed == null) {
                        Toast.makeText(requireContext(), Lang.t(getContext(), "Нужно число.", "Enter a number."), Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    final android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager)
                            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    final String text = parsed == null ? null : ScamDevFile.number(parsed);
                    main.post(() -> { if (root != null && isAdded()) set.accept(text); });
                    return true;
                });
                r.addView(field, space(-1, -2, 8));
                r.addView(text(Lang.t(getContext(), "Число; пусто — без строки (значение кода); «Готово» на клавиатуре — пересчитать.",
                        "A number; empty: no line (the code's value); Done on the keyboard renders again."), 12, MUTED), space(-1, -2, 6));
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
                // P357: a double tap puts the shot's value back (as «как снято» does)
                if (!isShot) com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(bar, () -> set.accept(atShot));
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

    private void info(Spec main, ScamLabRun.Result r, ScamLabRun.Shot s) {
        final String why = r.same ? inert(getContext(), main.key, effectiveKey(main.key, motionShot(s.prefs()), tree()), s.prefs(),
                forced(main.key, s.prefs(), tree())) : null;
        final String line = r.same ? ScamLabRun.burstLine(s, main.key) : null;
        String info = r.same ? Lang.t(getContext(), "На этой серии A и B совпадают до пикселя. ", "On this burst A and B are the same pixels. ")
                + (AT_SHOT.contains(main.key) ? Lang.t(getContext(), "Настройка задаётся при съёмке (уже вшита в серию).", "The setting is fixed at the shot (already in the burst).")
                : line != null ? burstFixed(line) + " "
                : why != null ? Lang.t(getContext(), "На этом снимке она не действует: ", "It has no effect on this shot: ") + why + " "
                : BURST_FRAMES.contains(main.key) ? Lang.t(getContext(), "На сохранённой серии она только убирает или ограничивает снятые кадры светов и теней: больше, чем снято, добавить нельзя. ",
                        "On a saved burst it only drops or limits the highlight and shadow frames taken: it cannot add more than were shot. ")
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
                    final ScamLabRun.Progress progress = text -> {
                        runStage = text;
                        redraw();
                    };
                    final ScamLabRun.Result r = j.page.endsWith("|" + PIPELINE) ? ScamLabRun.pipeline(app, j.shot, j.crop, progress)
                            : ScamLabRun.run(app, j.shot, j.crop, j.a, j.b, progress);
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
            if (root == null || !isAdded() || !("result".equals(arg(PAGE)) || "pipeline".equals(arg(PAGE)) || "sandbox".equals(arg(PAGE)))) return;
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
