package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Activity;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualInstanceProvider;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A manual panel on a Robolectric activity with a real console and models over a mocked module: the ISO and exposure
 * ranges are mutable, so a test can widen them as the capture path does or switch to another module.
 */
final class ManualHarness {
    final Activity activity;
    final ManualModeConsole console;
    final View panel;
    final List<CharSequence> messages = new ArrayList<>();
    Range<Integer> iso = new Range<>(100, 800);
    Range<Long> exposure = new Range<>(125_000L, 1_000_000_000L);
    boolean manualWhiteBalance = true;

    ManualHarness(Activity activity, int widthPx) {
        this.activity = activity;
        FrameLayout root = new FrameLayout(activity);
        panel = activity.getLayoutInflater().inflate(R.layout.manual_palette, root, false);
        root.addView(panel, new FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The top-bar summary is found by id on the same screen.
        com.particlesdevs.photoncamera.circularbarlib.ui.ManualSummaryView summary =
                new com.particlesdevs.photoncamera.circularbarlib.ui.ManualSummaryView(activity);
        summary.setId(R.id.manual_summary);
        summary.setVisibility(View.GONE);
        root.addView(summary, new FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);
        console = ManualInstanceProvider.getNewManualModeConsole();
        console.setMessageSink(messages::add);
    }

    CameraCharacteristics module() {
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(10f);
        when(c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)).thenReturn(new Rational(1, 3));
        when(c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)).thenReturn(new Range<>(-12, 12));
        when(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenAnswer(i -> iso);
        when(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)).thenAnswer(i -> exposure);
        if (manualWhiteBalance) {
            when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(new android.hardware.camera2.params.ColorSpaceTransform(
                    new int[]{1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1}));
            when(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{0, 1});
            when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{2});
        }
        return c;
    }

    /** Builds the models for a module, as CameraFragment does on a characteristics update. */
    CameraCharacteristics init() {
        CameraCharacteristics c = module();
        console.init(activity, c);
        console.onResume();
        return c;
    }

    void layout(int widthPx) {
        View root = (View) panel.getParent();
        root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, widthPx, root.getMeasuredHeight());
    }

    <T extends View> T find(int id) {
        return activity.findViewById(id);
    }
}
