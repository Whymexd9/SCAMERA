package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.ui.settings.SettingsActivity;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.RawCropView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P162 «Обрезка кадра»: the page shows the crop the next shot makes - the X200 Pro's black stripes on the left and the bottom of
 * the upright photo, the own margins, the kept frame and its size - follows the switch and the margins, and a dragged edge of
 * the kept frame stores that side's margin. Renders build/reports/module-concept/page-raw_crop_screen*.png for review.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RawCropSettingsTest {
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
    }

    @After
    public void tearDown() {
        camera.close();
    }

    @Test
    public void theNextShotsCropFollowsTheDetectionAndTheMargins() {
        // before any shot: a 4:3 sketch, nothing measured, nothing cut
        RawCropView.State none = RawCropView.compute(true, new int[4], "");
        assertFalse(none.measured);
        assertArrayEquals(new int[4], none.cut);
        // the X200 Pro: 4096x3072 sensor, image 4000x3000, mounted at 90 degrees
        String x200pro = "4096,3072,0,0,96,72,90";
        RawCropView.State auto = RawCropView.compute(true, new int[4], x200pro);
        assertTrue(auto.measured);
        assertEquals(3072, auto.width);
        assertEquals(4096, auto.height);
        assertEquals(3000, auto.keptWidth);
        assertEquals(4000, auto.keptHeight);
        assertArrayEquals("stripes on the left and the bottom of the upright photo", new int[]{72, 0, 0, 96}, auto.auto);
        assertArrayEquals(new int[]{72, 0, 0, 96}, auto.cut);
        // the switch off: only the own margins, rounded as the shot does (the sensor's left = the photo's top: 8 px periods)
        RawCropView.State own = RawCropView.compute(false, new int[]{0, 13, 0, 0}, x200pro);
        assertArrayEquals(new int[4], own.auto);
        assertArrayEquals(new int[]{0, 16, 0, 0}, own.cut);
        // both: each side the larger
        RawCropView.State both = RawCropView.compute(true, new int[]{40, 0, 0, 200}, x200pro);
        assertArrayEquals(new int[]{72, 0, 0, 200}, both.cut);
        assertEquals(4096 - 200, both.keptHeight);
    }

    @Test
    public void thePageShowsTheCropAndADraggedEdgeStoresItsMargin() throws Exception {
        prefs.edit().putString(PreferenceKeys.RAW_CROP_DETECTED, "4096,3072,0,0,96,72,90").commit();
        try (var controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            FragmentManager fm = activity.getSupportFragmentManager();
            fm.executePendingTransactions();
            var root = (SettingsActivity.SettingsFragment) fm.findFragmentById(R.id.settings_container);
            PreferenceScreen target = root.findPreference("raw_crop_screen");
            assertNotNull(target);
            for (String key : new String[]{"raw_crop_preview", PreferenceKeys.RAW_CROP_AUTO, "pref_raw_crop_left", "pref_raw_crop_top",
                    "pref_raw_crop_right", "pref_raw_crop_bottom"})
                assertNotNull(key, target.findPreference(key));
            activity.onPreferenceStartScreen(root, target);
            fm.executePendingTransactions();
            idle(600);
            View page = fm.findFragmentById(R.id.settings_container).requireView();
            layout(page);
            RawCropView picture = page.findViewWithTag("raw_crop_view");
            assertNotNull("the picture is on the page", picture);
            idle(800);   // the intro glide
            assertEquals(72f, picture.state().cut[0], 0f);
            render(page, "page-raw_crop_screen.png");

            // an own margin at the top: the picture glides there
            prefs.edit().putString("pref_raw_crop_top", "120").commit();
            idle(800);
            assertArrayEquals(new int[]{72, 120, 0, 96}, picture.state().cut);
            layout(page);
            render(page, "page-raw_crop_screen-margin.png");

            // drag the kept frame's right edge 40 view px inwards: the right margin is stored
            RectF frame = frameOf(picture);
            float scale = frame.width() / picture.state().width;
            float edgeX = frame.right, y = frame.centerY();
            long t = SystemClock.uptimeMillis();
            picture.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, edgeX - 2, y, 0));
            picture.dispatchTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, edgeX - 20, y, 0));
            picture.dispatchTouchEvent(MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_MOVE, edgeX - 40, y, 0));
            picture.dispatchTouchEvent(MotionEvent.obtain(t, t + 48, MotionEvent.ACTION_UP, edgeX - 40, y, 0));
            idle(800);
            int stored = (int) Math.round(Double.parseDouble(prefs.getString("pref_raw_crop_right", "0")));
            assertEquals("the right margin from the drag", Math.round(40 / scale / 2f) * 2, stored, 2);
            assertTrue(picture.state().cut[2] > 0);
            layout(page);
            render(page, "page-raw_crop_screen-drag.png");

            // the switch off: the stripes go back into the frame
            prefs.edit().putBoolean(PreferenceKeys.RAW_CROP_AUTO, false).commit();
            idle(800);
            assertArrayEquals(new int[4], picture.state().auto);
        }
    }

    private static RectF frameOf(RawCropView picture) throws Exception {
        java.lang.reflect.Method m = RawCropView.class.getDeclaredMethod("frameRect");
        m.setAccessible(true);
        return (RectF) m.invoke(picture);
    }

    private static void idle(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static void layout(View page) {
        int exact = View.MeasureSpec.EXACTLY;
        page.measure(View.MeasureSpec.makeMeasureSpec(800, exact), View.MeasureSpec.makeMeasureSpec(2000, exact));
        page.layout(0, 0, 800, 2000);
    }

    private static void render(View page, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(800, 2000, Bitmap.Config.ARGB_8888);
        page.draw(new Canvas(bitmap));
        File dir = new File("build/reports/module-concept");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
