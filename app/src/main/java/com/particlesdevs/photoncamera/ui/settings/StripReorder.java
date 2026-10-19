package com.particlesdevs.photoncamera.ui.settings;

import android.content.ClipData;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;

/**
 * P279: a strip's cards reordered by a long press and a drag (the settings' LUT and filter strips, the viewfinder's tray): the
 * card held is dropped on another card and takes its place; near the strip's ends it scrolls. The first card (none) neither
 * moves nor is taken: a drop on it puts the card second.
 */
public final class StripReorder {
    private StripReorder() {}

    public interface OnMove { void move(int from, int to); }

    /** {@code card} is item {@code index} of its strip. */
    public static void attach(View card, int index, OnMove onMove) {
        if (index > 0) card.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            return v.startDragAndDrop(ClipData.newPlainText("item", String.valueOf(index)), new View.DragShadowBuilder(v), index, 0);
        });
        card.setOnDragListener((v, e) -> {
            if (!(e.getLocalState() instanceof Integer)) return false;
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if ((Integer) e.getLocalState() == index) v.setAlpha(.4f);
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.setScaleX(1.06f);
                    v.setScaleY(1.06f);
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    scrollNearEdge(v, e.getX());
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    return true;
                case DragEvent.ACTION_DROP: {
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    final int from = (Integer) e.getLocalState();
                    if (from != index) v.post(() -> onMove.move(from, Math.max(1, index)));
                    return true;
                }
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    return true;
                default:
                    return true;
            }
        });
    }

    /** The strip scrolls while the dragged card is over a card at its visible ends. */
    private static void scrollNearEdge(View card, float x) {
        ViewParent p = card.getParent();
        while (p != null && !(p instanceof HorizontalScrollView)) p = p.getParent();
        if (!(p instanceof HorizontalScrollView)) return;
        final HorizontalScrollView scroll = (HorizontalScrollView) p;
        final int[] at = new int[2], in = new int[2];
        card.getLocationOnScreen(at);
        scroll.getLocationOnScreen(in);
        final float onScroll = at[0] - in[0] + x, edge = scroll.getWidth() * .15f;
        if (onScroll < edge) scroll.smoothScrollBy(-card.getWidth() / 2, 0);
        else if (onScroll > scroll.getWidth() - edge) scroll.smoothScrollBy(card.getWidth() / 2, 0);
    }
}
