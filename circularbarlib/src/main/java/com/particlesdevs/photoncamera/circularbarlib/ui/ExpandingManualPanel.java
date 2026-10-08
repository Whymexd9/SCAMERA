package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.particlesdevs.photoncamera.circularbarlib.R;

/**
 * The manual panel in the shade's card style (MANUAL_TASK.md §1-§2): the ruler card above a strip of the 52dp toggle card
 * and the card of five chips. The toggle collapses or expands the chips (collapsed: invisible, scaled to 60 % from the left);
 * collapsing also closes the ruler. While any applied parameter is manual the toggle shows a small accent dot.
 * <p>
 * The panel is on screen only while the shade is HIDDEN ({@link #setShadeShown}): it fades and slides out at the other
 * levels and comes back in the same state.
 */
public class ExpandingManualPanel extends LinearLayout {
    private View tabs, scale, toggle, dot;
    private ImageView toggleIcon;
    private ValueAnimator animator;
    private float progress;
    private boolean expanded;
    private Runnable onCollapse;
    private int accent;

    public ExpandingManualPanel(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        tabs = findViewById(R.id.buttons_container);
        scale = findViewById(R.id.knobViewContainer);
        toggle = findViewById(R.id.manual_toggle);
        dot = findViewById(R.id.manual_toggle_dot);
        toggleIcon = findViewById(R.id.manual_toggle_icon);
        Context c = getContext();
        tabs.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 20));
        scale.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 18));
        toggle.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 20));
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        dot.setBackground(round);
        // The ruler header's text in sp, grown with the font size only up to UiTokens.FONT_SCALE_CAP (P43).
        capText(R.id.manual_ruler_value, 13f);
        capText(R.id.manual_all_auto, 12.5f);
        setAccent(UiTokens.cameraAccent(c));
        tabs.setPivotX(0);
        tabs.setVisibility(INVISIBLE);
        tabs.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        toggle.setOnClickListener(v -> setExpanded(!expanded, true));
        progress = 0;
        updateFrame();
    }

    private void capText(int id, float sp) {
        View view = findViewById(id);
        if (view instanceof android.widget.TextView) UiTokens.setTextSp((android.widget.TextView) view, sp);
    }

    /** The camera accent of the toggle icon and dot. */
    public void setAccent(int accent) {
        this.accent = accent;
        if (toggleIcon != null) toggleIcon.setImageTintList(ColorStateList.valueOf(accent));
        if (dot != null && dot.getBackground() instanceof GradientDrawable) ((GradientDrawable) dot.getBackground()).setColor(accent);
    }

    /** Called when the strip collapses: the console closes the ruler and forgets the selected parameter. */
    public void setOnCollapseListener(Runnable listener) {
        onCollapse = listener;
    }

    /** The accent dot on the toggle: any applied parameter is manual. */
    public void setManualDot(boolean shown) {
        if (dot != null) dot.setVisibility(shown ? VISIBLE : GONE);
    }

    public boolean isManualDotShown() {
        return dot != null && dot.getVisibility() == VISIBLE;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean value, boolean animate) {
        expanded = value;
        if (animator != null) animator.cancel();
        if (!value) {
            // Collapsing closes the ruler (the owner's rule), it does not just hide it.
            scale.setVisibility(GONE);
            if (onCollapse != null) onCollapse.run();
        }
        tabs.setVisibility(VISIBLE);
        tabs.setImportantForAccessibility(value ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        toggle.setContentDescription(getContext().getString(value ? R.string.manual_panel_close : R.string.manual_panel_open));
        if (!animate) {
            progress = value ? 1 : 0;
            updateFrame();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, value ? 1 : 0);
        animator.setDuration(value ? 220 : 180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            updateFrame();
        });
        animator.start();
    }

    /** Collapsed = opacity 0 and scaleX .6 from the left; it takes no touches then. */
    private void updateFrame() {
        float p = Math.max(0, Math.min(1, progress));
        tabs.setAlpha(p);
        tabs.setScaleX(.6f + .4f * p);
        // The first animation frame has p=0. Later frames must restore visibility.
        tabs.setVisibility(expanded || p > 0 ? VISIBLE : INVISIBLE);
    }

    /** Whether the shade allows the panel on screen (the shade is HIDDEN). */
    private boolean shadeShown = true;
    /** Duration of the fade and slide when the shade leaves or returns to HIDDEN. */
    static final int SHADE_FADE_MS = 200;
    /** How far the panel slides down while it fades out. */
    static final float SHADE_SLIDE_DP = 16;

    public boolean isShadeShown() {
        return shadeShown;
    }

    /**
     * Shows the panel while the shade is HIDDEN and hides it at the other levels: a short fade and slide (alpha, 16dp down),
     * INVISIBLE at the end so it takes no touches. Nothing else changes, so it comes back open or closed with the same
     * parameter selected.
     */
    public void setShadeShown(boolean shown, boolean animate) {
        boolean changed = shown != shadeShown;
        shadeShown = shown;
        animate().cancel();
        float slide = dp(SHADE_SLIDE_DP);
        if (!animate) {
            setAlpha(shown ? 1f : 0f);
            setTranslationY(shown ? 0f : slide);
            setVisibility(shown ? VISIBLE : INVISIBLE);
            return;
        }
        if (!changed && (shown ? getVisibility() == VISIBLE && getAlpha() == 1f : getVisibility() != VISIBLE)) return;
        if (shown) {
            setVisibility(VISIBLE);
            animate().alpha(1f).translationY(0f).setDuration(SHADE_FADE_MS).start();
        } else {
            animate().alpha(0f).translationY(slide).setDuration(SHADE_FADE_MS)
                    .withEndAction(() -> { if (!shadeShown) setVisibility(INVISIBLE); }).start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
