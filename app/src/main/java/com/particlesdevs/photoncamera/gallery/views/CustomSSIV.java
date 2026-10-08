package com.particlesdevs.photoncamera.gallery.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import static com.particlesdevs.photoncamera.gallery.helper.Constants.DOUBLE_TAP_ZOOM_DURATION_MS;

public class CustomSSIV extends SubsamplingScaleImageView {

    private TouchCallBack touchCallBack;

    public CustomSSIV(Context context) {
        super(context);
        setMinimumDpi(40);
        setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
        setQuickScaleEnabled(true);
        setEagerLoadingEnabled(false);
        setDoubleTapZoomDuration(DOUBLE_TAP_ZOOM_DURATION_MS);
        setPreferredBitmapConfig(Bitmap.Config.ARGB_8888);
    }
    public CustomSSIV(Context context, AttributeSet attrs){
        super(context, attrs);
        setMinimumDpi(40);
        setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
        setQuickScaleEnabled(true);
        setEagerLoadingEnabled(false);
        setDoubleTapZoomDuration(DOUBLE_TAP_ZOOM_DURATION_MS);
        setPreferredBitmapConfig(Bitmap.Config.ARGB_8888);
    }

    /** P59b: a swipe down at the fit scale (the viewer closes); set by the viewer. */
    private Runnable swipeDown;
    private float downX, downY;
    private boolean multi;

    public void setSwipeDownListener(Runnable listener) {
        swipeDown = listener;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (touchCallBack != null) {
            touchCallBack.onTouched(getId());
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                multi = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                break;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX, dy = event.getY() - downY;
                float limit = 110 * getResources().getDisplayMetrics().density;
                if (swipeDown != null && !multi && isReady() && getScale() <= getMinScale() * 1.05f && dy > limit && dy > Math.abs(dx)) {
                    Runnable r = swipeDown;
                    post(r);
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    public void setTouchCallBack(TouchCallBack touchCallBack) {
        this.touchCallBack = touchCallBack;
    }

    public interface TouchCallBack {
        void onTouched(int id);
    }
}

