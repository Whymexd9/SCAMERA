package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.RawCrop;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.Locale;

/**
 * P162 (owner, 2026-10-16: «Сделай эту группу настроек с наглядной красивой визуализацией как кадр будет обрезан, с красивыми
 * анимациями и видом»): the frame as on an upright photo - a small picture standing for the shot - with what goes: the black
 * stripes the automatic crop found (black, hatched), the own margins (the accent's tint), the kept frame in the accent with
 * its corners, the cut of each side next to it, and the sizes before and after under it. Every change glides there
 * (Material's emphasized deceleration); the kept frame's edges drag with a finger and set the own margin of that side.
 */
public class RawCropView extends View {
    /** Sides in the order of RawCrop: left, top, right, bottom. */
    static final int LEFT = 0, TOP = 1, RIGHT = 2, BOTTOM = 3;
    static final long ANIMATION_MS = 420, INTRO_DELAY_MS = 120;
    /** A cut side is drawn at least this thick (the X200 Pro's 72 of 3072 px would be a hairline), growing in over 8 px. */
    static final float MIN_VISIBLE_DP = 10f;

    /** What the shot will cut, as on the upright photo. */
    public static final class State {
        /** The frame (as on the upright photo) and what stays of it, px. */
        public final int width, height, keptWidth, keptHeight;
        /** Per side (left, top, right, bottom): the zero padding the automatic crop cuts, and the whole cut. */
        public final int[] auto, cut;
        /** A shot measured the frame; else a 4:3 sketch without sizes. */
        public final boolean measured;
        public final boolean autoOn;

        State(int width, int height, int keptWidth, int keptHeight, int[] auto, int[] cut, boolean measured, boolean autoOn) {
            this.width = width; this.height = height; this.keptWidth = keptWidth; this.keptHeight = keptHeight;
            this.auto = auto; this.cut = cut; this.measured = measured; this.autoOn = autoOn;
        }

        boolean cuts() {
            return cut[0] + cut[1] + cut[2] + cut[3] > 0;
        }
    }

    /**
     * The crop of the next shot by the same rules as the processing (RawCrop.keep: the larger of the padding and the own margin
     * per side, the origin on whole colour periods): {@code detected} is the last shot's RawCrop.describe text ("" before one).
     */
    public static State compute(boolean autoOn, int[] manualPhoto, @Nullable String detected) {
        final int[] d = RawCrop.parse(detected);
        final boolean measured = d != null;
        final int w = measured ? d[0] : 4096, h = measured ? d[1] : 3072, orientation = measured ? d[6] : 90;
        final int[] padding = measured && autoOn ? new int[]{d[2], d[3], d[4], d[5]} : new int[4];
        final int[] manual = RawCrop.photoToSensor(manualPhoto, orientation);
        final RawCrop.Area area = RawCrop.keep(w, h, RawCrop.larger(padding, manual));
        final int[] cutSensor = area == null ? new int[4]
                : new int[]{area.x, area.y, w - area.x - area.width, h - area.y - area.height};
        final boolean turned = RawCrop.quarterTurns(orientation) % 2 == 1;
        final int keptW = area == null ? w : area.width, keptH = area == null ? h : area.height;
        return new State(turned ? h : w, turned ? w : h, turned ? keptH : keptW, turned ? keptW : keptH,
                RawCrop.sensorToPhoto(padding, orientation), RawCrop.sensorToPhoto(cutSensor, orientation), measured, autoOn);
    }

    /** A side's own margin dragged on the picture. */
    public interface OnMarginDrag {
        /** {@code done}: the finger lifted (store it); else the value while dragging. */
        void onMargin(int side, int px, boolean done);
    }

