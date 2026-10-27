package com.particlesdevs.photoncamera.gallery.editor;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

/**
 * P360 (owner, 2026-10-27: «Наш редактор фото в галерее сделай с более красивым интерфейсом с красивыми анимациями»): the
 * editor's motion - one easing family (Material's emphasized decelerate / standard), short durations, every animation through
 * ViewPropertyAnimator / ValueAnimator so the system's animation scale (0 = off) applies.
 */
final class EditMotion {
    private EditMotion() {}

    /** Material 3 emphasized decelerate (things arriving) and standard (things moving on screen). */
    static final Interpolator ENTER = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f), MOVE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    static final long SHORT = 120, MEDIUM = 240, LONG = 360;

    /** A press shrinks the view a little; the release springs it back (the view's own click stays). */
    @SuppressLint("ClickableViewAccessibility")
    static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(.9f).scaleY(.9f).setDuration(SHORT).setInterpolator(MOVE).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(MEDIUM).setInterpolator(new OvershootInterpolator(3f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Fades {@code v} in (from {@code dy} px below) or out (then GONE). */
    static void show(@Nullable View v, boolean on, float dy) {
        if (v == null) return;
        v.animate().cancel();
        if (on) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setTranslationY(dy);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).translationY(0f).setDuration(MEDIUM).setInterpolator(ENTER).withEndAction(null).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).translationY(dy).setDuration(SHORT + 40).setInterpolator(EXIT)
                    .withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    /**
     * The editor's arrival: a cover over the picture fades away (the picture is a GL surface: it is not scaled or faded
     * itself), the bars rise in.
     */
    static void enter(View cover, View... bars) {
        cover.setVisibility(View.VISIBLE);
        cover.setAlpha(1f);
        cover.animate().alpha(0f).setDuration(LONG + 80).setInterpolator(MOVE).withEndAction(() -> cover.setVisibility(View.GONE)).start();
        long delay = 60;
        for (View b : bars) {
            if (b == null) continue;
            b.setAlpha(0f);
            b.setTranslationY(b.getResources().getDisplayMetrics().density * 24);
            b.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(LONG).setInterpolator(ENTER).start();
            delay += 40;
        }
    }

    /** A panel's rows come in one after another (a short rise and fade each). */
    static void stagger(ViewGroup rows) {
        final float rise = rows.getResources().getDisplayMetrics().density * 10;
        for (int i = 0, n = Math.min(rows.getChildCount(), 12); i < n; i++) {
            final View r = rows.getChildAt(i);
            r.setAlpha(0f);
            r.setTranslationY(rise);
            r.animate().alpha(1f).translationY(0f).setStartDelay(25L * i).setDuration(MEDIUM).setInterpolator(ENTER).start();
        }
    }

    /** A colour from {@code from} to {@code to} over {@code ms}, each step to {@code apply}. */
    static ValueAnimator colour(int from, int to, long ms, java.util.function.IntConsumer apply) {
        final ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(ms);
        a.setInterpolator(MOVE);
        a.addUpdateListener(x -> apply.accept((Integer) x.getAnimatedValue()));
        a.start();
        return a;
    }

    /** 0..1 over {@code ms} with {@code i}, each step to {@code apply}. */
    static ValueAnimator fraction(float from, float to, long ms, Interpolator i, java.util.function.Consumer<Float> apply) {
        final ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.setInterpolator(i == null ? new DecelerateInterpolator() : i);
        a.addUpdateListener(x -> apply.accept((Float) x.getAnimatedValue()));
        a.start();
        return a;
    }
}
