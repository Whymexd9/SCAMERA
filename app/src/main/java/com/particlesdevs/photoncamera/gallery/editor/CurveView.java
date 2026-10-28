package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;

import java.util.ArrayList;
import java.util.List;

/**
 * P351: the point curve editor of «Кривая» (one channel at a time: master, R, G, B). A touch near a point drags it (between
 * its neighbours), a touch elsewhere on the curve adds a point there, a double tap on a point removes it (the end points stay).
 * Drawn as {@link EditMath#curveAt} evaluates it (monotone cubic), over a 4 x 4 grid and the straight diagonal.
 */
public final class CurveView extends View {
    public interface Listener {
        void curve(@Nullable float[] points, boolean done);
    }

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final GestureDetector taps;
    private List<float[]> pts = new ArrayList<>();
    private int colour = 0xFFFFFFFF, drag = -1;
    /** The double tap (reported inside its second ACTION_DOWN) removed a point: that DOWN must not add one back. */
    private boolean removedByDoubleTap;
    @Nullable private Listener listener;

    public CurveView(Context c) {
        super(c);
        grid.setColor(0x33FFFFFF);
        grid.setStrokeWidth(GalleryUi.dp(c, 1));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(GalleryUi.dp(c, 2));
        dot.setStyle(Paint.Style.FILL);
        set(null, 0xFFFFFFFF);
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int i = nearest(e.getX(), e.getY());
                if (i > 0 && i < pts.size() - 1) {
                    pts.remove(i);
                    drag = -1;
                    removedByDoubleTap = true;
                    changed(true);
                }
                return true;
            }
        });
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
    }

    /** The points of the channel (null: straight) and its colour. */
    public void set(@Nullable float[] points, int colour) {
        this.colour = colour;
        pts = new ArrayList<>();
        if (points == null || points.length < 4) {
            pts.add(new float[]{0, 0});
            pts.add(new float[]{1, 1});
        } else for (int i = 0; i + 1 < points.length; i += 2) pts.add(new float[]{points[i], points[i + 1]});
        invalidate();
    }

    @Nullable
    float[] points() {
        if (pts.size() == 2 && pts.get(0)[0] == 0 && pts.get(0)[1] == 0 && pts.get(1)[0] == 1 && pts.get(1)[1] == 1) return null;
        float[] out = new float[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            out[2 * i] = pts.get(i)[0];
            out[2 * i + 1] = pts.get(i)[1];
        }
        return out;
    }

    private void changed(boolean done) {
        invalidate();
        if (listener != null) listener.curve(points(), done);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = getDefaultSize(GalleryUi.dp(getContext(), 240), w);
        int side = Math.min(width, GalleryUi.dp(getContext(), 220));
        setMeasuredDimension(width, side);
    }

    private float left() { return (getWidth() - side()) / 2f; }

    private float side() { return Math.min(getWidth(), getHeight()) - GalleryUi.dp(getContext(), 12); }

    private float top() { return (getHeight() - side()) / 2f; }

    @Override
    protected void onDraw(Canvas c) {
        float s = side(), l = left(), t = top();
        for (int i = 0; i <= 4; i++) {
            c.drawLine(l + s * i / 4, t, l + s * i / 4, t + s, grid);
            c.drawLine(l, t + s * i / 4, l + s, t + s * i / 4, grid);
        }
        c.drawLine(l, t + s, l + s, t, grid);
        float[] p = points();
        line.setColor(colour);
        path.reset();
        for (int i = 0; i <= 64; i++) {
            float x = i / 64f, y = EditMath.curveAt(p, x);
            if (i == 0) path.moveTo(l + x * s, t + (1 - y) * s);
            else path.lineTo(l + x * s, t + (1 - y) * s);
        }
        c.drawPath(path, line);
        dot.setColor(colour);
        for (float[] q : pts) c.drawCircle(l + q[0] * s, t + (1 - q[1]) * s, GalleryUi.dp(getContext(), 6), dot);
    }

    private int nearest(float x, float y) {
        float s = side(), l = left(), t = top(), best = GalleryUi.dp(getContext(), 28);
        int idx = -1;
        for (int i = 0; i < pts.size(); i++) {
            float d = (float) Math.hypot(l + pts.get(i)[0] * s - x, t + (1 - pts.get(i)[1]) * s - y);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        return idx;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        taps.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && removedByDoubleTap) {
            // the rest of this touch neither adds nor drags a point (it put a new one where the removed one was)
            removedByDoubleTap = false;
            drag = -1;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        float s = side(), l = left(), t = top();
        float x = Math.max(0, Math.min(1, (e.getX() - l) / s)), y = Math.max(0, Math.min(1, 1 - (e.getY() - t) / s));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                drag = nearest(e.getX(), e.getY());
                if (drag < 0 && pts.size() < 12) {
                    int at = 0;
                    while (at < pts.size() && pts.get(at)[0] < x) at++;
                    if (at > 0 && at < pts.size()) {
                        pts.add(at, new float[]{x, EditMath.curveAt(points(), x)});
                        drag = at;
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drag < 0) return true;
                float lo = drag == 0 ? 0 : pts.get(drag - 1)[0] + 0.02f, hi = drag == pts.size() - 1 ? 1 : pts.get(drag + 1)[0] - 0.02f;
                pts.get(drag)[0] = Math.max(lo, Math.min(hi, x));
                pts.get(drag)[1] = y;
                changed(false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drag >= 0) changed(true);
                drag = -1;
                return true;
            default:
                return true;
        }
    }
}
