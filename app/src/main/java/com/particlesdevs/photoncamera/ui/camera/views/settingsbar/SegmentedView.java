package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/**
 * Segmented control of a list row: one segment per value, the selected one filled with the accent (INK text). Equal
 * columns when every label fits on one line, two columns for four long labels, otherwise as many columns as fit; labels
 * shrink to 11sp and may take two lines, never an ellipsis.
 */
final class SegmentedView extends ViewGroup {
    interface OnPick {
        void onPick(int index);
    }

    private final int gap, pad;
    private TextView[] segments = new TextView[0];
    private int selected = -1, columns = 1, accent;
    private OnPick listener;

    SegmentedView(Context context) {
        super(context);
        gap = ShadeStyle.dp(context, 6);
        pad = ShadeStyle.dp(context, 5);
        setPadding(pad, pad, pad, pad);
        setBackground(ShadeStyle.card(context, 0xFF121417, ShadeStyle.LINE, 18));
    }

    void setOnPick(OnPick listener) {
        this.listener = listener;
    }

    /** Short labels on the segments, full labels for TalkBack and tooltips. */
    void setOptions(CharSequence[] labels, CharSequence[] descriptions) {
        removeAllViews();
        accent = ShadeStyle.accent(getContext());
        segments = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = new TextView(getContext());
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setIncludeFontPadding(false);
            t.setMaxLines(2);
            t.setEllipsize(null);
            int h = ShadeStyle.dp(getContext(), 6), v = ShadeStyle.dp(getContext(), 10);
            t.setPadding(h, v, h, v);
            CharSequence description = descriptions != null && i < descriptions.length ? descriptions[i] : labels[i];
            t.setContentDescription(description);
            t.setTooltipText(description);
            t.setTag("segment_" + i);
            t.setOnClickListener(x -> {
                if (listener != null && index != selected) listener.onPick(index);
            });
            t.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCheckable(true);
                    info.setChecked(index == selected);
                }
            });
            segments[i] = t;
            addView(t);
        }
        setSelectedIndex(selected);
    }

    void setSelectedIndex(int index) {
        selected = index;
        for (int i = 0; i < segments.length; i++) {
            boolean on = i == index;
            TextView t = segments[i];
            t.setBackground(on ? ShadeStyle.card(getContext(), accent, 0, 14) : ShadeStyle.pressable(getContext(), 0, 0, 14));
            t.setTextColor(on ? ShadeStyle.INK : ShadeStyle.MUTED);
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int inner = width - getPaddingLeft() - getPaddingRight();
        int n = Math.max(1, segments.length);
        columns = columnsFor(inner, n);
        int colWidth = (inner - gap * (columns - 1)) / columns;
        int rows = (segments.length + columns - 1) / columns;
        int height = getPaddingTop() + getPaddingBottom();
        for (int r = 0; r < rows; r++) {
            int rowHeight = 0;
            for (int c = 0; c < columns; c++) {
                int i = r * columns + c;
                if (i >= segments.length) break;
                TextView t = segments[i];
                ShadeStyle.fit(t, colWidth - t.getPaddingLeft() - t.getPaddingRight(), 14, 11, 11, 2);
                t.measure(MeasureSpec.makeMeasureSpec(colWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rowHeight = Math.max(rowHeight, t.getMeasuredHeight());
            }
            for (int c = 0; c < columns; c++) {
                int i = r * columns + c;
                if (i >= segments.length) break;
                segments[i].measure(MeasureSpec.makeMeasureSpec(colWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
            }
            height += rowHeight + (r > 0 ? gap : 0);
        }
        setMeasuredDimension(width, height);
    }

    /**
     * All in one row when the widest label fits (at 12sp, the labels shrink that far); otherwise rows as even as possible
     * (four in two rows of two, five in three and two), with as many columns as fit.
     */
    private int columnsFor(int inner, int n) {
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        float widest = 0;
        for (TextView t : segments) widest = Math.max(widest, paint.measureText(t.getText().toString()));
        int segPad = 2 * ShadeStyle.dp(getContext(), 6);
        int fit = 1;
        for (int k = n; k >= 1; k--) {
            int col = (inner - gap * (k - 1)) / k;
            if (widest + segPad <= col) {
                fit = k;
                break;
            }
        }
        if (fit >= n) return n;
        int rows = (n + fit - 1) / fit;
        return (n + rows - 1) / rows;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int y = getPaddingTop();
        int rows = (segments.length + columns - 1) / Math.max(1, columns);
        for (int row = 0; row < rows; row++) {
            int x = getPaddingLeft(), rowHeight = 0;
            for (int c = 0; c < columns; c++) {
                int i = row * columns + c;
                if (i >= segments.length) break;
                View v = segments[i];
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                x += v.getMeasuredWidth() + gap;
                rowHeight = Math.max(rowHeight, v.getMeasuredHeight());
            }
            y += rowHeight + gap;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (TextView t : segments) t.setEnabled(enabled);
    }
}
