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
 * One tile of the quick sheet, as the concept draws it (SIMPLE_UI §2, .tile): a compact rectangle, wider than tall (50dp of
 * card, 5dp gaps - half of it this view's padding; x the screen's scale), 13dp corners, the card colour at the sheet's
 * alpha with a LINE stroke, holding the icon of the current value (18dp, muted), the short name (11sp, weight 600, one
 * line) and the value (10sp, muted, one line). A value that differs from the default tints the card with the accent (14 %,
 * a 35 % accent stroke) and turns the icon and the value accent; an unavailable setting is dimmed to 45 %. The text shrinks
 * (down to 8sp) instead of ending in an ellipsis. The last tile «Добавить» has a dashed outline and no fill.
 */
public class ShadeTileView extends FrameLayout {
    private static final float CARD_PADDING_DP = 2f;
    /** The card's least height (the concept's .tile min-height) before the scale. */
    static final float CARD_HEIGHT_DP = 50f;    /** The sheet's alpha the cards share (the concept's 40 % transparency: the fill at 60 %); SettingsBarLayout sets it. */
    static float sheetAlpha = .6f;
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
        int half = Math.round(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dpF(context, 2.5f));
        setPadding(half, half, half, half);
        setClipToPadding(false);
        setClipChildren(false);
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int p = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, CARD_PADDING_DP);
        card.setPadding(p, com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 4), p,
                com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 3));
        card.setDuplicateParentStateEnabled(true);
        addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconSize = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 18);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
        ip.bottomMargin = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 2);
        card.addView(icon, ip);

        // the concept's order: the name (11sp, 600) over the value (10sp, muted)
        name = text(context, 11, ShadeStyle.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setLetterSpacing(-.01f);
        card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        value = text(context, 10, ShadeStyle.MUTED);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 1);
        card.addView(value, vp);

        // the concept's .x: a 20dp card disc with a line, the «×» in the text colour
        remove = new TextView(context);
        remove.setText("×");
        remove.setGravity(Gravity.CENTER);
        remove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        remove.setTextColor(ShadeStyle.TEXT);
        remove.setIncludeFontPadding(false);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF232A2E);
        dot.setStroke(Math.max(1, ShadeStyle.dp(context, 1)), ShadeStyle.LINE);
        remove.setBackground(dot);
        remove.setVisibility(GONE);
        remove.setTag("shade_tile_remove");
        int size = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 20);
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
        t.setMaxLines(1);
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
        // SIMPLE_UI: the icon as it is (the concept strikes nothing through; the value says «Выкл.»)
        icon.setImageDrawable(catalog.icon(entry));
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
        name.setText(R.string.shade_add);
        name.setVisibility(VISIBLE);
        value.setText("");
        value.setVisibility(GONE);
        remove.setVisibility(GONE);
        GradientDrawable dashed = new GradientDrawable();
        dashed.setCornerRadius(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dpF(getContext(), 13));
        dashed.setColor(0);
        dashed.setStroke(ShadeStyle.dp(getContext(), 1), ShadeStyle.LINE, ShadeStyle.dp(getContext(), 5), ShadeStyle.dp(getContext(), 4));
        card.setBackground(dashed);
        icon.setImageTintList(ColorStateList.valueOf(ShadeStyle.MUTED));
        name.setTextColor(ShadeStyle.MUTED);
        name.setTypeface(Typeface.DEFAULT);
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
        value.setVisibility(VISIBLE);
        int alpha = Math.round(255 * ShadeStyle.sheetAlpha(getContext()));   // the sheet's see-through alpha
        int fill = active ? (accent & 0x00FFFFFF) | 0x24000000 : (ShadeStyle.CARD & 0x00FFFFFF) | (alpha << 24);
        int stroke = open ? accent : active ? (accent & 0x00FFFFFF) | 0x59000000 : ShadeStyle.LINE;
        card.setBackground(ShadeStyle.pressable(getContext(), fill, stroke,
                com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dpF(getContext(), 13) / getResources().getDisplayMetrics().density));
        icon.setImageTintList(ColorStateList.valueOf(active ? accent : ShadeStyle.MUTED));
        value.setTextColor(active ? accent : ShadeStyle.MUTED);
        name.setTextColor(ShadeStyle.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        wobble = android.animation.ObjectAnimator.ofFloat(card, ROTATION, -.8f, .8f);
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
        int inner = width - getPaddingLeft() - getPaddingRight() - 2 * com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(getContext(), CARD_PADDING_DP);
        float k = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.k(getContext());
        ShadeStyle.fit(name, inner, 11 * k, 9 * k, 8, 1);
        ShadeStyle.fit(value, inner, 10 * k, 9 * k, 8, 1);
        // Content height at this width, at least the concept's 50dp card (a compact rectangle, wider than tall).
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int least = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(getContext(), CARD_HEIGHT_DP) + getPaddingTop() + getPaddingBottom();
        int height = Math.max(least, card.getMeasuredHeight() + getPaddingTop() + getPaddingBottom());
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            height = Math.max(height, MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