    private final float d;
    private final int accent;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            hatch = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF frame = new RectF(), kept = new RectF(), band = new RectF();
    private final PathInterpolator emphasized = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    @Nullable private State state;
    /** Shown per side (px of the frame), gliding to the state's. */
    private final float[] autoShown = new float[4], cutShown = new float[4], fromAuto = new float[4], fromCut = new float[4];
    /** 0..1 of the running glide (hatching moves with it), and the labels' fade. */
    private float progress = 1f, labels = 1f;
    @Nullable private ValueAnimator glide;
    private boolean introduced;
    @Nullable private OnMarginDrag onDrag;
    /** The side a finger drags (-1: none) and its value, px. */
    private int dragging = -1, dragValue;

    public RawCropView(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        accent = SettingsStyle.accent(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        hatch.setStyle(Paint.Style.STROKE);
        hatch.setStrokeWidth(Math.max(1f, d));
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(Math.max(1f, d));
        dash.setPathEffect(new DashPathEffect(new float[]{4 * d, 3 * d}, 0));
        text.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setOnMarginDrag(@Nullable OnMarginDrag onDrag) {
        this.onDrag = onDrag;
    }

    @Nullable
    public State state() {
        return state;
    }

    /** Shows {@code next}: gliding from what is shown, or at once. The first state glides in from a whole frame. */
    public void show(State next, boolean animate) {
        final State before = state;
        state = next;
        setContentDescription(status(next));
        if (dragging >= 0) return;   // the finger leads; the stored state follows when it lifts
        final boolean intro = !introduced && isAttachedToWindow() && animatorsOn();
        if (!intro && (!animate || before == null || !animatorsOn())) {
            if (glide != null) glide.cancel();
            for (int i = 0; i < 4; i++) {
                autoShown[i] = next.auto[i];
                cutShown[i] = next.cut[i];
            }
            progress = 1f;
            labels = 1f;
            introduced |= isAttachedToWindow();
            invalidate();
            return;
        }
        if (intro) {
            introduced = true;
            java.util.Arrays.fill(autoShown, 0f);
            java.util.Arrays.fill(cutShown, 0f);
            labels = 0f;
        }
        glideTo(next, intro ? INTRO_DELAY_MS : 0);
    }

    private void glideTo(State next, long delay) {
        if (glide != null) glide.cancel();
        System.arraycopy(autoShown, 0, fromAuto, 0, 4);
        System.arraycopy(cutShown, 0, fromCut, 0, 4);
        final float fromLabels = labels;
        glide = ValueAnimator.ofFloat(0f, 1f);
        glide.setDuration(ANIMATION_MS);
        glide.setStartDelay(delay);
        glide.setInterpolator(emphasized);
        glide.addUpdateListener(a -> {
            final float t = (float) a.getAnimatedValue();
            for (int i = 0; i < 4; i++) {
                autoShown[i] = fromAuto[i] + (next.auto[i] - fromAuto[i]) * t;
                cutShown[i] = fromCut[i] + (next.cut[i] - fromCut[i]) * t;
            }
            progress = t;
            labels = fromLabels + (1f - fromLabels) * t;
            invalidate();
        });
        glide.start();
    }

    private static boolean animatorsOn() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (state != null && !introduced) show(state, true);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (glide != null) glide.cancel();
        super.onDetachedFromWindow();
    }

    // ---- layout ----

    private float topGutter() { return 40 * d; }
    private float bottomGutter() { return 52 * d; }
    private float sideGutter() { return 36 * d; }
    private float maxFrameHeight() { return 250 * d; }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int width = MeasureSpec.getSize(widthSpec);
        final float aspect = state == null ? 4f / 3f : state.height / (float) state.width;
        final float frameH = Math.min(maxFrameHeight(), Math.max(80 * d, (width - 2 * sideGutter()) * aspect));
        setMeasuredDimension(width, Math.round(topGutter() + frameH + bottomGutter()));
    }

    /** The frame's rectangle on the view (fits between the gutters, centred). */
    private void layoutFrame() {
        final float aspect = state == null ? 4f / 3f : state.height / (float) state.width;
        final float availW = getWidth() - 2 * sideGutter(), availH = getHeight() - topGutter() - bottomGutter();
        float fw = availW, fh = fw * aspect;
        if (fh > availH) {
            fh = availH;
            fw = fh / aspect;
        }
        final float left = (getWidth() - fw) / 2f, top = topGutter();
        frame.set(left, top, left + fw, top + fh);
    }

