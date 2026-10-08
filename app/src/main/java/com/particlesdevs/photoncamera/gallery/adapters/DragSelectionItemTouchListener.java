package com.particlesdevs.photoncamera.gallery.adapters;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.gallery.interfaces.OnItemInteractionListener;

// Taken from DragSelectionItemTouchListener.java created by NikolaDespotoski

/**
 * Long press starts a selection, dragging then reports the range from the long-pressed item to the item under the finger
 * ({@link OnItemInteractionListener#onDragRange}): rows and columns alike (P59b: a vertical drag in one column hovered one
 * cell only, the cells in between were never selected). Near the top / bottom edge the list scrolls and the range follows.
 */
public class DragSelectionItemTouchListener extends LongPressItemTouchListener implements RecyclerView.OnItemTouchListener {
    private int lastPosition = RecyclerView.NO_POSITION;
    private float lastX, lastY;
    private final int edge;
    private RecyclerView scrolling;
    private int scrollStep;
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (scrolling == null || scrollStep == 0 || mViewHolderLongPressed == null) return;
            scrolling.scrollBy(0, scrollStep);
            reportRange(scrolling, lastX, lastY, true);
            scrolling.postOnAnimation(this);
        }
    };

    public DragSelectionItemTouchListener(Context context, OnItemInteractionListener listener) {
        super(context, listener);
        edge = Math.round(56 * context.getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelPreviousSelection();
            return false;
        } else {
            onLongPressedEvent(rv, e);
        }
        return mViewHolderLongPressed != null;
    }

    private void cancelPreviousSelection() {
        mViewHolderLongPressed = null;
        mViewHolderInFocus = null;
        lastPosition = RecyclerView.NO_POSITION;
        stopScroll();
    }

    private void stopScroll() {
        scrollStep = 0;
        if (scrolling != null) scrolling.removeCallbacks(autoScroll);
        scrolling = null;
    }

    /** The range from the long-pressed item to the item under (x, y) (or the nearest one), once per change. */
    private void reportRange(RecyclerView rv, float x, float y, boolean force) {
        if (mViewHolderLongPressed == null || mListener == null) return;
        float cx = Math.max(1, Math.min(rv.getWidth() - 1, x)), cy = Math.max(1, Math.min(rv.getHeight() - 1, y));
        View under = rv.findChildViewUnder(cx, cy);
        if (under == null) under = nearest(rv, cx, cy);
        if (under == null) return;
        int pos = rv.getChildAdapterPosition(under);
        if (pos == RecyclerView.NO_POSITION || (pos == lastPosition && !force)) return;
        lastPosition = pos;
        mListener.onDragRange(rv, mViewHolderLongPressed.getAbsoluteAdapterPosition(), pos);
    }

    private static View nearest(RecyclerView rv, float x, float y) {
        View best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); ++i) {
            View v = rv.getChildAt(i);
            float dx = Math.max(0, Math.max(v.getLeft() - x, x - v.getRight())), dy = Math.max(0, Math.max(v.getTop() - y, y - v.getBottom()));
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelPreviousSelection();
        } else if (mViewHolderLongPressed != null) {
            lastX = e.getX();
            lastY = e.getY();
            reportRange(rv, lastX, lastY, false);
            int step = lastY < edge ? -Math.round((edge - lastY) / 4f) - 1 : lastY > rv.getHeight() - edge ? Math.round((lastY - rv.getHeight() + edge) / 4f) + 1 : 0;
            if (step != 0 && scrollStep == 0) {
                scrolling = rv;
                scrollStep = step;
                rv.postOnAnimation(autoScroll);
            } else if (step == 0) stopScroll();
            else scrollStep = step;
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {

    }
}
