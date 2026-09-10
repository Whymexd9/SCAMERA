package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.app.Activity;
import android.provider.Settings;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.circularbarlib.R;
import com.particlesdevs.photoncamera.circularbarlib.model.KnobModel;
import com.particlesdevs.photoncamera.circularbarlib.model.ManualModeModel;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobView;

import java.util.Arrays;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * Created by vibhorSrv
 */
public class ViewObserver implements Observer {
    private final Activity activity;
    private final RelativeLayout manualMode;
    private final KnobView knobView;
    private final com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView linearScaleView;
    /** True while the linear scale replaces the wheel. */
    private final boolean useLinearScale;
    private final TextView isoOption;
    private final TextView expOption;
    private final TextView evOption;
    private final TextView focusOption;
    private final List<TextView> textViews;
    private final OrientationEventListener orientationEventListener;
    private final LinearLayout buttonsContainer;
    private int rotation = 0;
    /** Model backing whichever parameter is on screen, shared by both controls. */
    private com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel<?> currentModel;


    public ViewObserver(Activity activity) {
        this.activity = activity;
        manualMode = findViewById(R.id.manual_mode);
        buttonsContainer = findViewById(R.id.buttons_container);
        knobView = findViewById(R.id.knobView);
        linearScaleView = findViewById(R.id.linearScaleView);
        useLinearScale = android.preference.PreferenceManager
                .getDefaultSharedPreferences(activity.getApplicationContext())
                .getBoolean("pref_linear_manual_scale_key", true);
        if (linearScaleView != null) {
            linearScaleView.setListener(
                    new com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView.OnValueChangedListener() {
                        @Override
                        public void onValueChanged(
                                com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo item,
                                boolean fromUser) {
                            // Reuse the wheel's own callback so both controls
                            // drive the model through one path: whatever the
                            // wheel does on a rotation, the scale does on a drag.
                            if (currentModel != null && item != null && fromUser) {
                                currentModel.onSelectedKnobItemChanged(knobView, item, item);
                            }
                        }

                        @Override
                        public void onDragStateChanged(boolean dragging) {
                            if (currentModel != null) {
                                currentModel.onRotationStateChanged(knobView,
                                        dragging ? KnobView.RotationState.ROTATING
                                                 : KnobView.RotationState.IDLE);
                            }
                        }
                    });
        }
        isoOption = findViewById(R.id.iso_option_tv);
        expOption = findViewById(R.id.exposure_option_tv);
        evOption = findViewById(R.id.ev_option_tv);
        focusOption = findViewById(R.id.focus_option_tv);
        textViews = Arrays.asList(isoOption, evOption, expOption, focusOption);
        orientationEventListener = new OrientationEventListener(activity.getBaseContext()) {
            private static final int ROT_DUR = 350;
            private int prevOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;

            @Override
            public void onOrientationChanged(int orientation) {
                if (android.provider.Settings.System.getInt(activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 0) // 0 = Auto Rotate Disabled
                    return;
                int currentOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
                if (orientation >= 340 || orientation < 20 && rotation != 0) {
                    currentOrientation = Surface.ROTATION_0;
                    rotation = 0;

                } else if (orientation >= 70 && orientation < 110 && rotation != 90) {
                    currentOrientation = Surface.ROTATION_270;
                    rotation = -90;

                } else if (orientation >= 160 && orientation < 200 && rotation != 180) {
                    currentOrientation = Surface.ROTATION_180;
                    rotation = 180;

                } else if (orientation >= 250 && orientation < 290 && rotation != 270) {
                    currentOrientation = Surface.ROTATION_90;
                    rotation = 90;
                }
                if (prevOrientation != currentOrientation && orientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                    prevOrientation = currentOrientation;
                    if (currentOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        Binding.rotateKnobView(knobView, rotation);
                        Binding.rotateViewGroupChild(buttonsContainer, rotation, ROT_DUR);
                    }
                }
            }
        };
    }

    public void enableOrientationListener() {
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    public void disableOrientationListener() {
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    private <T extends View> T findViewById(int id) {
        return activity.findViewById(id);
    }


    @Override
    public void update(Observable o, Object arg) {
        if (o != null && arg != null) {
            if (o instanceof KnobModel) {
                KnobModel knobModel = (KnobModel) o;
                switch ((KnobModel.KnobModelFields) arg) {
                    case RESET:
                        Binding.resetKnob(knobView, knobModel.isKnobResetCalled());
                        break;
                    case VISIBILITY:
                        // Only one control is ever visible. The wheel keeps its
                        // own visibility logic; the scale mirrors it.
                        Binding.setKnobVisibility(knobView, !useLinearScale && knobModel.isKnobVisible());
                        if (linearScaleView != null) {
                            linearScaleView.setVisibility(
                                    useLinearScale && Boolean.TRUE.equals(knobModel.isKnobVisible())
                                            ? android.view.View.VISIBLE : android.view.View.GONE);
                        }
                        break;
                    case MANUAL_MODEL:
                        currentModel = knobModel.getManualModel();
                        Binding.setModelToKnob(knobView, currentModel);
                        if (linearScaleView != null && currentModel != null) {
                            java.util.List<com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo>
                                    items = currentModel.getKnobInfoList();
                            int selected = items == null ? 0
                                    : Math.max(0, items.indexOf(currentModel.getCurrentInfo()));
                            linearScaleView.setItems(items, selected);
                        }
                        break;
                }
            }
            if (o instanceof ManualModeModel) {
                ManualModeModel manualModeModel = (ManualModeModel) o;
                switch ((ManualModeModel.ManualModelFields) arg) {
                    case EV_TEXT:
                        evOption.setText(manualModeModel.getEvText());
                        break;
                    case EXP_TEXT:
                        expOption.setText(manualModeModel.getExposureText());
                        break;
                    case ISO_TEXT:
                        isoOption.setText(manualModeModel.getIsoText());
                        break;
                    case FOCUS_TEXT:
                        focusOption.setText(manualModeModel.getFocusText());
                        break;
                    case EV_LISTENER:
                        evOption.setOnClickListener(manualModeModel.getEvTextClicked());
                        break;
                    case EXP_LISTENER:
                        expOption.setOnClickListener(manualModeModel.getExposureTextClicked());
                        break;
                    case FOCUS_LISTENER:
                        focusOption.setOnClickListener(manualModeModel.getFocusTextClicked());
                        break;
                    case ISO_LISTENER:
                        isoOption.setOnClickListener(manualModeModel.getIsoTextClicked());
                        break;
                    case SELECTED_TV:
                        View v = findViewById(manualModeModel.getSelectedTextViewId());
                        if (v != null) {
                            for (TextView textView : textViews) {
                                textView.setSelected(v.equals(textView));
                            }
                        } else {
                            for (TextView textView : textViews) {
                                textView.setSelected(false);
                            }
                        }
                        break;
                    case PANEL_VISIBILITY:
                        Binding.togglePanelVisibility(manualMode, manualModeModel.isManualPanelVisible());
                        break;

                }
            }
        }

    }
}