    private float scale() {
        return state == null || state.width <= 0 ? 0f : frame.width() / state.width;
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        if (state == null) return;
        layoutFrame();
        final float s = scale();
        keptRect(s);
        final float radius = 8 * d;
        drawPicture(canvas, radius);
        // what goes: a scrim over the cut, the black stripes (hatched), the own margins in the accent's tint
        final int saved = canvas.save();
        path.reset();
        path.addRoundRect(frame, radius, radius, Path.Direction.CW);
        canvas.clipPath(path);
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(frame, Path.Direction.CW);
        path.addRect(kept, Path.Direction.CW);
        fill.setShader(null);
        fill.setColor(SettingsStyle.tint(accent, .30f) & 0x00FFFFFF | 0x99000000);
        canvas.drawPath(path, fill);
        for (int side = 0; side < 4; side++) drawStripe(canvas, side, s);
        canvas.restoreToCount(saved);
        // the frame's own outline, then the kept frame: a soft glow, the line and its corners
        stroke.setColor(SettingsStyle.LINE);
        stroke.setStrokeWidth(Math.max(1f, d));
        canvas.drawRoundRect(frame, radius, radius, stroke);
        final boolean cuts = cutShown[0] + cutShown[1] + cutShown[2] + cutShown[3] > .5f;
        if (cuts) {
            stroke.setColor((accent & 0x00FFFFFF) | 0x38000000);
            stroke.setStrokeWidth(6 * d);
            canvas.drawRect(kept, stroke);
            stroke.setColor(accent);
            stroke.setStrokeWidth(1.5f * d);
            canvas.drawRect(kept, stroke);
        }
        drawCorners(canvas, cuts ? kept : frame, cuts ? accent : SettingsStyle.MUTED);
        drawSideLabels(canvas);
        drawLegend(canvas);
        drawSizes(canvas);
    }

    /** A small landscape standing for the shot: sky of the accent's tones, a sun, two hills. */
    private void drawPicture(Canvas canvas, float radius) {
        final int saved = canvas.save();
        path.reset();
        path.setFillType(Path.FillType.WINDING);
        path.addRoundRect(frame, radius, radius, Path.Direction.CW);
        canvas.clipPath(path);
        final int card = SettingsStyle.CARD;
        fill.setShader(new LinearGradient(0, frame.top, 0, frame.bottom, mix(card, accent, .42f), mix(card, accent, .12f), Shader.TileMode.CLAMP));
        canvas.drawRect(frame, fill);
        fill.setShader(null);
        final float w = frame.width(), h = frame.height();
        fill.setColor(mix(SettingsStyle.TEXT, accent, .35f));
        canvas.drawCircle(frame.left + .70f * w, frame.top + .27f * h, .085f * w, fill);
        path.reset();
        path.moveTo(frame.left, frame.top + .68f * h);
        path.quadTo(frame.left + .30f * w, frame.top + .50f * h, frame.left + .62f * w, frame.top + .70f * h);
        path.quadTo(frame.left + .82f * w, frame.top + .80f * h, frame.right, frame.top + .66f * h);
        path.lineTo(frame.right, frame.bottom);
        path.lineTo(frame.left, frame.bottom);
        path.close();
        fill.setColor(mix(card, accent, .24f));
        canvas.drawPath(path, fill);
        path.reset();
        path.moveTo(frame.left, frame.top + .82f * h);
        path.quadTo(frame.left + .45f * w, frame.top + .70f * h, frame.right, frame.top + .86f * h);
        path.lineTo(frame.right, frame.bottom);
        path.lineTo(frame.left, frame.bottom);
        path.close();
        fill.setColor(mix(SettingsStyle.BG, accent, .10f));
        canvas.drawPath(path, fill);
        canvas.restoreToCount(saved);
    }

