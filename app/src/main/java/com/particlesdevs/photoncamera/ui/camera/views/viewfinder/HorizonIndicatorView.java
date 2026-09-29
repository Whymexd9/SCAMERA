package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import static java.lang.Math.abs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.View;
import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.control.Gyro;

import java.util.Locale;

/**
 * A custom view that draws a rotating horizon line and orientation-aware target marks.
 * The orientation is now passed in from the outside to ensure it's always up-to-date.
 */
public class HorizonIndicatorView extends View {

    private final Paint linePaint;
    private final Paint reticlePaint;
    private final Paint targetPaint;
    private float rollAngle = 0f;
    private float pitchAngle = 0f;
    private float yawAngle = 0f;
    private int currentDisplayRotation = Surface.ROTATION_0;
    private final int LINE_LENGTH_PX = 400;
    private boolean isViewfinderMagnified = false;
    int offset = 350; // so that the focus loupe is not too close to the virtual horizon

    private final Paint debugTextPaintYellow;
    private final Paint debugTextPaintRed;
    private final Paint debugTextPaintGreen;
    private Gyro gyro;

    private boolean isVisible = true;

    public HorizonIndicatorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        linePaint = new Paint();
        linePaint.setColor(Color.YELLOW);
        linePaint.setStrokeWidth(5f);
        linePaint.setAntiAlias(true);

        reticlePaint = new Paint();
        reticlePaint.setColor(Color.YELLOW);
        reticlePaint.setStrokeWidth(5f);
        reticlePaint.setAntiAlias(true);
        reticlePaint.setAlpha(150);

        targetPaint = new Paint();
        targetPaint.setColor(Color.MAGENTA);
        targetPaint.setStrokeWidth(5f);
        targetPaint.setAntiAlias(true);

        debugTextPaintYellow = new Paint();
        debugTextPaintYellow.setColor(Color.YELLOW);
        debugTextPaintYellow.setTextSize(40f);
        debugTextPaintYellow.setTextAlign(Paint.Align.CENTER);
        debugTextPaintYellow.setAntiAlias(true);
        debugTextPaintYellow.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        debugTextPaintGreen = new Paint();
        debugTextPaintGreen.setColor(Color.GREEN);
        debugTextPaintGreen.setTextSize(40f);
        debugTextPaintGreen.setTextAlign(Paint.Align.CENTER);
        debugTextPaintGreen.setAntiAlias(true);
        debugTextPaintGreen.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        debugTextPaintRed = new Paint();
        debugTextPaintRed.setColor(Color.MAGENTA);
        debugTextPaintRed.setTextSize(40f);
        debugTextPaintRed.setTextAlign(Paint.Align.CENTER);
        debugTextPaintRed.setAntiAlias(true);
        debugTextPaintRed.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    /**
     * Updates the angles for the horizon line from the sensor data.
     */
    public void updateAngles(float roll, float pitch, float yaw) {
        this.rollAngle = roll;
        this.pitchAngle = pitch;
        this.yawAngle = yaw;
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
            if (isVisible && isAttachedToWindow()) {
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

    /**
     * Updates the current display orientation from the outside.
     * @param rotation The display rotation value (e.g., Surface.ROTATION_90).
     */
    public void updateDisplayRotation(int rotation) {
        if (this.currentDisplayRotation != rotation) {
            this.currentDisplayRotation = rotation;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isVisible) {
            int centerX = getWidth() / 2;
            int centerY = getHeight() / 2;
        /*if (isViewfinderMagnified && PhotonCamera.getSettings().useAlternateLoupe) {
            centerY -= offset;
        }*/
            float lineHalf = LINE_LENGTH_PX / 2f;
            float pitchDist = 2 * abs(pitchAngle);

            // rotating part
            canvas.save();
            canvas.rotate(-rollAngle, centerX, centerY);
            canvas.drawLine(centerX - lineHalf, centerY, centerX + lineHalf, centerY, linePaint);
            canvas.drawLine(centerX - lineHalf / 2, centerY + pitchDist, centerX + lineHalf / 2, centerY + pitchDist, targetPaint);
            canvas.drawLine(centerX - lineHalf / 2, centerY - pitchDist, centerX + lineHalf / 2, centerY - pitchDist, targetPaint);
            canvas.restore();

            // not rotating but reframed with orientation changes
            canvas.save();
            canvas.rotate(currentDisplayRotation, centerX, centerY);
            canvas.drawLine(centerX - 220, centerY, centerX - 180, centerY, targetPaint);
            canvas.drawLine(centerX + 180, centerY, centerX + 220, centerY, targetPaint);
        /*if (PhotonCamera.getSettings().useVirtualHorizonText) {
            String formattedPitch = String.format(Locale.US, "%.2f°", pitchAngle);
            String formattedRoll = String.format(Locale.US, "%.2f°", rollAngle);
            String formattedYaw = String.format(Locale.US, "%.2f°", yawAngle);
            canvas.drawText(formattedPitch, centerX - lineHalf - 150, centerY + 35, debugTextPaintRed);
            canvas.drawText(formattedRoll, centerX - lineHalf - 150, centerY - 35, debugTextPaintYellow);
            //canvas.drawText(formattedYaw, centerX - lineHalf - 180, centerY - 70, debugTextPaintGreen);
        }*/
            canvas.restore();

            canvas.drawLine(centerX - 20, centerY, centerX + 20, centerY, reticlePaint);
            canvas.drawLine(centerX, centerY - 20, centerX, centerY + 20, reticlePaint);
        }
    }
}
