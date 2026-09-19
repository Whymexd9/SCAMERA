package com.particlesdevs.photoncamera.circularbarlib.control.models;

import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Log;
import android.util.Range;

import com.particlesdevs.photoncamera.circularbarlib.R;
import com.particlesdevs.photoncamera.circularbarlib.camera.ExposureIndex;
import com.particlesdevs.photoncamera.circularbarlib.camera.IsoExpoSelector;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobInfo;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.ShadowTextDrawable;

import java.util.ArrayList;

/**
 * Created by killerink, vibhorSrv, eszdman
 */
public class ShutterModel extends ManualModel<Long> {

    public ShutterModel(Context context, CameraCharacteristics cameraCharacteristics, Range<Long> range,
                        ManualParamModel manualParamModel, ValueChangedEvent valueChangedEvent, Vibrator v) {
        super(context, cameraCharacteristics, range, manualParamModel, valueChangedEvent,v);
    }

    @Override
    protected void fillKnobInfoList() {
        KnobItemInfo auto = getNewAutoItem(ManualParamModel.EXPOSURE_AUTO, null);
        getKnobInfoList().add(auto);
        currentInfo = auto;
        if (range == null) return;
        java.util.List<Long> values = com.particlesdevs.photoncamera.circularbarlib.camera.ManualStops.shutter(
                range.getLower().longValue(), range.getUpper().longValue());
        int tick = 0;
        for (long value : values) {
            String label = com.particlesdevs.photoncamera.circularbarlib.camera.ManualStops.shutterLabel(value);
            ShadowTextDrawable normal = new ShadowTextDrawable();
            normal.setTextAppearance(context, R.style.ManualModeKnobText);
            normal.setText(label);
            ShadowTextDrawable selected = new ShadowTextDrawable();
            selected.setTextAppearance(context, R.style.ManualModeKnobTextSelected);
            selected.setText(label);
            StateListDrawable drawable = new StateListDrawable();
            drawable.addState(new int[]{-android.R.attr.state_selected}, normal);
            drawable.addState(new int[]{android.R.attr.state_selected}, selected);
            KnobItemInfo item = new KnobItemInfo(drawable, label, ++tick, value);
            item.majorTick = tick == 1 || tick == values.size()
                    || com.particlesdevs.photoncamera.circularbarlib.camera.ManualStops.majorShutter(value);
            getKnobInfoList().add(item);
        }
        knobInfo = new KnobInfo(0, context.getResources().getInteger(R.integer.manual_exposure_knob_view_angle_half),
                0, values.size(), context.getResources().getInteger(R.integer.manual_exposure_knob_view_auto_angle));
    }

    @Override
    public void onRotationStateChanged(KnobView knobView, KnobView.RotationState rotationState) {

    }

    @Override
    public void onSelectedKnobItemChanged(KnobItemInfo knobItemInfo) {
        currentInfo = knobItemInfo;
        manualParamModel.setCurrentExposureValue(knobItemInfo.value);
    }

    private int findPreferredIntervalCount(int totalCount) {
        int result = 12;
        int minRemainder = Integer.MAX_VALUE;
        int i = 9;
        while (i >= 5 && (((float) (totalCount - 1)) / ((float) i)) + 1.0f <= 7.0f) {
            int remainder = ((totalCount % i) + (i - 1)) % i;
            if (minRemainder > remainder) {
                minRemainder = remainder;
                result = i;
            }
            i--;
        }
        return result;
    }

    private int findPreferredKnobViewAngle(int indicatorCount) {
        return (indicatorCount - 1) * 30;
    }
}