    /** A cut of {@code px} frame pixels on the view: to scale, but at least MIN_VISIBLE_DP once it is 8 px or more. */
    private float visual(float px, float s) {
        if (px <= .5f) return 0f;
        return Math.max(px * s, MIN_VISIBLE_DP * d * Math.min(1f, px / 8f));
    }

    /** The automatic part of a side's cut on the view. */
    private float autoVisual(int side, float s) {
        return visual(Math.min(autoShown[side], cutShown[side]), s);
    }

    /** A side's whole cut on the view: the own margin past the stripe stays visible too. */
    private float cutVisual(int side, float s) {
        final float auto = autoVisual(side, s), own = cutShown[side] - Math.min(autoShown[side], cutShown[side]);
        return own <= .5f ? auto : Math.max(visual(cutShown[side], s), auto + 3 * d * Math.min(1f, own / 8f));
    }

    /** {@link #kept} = the kept frame on the view. */
    private void keptRect(float s) {
        kept.set(frame.left + cutVisual(LEFT, s), frame.top + cutVisual(TOP, s),
                frame.right - cutVisual(RIGHT, s), frame.bottom - cutVisual(BOTTOM, s));
    }

    /** One side's stripe: the zero padding black and hatched, then the own margin past it in the accent's tint. */
    private void drawStripe(Canvas canvas, int side, float s) {
        final float auto = autoVisual(side, s), cut = cutVisual(side, s);
        if (auto > .25f) {
            sideBand(side, 0, auto);
            fill.setColor(0xFF050607);
            canvas.drawRect(band, fill);
            final int saved = canvas.save();
            canvas.clipRect(band);
            hatch.setColor((SettingsStyle.MUTED & 0x00FFFFFF) | 0x8C000000);
            final float step = 7 * d, shift = progress * step * 2;
            for (float x = band.left - band.height() - step + (shift % step); x < band.right + step; x += step)
                canvas.drawLine(x, band.bottom, x + band.height(), band.top, hatch);
            canvas.restoreToCount(saved);
        }
        if (cut - auto > .25f) {
            sideBand(side, auto, cut);
            fill.setColor((accent & 0x00FFFFFF) | 0x4D000000);
            canvas.drawRect(band, fill);
        }
        if (cut > .25f) {   // the cut's edge, dashed
            dash.setColor((accent & 0x00FFFFFF) | 0x99000000);
            switch (side) {
                case LEFT: canvas.drawLine(frame.left + cut, frame.top, frame.left + cut, frame.bottom, dash); break;
                case TOP: canvas.drawLine(frame.left, frame.top + cut, frame.right, frame.top + cut, dash); break;
                case RIGHT: canvas.drawLine(frame.right - cut, frame.top, frame.right - cut, frame.bottom, dash); break;
                default: canvas.drawLine(frame.left, frame.bottom - cut, frame.right, frame.bottom - cut, dash); break;
            }
        }
    }

    /** {@link #band} = the strip of {@code side} from {@code from} to {@code to} (view px inwards from the frame's edge). */
    private void sideBand(int side, float from, float to) {
        switch (side) {
            case LEFT: band.set(frame.left + from, frame.top, frame.left + to, frame.bottom); break;
            case TOP: band.set(frame.left, frame.top + from, frame.right, frame.top + to); break;
            case RIGHT: band.set(frame.right - to, frame.top, frame.right - from, frame.bottom); break;
            default: band.set(frame.left, frame.bottom - to, frame.right, frame.bottom - from); break;
        }
    }

    private void drawCorners(Canvas canvas, RectF r, int color) {
        final float len = Math.min(14 * d, Math.min(r.width(), r.height()) / 3f);
        stroke.setColor(color);
        stroke.setStrokeWidth(3f * d);
        path.reset();
        path.moveTo(r.left, r.top + len); path.lineTo(r.left, r.top); path.lineTo(r.left + len, r.top);
        path.moveTo(r.right - len, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + len);
        path.moveTo(r.right, r.bottom - len); path.lineTo(r.right, r.bottom); path.lineTo(r.right - len, r.bottom);
        path.moveTo(r.left + len, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - len);
        canvas.drawPath(path, stroke);
    }

