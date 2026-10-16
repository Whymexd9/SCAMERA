package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

/**
 * P177: the other operators on the own curve's graph - a chip per operator draws it (remembered for every editor), a template
 * loads it as an own curve of nine points following it; renders build/reports/tone-curve/compare.png for review.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ToneCurveCompareTest {
    private Context context;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        PreferenceKeys.initialise(manager);
        ToneCurveCompare.prefs(context).edit().clear().commit();
    }

    @After
    public void tearDown() {
        camera.close();
    }

    @Test
    public void chipsDrawTheOperatorsAndTemplatesLoadThem() throws Exception {
        ToneCurveView graph = new ToneCurveView(context);
        LinearLayout chips = ToneCurveCompare.chips(context, graph);
        assertEquals("none at first", 0, graph.references());
        for (String name : ToneCurveCompare.NAMES) {
            View chip = chips.findViewWithTag("tone_curve_compare_" + name);
            assertNotNull(name, chip);
            chip.performClick();
        }
        assertEquals("all three drawn", 3, graph.references());
        // remembered for another editor (the shade's)
        ToneCurveView other = new ToneCurveView(context);
        ToneCurveCompare.apply(context, other);
        assertEquals(3, other.references());
        // the template of AgX: nine points along AgX with today's settings, monotone, its middle at AgX's middle
        ToneCurve agx = ToneCurveCompare.template(2);
        assertEquals(ToneCurveCompare.TEMPLATE_POINTS, agx.size());
        float mid = (float) com.particlesdevs.photoncamera.processing.opengl.postpipeline.ArkTone.reference(ToneCurve.AGX).applyAsDouble(0.5);
        assertEquals(mid, agx.get(4).y, 1e-4f);
        for (int i = 1; i < agx.size(); i++) assertTrue(agx.get(i).y >= agx.get(i - 1).y);
        assertEquals("S-curve and straight stay the first two", ToneCurve.DEFAULT_POINTS, ToneCurveCompare.template(0).serialize());
        // the own S-curve with the three operators, for the owner
        graph.setCurve(ToneCurve.defaults());
        int w = 800;
        graph.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        graph.layout(0, 0, w, graph.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(w, graph.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF1B1F23);
        graph.draw(new Canvas(b));
        File dir = new File("build/reports/tone-curve");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "compare.png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        // a second tap takes one off
        chips.findViewWithTag("tone_curve_compare_ACES").performClick();
        assertEquals(2, graph.references());
    }
}
