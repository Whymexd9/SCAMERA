package com.particlesdevs.photoncamera.ui.camera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;

/**
 * The LUT tray's motion (owner, 2026-10-27: «оформить это подменю более красиво с красивыми и плавными анимациями аля iOS / Mac OS»):
 * damped springs as UIKit / SwiftUI describe them (a response - the period of the undamped spring - and a damping ratio), the tray
 * rising from below with its sections one after another, chips that give under the finger, and a selection pill that glides from
 * the old chip to the new one. Every animation is a ValueAnimator / ViewPropertyAnimator, so the system's animator scale (and
 * «remove animations») applies; the state each animation ends in is set at once, the motion only follows it.
 */
final class TrayMotion {
    private TrayMotion() {}

    /** The ease of a short exit (UIKit's ease-in for dismissals). */
    static final TimeInterpolator EASE_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    /** The ease of an entry (Material's emphasised decelerate, close to iOS' default curve). */
    static final TimeInterpolator EASE_OUT = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** The ease of the tray's exit (an emphasised accelerate: it starts moving at once and gathers speed, so it reads as a motion). */
    static final TimeInterpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    /**
     * A damped spring from 0 to 1 over {@link Spring#durationMs}: x(t) = 1 - e^(-z w t) (cos(wd t) + z w / wd sin(wd t)), w = 2 pi /
     * response, wd = w sqrt(1 - z^2) (z < 1: a small overshoot); the last frame is exactly 1.
     */
    static final class Spring implements TimeInterpolator {
        final float response, damping;
        final long durationMs;

        Spring(float response, float damping) {
            this.response = response;
            this.damping = Math.max(0.05f, Math.min(0.999f, damping));
            // until the envelope e^(-z w t) is below 1e-3
            final double w = 2 * Math.PI / response;
            this.durationMs = Math.max(120L, Math.round(1000.0 * Math.log(1000.0) / (this.damping * w)));
        }

