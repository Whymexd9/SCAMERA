package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.List;

/**
 * P59b: the gallery's bottom sheets in the card style: delete («Удалить N снимков?»), folders (switch rows) and the
 * details of a photo (replaces exif_dialog). Each is a view built here (testable on its own) shown in a BottomSheetDialog:
 * drag down or tap the scrim to close, back closes it, focus goes into it.
 */
public final class GallerySheets {
    private GallerySheets() {}

    /** The sheet frame: the grab handle on top, the content below, the sheet colour with a 26dp top radius. */
    static LinearLayout frame(Context c) {
        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        float r = GalleryUi.dp(c, 26);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(GalleryUi.SHEET);
        bg.setStroke(Math.max(1, GalleryUi.dp(c, 1)), GalleryUi.LINE);
        sheet.setBackground(bg);
        sheet.setPadding(GalleryUi.dp(c, 16), 0, GalleryUi.dp(c, 16), GalleryUi.dp(c, 18));
        View grab = new View(c);
        grab.setBackground(GalleryUi.round(c, 0xFF4A4F57, 0xFF4A4F57, 2));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.topMargin = GalleryUi.dp(c, 12);
        gp.bottomMargin = GalleryUi.dp(c, 16);
        grab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sheet.addView(grab, gp);
        return sheet;
    }

