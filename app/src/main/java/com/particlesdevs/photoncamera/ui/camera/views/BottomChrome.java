package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.particlesdevs.photoncamera.R;

import androidx.annotation.Nullable;

/**
 * Geometry of the camera screen's bottom chrome (P32, owner 2026-10-07).
 * <ul>
 * <li>The bottom controls (the lens strip, the shutter row and the zoom ruler's place above the strip) sit
 * {@link #RAISE_FRACTION} of the bottom panel's height higher, all by the same amount. The panel is the bottom bar: the
 * area under the 3:4 viewfinder, or (16:9, video) the translucent bar over the preview's lower part.</li>
 * <li>The HIDDEN shade handle (the shade's dark top edge with its grip) starts at the viewfinder's bottom edge instead of
 * covering the viewfinder's bottom.</li>
 * </ul>
 * Neither may reach the other. Where the handle lies under the viewfinder, the raise stops where the strip would come
 * closer than {@link #CLEARANCE_DP} to it. Where the preview runs on under the controls, the handle floats over the
 * preview just above the strip, so only the panel's top limits the raise (camera_container clips whatever leaves the
 * panel). The manual palette hangs {@link #MANUAL_GAP_DP} above the handle's top wherever the handle is (P43, as in
 * the concept: 12dp above the handle, 40dp above its bottom edge), never lower than that over the panel's top. The zoom
 * ruler above the strip only shows while the zoom changes; when it would come closer than that to the handle, the
 * handle steps aside (fades out and takes no touches) until the ruler has faded out. The pure functions take pixels
 * relative to the panel's top; {@link #attach} applies them on every layout.
 */
public final class BottomChrome {
    /** Share of the bottom panel's height the controls are raised by. */
    public static final float RAISE_FRACTION = 0.10f;
    /** Least gap between the HIDDEN handle and the lens strip or the zoom ruler. */
    static final float CLEARANCE_DP = 8f;
    /** Gap between the manual palette's bottom and the HIDDEN handle's top (P43 concept: 12dp). */
    public static final float MANUAL_GAP_DP = 12f;
    static final long YIELD_MS = 120, RETURN_MS = 220;

    private BottomChrome() {
    }

    /**
     * P157: the screen width at which the bottom bar's dp sizes hold as written (the shutter a fifth of the width). SIMPLE_UI
     * step 0: one formula for the whole camera chrome, {@link com.particlesdevs.photoncamera.circularbarlib.ui.UiScale} (the
     * concept's 392dp phone); the bar's dp values are the concept's.
     */
    public static final float REFERENCE_WIDTH_DP = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.REFERENCE_DP;
    static final float MIN_SCALE = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.MIN,
            MAX_SCALE = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.MAX;

    /**
     * P157 (owner, 2026-10-16: «Исправь масштабирование нижних кнопок в видоискателе, чтобы при разных масштабах было
     * примерно одинаково»): the bottom bar under the 3:4 preview is a share of the screen's width at every display size,
     * so its controls are too: their dp sizes times the width over {@link #REFERENCE_WIDTH_DP} (display size «small» on a
     * 1440 px panel, 480dp: x1.33; the X300 Ultra's 434dp: x1.21; a 360dp screen: as written), within MIN..MAX.
     */
    public static float scale(float widthDp) {
        return com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.k(widthDp);
    }

    /** {@link #scale(float)} of this window (its short side in the current configuration: the camera screen is portrait). */
    public static float scale(Context context) {
        return com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.k(context);
    }

    /**
     * P157: the bottom bar's own views: scaled once by {@code f} (sizes, margins, paddings, text sizes; the lens strip sizes
     * its buttons itself by {@link #scale(Context)}, so only its own margins and padding change here), and styled
     * ({@link #style}). Called before {@link #attach}, which reads the row's margin.
     */
    public static void scaleBar(View bar, float f) {
        if (Math.abs(f - 1f) >= 1e-3f && !Boolean.TRUE.equals(bar.getTag(R.id.layout_bottombar))) {
            bar.setTag(R.id.layout_bottombar, Boolean.TRUE);
            scaleTree(bar, f, true);
        }
        style(bar, f);
    }

    /** Corner radius of the gallery card and the front / back switch (dp before the scale): a large Material shape. */
    public static final float SIDE_RADIUS_DP = 20f;
    /**
     * Corner radius of the lens strip (dp before the scale). P171 (owner, 2026-10-16: «Сделай обводку вокруг модулей камер более
     * закругленную»): 22 of its 46dp height; P185: a capsule - 24 of its 48dp (40dp lenses and the padding), round ends.
     */
    public static final float STRIP_RADIUS_DP = 24f;

