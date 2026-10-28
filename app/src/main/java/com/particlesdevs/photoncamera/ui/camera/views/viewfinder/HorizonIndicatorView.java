package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.control.Gyro;

import java.util.Locale;

/**
 * The horizon level of the viewfinder. P174 (owner, 2026-10-16: «поменяй стиль уровня горизонта на схожий с нашим общим стилем
 * камеры. Сделай так чтобы при 0° он менял цвет и давай слабый виброотклик»): in the camera's style - a thin line with round
 * ends and a gap in its middle that turns with the phone, two short fixed marks at its ends, a small ring in the centre and the
 * tilt in degrees above it, white with a soft shadow; level (within half a degree of the frame's horizontal, in every
 * orientation) everything turns the camera accent, the line closes, and a light haptic tick marks the moment (leaving takes
 * one degree, so it does not flicker). P245 (owner, 2026-10-18: «когда он стоит ровно добавь мягкую легкую вибрацию»): the tick
 * is the app's soft vibrator pulse (Vibration.Soft) - the View's CLOCK_TICK was not felt on the OPPO / vivo phones.
 */
public class HorizonIndicatorView extends View {
    /** Tilt (degrees) that counts as level, and the one that leaves it. */
    static final float LEVEL_IN = 0.5f, LEVEL_OUT = 1.0f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), marks = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private float rollAngle = 0f;
    private float pitchAngle = 0f;
    private float yawAngle = 0f;
    private int currentDisplayRotation = 0;
    private Gyro gyro;
    private boolean isVisible = true;
    /** The window is shown: a stopped camera activity (Home, the gallery, the settings) keeps its views attached, hidden. */
    private boolean windowShown = true;
    private boolean level;

    public HorizonIndicatorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{line, marks, ring}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setShadowLayer(2.5f * d, 0, 0.5f * d, 0x66000000);
        }
        line.setStrokeWidth(2f * d);
        marks.setStrokeWidth(2f * d);
        ring.setStrokeWidth(1.5f * d);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        text.setShadowLayer(2.5f * d, 0, 0.5f * d, 0x66000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // the shadow layers of the strokes
        setHapticFeedbackEnabled(true);
    }

    /**
     * Updates the angles for the horizon line from the sensor data.
     */
    public void updateAngles(float roll, float pitch, float yaw) {
        this.rollAngle = roll;
        this.pitchAngle = pitch;
        this.yawAngle = yaw;
        updateLevel();
        invalidate();
    }

    public void setGyro(Gyro gyro) {
        this.gyro = gyro;
    }

    public void setVisible(boolean visible) {
        this.isVisible = visible;
        removeCallbacks(gyroTick);
        if (visible) post(gyroTick);
        invalidate();
    }

    public void updateFromGyro() {
        if (gyro != null) {
            float roll = gyro.getRoll(), pitch = gyro.getPitch(), yaw = gyro.getYaw();
            // Redraw only on a visible change: onDraw used to call this and
            // invalidate() itself, redrawing the UI at the full display rate
            // (120 Hz) even with the phone still or the indicator hidden.
            if (Math.abs(roll - rollAngle) > 0.1f || Math.abs(pitch - pitchAngle) > 0.1f)
                updateAngles(roll, pitch, yaw);
        }
    }

    // Gyro poll at ~30 Hz while shown, instead of an invalidate loop.
    private final Runnable gyroTick = new Runnable() {
        @Override public void run() {
            if (isVisible && windowShown && isAttachedToWindow()) {
                updateFromGyro();
                postDelayed(this, 33);
            }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(gyroTick);
        if (isVisible) post(gyroTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(gyroTick);
        super.onDetachedFromWindow();
    }

    /** No 30 Hz poll of a hidden window (the gyro is unregistered in the camera's onPause: its values are stale anyway). */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowShown = visibility == VISIBLE;
        removeCallbacks(gyroTick);
        if (windowShown && isVisible && isAttachedToWindow()) post(gyroTick);
    }

    /**
     * Updates the current display orientation from the outside.
     * @param rotation the orientation of the camera screen in degrees (0, 90, 180, 270)
     */
    public void updateDisplayRotation(int rotation) {
        if (this.currentDisplayRotation != rotation) {
            this.currentDisplayRotation = rotation;
            updateLevel();
            invalidate();
        }
    }

    /** The line's tilt against the frame's horizontal in this orientation, degrees in (-90, 90]. */
    static float tilt(float roll, int displayRotation) {
        float t = (-roll - displayRotation) % 180f;
        if (t > 90f) t -= 180f;
        if (t <= -90f) t += 180f;
        return t;
    }

    /** Level after {@code wasLevel} at tilt {@code t} degrees (the hysteresis: in at LEVEL_IN, out beyond LEVEL_OUT). */
    static boolean levelNow(boolean wasLevel, float t) {
        t = Math.abs(t);
        return wasLevel ? t <= LEVEL_OUT : t <= LEVEL_IN;
    }

    /** Level now (with the hysteresis): one soft pulse on the way in, none while it stays level. */
    private void updateLevel() {
        final boolean now = levelNow(level, tilt(rollAngle, currentDisplayRotation));
        if (now && !level && isVisible) {
            com.particlesdevs.photoncamera.control.Vibration v = com.particlesdevs.photoncamera.app.ScameraApp.getVibration();
            if (v != null) v.Soft();
        }
        level = now;
    }

    public boolean isLevel() {
        return level;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isVisible) return;
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        final int accent = AccentPalette.camera(getContext());
        final int colour = level ? accent : 0xF2FFFFFF;
        line.setColor(colour);
        marks.setColor(level ? accent : 0x99FFFFFF);
        ring.setColor(colour);
        text.setColor(0xE6FFFFFF);
        final float half = 70 * d, gap = 12 * d;

        // the line, turning with the phone; closed through the centre when level
        canvas.save();
        canvas.rotate(-rollAngle, cx, cy);
        if (level) canvas.drawLine(cx - half, cy, cx + half, cy, line);
        else {
            canvas.drawLine(cx - half, cy, cx - gap, cy, line);
            canvas.drawLine(cx + gap, cy, cx + half, cy, line);
        }
        canvas.restore();

        // the frame's horizontal: two fixed marks beyond the line's ends, the tilt above the centre
        canvas.save();
        canvas.rotate(currentDisplayRotation, cx, cy);
        final float m0 = half + 8 * d, m1 = half + 26 * d;
        canvas.drawLine(cx - m1, cy, cx - m0, cy, marks);
        canvas.drawLine(cx + m0, cy, cx + m1, cy, marks);
        final float t = tilt(rollAngle, currentDisplayRotation);
        if (!level) canvas.drawText(String.format(Locale.ROOT, "%d°", Math.round(Math.abs(t))), cx, cy - 16 * d, text);
        canvas.restore();

        canvas.drawCircle(cx, cy, 4.5f * d, ring);
    }
}
