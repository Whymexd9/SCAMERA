package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.settings.FormatChoice;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SIMPLE_UI §2, the camera's sheets as the concept draws them (.sheet over the camera: the see-through sheet colour, 28dp
 * top corners, the grab, a 16.5sp title; group titles in the accent, 11.5sp caps): «Формат файла» - two choices instead
 * of one long list, «Фото» (JPEG / HEIC / WebP / AVIF) and «RAW (DNG)» (Нет / + RAW / Только RAW), each a segmented control
 * with a one-line explanation, the summary «Сохранится: …» and the note; selecting does not close it. It is a UI over the
 * existing pref_save_raw_key and photo codec (FormatChoice): (X, Нет) -> X, (X, + RAW) -> RAW + X, (any, Только RAW) -> RAW;
 * «Только RAW» keeps the last photo choice so «+ RAW» restores it. «Размер фото» - the three sizes as radio rows.
 */
public final class CameraSheets {
    private CameraSheets() {}

    /** The photo codecs of the «Фото» control, in order. */
    static final PhotoFormat[] PHOTO = {PhotoFormat.JPEG, PhotoFormat.HEIC, PhotoFormat.WEBP, PhotoFormat.AVIF};
    /** The RAW modes: 0 none, 1 + RAW, 2 RAW only (the save mode). */
    static final int RAW_NONE = 0, RAW_PAIRED = 1, RAW_ONLY = 2;

    /** The choice of a photo codec and a RAW mode (the 9 FormatChoice values). */
    static FormatChoice choice(PhotoFormat photo, int rawMode) {
        return FormatChoice.of(rawMode, photo);
    }

    /** The «Фото» name of a codec. */
    static String photoName(PhotoFormat f) {
        switch (f) {
            case HEIC: return "HEIC";
            case WEBP: return "WebP";
            case AVIF: return "AVIF";
            default: return "JPEG";
        }
    }

    static String photoLine(Context c, PhotoFormat f) {
        switch (f) {
            case HEIC: return Lang.t(c, "Как JPEG, но вдвое меньше места", "Like JPEG in half the space");
            case WEBP: return Lang.t(c, "Для сайтов и мессенджеров", "For websites and messengers");
            case AVIF: return Lang.t(c, "Самый компактный, открывается не везде", "The most compact; not every app opens it");
            default: return Lang.t(c, "Обычное фото, открывается везде", "A usual photo that opens everywhere");
        }
    }

    static String rawName(Context c, int mode) {
        return mode == RAW_ONLY ? Lang.t(c, "Только RAW", "RAW only") : mode == RAW_PAIRED ? "+ RAW" : Lang.t(c, "Нет", "None");
    }

    static String rawLine(Context c, int mode) {
        return mode == RAW_ONLY ? Lang.t(c, "Только DNG, без готового фото", "DNG only, no finished photo")
                : mode == RAW_PAIRED ? Lang.t(c, "Фото и сырой снимок DNG рядом — для обработки на компьютере",
                "The photo and a raw DNG beside it, to edit on a computer")
                : Lang.t(c, "Сохраняется только фото", "Only the photo is saved");
    }

    /** «RAW (DNG) + HEIC» / «HEIC» / «RAW (DNG)». */
    static String summary(PhotoFormat photo, int rawMode) {
        if (rawMode == RAW_ONLY) return "RAW (DNG)";
        return rawMode == RAW_PAIRED ? "RAW (DNG) + " + photoName(photo) : photoName(photo);
    }

    // ───── the sheet frame

