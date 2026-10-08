package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.IsoModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.ShutterModel;
import com.particlesdevs.photoncamera.circularbarlib.ui.ManualFormat;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/**
 * Every phone and every module has its own ISO and exposure range (owner's requirement): the ISO and shutter rulers are
 * built from the model of the active module, cover exactly [min, max] (the exact ends as end stops, third stops in
 * between), show nothing the camera cannot do, label only octaves (and the ends), and are rebuilt for a new module or a
 * range the capture path widened.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class ManualRangeTest {
    private final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);

    /** The ruler as ViewObserver builds it from a model. */
    private LinearScaleView ruler(ManualModel<?> model, int mode) {
        LinearScaleView ruler = new LinearScaleView(context);
        ruler.setMode(mode, ManualFormat.EN);
        KnobItemInfo auto = model.getKnobInfoList().get(0);
        assertEquals(0, auto.value, 0);
        ruler.setItems(model.getKnobInfoList(), model.getCurrentInfo(), auto);
        return ruler;
    }

    private LinearScaleView isoRuler(int min, int max) {
        IsoModel model = new IsoModel(context, mock(CameraCharacteristics.class), new Range<>(min, max), new ManualParamModel(), null, null);
        return ruler(model, LinearScaleView.MODE_ISO);
    }

    private static void assertInside(List<KnobItemInfo> stops, double min, double max) {
        for (int i = 0; i < stops.size(); i++) {
            double v = stops.get(i).value;
            assertTrue(v + " outside [" + min + ", " + max + "]", v >= min && v <= max);
            if (i > 0) assertTrue(v > stops.get(i - 1).value);
        }
    }

    @Test
    public void isoRulerCoversExactlyTheModuleRange() {
        // Nubia NX563J: 100..800
        List<KnobItemInfo> stops = isoRuler(100, 800).stopItems();
        assertEquals(100, stops.get(0).value, 0);
        assertEquals(800, stops.get(stops.size() - 1).value, 0);
        assertInside(stops, 100, 800);
        assertEquals(10, stops.size()); // 100 125 160 200 250 320 400 500 640 800
        assertEquals(Arrays.asList("100", "200", "400", "800"), isoRuler(100, 800).majorLabels());
        // a wide sensor: 50..12800
        stops = isoRuler(50, 12800).stopItems();
        assertEquals(50, stops.get(0).value, 0);
        assertEquals(12800, stops.get(stops.size() - 1).value, 0);
        assertInside(stops, 50, 12800);
        assertEquals(Arrays.asList("50", "100", "200", "400", "800", "1600", "3200", "6400", "12800"), isoRuler(50, 12800).majorLabels());
        // ends that are not on a third stop are end stops of their own, labelled; octaves inside are labelled
        stops = isoRuler(72, 9000).stopItems();
        assertEquals(72, stops.get(0).value, 0);
        assertEquals(9000, stops.get(stops.size() - 1).value, 0);
        assertInside(stops, 72, 9000);
        assertEquals(Arrays.asList("72", "100", "200", "400", "800", "1600", "3200", "6400", "9000"), isoRuler(72, 9000).majorLabels());
    }

    @Test
    public void shutterRulerCoversExactlyTheModuleRange() {
        ShutterModel model = new ShutterModel(context, mock(CameraCharacteristics.class), new Range<>(125_000L, 1_000_000_000L),
                new ManualParamModel(), null, null);
        List<KnobItemInfo> stops = ruler(model, LinearScaleView.MODE_SHUTTER).stopItems();
        assertEquals(125_000, stops.get(0).value, 0);
        assertEquals(1_000_000_000L, stops.get(stops.size() - 1).value, 0);
        assertInside(stops, 125_000, 1_000_000_000L);
        List<String> labels = ruler(model, LinearScaleView.MODE_SHUTTER).majorLabels();
        assertEquals("1/8000", labels.get(0));
        assertEquals("1", labels.get(labels.size() - 1));
        // a long-exposure module: 43125 ns .. 33 s
        model = new ShutterModel(context, mock(CameraCharacteristics.class), new Range<>(43_125L, 33_024_215_041L),
                new ManualParamModel(), null, null);
        stops = ruler(model, LinearScaleView.MODE_SHUTTER).stopItems();
        assertEquals(43_125, stops.get(0).value, 0);
        assertEquals(33_024_215_041L, stops.get(stops.size() - 1).value, 0);
        assertInside(stops, 43_125, 33_024_215_041L);
    }

    @Test
    public void theRulerIsRebuiltForAWidenedRangeAndForANewModule() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            ManualHarness h = new ManualHarness(controller.get(), 336);
            h.iso = new Range<>(400, 800); // a vivo tele that reports [400, 800]
            h.init();
            h.layout(336);
            LinearScaleView ruler = h.find(R.id.linearScaleView);
            View isoChip = h.find(R.id.iso_option_tv);
            isoChip.performClick();
            assertEquals(View.VISIBLE, ruler.getVisibility());
            List<KnobItemInfo> stops = ruler.stopItems();
            assertEquals(400, stops.get(0).value, 0);
            assertEquals(800, stops.get(stops.size() - 1).value, 0);
            // the capture path saw ISO 100 and 3200 and widened the module's range: the next opening covers it
            isoChip.performClick(); // close
            h.iso = new Range<>(100, 3200);
            isoChip.performClick();
            stops = ruler.stopItems();
            assertEquals(100, stops.get(0).value, 0);
            assertEquals(3200, stops.get(stops.size() - 1).value, 0);
            assertInside(stops, 100, 3200);
            // another module: its own range, the ruler closed and rebuilt on the next tap
            h.iso = new Range<>(50, 12800);
            h.init();
            assertEquals(View.GONE, ruler.getVisibility());
            isoChip.performClick();
            stops = ruler.stopItems();
            assertEquals(50, stops.get(0).value, 0);
            assertEquals(12800, stops.get(stops.size() - 1).value, 0);
            // the shutter ruler follows the module's exposure range
            h.exposure = new Range<>(250_000L, 500_000_000L);
            h.init();
            h.find(R.id.exposure_option_tv).performClick();
            stops = ruler.stopItems();
            assertEquals(250_000, stops.get(0).value, 0);
            assertEquals(500_000_000L, stops.get(stops.size() - 1).value, 0);
        }
    }
}
