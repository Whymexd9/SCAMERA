package com.particlesdevs.photoncamera.ui.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * P384 (owner, 2026-10-28: «Сделай это меню красивым с плавными анимациями и красивыми иконками»): the motion of the settings' main
 * page - the module cards opening and closing in the list (the rows below glide, the new rows fade in with a small rise), the
 * chevron turning, the module chips' gliding pill and the press of a chip. The springs and curves are the LUT tray's (TrayMotion,
 * P372; copied, not shared: the tray's file is its own); every animation is a ValueAnimator / ObjectAnimator, so the system's
 * animator scale and «remove animations» apply, and with motion off or the view not on screen everything is at its end at once.
 */
public final class SettingsMotion {
    private SettingsMotion() {}

    /** The ease of an entry (Material's emphasised decelerate, close to iOS' default curve). */
    public static final TimeInterpolator EASE_OUT = new PathInterpolator(.05f, .7f, .1f, 1f);
    /** The ease of an exit (an emphasised accelerate). */
    public static final TimeInterpolator EXIT = new PathInterpolator(.3f, 0f, .8f, .15f);

    /**
     * A damped spring from 0 to 1 over {@link Spring#durationMs}: x(t) = 1 - e^(-z w t) (cos(wd t) + z w / wd sin(wd t)), w = 2 pi /
     * response, wd = w sqrt(1 - z^2); the last frame is exactly 1 (TrayMotion.Spring).
     */
    public static final class Spring implements TimeInterpolator {
        final float response, damping;
        final long durationMs;

        Spring(float response, float damping) {
            this.response = response;
            this.damping = Math.max(0.05f, Math.min(0.999f, damping));
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

    static final Spring OPEN = new Spring(0.42f, 0.82f);
    /** The chips' selection pill. */
    static final Spring GLIDE = new Spring(0.34f, 0.78f);
    /** A chip coming back after the finger. */
    static final Spring RELEASE = new Spring(0.3f, 0.6f);

    /** Motion runs: the system's animators are on and the view is on screen. */
    public static boolean enabled(@Nullable View v) {
        return v != null && ValueAnimator.areAnimatorsEnabled() && v.isAttachedToWindow();
    }

    /**
     * The card's chevron to {@code deg} (0 closed, 90 open): turned over 240 ms from where it is, or set at once. The card's row is
     * bound again in the same view (the list's change animations are off), so the old angle is still there to turn from.
     */
    public static void turn(@Nullable View chevron, float deg) {
        if (chevron == null) return;
        final Object running = chevron.getTag(R.id.settings_rise);
        if (running instanceof Animator) ((Animator) running).cancel();
        if (chevron.getRotation() == deg || !enabled(chevron)) {
            chevron.setRotation(deg);
            return;
        }
        final ObjectAnimator a = ObjectAnimator.ofFloat(chevron, View.ROTATION, chevron.getRotation(), deg);
        a.setDuration(240);
        a.setInterpolator(EASE_OUT);
        chevron.setTag(R.id.settings_rise, a);
        a.start();
    }

    /**
     * The chips' pill to {@code x} with width {@code w} on the {@link #GLIDE} spring ({@code frame} gets the progress 0..1 of each
     * frame: the chips' text colours follow it); placed at once without motion.
     */
    public static void glide(View pill, float x, int w, @Nullable DoubleConsumer frame) {
        final Object running = pill.getTag(R.id.settings_rise);
        if (running instanceof Animator) ((Animator) running).cancel();
        final ViewGroup.LayoutParams lp = pill.getLayoutParams();
        if (!enabled(pill) || lp.width <= 0 || pill.getVisibility() != View.VISIBLE) {
            pill.setTranslationX(x);
            lp.width = w;
            pill.setLayoutParams(lp);
            if (frame != null) frame.accept(1);
            return;
        }
        final float x0 = pill.getTranslationX();
        final int w0 = lp.width;
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(GLIDE.durationMs);
        a.setInterpolator(GLIDE);
        a.addUpdateListener(v -> {
            final float k = (float) v.getAnimatedValue();
            pill.setTranslationX(x0 + (x - x0) * k);
            lp.width = Math.max(1, Math.round(w0 + (w - w0) * k));
            pill.setLayoutParams(lp);
            if (frame != null) frame.accept(Math.min(1, Math.max(0, v.getAnimatedFraction())));
        });
        pill.setTag(R.id.settings_rise, a);
        a.start();
    }

    /** A chip gives under the finger (scale .97 in 90 ms) and springs back on release; the click stays the view's own. */
    @SuppressLint("ClickableViewAccessibility")
    public static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    if (enabled(view)) view.animate().scaleX(.97f).scaleY(.97f).setDuration(90).setStartDelay(0).setInterpolator(EASE_OUT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    if (enabled(view)) view.animate().scaleX(1f).scaleY(1f).setDuration(RELEASE.durationMs).setStartDelay(0)
                            .setInterpolator(RELEASE).start();
                    else { view.setScaleX(1f); view.setScaleY(1f); }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** A light tick for a choice made (the system's own setting decides whether it is felt). */
    public static void tick(@Nullable View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** One colour between {@code a} and {@code b} (ARGB, linear per channel). */
    public static int mix(int a, int b, float k) {
        int out = 0;
        for (int s = 0; s <= 24; s += 8) out |= Math.round(((a >>> s) & 255) * (1 - k) + ((b >>> s) & 255) * k) << s;
        return out;
    }

    /**
     * An opened card whose rows end below the list's bottom is scrolled into view, smoothly: its last row to the bottom when the
     * whole card fits, else its header to the top. Nothing when it is all on screen already.
     */
    public static void revealSection(@Nullable RecyclerView list, int sectionPos, int lastChildPos) {
        if (list == null || sectionPos < 0 || list.getLayoutManager() == null) return;
        final RecyclerView.LayoutManager lm = list.getLayoutManager();
        final View header = lm.findViewByPosition(sectionPos), last = lm.findViewByPosition(lastChildPos);
        final int bottom = list.getHeight() - list.getPaddingBottom();
        if (last != null && last.getBottom() + last.getTranslationY() <= bottom) return;
        final boolean fits = header != null && last != null && last.getBottom() - header.getTop() <= bottom - list.getPaddingTop();
        final int target = fits ? lastChildPos : sectionPos;
        if (!enabled(list)) {
            lm.scrollToPosition(target);
            return;
        }
        final LinearSmoothScroller scroller = new LinearSmoothScroller(list.getContext()) {
            @Override protected int getVerticalSnapPreference() { return fits ? SNAP_TO_END : SNAP_TO_START; }
            @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics m) { return 60f / m.densityDpi; }
        };
        scroller.setTargetPosition(target);
        lm.startSmoothScroll(scroller);
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * The settings list's item animations (root pages only): rows that go fade out (120 ms), the rows below glide to their place
     * (320 ms, EASE_OUT: most of the way at once, then settling; DefaultItemAnimator's own curve starts slow), rows that come in
     * fade in with a 6 dp rise (280 ms, EASE_OUT) to their own resting alpha (a dimmed row stays dimmed); no change animation (a
     * row bound again stays in its view: the chevron and the badge animate themselves). The rise is this animator's own, not
     * DefaultItemAnimator's add: that one waits for the whole glide (an opened card stood empty, 320 + 240 ms); this one starts
     * when the glide is {@link #ADD_AT} of its time in (about 90 % of the way: the rows are transparent, so a row that rises
     * earlier shows through the rows still gliding past it).
     */
    public static final class ItemAnimator extends DefaultItemAnimator {
        /** The share of the glide's time after which the new rows rise (after the fade-outs, when there are some). */
        static final float ADD_AT = 0.4f;
        private final float rise;
        private final List<RecyclerView.ViewHolder> adds = new ArrayList<>();
        /** The rows rising now and their animators (in the order they started). */
        private final Map<RecyclerView.ViewHolder, Animator> rising = new LinkedHashMap<>();
        /** This layout's pass has fade-outs / glides (DefaultItemAnimator keeps its own lists private). */
        private boolean removing, moving;

        public ItemAnimator(Context c) {
            rise = dp(c, 6);
            setAddDuration(280);
            setRemoveDuration(120);
            setMoveDuration(320);
            setChangeDuration(0);
            setSupportsChangeAnimations(false);
        }

        /** The alpha the row rests at (a dimmed or disabled row). */
        private static float rest(View v) {
            final Object p = v.getTag(R.id.settings_bound_preference);
            return p instanceof androidx.preference.Preference ? SettingsStyle.restingAlpha((androidx.preference.Preference) p) : 1f;
        }

        @Override public boolean animateMove(RecyclerView.ViewHolder h, int fromX, int fromY, int toX, int toY) {
            final boolean pending = super.animateMove(h, fromX, fromY, toX, toY);
            // super set its default curve on the view's animator; the glide it starts in runPendingAnimations takes this one
            if (pending) h.itemView.animate().setInterpolator(EASE_OUT);
            moving |= pending;
            return pending;
        }

        @Override public boolean animateRemove(RecyclerView.ViewHolder h) {
            final boolean pending = super.animateRemove(h);
            removing |= pending;
            return pending;
        }

        @Override public boolean animateAdd(RecyclerView.ViewHolder h) {
            endAnimation(h);
            h.itemView.setAlpha(0f);
            h.itemView.setTranslationY(-rise);
            adds.add(h);
            return true;
        }

        @Override public void runPendingAnimations() {
            super.runPendingAnimations();
            final long delay = (removing ? getRemoveDuration() : 0) + (moving ? Math.round(getMoveDuration() * ADD_AT) : 0);
            removing = moving = false;
            if (adds.isEmpty()) return;
            final List<RecyclerView.ViewHolder> now = new ArrayList<>(adds);
            adds.clear();
            for (RecyclerView.ViewHolder h : now) riseIn(h, delay);
        }

        private void riseIn(RecyclerView.ViewHolder h, long delay) {
            final View v = h.itemView;
            final float rest = rest(v), y0 = v.getTranslationY();
            final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(getAddDuration());
            a.setStartDelay(delay);
            a.setInterpolator(EASE_OUT);
            a.addUpdateListener(x -> {
                final float k = (float) x.getAnimatedValue();
                v.setAlpha(rest * Math.min(1f, k * 1.15f));
                v.setTranslationY(y0 * (1f - k));
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationStart(Animator animation) {
                    dispatchAddStarting(h);
                }

                @Override public void onAnimationEnd(Animator animation) {
                    v.setAlpha(rest(v));
                    v.setTranslationY(0f);
                    if (rising.get(h) == animation) {
                        rising.remove(h);
                        dispatchAddFinished(h);
                        if (!isRunning()) dispatchAnimationsFinished();
                    }
                }
            });
            rising.put(h, a);
            a.start();
        }

        /** The row's rise (pending or running) ends at once: at its resting alpha, in its place. */
        private void finishAdd(RecyclerView.ViewHolder h) {
            final View v = h.itemView;
            if (adds.remove(h)) {
                v.setAlpha(rest(v));
                v.setTranslationY(0f);
                dispatchAddFinished(h);
                return;
            }
            final Animator running = rising.get(h);
            if (running != null) running.end();
        }

        @Override public void endAnimation(@NonNull RecyclerView.ViewHolder h) {
            finishAdd(h);
            super.endAnimation(h);
        }

        @Override public void endAnimations() {
            for (RecyclerView.ViewHolder h : new ArrayList<>(adds)) finishAdd(h);
            for (RecyclerView.ViewHolder h : new ArrayList<>(rising.keySet())) finishAdd(h);
            super.endAnimations();
        }

        /** The rise counts: the list redraws its card decoration while a row still rises. */
        @Override public boolean isRunning() {
            return super.isRunning() || !adds.isEmpty() || !rising.isEmpty();
        }
    }
}
