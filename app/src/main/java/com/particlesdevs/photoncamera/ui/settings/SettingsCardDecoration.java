package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SIMPLE_UI §4, the concept's settings cards (.card / .row): the rows between two group titles share one card - the card
 * colour, a 1dp line, 20dp corners - divided by 1dp lines (.row + .row). The rows themselves are transparent; this draws
 * the card behind each run of rows, also when the run starts or ends off screen (its square edge then). A row that draws
 * its own card (the tone curve, the crop picture, the «Конфиг» tiles, the info notes) breaks the run.
 * <p>
 * P384 (owner, 2026-10-28: «Сделай это меню красивым с плавными анимациями»): the main page's module cards open and close in the
 * list (rows fade in and out, the rows below glide). A row's preference comes from the view (SettingsStyle.bind tags it), so a
 * row fading out - no adapter position any more, and put at the end of the list's children - still belongs to its card; the
 * runs are built in the order the rows are drawn on screen, the card follows the rows' translation, a line between two rows
 * takes the fainter one's fade, and the rows of an open module card get a lighter fill (CARD_NESTED) inside the card's outline.
 */
public final class SettingsCardDecoration extends RecyclerView.ItemDecoration {
    /** Rows that draw their own card or are no row at all: they break a run. */
    public interface Standalone {
        boolean standalone(@Nullable Preference p);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(),
            nested = new Paint();
    private final float radius;
    private final Standalone standalone;
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final List<Item> items = new ArrayList<>();

    /** A child of the list as drawn: its view, its preference, its top and bottom on screen. */
    private static final class Item {
        View view;
        Preference p;
        int position;
        float top, bottom;
    }

    public SettingsCardDecoration(Context c, Standalone standalone) {
        float d = c.getResources().getDisplayMetrics().density;
        radius = 20 * d;
        fill.setColor(SettingsStyle.CARD);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, d));
        stroke.setColor(SettingsStyle.LINE);
        line.setColor(SettingsStyle.LINE);
        nested.setColor(SettingsStyle.CARD_NESTED);
        this.standalone = standalone;
    }

    private boolean row(@Nullable Preference p) {
        return p != null && (p instanceof ModuleSection || (!(p instanceof PreferenceCategory) && !standalone.standalone(p)));
    }

    /** The row at an adapter position (the neighbours outside the drawn children decide a square or a rounded edge). */
    private boolean row(RecyclerView parent, int position) {
        if (position < 0 || !(parent.getAdapter() instanceof PreferenceGroupAdapter)) return false;
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) parent.getAdapter();
        if (position >= adapter.getItemCount()) return false;
        return row(adapter.getItem(position));
    }

    @Nullable
    private static Preference preference(RecyclerView parent, View child, int position) {
        Object tag = child.getTag(R.id.settings_bound_preference);
        if (tag instanceof Preference) return (Preference) tag;
        if (position >= 0 && parent.getAdapter() instanceof PreferenceGroupAdapter && position < parent.getAdapter().getItemCount())
            return ((PreferenceGroupAdapter) parent.getAdapter()).getItem(position);
        return null;
    }

    /** How far a row has faded in or out (its alpha against the alpha it rests at). */
    private static float fade(Item item) {
        float rest = SettingsStyle.restingAlpha(item.p);
        return rest <= 0f ? 1f : Math.max(0f, Math.min(1f, item.view.getAlpha() / rest));
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        items.clear();
        for (int i = 0; i < parent.getChildCount(); i++) {
            Item item = new Item();
            item.view = parent.getChildAt(i);
            item.position = parent.getChildAdapterPosition(item.view);
            item.p = preference(parent, item.view, item.position);
            item.top = item.view.getTop() + item.view.getTranslationY();
            item.bottom = item.view.getBottom() + item.view.getTranslationY();
            // a title not yet faded in (a module's sensor rows swapping) does not split the card it sits in
            if (!row(item.p) && item.view.getAlpha() <= 0f) continue;
            items.add(item);
        }
        // the order on screen (a row fading out is the list's last child, drawn at its old place)
        Collections.sort(items, (a, b) -> Float.compare(a.top, b.top));
        int n = items.size();
        int i = 0;
        while (i < n) {
            if (!row(items.get(i).p)) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n && row(items.get(j + 1).p)) j++;
            Item first = items.get(i), last = items.get(j);
            boolean roundTop = i > 0 || !row(parent, first.position - 1);
            boolean roundBottom = j < n - 1 || !row(parent, last.position + 1);
            if (first.position < 0) roundTop = true;
            if (last.position < 0) roundBottom = true;
            float top = first.top, bottom = last.bottom;
            float left = first.view.getLeft(), right = first.view.getRight();
            float half = stroke.getStrokeWidth() / 2f;
            rect.set(left + half, top + half, right - half, bottom - half);
            float rt = roundTop ? radius : 0f, rb = roundBottom ? radius : 0f;
            path.reset();
            path.addRoundRect(rect, new float[]{rt, rt, rt, rt, rb, rb, rb, rb}, Path.Direction.CW);
            canvas.drawPath(path, fill);
            // an open module card's rows: a lighter fill, faded with them, inside the card's rounded outline
            boolean clipped = false;
            for (int k = i; k <= j; k++) {
                Item item = items.get(k);
                if (!(item.p != null && item.p.getParent() instanceof ModuleSection)) continue;
                if (!clipped) {
                    canvas.save();
                    canvas.clipPath(path);
                    clipped = true;
                }
                nested.setAlpha(Math.round(255 * fade(item)));
                canvas.drawRect(left, item.top, right, item.bottom, nested);
            }
            if (clipped) canvas.restore();
            canvas.drawPath(path, stroke);
            // a divider above each shown row but the first: a row not yet faded in (still under the rows gliding past it) is
            // skipped, so the rows on screen keep their lines
            Item above = null;
            for (int k = i; k <= j; k++) {
                Item item = items.get(k);
                if (fade(item) <= 0f) continue;
                if (above != null) {
                    line.setAlpha(Math.round(255 * Math.min(fade(item), fade(above))));
                    float y = item.top;
                    canvas.drawRect(left, y, right, y + stroke.getStrokeWidth(), line);
                }
                above = item;
            }
            i = j + 1;
        }
    }
}
