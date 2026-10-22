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
                // owner, 2026-10-20: a tap on the empty viewfinder closes the LUT tray, without focus
                com.particlesdevs.photoncamera.ui.camera.LutTray tray = cameraFragment.lutTray;
                if (tray != null && tray.isOpen()) {
                    tray.setOpen(false);
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
                if (e1 == null) return false;
                float diffY = e2.getY() - e1.getY();
                float diffX = e2.getX() - e1.getX();
                // SIMPLE_UI §1: a horizontal swipe (|dx| > 60dp and > 1.5 |dy|) steps the LUT
                float horizontal = 60 * cameraFragment.getResources().getDisplayMetrics().density;
                if (Math.abs(diffX) > 1.5f * Math.abs(diffY)) {
                    if (Math.abs(diffX) > horizontal && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            Log.d(TAG, "Right");
                            SwipeRight();
                        } else {
                            Log.d(TAG, "Left");
                            SwipeLeft();
                        }
                        return true;
                    }
                } else if (Math.abs(diffY) > Math.abs(diffX) && Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
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

    /**
     * P42: long press on the viewfinder -> tracking autofocus when «Следить за объектом» is on long press; otherwise
     * (SIMPLE_UI §1) it locks the focus and the exposure there («Закреплено»), a tap elsewhere releases them. Auto focus only,
     * as the tap focus.
     */
    private void startTracking(MotionEvent event) {
        if (manualModeConsole.getManualParamModel().getCurrentFocusValue() != ManualParamModel.FOCUS_AUTO) return;
        com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = cameraFragment.getSubjectFocus();
        boolean done = subject != null && subject.onLongPress(event.getRawX(), event.getRawY());
        if (!done) done = lockFocusAndExposure(event);
        if (done) {
            android.view.View holder = cameraFragment.findViewById(R.id.textureHolder);
            if (holder != null) holder.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    }

    /** SIMPLE_UI §1: the long press's lock, at the same point mapping as a tap. */
    private boolean lockFocusAndExposure(MotionEvent event) {
        TouchFocus touchFocus = cameraFragment.getTouchFocus();
        if (touchFocus == null) return false;
        ConstraintLayout camera_container = cameraFragment.findViewById(R.id.camera_container);
        FrameLayout layout_viewfinder = cameraFragment.findViewById(R.id.layout_viewfinder);
        if (camera_container == null || layout_viewfinder == null) return false;
        RectF viewfinderRect = new RectF(layout_viewfinder.getLeft(), camera_container.getY(), layout_viewfinder.getRight(),
                layout_viewfinder.getBottom() + camera_container.getY());
        if (!viewfinderRect.contains(event.getX(), event.getY())) return false;
        com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = cameraFragment.getSubjectFocus();
        if (subject != null) subject.stopTracking(false);
        touchFocus.lockAt(event.getX() - camera_container.getLeft(), event.getY() - camera_container.getTop());
        return true;
    }

    /**
     * Raises the settings sheet one level: HIDDEN -> PEEK -> FULL. SIMPLE_UI §1: it no longer resets the focus or the
     * tracking (opening the quick sheet must not lose the subject).
     */
    public void SwipeUp() {
        cameraFragmentViewModel.sheetLevelUp();
    }

    /** Lowers the settings sheet one level: FULL -> PEEK -> HIDDEN. */
    public void SwipeDown() {
        cameraFragmentViewModel.sheetLevelDown();
    }


    /** SIMPLE_UI §1: the previous LUT, its name in the message band (only while the quick sheet is closed). */
    public void SwipeRight() {
        stepLut(-1);
    }

    /** SIMPLE_UI §1: the next LUT. */
    public void SwipeLeft() {
        stepLut(1);
    }

    private void stepLut(int direction) {
        if (cameraFragmentViewModel.getSheetLevel() != CameraFragmentModel.SHEET_HIDDEN) return;
        com.particlesdevs.photoncamera.ui.camera.LutTray tray = cameraFragment.lutTray;
        if (tray == null) return;
        // P319: the new look wipes in from the side the finger moved to (LutWipeView), the name in the message band
        final Runnable change = () -> cameraFragment.showCardToast(tray.step(direction));
        final com.particlesdevs.photoncamera.databinding.CameraFragmentBinding b = cameraFragment.cameraFragmentBinding;
        if (b == null || b.layoutViewfinder.lutWipe == null) { change.run(); return; }
        b.layoutViewfinder.lutWipe.wipe(b.layoutViewfinder.texture, direction, change);
    }

}