    /** The see-through camera sheet with the grab and the title; returns {dialog body}: add the content to the body. */
    static LinearLayout frame(Context c, CharSequence title) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((ShadeStyle.SHEET_FILL & 0x00FFFFFF) | (Math.round(255 * ShadeStyle.sheetAlpha(c)) << 24));
        float r = UiScale.dpF(c, 28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        body.setBackground(bg);
        int side = UiScale.dp(c, 14);
        body.setPadding(side, UiScale.dp(c, 10), side, UiScale.dp(c, 16));
        View grab = new View(c);
        grab.setBackground(SettingsStyle.shape(c, 0xFF4A5057, 0, 2));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(UiScale.dp(c, 40), UiScale.dp(c, 4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = UiScale.dp(c, 10);
        body.addView(grab, gp);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(ShadeStyle.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        UiScale.textFixed(t, 16.5f);
        t.setPadding(UiScale.dp(c, 4), 0, 0, UiScale.dp(c, 12));
        body.addView(t);
        return body;
    }

    /** A group title: 11.5sp, 600, caps, letter-spaced, in the accent (the concept's .gt). */
    static TextView groupTitle(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setAllCaps(true);
        t.setLetterSpacing(.09f);
        t.setTextColor(ShadeStyle.accent(c));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        UiScale.textFixed(t, 11.5f);
        t.setPadding(UiScale.dp(c, 6), UiScale.dp(c, 4), 0, UiScale.dp(c, 6));
        return t;
    }

    /**
     * A segmented control (the concept's .seg: a card with a line, 4dp padding and gaps, 42dp buttons with 12dp corners,
     * the chosen one filled with the accent in INK, 13.5sp 600); {@code disabled}: dimmed, no taps.
     */
    static LinearLayout segmented(Context c, List<String> labels, int selected, boolean disabled, java.util.function.IntConsumer pick) {
        LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        int p = UiScale.dp(c, 4);
        seg.setPadding(p, p, p, p);
        seg.setBackground(SettingsStyle.shape(c, ShadeStyle.CARD, ShadeStyle.LINE, UiScale.dpF(c, 16) / c.getResources().getDisplayMetrics().density));
        int accent = ShadeStyle.accent(c);
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            TextView b = new TextView(c);
            b.setText(labels.get(i));
            b.setGravity(Gravity.CENTER);
            b.setMaxLines(1);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            UiScale.textFixed(b, 13.5f);
            boolean on = i == selected && !disabled;
            b.setTextColor(on ? ShadeStyle.INK : ShadeStyle.TEXT);
            b.setBackground(on ? SettingsStyle.shape(c, accent, 0, UiScale.dpF(c, 12) / c.getResources().getDisplayMetrics().density)
                    : ShadeStyle.ripple(c, 12));
            b.setAlpha(disabled ? .35f : 1f);
            b.setEnabled(!disabled);
            b.setSelected(on);
            b.setTag("seg_" + i);
            b.setOnClickListener(v -> pick.accept(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, UiScale.dp(c, 42), 1f);
            if (i > 0) lp.setMarginStart(p);
            seg.addView(b, lp);
        }
        return seg;
    }

    static TextView line(Context c, CharSequence text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        UiScale.textFixed(t, sp);
        return t;
    }

    // ───── «Формат файла»

    /** The state the sheet edits: the photo codec (kept under «Только RAW») and the RAW mode. */
    static final class FormatState {
        PhotoFormat photo;
        int raw;

        FormatState(FormatChoice now, PhotoFormat storedCodec) {
            raw = now.saveMode;
            photo = now.codec != null ? now.codec : storedCodec != null ? storedCodec : PhotoFormat.JPEG;
        }
    }

    /**
     * Shows «Формат файла» at {@code current}. {@code offered}: the choices this phone offers (FormatChoice.offered); {@code apply} stores a
     * choice (the caller's path: CameraUIController FORMAT_CHOICE, the badges, the sheet's tiles).
     */
    public static BottomSheetDialog format(Context c, List<FormatChoice> offered, FormatChoice current, PhotoFormat storedCodec,
                                         Consumer<FormatChoice> apply) {
        LinearLayout body = frame(c, Lang.t(c, "Формат файла", "File format"));
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setTag("format_sheet");
        body.addView(content);
        FormatState state = new FormatState(current, storedCodec);
        List<PhotoFormat> photos = new ArrayList<>();
        for (PhotoFormat f : PHOTO) if (offered.contains(choice(f, RAW_NONE))) photos.add(f);
        if (!photos.contains(state.photo)) state.photo = photos.isEmpty() ? PhotoFormat.JPEG : photos.get(0);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            content.removeAllViews();
            List<String> names = new ArrayList<>();
            for (PhotoFormat f : photos) names.add(photoName(f));
            boolean rawOnly = state.raw == RAW_ONLY;
            content.addView(groupTitle(c, Lang.t(c, "Фото", "Photo")));
            content.addView(segmented(c, names, photos.indexOf(state.photo), rawOnly, i -> {
                state.photo = photos.get(i);
                store(state, offered, apply);
                render[0].run();
            }));
            TextView pd = line(c, rawOnly ? Lang.t(c, "Не сохраняется: выбрано «Только RAW»", "Not saved: «RAW only» is chosen")
                    : photoLine(c, state.photo), 12.5f, ShadeStyle.MUTED);
            pd.setPadding(UiScale.dp(c, 6), UiScale.dp(c, 6), UiScale.dp(c, 6), UiScale.dp(c, 12));
            pd.setTag("format_photo_line");
            content.addView(pd);
            content.addView(groupTitle(c, "RAW (DNG)"));
            List<String> raws = new ArrayList<>();
            for (int m = 0; m < 3; m++) raws.add(rawName(c, m));
            content.addView(segmented(c, raws, state.raw, false, m -> {
                state.raw = m;
                store(state, offered, apply);
                render[0].run();
            }));
            TextView rd = line(c, rawLine(c, state.raw), 12.5f, ShadeStyle.MUTED);
            rd.setPadding(UiScale.dp(c, 6), UiScale.dp(c, 6), UiScale.dp(c, 6), UiScale.dp(c, 12));
            content.addView(rd);
            TextView sum = line(c, android.text.Html.fromHtml(Lang.t(c, "Сохранится: ", "Saved: ") + "<b><font color='#"
                    + String.format("%06X", ShadeStyle.accent(c) & 0xFFFFFF) + "'>" + summary(state.photo, state.raw) + "</font></b>",
                    android.text.Html.FROM_HTML_MODE_LEGACY), 13, ShadeStyle.MUTED);
            sum.setTag("format_summary");
            sum.setPadding(UiScale.dp(c, 6), 0, UiScale.dp(c, 6), UiScale.dp(c, 12));
            content.addView(sum);
            content.addView(note(c, Lang.t(c, "Не знаете, что выбрать — JPEG без RAW. RAW нужен для обработки в Lightroom или RawTherapee.",
                    "Not sure? JPEG without RAW. RAW is for editing in Lightroom or RawTherapee.")));
        };
        render[0].run();
        BottomSheetDialog d = SettingsStyle.sheet(c, body);
        d.show();
        return d;
    }

    /** Stores the state's choice when this phone offers it (AVIF without its encoder, HEIC before Android 9: not offered). */
    static void store(FormatState state, List<FormatChoice> offered, Consumer<FormatChoice> apply) {
        FormatChoice choice = choice(state.photo, state.raw);
        if (offered.contains(choice)) apply.accept(choice);
    }

    /** The concept's .note: the info icon in the accent and a muted text on the second card colour, 18dp corners. */
    static View note(Context c, CharSequence text) {
        LinearLayout n = new LinearLayout(c);
        n.setOrientation(LinearLayout.HORIZONTAL);
        int ph = UiScale.dp(c, 14), pv = UiScale.dp(c, 12);
        n.setPadding(ph, pv, ph, pv);
        n.setBackground(SettingsStyle.shape(c, 0xFF232A2E, 0, UiScale.dpF(c, 18) / c.getResources().getDisplayMetrics().density));
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ui_ic_info);
        icon.setImageTintList(ColorStateList.valueOf(ShadeStyle.accent(c)));
        int s = UiScale.dp(c, 20);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
        ip.setMarginEnd(UiScale.dp(c, 10));
        n.addView(icon, ip);
        n.addView(line(c, text, 13, ShadeStyle.MUTED), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return n;
    }

    // ───── «Размер фото»

    /**
     * Shows «Размер фото»: «Обычный · 12 Мп», «Подробный · 50 Мп», «Максимум · 200 Мп» as radio rows with what each gives;
     * a size the module lacks is dimmed and says why; a pick applies, closes the sheet and the caller shows the message.
     */
    public static BottomSheetDialog size(Context c, int current, boolean full, boolean max, java.util.function.IntConsumer pick,
                                         @Nullable Consumer<CharSequence> refuse) {
        LinearLayout body = frame(c, Lang.t(c, "Размер фото", "Photo size"));
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SettingsStyle.shape(c, ShadeStyle.CARD, ShadeStyle.LINE, UiScale.dpF(c, 20) / c.getResources().getDisplayMetrics().density));
        card.setTag("size_sheet");
        body.addView(card);
        String[][] rows = {
                {Lang.t(c, "Обычный · 12 Мп", "Normal · 12 MP"), Lang.t(c, "Быстро и немного места. Подходит почти всегда", "Fast and small. Fits nearly always")},
                {Lang.t(c, "Подробный · 50 Мп", "Detailed · 50 MP"), Lang.t(c, "Больше деталей при хорошем свете. Снимок дольше", "More detail in good light. A slower shot")},
                {Lang.t(c, "Максимум · 200 Мп", "Maximum · 200 MP"), Lang.t(c, "Для печати и кадрирования", "For prints and cropping")}};
        int[] sizes = {PhotoSize.NORMAL, PhotoSize.FULL, PhotoSize.MAX};
        BottomSheetDialog[] dialog = new BottomSheetDialog[1];
        int accent = ShadeStyle.accent(c);
        for (int i = 0; i < 3; i++) {
            final int size = sizes[i];
            boolean available = i == 0 || i == 1 && full || i == 2 && max;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int ph = UiScale.dp(c, 14), pv = UiScale.dp(c, 12);
            row.setPadding(ph, pv, ph, pv);
            row.setMinimumHeight(UiScale.dp(c, 58));
            row.setBackground(ShadeStyle.ripple(c, 20));
            row.setAlpha(available ? 1f : .45f);
            row.setTag("size_" + size);
            View radio = radio(c, size == current, accent);
            int rs = UiScale.dp(c, 22);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(rs, rs);
            rp.setMarginEnd(UiScale.dp(c, 12));
            row.addView(radio, rp);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = line(c, rows[i][0], 14.5f, ShadeStyle.TEXT);
            texts.addView(t);
            texts.addView(line(c, available ? rows[i][1] : Lang.t(c, "Нет на этом объективе", "Not on this lens"), 12.5f, ShadeStyle.MUTED));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> {
                if (!available) {
                    if (refuse != null) refuse.accept(Lang.t(c, size + " Мп — нет на этом объективе", size + " MP: not on this lens"));
                    return;
                }
                pick.accept(size);
                if (dialog[0] != null) v.postDelayed(dialog[0]::dismiss, 180);
            });
            if (i > 0) {
                View divider = new View(c);
                divider.setBackgroundColor(ShadeStyle.LINE);
                card.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiScale.dp(c, 1))));
            }
            card.addView(row);
        }
        dialog[0] = SettingsStyle.sheet(c, body);
        dialog[0].show();
        return dialog[0];
    }

    /** The concept's .radio: a 22dp ring (2dp), the accent ring and a 10dp dot when on. */
    static View radio(Context c, boolean on, int accent) {
        View v = new View(c);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(UiScale.dp(c, 2), on ? accent : 0xFF6C757C);
        if (on) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{ring, dot});
            int inset = UiScale.dp(c, 6);
            layers.setLayerInset(1, inset, inset, inset, inset);
            v.setBackground(layers);
        } else {
            v.setBackground(ring);
        }
        v.setSelected(on);
        return v;
    }
}