    /** Each cut side's pixels next to it: the accent when the own margin leads, else muted. */
    private void drawSideLabels(Canvas canvas) {
        final State st = state;
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final Paint.FontMetrics fm = text.getFontMetrics();
        final float mid = -(fm.ascent + fm.descent) / 2f;
        for (int side = 0; side < 4; side++) {
            final int value = dragging == side ? Math.max(dragValue, st.auto[side]) : st.cut[side];
            if (value <= 0 && dragging != side) continue;
            final boolean own = dragging == side || st.cut[side] > st.auto[side];
            text.setColor(own ? accent : SettingsStyle.MUTED);
            text.setAlpha(Math.round(255 * labels));
            final String label = String.valueOf(value);
            switch (side) {
                case LEFT: canvas.drawText(label, (getPaddingLeft() + frame.left) / 2f, kept.centerY() + mid, text); break;
                case RIGHT: canvas.drawText(label, (frame.right + getWidth()) / 2f, kept.centerY() + mid, text); break;
                case TOP: canvas.drawText(label, kept.centerX(), frame.top - 7 * d, text); break;
                default: canvas.drawText(label, kept.centerX(), frame.bottom + 14 * d, text); break;
            }
        }
        text.setAlpha(255);
    }

    /** Top: what the colours mean. */
    private void drawLegend(Canvas canvas) {
        text.setTextSize(10.5f * getResources().getDisplayMetrics().scaledDensity);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(SettingsStyle.MUTED);
        final float box = 9 * d, y = 6 * d;
        final String stripes = Lang.t(getContext(), "Чёрные полосы", "Black stripes"), own = Lang.t(getContext(), "Свои поля", "Own margins");
        final float w1 = box + 5 * d + text.measureText(stripes), w2 = box + 5 * d + text.measureText(own);
        float x = (getWidth() - (w1 + 14 * d + w2)) / 2f;
        band.set(x, y, x + box, y + box);
        fill.setColor(0xFF050607);
        canvas.drawRoundRect(band, 2 * d, 2 * d, fill);
        stroke.setColor(SettingsStyle.LINE);
        stroke.setStrokeWidth(Math.max(1f, d));
        canvas.drawRoundRect(band, 2 * d, 2 * d, stroke);
        canvas.drawText(stripes, x + box + 5 * d, y + box - 1 * d, text);
        x += w1 + 14 * d;
        band.set(x, y, x + box, y + box);
        fill.setColor((accent & 0x00FFFFFF) | 0x80000000);
        canvas.drawRoundRect(band, 2 * d, 2 * d, fill);
        canvas.drawText(own, x + box + 5 * d, y + box - 1 * d, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Bottom: the sizes before and after (or what is still unknown) and what the automatic crop found. */
    private void drawSizes(Canvas canvas) {
        final State st = state;
        final float base = frame.bottom + 34 * d;
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        text.setColor(SettingsStyle.TEXT);
        canvas.drawText(sizes(st), getWidth() / 2f, base, text);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(11.5f * getResources().getDisplayMetrics().scaledDensity);
        text.setColor(SettingsStyle.MUTED);
        canvas.drawText(found(getContext(), st), getWidth() / 2f, base + 16 * d, text);
    }

    static String sizes(State st) {
        if (!st.measured) return Lang.t("Размер кадра — после первого снимка", "The frame's size: after the first shot");
        final String before = st.width + " × " + st.height;
        if (!st.cuts()) return Lang.t("Кадр целиком · ", "Whole frame · ") + before;
        final double lost = 100.0 * (1.0 - (double) st.keptWidth * st.keptHeight / ((double) st.width * st.height));
        return before + "  →  " + st.keptWidth + " × " + st.keptHeight + "  ·  −" + String.format(Lang.ru() ? new Locale("ru") : Locale.US, "%.1f", lost) + " %";
    }

    static String found(Context c, State st) {
        if (!st.autoOn) return Lang.t(c, "Автоматическая обрезка выключена", "Automatic crop is off");
        if (!st.measured) return Lang.t(c, "Полосы найдутся на первом снимке этим модулем", "Stripes are found on the first shot with this module");
        final String[] names = {Lang.t(c, "слева", "left"), Lang.t(c, "сверху", "top"), Lang.t(c, "справа", "right"), Lang.t(c, "снизу", "bottom")};
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (st.auto[i] <= 0) continue;
            sb.append(sb.length() == 0 ? "" : ", ").append(names[i]).append(' ').append(st.auto[i]);
        }
        return sb.length() == 0 ? Lang.t(c, "Чёрных полос в кадре нет", "No black stripes in the frame")
                : Lang.t(c, "Чёрные полосы: ", "Black stripes: ") + sb;
    }

    private String status(State st) {
        return sizes(st) + ". " + found(getContext(), st);
    }

    private static int mix(int base, int accent, float amount) {
        return com.particlesdevs.photoncamera.ui.camera.views.BottomChrome.tone(base, accent, amount);
    }

    // ---- dragging an edge ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (state == null || !isEnabled()) return false;
        final float x = e.getX(), y = e.getY(), s = scale();
        if (s <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragging = nearestEdge(x, y);
                if (dragging < 0) return false;
                if (glide != null) glide.cancel();
                getParent().requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                dragValue = state.cut[dragging];
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging < 0) return false;
                final int value = marginAt(dragging, x, y, s);
                if (value != dragValue) {
                    if (value / 64 != dragValue / 64) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    dragValue = value;
                    cutShown[dragging] = Math.max(state.auto[dragging], value);
                    if (onDrag != null) onDrag.onMargin(dragging, value, false);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging < 0) return false;
                final int side = dragging, value = dragValue;
                dragging = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (onDrag != null) onDrag.onMargin(side, value, true);
                if (state != null) glideTo(state, 0);   // to the stored crop (whole colour periods)
                return true;
            }
            default:
                return false;
        }
    }

    /** The kept frame's edge under the finger (24dp each way), else -1. */
    int nearestEdge(float x, float y) {
        layoutFrame();
        final float s = scale(), reach = 24 * d;
        keptRect(s);
        int best = -1;
        float bestDistance = reach;
        final boolean alongX = x > kept.left - reach && x < kept.right + reach, alongY = y > kept.top - reach && y < kept.bottom + reach;
        final float[] distance = {
                alongY ? Math.abs(x - kept.left) : Float.MAX_VALUE, alongX ? Math.abs(y - kept.top) : Float.MAX_VALUE,
                alongY ? Math.abs(x - kept.right) : Float.MAX_VALUE, alongX ? Math.abs(y - kept.bottom) : Float.MAX_VALUE};
        for (int side = 0; side < 4; side++) {
            if (distance[side] < bestDistance) {
                bestDistance = distance[side];
                best = side;
            }
        }
        return best;
    }

    /** The own margin of {@code side} with its edge at the finger: px of the frame, even, 0..MAX_MARGIN, half the frame stays. */
    int marginAt(int side, float x, float y, float s) {
        final float inward;
        switch (side) {
            case LEFT: inward = x - frame.left; break;
            case TOP: inward = y - frame.top; break;
            case RIGHT: inward = frame.right - x; break;
            default: inward = frame.bottom - y; break;
        }
        final int along = side == LEFT || side == RIGHT ? state.width : state.height;
        final int opposite = state.cut[(side + 2) % 4];
        final int limit = Math.min(RawCrop.MAX_MARGIN, Math.max(0, along / 2 - opposite));
        final int px = Math.round(inward / s / 2f) * 2;
        return Math.max(0, Math.min(limit, px));
    }

    /** Tests: the frame's rectangle after layout. */
    RectF frameRect() {
        layoutFrame();
        return new RectF(frame);
    }

    float shownCut(int side) {
        return cutShown[side];
    }
}