    static TextView title(Context c, String value) {
        TextView t = GalleryUi.text(c, value, 17, GalleryUi.TEXT);
        t.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 2), GalleryUi.dp(c, 4), GalleryUi.dp(c, 2));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAccessibilityHeading(true);
        return t;
    }

    static TextView sub(Context c, String value) {
        TextView t = GalleryUi.text(c, value, 13, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 6));
        t.setLineSpacing(0, 1.3f);
        return t;
    }

    /** Shows {@code content} as a bottom sheet; returns the dialog. */
    public static BottomSheetDialog show(Context c, View content) {
        BottomSheetDialog d = new BottomSheetDialog(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.addView(content);
        d.setContentView(scroll);
        d.setOnShowListener(x -> {
            View host = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (host != null) {
                host.setBackgroundColor(0);
                BottomSheetBehavior.from(host).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            content.setFocusable(true);
            content.requestFocus();
            content.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
        });
        d.show();
        return d;
    }

    // ---------------------------------------------------------------- delete

    public static String deleteTitle(int n) {
        if (n == 1) return Lang.t("Удалить снимок?", "Delete the photo?");
        return Lang.t("Удалить ", "Delete ") + GalleryUi.shots(n) + "?";
    }

    /** «Удалить N снимков?», the count and size, the warning «Удалить» and «Отмена». */
    public static LinearLayout delete(Context c, int n, long bytes, Runnable onDelete, Runnable onCancel) {
        LinearLayout s = frame(c);
        s.addView(title(c, deleteTitle(n)));
        s.addView(sub(c, GalleryUi.shots(n) + " · " + GalleryUi.size(bytes) + ". "
                + Lang.t("Файлы удалятся с телефона без корзины.", "The files are removed from the phone, not moved to a bin.")));
        TextView yes = bigButton(c, Lang.t("Удалить", "Delete"), true);
        yes.setOnClickListener(v -> onDelete.run());
        TextView no = bigButton(c, Lang.t("Отмена", "Cancel"), false);
        no.setOnClickListener(v -> onCancel.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = GalleryUi.dp(c, 10);
        s.addView(yes, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = GalleryUi.dp(c, 10);
        s.addView(no, lp2);
        return s;
    }

    public static void showDelete(Context c, int n, long bytes, Runnable onDelete) {
        final BottomSheetDialog[] d = new BottomSheetDialog[1];
        LinearLayout content = delete(c, n, bytes, () -> {
            if (d[0] != null) d[0].dismiss();
            onDelete.run();
        }, () -> {
            if (d[0] != null) d[0].dismiss();
        });
        d[0] = show(c, content);
    }

    static TextView bigButton(Context c, String label, boolean warn) {
        TextView b = GalleryUi.text(c, label, 16, warn ? 0xFF2A1210 : GalleryUi.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(GalleryUi.dp(c, 14), GalleryUi.dp(c, 14), GalleryUi.dp(c, 14), GalleryUi.dp(c, 14));
        b.setTypeface(warn ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setBackground(GalleryUi.pressable(warn ? GalleryUi.round(c, GalleryUi.WARN, GalleryUi.WARN, 16) : GalleryUi.card(c, GalleryUi.CARD, 16)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    // ---------------------------------------------------------------- folders

    /** A folder of the folders sheet. */
    public static final class Folder {
        public final String id, name, path;
        public boolean on;

        public Folder(String id, String name, String path, boolean on) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.on = on;
        }
    }

    public interface FolderToggle {
        void toggled(@Nullable Folder folder, boolean on); // folder null: the «Показывать папки» switch
    }

    /** «Папки галереи»: the chips switch, then one switch row per folder (folder icon, name, path). */
    public static LinearLayout folders(Context c, boolean chipsShown, List<Folder> folders, FolderToggle toggle) {
        LinearLayout s = frame(c);
        s.addView(title(c, Lang.t("Папки галереи", "Gallery folders")));
        s.addView(sub(c, Lang.t("Какие папки показывать в галерее", "Which folders the gallery shows")));
        LinearLayout rows = column(c);
        rows.addView(switchRow(c, R.drawable.ic_gallery_merge, Lang.t("Показывать папки", "Show folders"),
                Lang.t("Лента папок над сеткой", "The folder chips above the grid"), chipsShown, on -> toggle.toggled(null, on)));
        s.addView(rows);
        s.addView(GalleryUi.sectionLabel(c, Lang.t("Папки", "Folders")));
        LinearLayout list = column(c);
        for (Folder f : folders)
            list.addView(switchRow(c, R.drawable.ic_gallery_folder, f.name, f.path, f.on, on -> {
                f.on = on;
                toggle.toggled(f, on);
            }));
        s.addView(list);
        return s;
    }

    interface Switched {
        void on(boolean on);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setDividerDrawable(gap(c));
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return l;
    }

    static GradientDrawable gap(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setSize(1, GalleryUi.dp(c, 8));
        g.setColor(0);
        return g;
    }

    /** A card row: icon, title / subtitle, and a switch (the settings' switch look). */
    static LinearLayout switchRow(Context c, @DrawableRes int icon, String t, String sub, boolean on, Switched switched) {
        LinearLayout row = cardRow(c);
        row.addView(GalleryUi.icon(c, icon, GalleryUi.accent(c), 22));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tt = GalleryUi.text(c, t, 15, GalleryUi.TEXT);
        TextView st = GalleryUi.text(c, sub, 12.5f, GalleryUi.MUTED);
        st.setPadding(0, GalleryUi.dp(c, 3), 0, 0);
        texts.addView(tt);
        texts.addView(st);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = tp.rightMargin = GalleryUi.dp(c, 12);
        row.addView(texts, tp);
        View sw = toggleView(c, on);
        row.addView(sw);
        row.setContentDescription(t + ", " + sub);
        row.setStateDescription(on ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
        row.setTag(on);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            boolean now = !(Boolean) row.getTag();
            row.setTag(now);
            setToggle(sw, now);
            row.setStateDescription(now ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
            switched.on(now);
        });
        return row;
    }

    static LinearLayout cardRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(GalleryUi.pressable(GalleryUi.card(c, GalleryUi.CARD, 16)));
        row.setPadding(GalleryUi.dp(c, 14), GalleryUi.dp(c, 12), GalleryUi.dp(c, 14), GalleryUi.dp(c, 12));
        return row;
    }

    /** The switch: a 46 x 26dp track with a 20dp thumb (accent when on). */
    public static View toggleView(Context c, boolean on) {
        FrameLayout track = new FrameLayout(c);
        View thumb = new View(c);
        track.addView(thumb, new FrameLayout.LayoutParams(GalleryUi.dp(c, 20), GalleryUi.dp(c, 20), Gravity.CENTER_VERTICAL));
        track.setLayoutParams(new LinearLayout.LayoutParams(GalleryUi.dp(c, 46), GalleryUi.dp(c, 26)));
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setToggle(track, on);
        return track;
    }

    public static void setToggle(View track, boolean on) {
        Context c = track.getContext();
        track.setBackground(GalleryUi.round(c, on ? GalleryUi.accent(c) : 0xFF3A3E45, on ? GalleryUi.accent(c) : 0xFF3A3E45, 999));
        View thumb = ((FrameLayout) track).getChildAt(0);
        thumb.setBackground(GalleryUi.round(c, on ? 0xFFFFFFFF : 0xFF9AA0A7, 0, 999));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) thumb.getLayoutParams();
        lp.leftMargin = GalleryUi.dp(c, on ? 23 : 3);
        thumb.setLayoutParams(lp);
    }

    // ---------------------------------------------------------------- details

    /** What the details sheet shows (null values: not in the file, the card / row is hidden or shows a dash). */
    public static final class Details {
        public String fileName, when, folder;
        public String iso, shutter, fnum, focal;
        @Nullable public String route;
        public String format;
        public boolean ultraHdr;
        @DrawableRes public int formatIcon = R.drawable.ic_shade_jpeg;
        @Nullable public String lens, device;
        public int width, height;
        public long bytes;
    }

    /** The details sheet: name and «день, время · папка», Съёмка (4 cards), Гистограмма, Файл (rows). */
    public static LinearLayout details(Context c, Details d, @Nullable View histogram) {
        LinearLayout s = frame(c);
        TextView name = title(c, d.fileName == null ? "" : d.fileName);
        s.addView(name);
        s.addView(sub(c, (d.when == null ? "" : d.when) + (GalleryUi.empty(d.folder) ? "" : " · " + d.folder)));
        s.addView(GalleryUi.sectionLabel(c, Lang.t("Съёмка", "Capture")));
        LinearLayout cards = new LinearLayout(c);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.addView(shotCard(c, com.particlesdevs.photoncamera.circularbarlib.R.drawable.manual_ic_iso, d.iso, Lang.t("Чувствительность", "Sensitivity")));
        cards.addView(shotCard(c, com.particlesdevs.photoncamera.circularbarlib.R.drawable.manual_ic_shutter, d.shutter, Lang.t("Выдержка", "Shutter speed")));
        cards.addView(shotCard(c, R.drawable.settings_ic_aperture, d.fnum, Lang.t("Диафрагма", "Aperture")));
        cards.addView(shotCard(c, R.drawable.ic_gallery_focal, d.focal, Lang.t("Фокусное расстояние", "Focal length")));
        s.addView(cards);
        s.addView(GalleryUi.sectionLabel(c, Lang.t("Гистограмма", "Histogram")));
        FrameLayout histo = new FrameLayout(c);
        histo.setBackground(GalleryUi.card(c, GalleryUi.CARD, 16));
        int pad = GalleryUi.dp(c, 10);
        histo.setPadding(pad, pad, pad, pad);
        if (histogram != null) {
            if (histogram.getParent() instanceof ViewGroup) ((ViewGroup) histogram.getParent()).removeView(histogram);
            histogram.setContentDescription(Lang.t("Гистограмма RGB", "RGB histogram"));
            histo.addView(histogram, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        s.addView(histo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GalleryUi.dp(c, 120)));
        s.addView(GalleryUi.sectionLabel(c, Lang.t("Файл", "File")));
        LinearLayout rows = column(c);
        rows.setTag("details_rows");
        if (d.route != null)
            rows.addView(infoRow(c, GalleryFormat.routeIcon(d.route), Lang.t("Склейка", "Merge"), d.route, "route"));
        rows.addView(infoRow(c, d.formatIcon, Lang.t("Формат", "Format"), (d.format == null ? "" : d.format) + (d.ultraHdr ? " · Ultra HDR" : ""), "format"));
        String lens = join(d.lens, d.device);
        if (!lens.isEmpty()) rows.addView(infoRow(c, R.drawable.ic_gallery_lens, Lang.t("Объектив", "Lens"), lens, "lens"));
        if (d.width > 0 && d.height > 0)
            rows.addView(infoRow(c, R.drawable.ic_gallery_res, Lang.t("Разрешение", "Resolution"),
                    d.width + " × " + d.height + " · " + Math.round(d.width * (double) d.height / 1e6) + Lang.t(" МП", " MP"), "res"));
        rows.addView(infoRow(c, R.drawable.ic_gallery_size, Lang.t("Размер", "Size"), GalleryUi.size(d.bytes), "size"));
        s.addView(rows);
        s.setContentDescription(d.fileName);
        return s;
    }

    static String join(@Nullable String a, @Nullable String b) {
        boolean ea = GalleryUi.empty(a), eb = GalleryUi.empty(b);
        return ea && eb ? "" : ea ? b : eb ? a : a + " · " + b;
    }

    static LinearLayout shotCard(Context c, @DrawableRes int icon, @Nullable String value, String name) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(GalleryUi.card(c, GalleryUi.CARD, 16));
        card.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 10), GalleryUi.dp(c, 4), GalleryUi.dp(c, 10));
        card.addView(GalleryUi.icon(c, icon, GalleryUi.accent(c), 22));
        TextView v = GalleryUi.fitText(c, value == null ? "—" : value, 14, GalleryUi.TEXT);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = GalleryUi.dp(c, 5);
        card.addView(v, vp);
        card.setContentDescription(name + " " + (value == null ? "—" : value));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = lp.rightMargin = GalleryUi.dp(c, 3);
        card.setLayoutParams(lp);
        return card;
    }

    static LinearLayout infoRow(Context c, @DrawableRes int icon, String key, String value, String tag) {
        LinearLayout row = cardRow(c);
        row.setTag(tag);
        ImageView i = GalleryUi.icon(c, icon, GalleryUi.accent(c), 22);
        row.addView(i);
        TextView k = GalleryUi.text(c, key, 14, GalleryUi.MUTED);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.leftMargin = GalleryUi.dp(c, 12);
        row.addView(k, kp);
        TextView v = GalleryUi.text(c, value, 14, GalleryUi.TEXT);
        v.setGravity(Gravity.END);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        vp.leftMargin = GalleryUi.dp(c, 12);
        row.addView(v, vp);
        row.setContentDescription(key + ": " + value);
        return row;
    }
}
