package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;

/**
 * One tile of the shade: a square CARD with a LINE stroke (10dp gap to its neighbours, half of it is this view's
 * padding) holding the icon of the current value, a short value and the setting's name. A value that differs from the
 * default fills the card with the accent and the content turns INK; an unavailable setting is dimmed to 45 %. The text
 * shrinks (down to 8sp, two lines) instead of ending in an ellipsis. The last tile «Добавить» has a dashed outline.
 * A tile is at least as tall as it is wide; a row of the grid takes the height of its tallest tile.
 */
public class ShadeTileView extends FrameLayout {
    private static final int CARD_PADDING_DP = 4;
    final LinearLayout card;
    final ImageView icon;
    final TextView value;
    final TextView name;
    /** «×» in the edit mode: unpins the tile. */
    final TextView remove;
    @Nullable String key;
    boolean active, dimmed, addTile;
    /** The inline slider card under the grid belongs to this tile: an accent outline. */
    boolean open;
    /** Edit mode: «×» shown, the tile wobbles. */
    boolean editing;
    private int accent;
    @Nullable private android.animation.ObjectAnimator wobble;

    public ShadeTileView(Context context) {
        super(context);
        int half = ShadeStyle.dp(context, 5);
        setPadding(half, half, half, half);
        setClipToPadding(false);
        setClipChildren(false);
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int p = ShadeStyle.dp(context, CARD_PADDING_DP);
        card.setPadding(p, ShadeStyle.dp(context, 6), p, ShadeStyle.dp(context, 6));
        card.setDuplicateParentStateEnabled(true);
        addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(icon, new LinearLayout.LayoutParams(ShadeStyle.dp(context, 26), ShadeStyle.dp(context, 26)));

        value = text(context, 12, ShadeStyle.TEXT);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = ShadeStyle.dp(context, 5);
        card.addView(value, vp);

        name = text(context, 11, ShadeStyle.MUTED);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = ShadeStyle.dp(context, 3);
        card.addView(name, np);

        remove = new TextView(context);
        remove.setText("×");
        remove.setGravity(Gravity.CENTER);
        remove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        remove.setTextColor(0xFF111111);
        remove.setIncludeFontPadding(false);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFE8E9EB);
        remove.setBackground(dot);
        remove.setVisibility(GONE);
        remove.setTag("shade_tile_remove");
        int size = ShadeStyle.dp(context, 22);
        LayoutParams rp = new LayoutParams(size, size, Gravity.TOP | Gravity.END);
        // Over the card's corner, up to the tile's edge (the grid clips anything further out).
        rp.topMargin = -half;
        rp.setMarginEnd(-half);
        addView(remove, rp);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setClickable(true);
    }

    private static TextView text(Context context, float sp, int color) {
        TextView t = new TextView(context);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setMaxLines(2);
        t.setEllipsize(null);
        t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    /** Shows a setting: icon of its current value, short value, name, highlight and dimming. */
    void bind(ShadeCatalog catalog, ShadeCatalog.Entry entry, @Nullable String reason) {
        key = entry.key;
        addTile = false;
        accent = ShadeStyle.accent(getContext());
        active = catalog.changed(entry);
        dimmed = reason != null;
        Drawable d = catalog.icon(entry);
        // A switch that is off: its icon struck through (P25 concept).
        if (d != null && entry.kind == ShadeCatalog.TOGGLE && !catalog.on(entry)) {
            Drawable slash = getContext().getDrawable(R.drawable.ic_shade_slash);
            if (slash != null) d = new android.graphics.drawable.LayerDrawable(new Drawable[]{d, slash});
        }
        icon.setImageDrawable(d);
        value.setText(catalog.valueText(entry, true));
        name.setText(entry.shortTitle);
        name.setVisibility(VISIBLE);
        applyColors();
        String description = getContext().getString(R.string.shade_toast_value, entry.shortTitle, catalog.valueText(entry, false));
        if (active) description += ", " + getContext().getString(R.string.shade_changed);
        if (dimmed) description += ". " + reason;
        setContentDescription(description);
        requestLayout();
    }

    /** The dashed «Добавить» tile. */
    void bindAdd() {
        key = null;
        addTile = true;
        active = false;
        dimmed = false;
        accent = ShadeStyle.accent(getContext());
        icon.setImageResource(R.drawable.ic_shade_plus);
        value.setText(R.string.shade_add);
        name.setText("");
        name.setVisibility(GONE);
        remove.setVisibility(GONE);
        GradientDrawable dashed = new GradientDrawable();
        dashed.setCornerRadius(ShadeStyle.dp(getContext(), 20));
        dashed.setColor(0);
        dashed.setStroke(ShadeStyle.dp(getContext(), 1), ShadeStyle.LINE, ShadeStyle.dp(getContext(), 5), ShadeStyle.dp(getContext(), 4));
        card.setBackground(dashed);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        value.setTextColor(accent);
        card.setAlpha(1f);
        setContentDescription(getContext().getString(R.string.shade_add_description));
        requestLayout();
    }

    void setOpen(boolean open) {
        if (this.open == open) return;
        this.open = open;
        if (!addTile) applyColors();
    }

    private void applyColors() {
        int ink = active ? ShadeStyle.INK : 0;
        int stroke = active ? (open ? ShadeStyle.TEXT : accent) : open ? accent : ShadeStyle.LINE;
        card.setBackground(ShadeStyle.pressable(getContext(), active ? accent : ShadeStyle.CARD, stroke, 20));
        icon.setImageTintList(ColorStateList.valueOf(active ? ink : accent));
        value.setTextColor(active ? ink : ShadeStyle.TEXT);
        value.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        name.setTextColor(active ? ink : ShadeStyle.MUTED);
        card.setAlpha(dimmed ? ShadeStyle.DIMMED : 1f);
    }

    /** Edit mode: «×» on the tile and a gentle wobble (none when animations are off). */
    void setEditing(boolean editing, int index) {
        this.editing = editing;
        remove.setVisibility(editing && !addTile ? VISIBLE : GONE);
        if (editing && !addTile) startWobble(index);
        else stopWobble();
    }

    private void startWobble(int index) {
        if (wobble != null || !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        wobble = android.animation.ObjectAnimator.ofFloat(card, ROTATION, -1.3f, 1.3f);
        wobble.setDuration(320);
        wobble.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        wobble.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wobble.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        // Neighbours out of step, as in the concept.
        if (index % 2 == 1) wobble.setCurrentPlayTime(160);
        wobble.start();
    }

    void stopWobble() {
        if (wobble != null) {
            wobble.cancel();
            wobble = null;
        }
        card.setRotation(0f);
    }

    /** The tile under the finger: a bit larger, the wobble stops. */
    void lift(boolean lifted) {
        if (lifted) stopWobble();
        animate().scaleX(lifted ? 1.08f : 1f).scaleY(lifted ? 1.08f : 1f).setDuration(150).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopWobble();
        super.onDetachedFromWindow();
    }

    /** Landscape: the icon turns with the phone, the labels stay (owner's answer 11). */
    void setIconRotation(float degrees) {
        icon.setRotation(degrees);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int inner = width - getPaddingLeft() - getPaddingRight() - 2 * ShadeStyle.dp(getContext(), CARD_PADDING_DP);
        ShadeStyle.fit(value, inner, 12, 10, 8, 2);
        ShadeStyle.fit(name, inner, 11, 10, 8, 2);
        // Content height at this width, then a square at least.
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int height = Math.max(width, card.getMeasuredHeight() + getPaddingTop() + getPaddingBottom());
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            height = Math.max(height, MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
