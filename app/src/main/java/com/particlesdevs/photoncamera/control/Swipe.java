package com.particlesdevs.photoncamera.control;

import android.graphics.RectF;
import com.particlesdevs.photoncamera.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.ui.camera.CameraFragment;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;
import com.particlesdevs.photoncamera.ui.camera.viewmodel.CameraFragmentViewModel;

public class Swipe {
    private static final String TAG = "Swipe";
    private final CameraFragment cameraFragment;
    private final CaptureController captureController;
    private GestureDetector gestureDetector;
    private ManualModeConsole manualModeConsole;
    private CameraFragmentViewModel cameraFragmentViewModel;

    public Swipe(CameraFragment cameraFragment) {
        this.cameraFragment = cameraFragment;
        this.captureController = cameraFragment.getCaptureController();
    }

    public void init() {
        Log.d(TAG, "SwipeDetection - ON");
        manualModeConsole = cameraFragment.getManualModeConsole();
        cameraFragmentViewModel = cameraFragment.getCameraFragmentViewModel();
        manualModeConsole.setPanelVisibility(true);
        gestureDetector = new GestureDetector(cameraFragment.getContext(), new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // In FULL the scrim takes the touches; should one get here, it only lowers the sheet
                // to PEEK, without focus (P25, owner's answer 3).
                if (cameraFragmentViewModel.getSheetLevel() == CameraFragmentModel.SHEET_FULL) {
                    cameraFragmentViewModel.setSheetLevel(CameraFragmentModel.SHEET_PEEK);
                    return true;
                }
                startTouchToFocus(e);
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // P42: a long press locks the tracking autofocus onto the subject (setting «Tracking autofocus»).
                if (cameraFragmentViewModel.getSheetLevel() == CameraFragmentModel.SHEET_FULL) return;
                startTracking(e);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float diffY = e2.getY() - e1.getY();
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            Log.d(TAG, "Right");
                            SwipeRight();
                        } else {
                            Log.d(TAG, "Left");
                            SwipeLeft();
                        }
                        return true;
                    }
                } else if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY > 0) {
                        Log.d(TAG, "Bottom");//it swipes from top to bottom
                        SwipeDown();
                    } else {
                        Log.d(TAG, "Top");//it swipes from bottom to top
                        SwipeUp();
                    }
                    return true;
                }
                return false;
            }
        });
        android.view.ScaleGestureDetector scaleDetector = new android.view.ScaleGestureDetector(cameraFragment.getContext(),
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                        ZoomController.syncToActive(); // P37: a pinch starts from the active module's zoom
                        return true;
                    }

                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        cameraFragment.zoomTo(ZoomController.zoom() * detector.getScaleFactor());
                        return true;
                    }
                });
        View.OnTouchListener touchListener = (view, motionEvent) -> {
            // No pinch zoom under the FULL sheet's scrim.
            if (cameraFragmentViewModel.getSheetLevel() == CameraFragmentModel.SHEET_FULL) return gestureDetector.onTouchEvent(motionEvent);
            if (motionEvent.getPointerCount() > 1) Log.d(TAG, "pinch touch pointers=" + motionEvent.getPointerCount() + " action=" + motionEvent.getActionMasked());
            scaleDetector.onTouchEvent(motionEvent);
            if (scaleDetector.isInProgress() || motionEvent.getPointerCount() > 1) return true;
            return gestureDetector.onTouchEvent(motionEvent);
        };
        View holder = cameraFragment.findViewById(R.id.textureHolder);
        Log.d(TAG, "input:" + holder);
        if (holder != null) holder.setOnTouchListener(touchListener);
    }

    private void startTouchToFocus(MotionEvent event) {
        //takes into consideration the top and bottom translation of camera_container(if it has been moved due to different display ratios)
        // for calculation of size of viewfinder RectF.(for touch focus detection)
        ConstraintLayout camera_container = cameraFragment.findViewById(R.id.camera_container);
        FrameLayout layout_viewfinder = cameraFragment.findViewById(R.id.layout_viewfinder);
        RectF viewfinderRect = new RectF(
                layout_viewfinder.getLeft(),//left edge of viewfinder
                camera_container.getY(), //y position of camera_container
                layout_viewfinder.getRight(), //right edge of viewfinder
                layout_viewfinder.getBottom() + camera_container.getY() //bottom edge of viewfinder + y position of camera_container
        );
        // Interface.getCameraFragment().showToast(previewRect.toString()+"\nCurX"+event.getX()+"CurY"+event.getY());
        if (viewfinderRect.contains(event.getX(), event.getY())) {
            float translateX = event.getX() - camera_container.getLeft();
            float translateY = event.getY() - camera_container.getTop();
            if (manualModeConsole.getManualParamModel().getCurrentFocusValue() == ManualParamModel.FOCUS_AUTO) {
                // P42: in «tap» mode the tap starts / stops tracking; otherwise a running track gives way to the tap focus.
                com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = cameraFragment.getSubjectFocus();
                if (subject != null && subject.onTap(event.getRawX(), event.getRawY())) return;
                cameraFragment.getTouchFocus().processTouchToFocus(translateX, translateY);
            }
        }
    }

    /** P42: long press on the viewfinder -> tracking autofocus (auto focus only, as the tap focus). */
    private void startTracking(MotionEvent event) {
        com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = cameraFragment.getSubjectFocus();
        if (subject == null) return;
        if (manualModeConsole.getManualParamModel().getCurrentFocusValue() != ManualParamModel.FOCUS_AUTO) return;
        if (subject.onLongPress(event.getRawX(), event.getRawY())) {
            android.view.View holder = cameraFragment.findViewById(R.id.textureHolder);
            if (holder != null) holder.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    }

    /** Raises the settings sheet one level: HIDDEN -> PEEK -> FULL. */
    public void SwipeUp() {
        boolean wasHidden = cameraFragmentViewModel.getSheetLevel() == CameraFragmentModel.SHEET_HIDDEN;
        cameraFragmentViewModel.sheetLevelUp();
        // With the settings closed, a swipe up has always reset touch focus to auto. Keep that.
        if (wasHidden) {
            com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = cameraFragment.getSubjectFocus();
            if (subject != null) subject.stopTracking(false); // the reset below restores the default regions
            TouchFocus touchFocus = cameraFragment.getTouchFocus();
            if (touchFocus != null) touchFocus.resetFocusCircle();
        }
    }

    /** Lowers the settings sheet one level: FULL -> PEEK -> HIDDEN. */
    public void SwipeDown() {
        cameraFragmentViewModel.sheetLevelDown();
    }


    public void SwipeRight() {

    }

    public void SwipeLeft() {

    }

}
