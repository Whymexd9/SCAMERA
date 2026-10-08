package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The manual summary of the top bar (MANUAL_TASK.md §4): a card (CARD + LINE, 16dp) with an accent «M», then one item per
 * applied manual parameter in the strip's order, each the parameter icon (14dp) and its value (12.5sp, tabular figures),
 * all in the accent. The items flow in a row and wrap to another line only when they do not fit; nothing is ever
 * truncated or ellipsized, the card grows instead. It takes the width its parent gives it (weight 1, min width 0).
 */
public class ManualSummaryView extends ViewGroup {
    private final TextView mark;
    private int accent;
    private final int padH, padV, gapMark, gapColumn, gapRow, minHeight;

    public ManualSummaryView(Context context) {
        this(context, null);
    }

    public ManualSummaryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        padH = UiTokens.dp(context, 8);
        padV = UiTokens.dp(context, 6);
        gapMark = UiTokens.dp(context, 6);
        gapColumn = UiTokens.dp(context, 12);
        gapRow = UiTokens.dp(context, 1);
        minHeight = UiTokens.dp(context, 44);
        setBackground(UiTokens.shape(context, UiTokens.CARD, UiTokens.LINE, 16));
        accent = UiTokens.cameraAccent(context);
        mark = new TextView(context);
        mark.setText("M");
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setIncludeFontPadding(false);
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(mark);
        setAccent(accent);
        setFocusable(true);
    }

    public void setAccent(int accent) {
        this.accent = accent;
        mark.setTextColor(accent);
        for (int i = 1; i < getChildCount(); i++) {
            LinearLayout item = (LinearLayout) getChildAt(i);
            ((ImageView) item.getChildAt(0)).setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN);
            ((TextView) item.getChildAt(1)).setTextColor(accent);
        }
    }

    /** The items: an icon and a value each, in order; {@code description} is the card's content description. */
    public void setItems(int[] icons, String[] values, CharSequence description) {
        Context c = getContext();
        while (getChildCount() - 1 > icons.length) removeViewAt(getChildCount() - 1);
        while (getChildCount() - 1 < icons.length) {
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ImageView icon = new ImageView(c);
            int size = UiTokens.dp(c, 14);
            item.addView(icon, new LinearLayout.LayoutParams(size, size));
            TextView value = new TextView(c);
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            value.setFontFeatureSettings("tnum");
            value.setIncludeFontPadding(false);
            value.setSingleLine(true);
            value.setEllipsize(null);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            vp.leftMargin = UiTokens.dp(c, 3);
            item.addView(value, vp);
            addView(item);
        }
        for (int i = 0; i < icons.length; i++) {
            LinearLayout item = (LinearLayout) getChildAt(i + 1);
            ((ImageView) item.getChildAt(0)).setImageResource(icons[i]);
            ((TextView) item.getChildAt(1)).setText(values[i]);
        }
        setAccent(accent);
        setContentDescription(description);
        requestLayout();
    }

    /** Number of items (the «M» not counted). */
    public int itemCount() {
        return getChildCount() - 1;
    }

    public View item(int i) {
        return getChildAt(i + 1);
    }

    /** Number of lines the items take after the last layout. */
    private int lines = 1;

    public int lineCount() {
        return lines;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE / 2
                : MeasureSpec.getSize(widthMeasureSpec);
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(unspecified, unspecified);
        int flowLeft = padH + mark.getMeasuredWidth() + gapMark;
        int flowRight = width - padH;
        int x = flowLeft, rowHeight = 0, height = 0, used = flowLeft;
        lines = 1;
        for (int i = 1; i < getChildCount(); i++) {
            View item = getChildAt(i);
            int w = item.getMeasuredWidth();
            if (x > flowLeft && x + w > flowRight) {
                height += rowHeight + gapRow;
                x = flowLeft;
                rowHeight = 0;
                lines++;
            }
            x += w + gapColumn;
            used = Math.max(used, x - gapColumn);
            rowHeight = Math.max(rowHeight, item.getMeasuredHeight());
        }
        height += Math.max(rowHeight, mark.getMeasuredHeight());
        int measuredWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY ? width
                : Math.min(width, used + padH);
        setMeasuredDimension(measuredWidth, Math.max(minHeight, height + 2 * padV));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        int flowLeft = padH + mark.getMeasuredWidth() + gapMark;
        int flowRight = width - padH;
        // Rows first, to centre the whole block vertically.
        int rowsHeight = 0, rowHeight = 0, x = flowLeft;
        java.util.List<int[]> rows = new java.util.ArrayList<>(); // {first child, last child, height}
        int first = 1;
        for (int i = 1; i < getChildCount(); i++) {
            View item = getChildAt(i);
            int w = item.getMeasuredWidth();
            if (x > flowLeft && x + w > flowRight) {
                rows.add(new int[]{first, i - 1, rowHeight});
                rowsHeight += rowHeight + gapRow;
                first = i;
                x = flowLeft;
                rowHeight = 0;
            }
            x += w + gapColumn;
            rowHeight = Math.max(rowHeight, item.getMeasuredHeight());
        }
        if (first < getChildCount()) {
            rows.add(new int[]{first, getChildCount() - 1, rowHeight});
            rowsHeight += rowHeight;
        }
        int top = Math.max(padV, (height - Math.max(rowsHeight, mark.getMeasuredHeight())) / 2);
        int firstRowHeight = rows.isEmpty() ? mark.getMeasuredHeight() : rows.get(0)[2];
        int markTop = top + (firstRowHeight - mark.getMeasuredHeight()) / 2;
        mark.layout(padH, markTop, padH + mark.getMeasuredWidth(), markTop + mark.getMeasuredHeight());
        int y = top;
        for (int[] row : rows) {
            x = flowLeft;
            for (int i = row[0]; i <= row[1]; i++) {
                View item = getChildAt(i);
                int itemTop = y + (row[2] - item.getMeasuredHeight()) / 2;
                item.layout(x, itemTop, x + item.getMeasuredWidth(), itemTop + item.getMeasuredHeight());
                x += item.getMeasuredWidth() + gapColumn;
            }
            y += row[2] + gapRow;
        }
    }

    /** True when every item lies whole inside the card's padding box (tests). */
    public boolean itemsInside() {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getLeft() < 0 || v.getTop() < 0 || v.getRight() > getWidth() || v.getBottom() > getHeight()) return false;
            if (i > 0) {
                TextView value = (TextView) ((LinearLayout) v).getChildAt(1);
                if (value.getLayout() != null && (value.getLayout().getEllipsisCount(0) > 0 || value.getLayout().getLineCount() > 1))
                    return false;
            }
        }
        return true;
    }
}
