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

/**
 * SIMPLE_UI §4, the concept's settings cards (.card / .row): the rows between two group titles share one card - the card
 * colour, a 1dp line, 20dp corners - divided by 1dp lines (.row + .row). The rows themselves are transparent; this draws
 * the card behind each run of rows, also when the run starts or ends off screen (its square edge then). A row that draws
 * its own card (the tone curve, the crop picture, the «Конфиг» tiles, the info notes) breaks the run.
 */
public final class SettingsCardDecoration extends RecyclerView.ItemDecoration {
    /** Rows that draw their own card or are no row at all: they break a run. */
    public interface Standalone {
        boolean standalone(@Nullable Preference p);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint();
    private final float radius;
    private final Standalone standalone;
    private final RectF rect = new RectF();
    private final Path path = new Path();

    public SettingsCardDecoration(Context c, Standalone standalone) {
        float d = c.getResources().getDisplayMetrics().density;
        radius = 20 * d;
        fill.setColor(SettingsStyle.CARD);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, d));
        stroke.setColor(SettingsStyle.LINE);
        line.setColor(SettingsStyle.LINE);
        this.standalone = standalone;
    }

    private boolean row(RecyclerView parent, int position) {
        if (position < 0 || !(parent.getAdapter() instanceof PreferenceGroupAdapter)) return false;
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) parent.getAdapter();
        if (position >= adapter.getItemCount()) return false;
        Preference p = adapter.getItem(position);
        return p != null && !(p instanceof PreferenceCategory) && !standalone.standalone(p);
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int n = parent.getChildCount();
        int i = 0;
        while (i < n) {
            View first = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(first);
            if (!row(parent, pos)) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n && row(parent, parent.getChildAdapterPosition(parent.getChildAt(j + 1)))
                    && parent.getChildAdapterPosition(parent.getChildAt(j + 1)) == parent.getChildAdapterPosition(parent.getChildAt(j)) + 1) j++;
            View last = parent.getChildAt(j);
            int lastPos = parent.getChildAdapterPosition(last);
            boolean roundTop = !row(parent, pos - 1), roundBottom = !row(parent, lastPos + 1);
            float top = first.getTop() + first.getTranslationY(), bottom = last.getBottom() + last.getTranslationY();
            float left = first.getLeft(), right = first.getRight();
            float half = stroke.getStrokeWidth() / 2f;
            rect.set(left + half, top + half, right - half, bottom - half);
            float rt = roundTop ? radius : 0f, rb = roundBottom ? radius : 0f;
            path.reset();
            path.addRoundRect(rect, new float[]{rt, rt, rt, rt, rb, rb, rb, rb}, Path.Direction.CW);
            canvas.drawPath(path, fill);
            canvas.drawPath(path, stroke);
            for (int k = i + 1; k <= j; k++) {
                View v = parent.getChildAt(k);
                float y = v.getTop() + v.getTranslationY();
                canvas.drawRect(left, y, right, y + stroke.getStrokeWidth(), line);
            }
            i = j + 1;
        }
    }
}
