package com.particlesdevs.photoncamera.ui.controls;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

/**
 * P357 (owner, 2026-10-27: «При двойном тапе по параметру он должен сбрасываться на дефолтное значение … сделай это для всех»): a
 * double tap on a parameter's slider or row puts its default back. The taps are only watched (the slider still moves with its
 * first tap; the reset then wins); a row whose single tap does something else ({@code onTap}: the precise value dialog) gets it
 * once the second tap did not come, so the two never both run. A tap is a press shorter than the long-press time that stays
 * inside the touch slop; two taps make a double tap within the double-tap time and distance.
 */
public final class DoubleTapReset {
    /** The main thread's queue (a view's own post waits for its window). */
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private DoubleTapReset() {}

    /** A double tap on {@code view} runs {@code reset}; its own touch handling stays as it is. */
    public static void attach(View view, Runnable reset) {
        attach(view, null, reset);
    }

    /**
     * A double tap on {@code view} runs {@code reset}; with {@code onTap} a single tap runs it instead of the view's click
     * listener (once the double tap is ruled out).
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void attach(View view, @Nullable Runnable onTap, Runnable reset) {
        if (view == null || reset == null) return;
        // the touches below never reach it; an accessibility click (TalkBack) still does
        if (onTap != null) view.setOnClickListener(v -> onTap.run());
        // a row with its own single tap, or a plain container (else it never sees the taps' ends), is ours; a slider or a view
        // with its own click keeps its handling (the slider's drag)
        final boolean own = onTap != null || !(view instanceof android.widget.AbsSeekBar) && !view.isClickable();
        final Taps taps = new Taps(view, onTap, reset);
        view.setOnTouchListener((v, e) -> {
            taps.on(e);
            if (!own) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.setPressed(true);
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) v.setPressed(false);
            return true;
        });
    }

    /** The tap state of one view. */
    static final class Taps {
        final View view;
        @Nullable final Runnable onTap;
        final Runnable reset;
        final int slop, doubleSlop;
        float downX, downY, lastX, lastY;
        long downTime, lastUp = Long.MIN_VALUE / 2;
        boolean moved, pendingTap;
        final Runnable single;

        Taps(View view, @Nullable Runnable onTap, Runnable reset) {
            this.view = view;
            this.onTap = onTap;
            this.reset = reset;
            final ViewConfiguration vc = ViewConfiguration.get(view.getContext());
            slop = vc.getScaledTouchSlop();
            doubleSlop = vc.getScaledDoubleTapSlop();
            single = () -> {
                pendingTap = false;
                if (this.onTap != null) this.onTap.run();
            };
        }

        void on(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downTime = e.getEventTime();
                    moved = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) moved = true;
                    break;
                case MotionEvent.ACTION_UP: {
                    if (moved || e.getEventTime() - downTime > ViewConfiguration.getLongPressTimeout()) {
                        lastUp = Long.MIN_VALUE / 2;
                        break;
                    }
                    final long gap = downTime - lastUp;
                    final boolean second = gap >= 0 && gap <= ViewConfiguration.getDoubleTapTimeout()
                            && Math.abs(downX - lastX) <= doubleSlop && Math.abs(downY - lastY) <= doubleSlop;
                    if (second) {
                        lastUp = Long.MIN_VALUE / 2;
                        if (pendingTap) {
                            MAIN.removeCallbacks(single);
                            pendingTap = false;
                        }
                        // after the view handled this tap (a slider jumps to it first)
                        MAIN.post(() -> {
                            if (!view.isEnabled()) return;
                            reset.run();
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        });
                    } else {
                        lastUp = e.getEventTime();
                        lastX = downX;
                        lastY = downY;
                        if (onTap != null) {
                            pendingTap = true;
                            MAIN.postDelayed(single, ViewConfiguration.getDoubleTapTimeout());
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    moved = true;
                    break;
                default:
                    break;
            }
        }
    }
}