    /**
     * P157 (owner, 2026-10-16: «переделай их полностью под наш стиль», then «Сделай эти иконки ближе к monet стилю, но чтобы
     * узнавался всё ещё наш стиль»): the shutter row and the lens strip in Material You's tonal manner on the camera's
     * colours - containers filled with the accent's tone over the card colour instead of grey cards with a line (only a faint
     * tonal outline is left of the cards), large rounded shapes, the chosen lens a filled accent capsule, the front / back
     * switch a tonal button with the accent glyph; the shutter's accent ring is set by CameraUIViewImpl.
     */
    static void style(View bar, float f) {
        Context c = bar.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        int accent = com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle.accent(c);
        int card = com.particlesdevs.photoncamera.ui.settings.SettingsStyle.CARD;
        View gallery = bar.findViewById(R.id.galery_button_container), flip = bar.findViewById(R.id.flip_camera_button);
        View strip = bar.findViewById(R.id.aux_buttons_container);
        if (gallery != null) gallery.setBackground(tonalShape(tone(card, accent, .10f), tone(card, accent, .22f), SIDE_RADIUS_DP * d * f, d));
        if (flip != null) {
            android.graphics.drawable.GradientDrawable mask = tonalShape(0xFFFFFFFF, 0, SIDE_RADIUS_DP * d * f, d);
            flip.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                    (accent & 0x00FFFFFF) | 0x33000000), tonalShape(tone(card, accent, .20f), tone(card, accent, .30f),
                    SIDE_RADIUS_DP * d * f, d), mask));
        }
        // P161 / P171: the strip's rounded outline (STRIP_RADIUS_DP)
        if (strip != null) strip.setBackground(tonalShape(tone(card, accent, .08f), tone(card, accent, .18f), STRIP_RADIUS_DP * d * f, d));
        View thumb = bar.findViewById(R.id.gallery_image_button);
        if (thumb instanceof com.google.android.material.imageview.ShapeableImageView)
            ((com.google.android.material.imageview.ShapeableImageView) thumb).setShapeAppearanceModel(
                    com.google.android.material.shape.ShapeAppearanceModel.builder().setAllCornerSizes((SIDE_RADIUS_DP - 3) * d * f).build());
    }

    /** {@code base} moved {@code amount} of the way to {@code accent} (opaque): a tonal colour of the accent. */
    public static int tone(int base, int accent, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(((base >> 16) & 0xFF) * (1 - a) + ((accent >> 16) & 0xFF) * a);
        int g = Math.round(((base >> 8) & 0xFF) * (1 - a) + ((accent >> 8) & 0xFF) * a);
        int b = Math.round((base & 0xFF) * (1 - a) + (accent & 0xFF) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static android.graphics.drawable.GradientDrawable tonalShape(int fill, int outline, float radiusPx, float density) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        if (outline != 0) g.setStroke(Math.max(1, Math.round(density)), outline);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static void scaleTree(View v, float f, boolean root) {
        if (!root) {   // the bar's own size and margins belong to camera_fragment (its height is the room the preview leaves)
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                if (lp.width > 0) lp.width = Math.round(lp.width * f);
                if (lp.height > 0) lp.height = Math.round(lp.height * f);
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    // Start / end read before any change and set only when the view has them: reading them after
                    // setMarginStart resolved the unset end margin to 0 and scaled the start twice (the gallery thumbnail's
                    // 3dp margin became 3dp f^2 on the left and 0 on the right, the picture slid over the card's right edge).
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    boolean relative = m.isMarginRelative();
                    int start = m.getMarginStart(), end = m.getMarginEnd();
                    m.setMargins(Math.round(m.leftMargin * f), Math.round(m.topMargin * f), Math.round(m.rightMargin * f),
                            Math.round(m.bottomMargin * f));
                    if (relative) {
                        m.setMarginStart(Math.round(start * f));
                        m.setMarginEnd(Math.round(end * f));
                    }
                }
                v.setLayoutParams(lp);
            }
            v.setMinimumWidth(Math.round(v.getMinimumWidth() * f));
            v.setMinimumHeight(Math.round(v.getMinimumHeight() * f));
        }
        v.setPaddingRelative(Math.round(v.getPaddingStart() * f), Math.round(v.getPaddingTop() * f),
                Math.round(v.getPaddingEnd() * f), Math.round(v.getPaddingBottom() * f));
        if (v instanceof android.widget.TextView)
            ((android.widget.TextView) v).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ((android.widget.TextView) v).getTextSize() * f);
        if (v instanceof AuxButtonsLayout || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) scaleTree(g.getChildAt(i), f, false);
    }

    /**
     * Pixels the bottom controls move up: {@link #RAISE_FRACTION} of the panel, within the room there is.
     * <ul>
     * <li>The viewfinder ends above the unraised strip: the handle lies under the viewfinder, and the strip stays
     * {@code clearance} under the handle (0 when even the unraised controls leave no room for it, the handle then
     * covers the least of the viewfinder).</li>
     * <li>The preview runs on under the strip (16:9, video): the handle floats over the preview above the strip
     * ({@link #handleTop}) wherever the strip is, so the strip only stays inside the panel.</li>
     * </ul>
     *
     * @param panel            the bottom panel's height
     * @param controls         height of the unraised controls from the panel's bottom edge up to the strip's top
     * @param viewfinderBottom the viewfinder's bottom edge relative to the panel's top
     * @param handle           the HIDDEN handle's height
     */
    public static int raise(int panel, int controls, int viewfinderBottom, int handle, int clearance) {
        if (panel <= 0) return 0;
        int wanted = Math.round(panel * RAISE_FRACTION);
        int stripRoom = panel - controls;
        int under = Math.max(0, viewfinderBottom);
        int room = under > stripRoom ? stripRoom : stripRoom - under - handle - clearance;
        return Math.max(0, Math.min(wanted, room));
    }

    /**
     * Top of the HIDDEN handle relative to the panel's top: the viewfinder's bottom edge, or the panel's top when the
     * viewfinder ends above it, so the handle never covers the viewfinder. It never comes closer than {@code clearance}
     * to the strip, though: when a 16:9 preview runs on under the controls, the handle sits just above the strip (over
     * the preview, as the whole bottom bar does then).
     *
     * @param viewfinderBottom the viewfinder's bottom edge relative to the panel's top
     * @param stripTop         the (raised) strip's top relative to the panel's top
     */
    public static int handleTop(int viewfinderBottom, int stripTop, int handle, int clearance) {
        return Math.min(Math.max(0, viewfinderBottom), stripTop - clearance - handle);
    }

    /**
     * Bottom margin of the manual palette, which hangs over the panel's top: {@code gap} above the handle's top, and at
     * least {@code gap} above the panel's top (a handle a few pixels under a taller preview leaves the palette where it
     * is). In the 3:4 layout the handle starts at the panel's top, so this is {@code gap}; when the handle floats over
     * the preview (16:9, video) the palette moves up with it.
     */
    public static int manualMargin(int handleTop, int gap) {
        return Math.max(gap, gap - handleTop);
    }

    /**
     * Whether the zoom ruler, while shown, comes closer than {@code clearance} to the HIDDEN handle (the handle spans
     * the screen's width, so only the heights count). Only the ruler's part inside the panel counts: camera_container
     * clips the rest away, so it can be neither seen nor touched.
     *
     * @param rulerTop    the ruler's top relative to the panel's top
     * @param rulerBottom the ruler's bottom relative to the panel's top
     * @param handleTop   the handle's top ({@link #handleTop})
     */
    public static boolean rulerMeetsHandle(int rulerTop, int rulerBottom, int handleTop, int handle, int clearance) {
        int top = Math.max(0, rulerTop);
        return top < rulerBottom && top < handleTop + handle + clearance && rulerBottom + clearance > handleTop;
    }

    /**
     * Keeps the views in step on every layout: the shutter row's bottom margin grows by {@link #raise} (the strip and the
     * ruler's place are chained above the row, so they follow it), {@code handle} moves to {@link #handleTop} by its
     * translation, the manual palette's bottom margin follows {@link #manualMargin} ({@link #MANUAL_GAP_DP} above the
     * handle), and the handle steps aside while the shown ruler meets it ({@link #rulerMeetsHandle}).
     *
     * @param panel      the bottom bar
     * @param row        the shutter row, anchored at the panel's bottom by its bottom margin
     * @param strip      the lens strip, anchored on the row by its bottom margin
     * @param handle     the HIDDEN handle (its slot), laid out with its top at the panel's top
     * @param viewfinder the preview whose bottom edge the handle must not cover
     * @param ruler      the zoom ruler in its slot, the slot anchored on the strip by its bottom margin (or null)
     * @param manual     the manual palette, anchored on the panel's top by its bottom margin (or null)
     */
    public static void attach(View panel, View row, View strip, View handle, View viewfinder, @Nullable ZoomDialView ruler,
                              @Nullable View manual) {
        attach(panel, row, strip, handle, viewfinder, ruler, manual, null);
    }

    /**
     * As above; {@code floats} hears whether the HIDDEN handle floats over the preview (16:9: the preview runs on under the
     * controls), each time that changes (P176: the shade's handle is a pill then).
     */
    public static void attach(View panel, View row, View strip, View handle, View viewfinder, @Nullable ZoomDialView ruler,
                              @Nullable View manual, @Nullable java.util.function.Consumer<Boolean> floats) {
        new Chrome(panel, row, strip, handle, viewfinder, ruler, manual).floats = floats;
    }

    /** P176: the handle floats over the preview when the preview goes on below the handle's top. */
    public static boolean handleFloats(int viewfinderBottom, int handleTop) {
        return viewfinderBottom > handleTop;
    }

    private static final class Chrome implements View.OnLayoutChangeListener {
        private final View panel, row, strip, handle, viewfinder, manual;
        private final ZoomDialView ruler;
        private final ViewGroup.MarginLayoutParams rowParams, manualParams;
        private final int baseMargin, manualGap, clearance;
        private boolean rulerShown, rulerMeets, yielded;
        @Nullable java.util.function.Consumer<Boolean> floats;
        @Nullable private Boolean floating;

        Chrome(View panel, View row, View strip, View handle, View viewfinder, @Nullable ZoomDialView ruler,
               @Nullable View manual) {
            this.panel = panel;
            this.row = row;
            this.strip = strip;
            this.handle = handle;
            this.viewfinder = viewfinder;
            this.ruler = ruler;
            this.manual = manual;
            rowParams = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            baseMargin = rowParams.bottomMargin;
            manualParams = manual != null ? (ViewGroup.MarginLayoutParams) manual.getLayoutParams() : null;
            float density = panel.getResources().getDisplayMetrics().density;
            manualGap = Math.round(MANUAL_GAP_DP * density);
            clearance = Math.round(CLEARANCE_DP * density);
            panel.addOnLayoutChangeListener(this);
            // The preview changes its height on its own (aspect ratio of a new camera session).
            viewfinder.addOnLayoutChangeListener(this);
            if (ruler != null) {
                rulerShown = ruler.getVisibility() == View.VISIBLE;
                ruler.setShownListener(shown -> {
                    rulerShown = shown;
                    yieldHandle();
                });
            }
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight,
                                   int oldBottom) {
            int height = panel.getHeight();
            if (height <= 0) return;
            // The slot's height, also while the shade is open (the controls must not move with the shade).
            int handleHeight = handle.getLayoutParams().height > 0 ? handle.getLayoutParams().height : handle.getHeight();
            int controls = baseMargin + row.getHeight() + ((ViewGroup.MarginLayoutParams) strip.getLayoutParams()).bottomMargin
                    + strip.getHeight();
            int panelY = windowY(panel);
            int viewfinderBottom = windowY(viewfinder) + viewfinder.getHeight() - panelY;
            int lift = raise(height, controls, viewfinderBottom, handleHeight, clearance);
            if (rowParams.bottomMargin != baseMargin + lift) {
                rowParams.bottomMargin = baseMargin + lift;
                // Not inside this layout pass.
                row.post(() -> row.setLayoutParams(rowParams));
            }
            int stripTop = height - controls - lift;
            int handleTop = handleTop(viewfinderBottom, stripTop, handleHeight, clearance);
            float offset = panelY + handleTop - (windowY(handle) - handle.getTranslationY());
            if (handle.getTranslationY() != offset) handle.setTranslationY(offset);
            boolean over = handleFloats(viewfinderBottom, handleTop);
            if (floats != null && !Boolean.valueOf(over).equals(floating)) {
                floating = over;
                floats.accept(over);
            }
            if (manualParams != null) {
                int margin = manualMargin(handleTop, manualGap);
                if (manualParams.bottomMargin != margin) {
                    manualParams.bottomMargin = margin;
                    manual.post(() -> manual.setLayoutParams(manualParams));
                }
            }
            if (ruler != null && ruler.getParent() instanceof View) {
                // The ruler's place in its slot does not move; the slot hangs on the (raised) strip.
                View slot = (View) ruler.getParent();
                int slotHeight = slot.getLayoutParams().height > 0 ? slot.getLayoutParams().height : slot.getHeight();
                int slotTop = stripTop - ((ViewGroup.MarginLayoutParams) slot.getLayoutParams()).bottomMargin - slotHeight;
                boolean laidOut = ruler.getHeight() > 0;
                rulerMeets = rulerMeetsHandle(slotTop + (laidOut ? ruler.getTop() : 0),
                        slotTop + (laidOut ? ruler.getBottom() : slotHeight), handleTop, handleHeight, clearance);
                yieldHandle();
            }
        }

        /** The handle fades out and takes no touches while the shown ruler meets it, and comes back after. */
        private void yieldHandle() {
            boolean yield = rulerShown && rulerMeets;
            if (yield == yielded) return;
            yielded = yield;
            handle.animate().alpha(yield ? 0f : 1f).setDuration(yield ? YIELD_MS : RETURN_MS).start();
            setEnabled(handle, !yield);
        }
    }

    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) setEnabled(((ViewGroup) view).getChildAt(i), enabled);
    }

    private static int windowY(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1];
    }
}
