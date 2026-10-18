package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P143 / P255: the shade header's profile button - the active profile's name in an accent frame with the profile icon and a
 * chevron.
 * A long name stays inside the frame: it shrinks from 13sp to 9sp and only then ends in an ellipsis. A press dips the frame,
 * the chevron turns while the config menu is open.
 */
@SuppressLint("ViewConstructor")
public final class ConfigChip extends LinearLayout {
    static final float MAX_SP = 13f, MIN_SP = 9f;
    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final ImageView icon, chevron;
    private final TextView name;
    private int accent;
    private boolean open;

    public ConfigChip(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(10), dp(6), dp(8), dp(6));
        setMinimumHeight(dp(32));
        setTag("shade_config");
        setClickable(true);
        setFocusable(true);
        icon = new ImageView(context);
        icon.setImageResource(R.drawable.settings_ic_config);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(icon, new LayoutParams(dp(16), dp(16)));
        name = new TextView(context);
        name.setTag("shade_config_name");
        name.setSingleLine(true);
        name.setIncludeFontPadding(false);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, MAX_SP);
        LayoutParams np = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        np.setMarginStart(dp(7));
        np.setMarginEnd(dp(3));
        addView(name, np);
        chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.settings_ic_chevron);
        chevron.setRotation(90f);
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(chevron, new LayoutParams(dp(14), dp(14)));
        setAccent(ShadeStyle.accent(context));
    }

    public void setAccent(int accent) {
        this.accent = accent;
        setBackground(ShadeStyle.pressable(getContext(), ShadeStyle.tint(accent, .10f), (accent & 0x00FFFFFF) | 0x73000000, 100));
        icon.setImageTintList(ColorStateList.valueOf(accent));
        chevron.setImageTintList(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0xB3000000));
        name.setTextColor(ShadeStyle.TEXT);
    }

    /** The config's name; the frame keeps its width limit (measure fits the text to it). */
    public void setName(CharSequence text) {
        if (TextUtils.equals(name.getText(), text)) return;
        name.setText(text);
        setContentDescription(Lang.t(getContext(), "Профиль: ", "Profile: ") + text);
        requestLayout();
    }

    public CharSequence getName() {
        return name.getText();
    }

    TextView nameView() {
        return name;
    }

    /** The menu is open: the chevron points up, the frame stays lit. */
    public void setOpen(boolean on) {
        if (open == on) return;
        open = on;
        chevron.animate().rotation(on ? 270f : 90f).setDuration(220).setInterpolator(EASE).start();
        setSelected(on);
        setBackground(ShadeStyle.pressable(getContext(), ShadeStyle.tint(accent, on ? .20f : .10f),
                (accent & 0x00FFFFFF) | (on ? 0xCC000000 : 0x73000000), 100));
    }

    public boolean isOpen() {
        return open;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int limit = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : MeasureSpec.getSize(widthMeasureSpec);
        LayoutParams np = (LayoutParams) name.getLayoutParams();
        int around = getPaddingLeft() + getPaddingRight() + dp(16) + dp(14) + np.getMarginStart() + np.getMarginEnd();
        fitName(limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, limit - around));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** 13sp down to 9sp in 0.5sp steps; what still does not fit ends in an ellipsis within {@code width}. */
    void fitName(int width) {
        CharSequence text = name.getText();
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        TextPaint paint = new TextPaint(name.getPaint());
        float sp = MAX_SP;
        if (text != null && width != Integer.MAX_VALUE) {
            for (; sp > MIN_SP; sp -= .5f) {
                paint.setTextSize(sp * scaled);
                if (paint.measureText(text, 0, text.length()) <= width) break;
            }
            sp = Math.max(MIN_SP, sp);
        }
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        paint.setTextSize(sp * scaled);
        boolean fits = text == null || width == Integer.MAX_VALUE || paint.measureText(text, 0, text.length()) <= width;
        name.setEllipsize(fits ? null : TextUtils.TruncateAt.END);
        name.setMaxWidth(width == Integer.MAX_VALUE ? Integer.MAX_VALUE : width);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
            animate().scaleX(.96f).scaleY(.96f).setDuration(90).setInterpolator(EASE).start();
        else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
            animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(EASE).start();
        return super.onTouchEvent(e);
    }

    private int dp(float v) {
        return ShadeStyle.dp(getContext(), v);
    }
}