        @Override public float getInterpolation(float f) {
            if (f >= 1f) return 1f;
            final double t = f * durationMs / 1000.0;
            final double w = 2 * Math.PI / response, z = damping, wd = w * Math.sqrt(1 - z * z);
            return (float) (1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + z * w / wd * Math.sin(wd * t)));
        }
    }

    /** The tray's entry: a soft spring with a little lift. */
    static final Spring OPEN = new Spring(0.42f, 0.82f);
    /** The selection pill: a quicker, slightly livelier spring. */
    static final Spring GLIDE = new Spring(0.34f, 0.78f);
    /** A chip coming back after the finger. */
    static final Spring RELEASE = new Spring(0.3f, 0.6f);

    /** The tray appears from below: lifted, scaled up a little and faded in; its rows follow one after another. */
    static void appear(ViewGroup tray) {
        final Context c = tray.getContext();
        final Object prev = tray.getTag(R_TAG_LIFT);
        if (prev instanceof ValueAnimator) ((ValueAnimator) prev).cancel(); // a leaving tray called back
        tray.animate().cancel();
        tray.setVisibility(View.VISIBLE);
        if (!tray.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { // nothing on screen to move: the end state
            rest(tray);
            return;
        }
        tray.setPivotX(tray.getWidth() > 0 ? tray.getWidth() / 2f : 0f);
        tray.setPivotY(tray.getHeight() > 0 ? tray.getHeight() : 0f);
        tray.setAlpha(0f);
        tray.setTranslationY(UiScale.dpF(c, 22));
        tray.setScaleX(.94f);
        tray.setScaleY(.94f);
        tray.animate().alpha(1f).setDuration(220).setStartDelay(0).setInterpolator(EASE_OUT).start();
        // the lift and the scale on the spring (one animator, so they move together)
        final ValueAnimator lift = ValueAnimator.ofFloat(0f, 1f);
        lift.setDuration(OPEN.durationMs);
        lift.setInterpolator(OPEN);
        final float from = tray.getTranslationY();
        lift.addUpdateListener(a -> {
            final float k = (float) a.getAnimatedValue();
            tray.setTranslationY(from * (1 - k));
            final float s = .94f + .06f * k;
            tray.setScaleX(s);
            tray.setScaleY(s);
        });
        tray.setTag(R_TAG_LIFT, lift);
        lift.start();
        final float rise = UiScale.dpF(c, 10);
        for (int i = 0; i < tray.getChildCount(); i++) {
            final View child = tray.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(rise);
            child.animate().alpha(1f).translationY(0f).setStartDelay(40L + 28L * i).setDuration(360).setInterpolator(EASE_OUT).start();
        }
    }

    /**
     * The tray leaves (owner, 2026-10-28: the exit was too short to be seen): its sections go first, the lowest first, then the
     * pane sinks, shrinks toward its bottom and fades - the entry played backwards, a little quicker (~0.3 s). {@code gone} runs at
     * its end unless it was opened again meanwhile.
     */
    static void disappear(ViewGroup tray, Runnable gone) {
        final Object lift = tray.getTag(R_TAG_LIFT);
        if (lift instanceof ValueAnimator) ((ValueAnimator) lift).cancel();
        for (int i = 0; i < tray.getChildCount(); i++) tray.getChildAt(i).animate().cancel();
        if (tray.getVisibility() != View.VISIBLE || !tray.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            gone.run();
            return;
        }
        final Context c = tray.getContext();
        tray.setPivotX(tray.getWidth() / 2f);
        tray.setPivotY(tray.getHeight());
        // the sections: fade and drop a little, bottom-up, 20 ms apart
        final int n = tray.getChildCount();
        final float drop = UiScale.dpF(c, 8);
        for (int i = 0; i < n; i++) {
            final View child = tray.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.animate().alpha(0f).translationY(drop).setStartDelay(20L * (n - 1 - i)).setDuration(150).setInterpolator(EASE_IN).start();
        }
        // the pane: sinks and shrinks on one animator (from where it is now: an exit during the entry carries on smoothly)
        final float y0 = tray.getTranslationY(), s0 = tray.getScaleX(), sink = UiScale.dpF(c, 28);
        final ValueAnimator down = ValueAnimator.ofFloat(0f, 1f);
        down.setDuration(300);
        down.setInterpolator(EXIT);
        down.addUpdateListener(a -> {
            final float k = (float) a.getAnimatedValue();
            tray.setTranslationY(y0 + (sink - y0) * k);
            final float s = s0 + (.92f - s0) * k;
            tray.setScaleX(s);
            tray.setScaleY(s);
        });
        tray.setTag(R_TAG_LIFT, down);
        down.start();
        // its fade a little later, so the motion is seen before it goes
        tray.animate().cancel();
        tray.animate().alpha(0f).setDuration(220).setStartDelay(90).setInterpolator(EASE_IN)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;
                    @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                    @Override public void onAnimationEnd(Animator a) {
                        tray.animate().setListener(null);
                        if (!cancelled) gone.run();
                    }
                }).start();
    }

    /** The tray at rest (no animation left running on it): a closed tray hidden and reset for its next entry. */
    static void rest(ViewGroup tray) {
        tray.setAlpha(1f);
        tray.setTranslationY(0f);
        tray.setScaleX(1f);
        tray.setScaleY(1f);
        for (int i = 0; i < tray.getChildCount(); i++) {
            final View child = tray.getChildAt(i);
            child.setAlpha(1f);
            child.setTranslationY(0f);
        }
    }

    /** A view tag key for the tray's lift animator (an id of this app's resources, so no other tag collides). */
    static final int R_TAG_LIFT = com.particlesdevs.photoncamera.R.id.lut_tray;

    /**
     * A chip gives under the finger (scale .95 at once) and springs back on release; the click, the long press and the drag of
     * StripReorder stay the view's own (the listener returns false).
     */
    @SuppressLint("ClickableViewAccessibility")
    static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    view.animate().scaleX(.95f).scaleY(.95f).setDuration(90).setStartDelay(0).setInterpolator(EASE_OUT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f).setDuration(RELEASE.durationMs).setStartDelay(0).setInterpolator(RELEASE).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** A light tick for a choice made (the system's own setting decides whether it is felt). */
    static void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** One colour between {@code a} and {@code b} (ARGB, linear per channel). */
    static int mix(int a, int b, float k) {
        int out = 0;
        for (int s = 0; s <= 24; s += 8) out |= Math.round(((a >>> s) & 255) * (1 - k) + ((b >>> s) & 255) * k) << s;
        return out;
    }

    /**
     * A row of chips that draws its selection pill under them: a rounded rectangle in the accent at a chip's bounds - placed at
     * once, or gliding on {@link #GLIDE} with its width following. Drawn by the row itself (before its children), so it takes no
     * part in the layout.
     */
    static final class ChipRow extends android.widget.LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF now = new RectF(), from = new RectF(), to = new RectF();
        private float radius;
        private boolean shown;
        @Nullable private View target;
        @Nullable private ValueAnimator glide;

        ChipRow(Context c, int color, float radiusPx) {
            super(c);
            paint.setColor(color);
            radius = radiusPx;
            setWillNotDraw(false);
            // a chip whose bounds change after the pill was placed (its text, a relayout) keeps the pill on it
            addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (target != null && target.getParent() == this && (glide == null || !glide.isRunning())) snap(target);
            });
        }

        /** No chip chosen: nothing drawn. */
        void hide() {
            if (glide != null) glide.cancel();
            target = null;
            shown = false;
            invalidate();
        }

        /** The pill to {@code chip} (one of this row's children); at once when not laid out yet or not {@code animate}. */
        void moveTo(View chip, boolean animate) {
            target = chip;
            if (chip.getWidth() <= 0) {
                shown = false; // placed by the layout listener once the chip has its bounds
                return;
            }
            to.set(chip.getLeft(), chip.getTop(), chip.getRight(), chip.getBottom());
            if (glide != null) glide.cancel();
            if (!animate || !shown || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
                snap(chip);
                return;
            }
            from.set(now);
            glide = ValueAnimator.ofFloat(0f, 1f);
            glide.setDuration(GLIDE.durationMs);
            glide.setInterpolator(GLIDE);
            glide.addUpdateListener(a -> {
                final float k = (float) a.getAnimatedValue();
                // the chip may have moved meanwhile (a relayout): aim at where it is now
                if (target != null) to.set(target.getLeft(), target.getTop(), target.getRight(), target.getBottom());
                now.set(from.left + (to.left - from.left) * k, from.top + (to.top - from.top) * k,
                        from.right + (to.right - from.right) * k, from.bottom + (to.bottom - from.bottom) * k);
                invalidate();
            });
            glide.start();
        }

        private void snap(View chip) {
            if (chip.getWidth() <= 0) return;
            now.set(chip.getLeft(), chip.getTop(), chip.getRight(), chip.getBottom());
            shown = true;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (shown) canvas.drawRoundRect(now, radius, radius, paint);
        }

        @Override protected void onDetachedFromWindow() {
            if (glide != null) glide.cancel();
            super.onDetachedFromWindow();
        }
    }
}
