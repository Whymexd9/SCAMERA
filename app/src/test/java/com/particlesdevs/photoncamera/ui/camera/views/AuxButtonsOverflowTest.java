package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.Dcg14Mode;
import com.particlesdevs.photoncamera.capture.PixelPro10;
import com.particlesdevs.photoncamera.capture.VivoQcomModes;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;
import com.particlesdevs.photoncamera.ui.camera.model.AuxButtonsModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class AuxButtonsOverflowTest {
    private MockedStatic<ScameraApp> app;
    private MockedStatic<VivoQcomModes> vivo;
    private Activity activity;
    private AuxButtonsLayout strip;
    private AuxButtonsModel model;
    private SharedPreferences prefs;
    private final List<String> selected = new ArrayList<>();

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(activity);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(activity);
        app.when(ScameraApp::getResourcesStatic).thenReturn(activity.getResources());
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(call -> activity.getString(call.getArgument(0)));
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(ScameraApp::getSettings).thenReturn(new Settings());
        ScameraApp instance = mock(ScameraApp.class);
        when(instance.getSettingsManager()).thenReturn(manager);
        app.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(instance);
        PreferenceKeys.initialise(manager);
        vivo = mockStatic(VivoQcomModes.class, CALLS_REAL_METHODS);
        vivo.when(VivoQcomModes::device).thenReturn(true);
        vivo.when(VivoQcomModes::enabled).thenReturn(false);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().apply();
        for (int i = 0; i < 9; i++) prefs.edit().putString("module_auto_back" + i, i == 2 || i == 8 ? "5" : "3")
                .putBoolean("module_visible_back" + i, true).putString("module_name_back" + i, "Owner " + i)
                .putString("module_zoom_back" + i, i == 8 ? "9.6" : i == 2 ? "2.4" : "1").apply();
        prefs.edit().putString("module_active", "back0").putString("module_name_back8", "9.6x (remosaic)")
                .putString("module_duplicate_back8", "back2").putBoolean("module_sensorcrop_back8", true).apply();
        TunableKeyManager.saveKeys(activity, "back8", Arrays.asList(new VendorTagUtils.TunableKey(
                VendorTagUtils.TunableKey.TYPE_SESSION, "vivo.control.forceSensorMode", Integer.class, 6)));
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        PixelPro10.setFullRes(false);
        model = new AuxButtonsModel();
        model.setBackCameras(Arrays.asList(new CameraLensData("3"), new CameraLensData("5")));
        model.setFrontCameras(Collections.emptyList());
        model.setAuxButtonListener(selected::add);
        strip = new AuxButtonsLayout(activity, null);
        strip.setGravity(android.view.Gravity.CENTER);
        strip.setAuxButtonsModel(model);
        strip.setActiveId("3");
        // P157: the strip NARROW px wide in the window too (the hold waits for a real layout pass): nine lens tiles overflow it
        // even shrunk to LensButton.MIN_FIT (P181: at 200 px they now shrink to fit)
        activity.setContentView(strip, new android.view.ViewGroup.LayoutParams(NARROW, 100));
        activity.getWindow().getDecorView().dispatchWindowFocusChanged(true);
    }

    @After public void cleanup() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null) dialog.dismiss();
        activity.finish();
        vivo.close();
        app.close();
    }

    /** P181: narrower than nine tiles at LensButton.MIN_FIT, so the module list of the long press is still needed. */
    private static final int NARROW = 100;

    private void size(int width) {
        strip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        strip.layout(0, 0, width, 100);
    }

    @Test public void overflowingLongPressListsEveryModuleAndSelectsTheClippedExistingButton() {
        size(NARROW);
        View clipped = strip.getChildAt(8);
        assertTrue(clipped.getRight() > strip.getWidth());
        PixelPro10.setFullRes(true);
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        String json = prefs.getString("pref_sensorconfig_back8_tunablekeys", "");
        assertTrue(strip.getChildAt(0).performLongClick());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals(9, dialog.getListView().getAdapter().getCount());
        String item = dialog.getListView().getAdapter().getItem(8).toString();
        assertTrue(item.contains("9.6x (remosaic)") && item.contains("camera 5"));
        dialog.getListView().performItemClick(null, 8, 8);
        assertEquals("back8", ModuleRegistry.active());
        assertEquals(Arrays.asList("5"), selected);
        assertTrue(clipped.isSelected());
        assertFalse(PixelPro10.fullResOn());
        assertFalse(Dcg14Mode.on());
        assertEquals(json, prefs.getString("pref_sensorconfig_back8_tunablekeys", ""));
    }

    @Test public void aRealStationaryHoldReachesTheLongClickWithoutChangingZoom() {
        size(NARROW);
        View first = null;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View child = strip.getChildAt(i);
            float center = (child.getLeft() + child.getRight()) / 2f;
            if (center > 0 && center < strip.getWidth()) { first = child; break; }
        }
        assertNotNull("a visible lens must accept the stationary hold", first);
        float x = (first.getLeft() + first.getRight()) / 2f;
        float y = (first.getTop() + first.getBottom()) / 2f;
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0);
        strip.dispatchTouchEvent(down);
        assertTrue("the visible lens received DOWN", first.isPressed());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals("back0", ModuleRegistry.active());
        assertTrue(selected.isEmpty());
        down.recycle();
    }

    @Test public void fittingOrDisabledStripsDoNotOpenTheOverflowDialog() {
        size(3000);
        assertFalse(strip.getChildAt(0).performLongClick());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        size(NARROW);
        model.setEnabled(false);
        assertFalse(strip.getChildAt(0).performLongClick());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    /**
     * P181 (owner: «у нас модули выходят за границы экрана»): nine modules too wide at their own size shrink into a strip
     * 3/4 of it, every tile whole (none squeezed below its fitted least width); a strip they cannot fit even at MIN_FIT
     * (200 px here) offers the module list again - a squeezed tile counts as one that does not fit.
     */
    @Test public void nineModulesShrinkIntoAStripTheyOverflowedBefore() {
        size(3000);
        int width = (int) Math.ceil(strip.naturalNeed() * 0.75f);
        size(width);
        for (int i = 0; i < strip.getChildCount(); i++) {
            LensButton tile = (LensButton) strip.getChildAt(i);
            assertTrue("tile " + i + " " + tile.getLeft() + ".." + tile.getRight(), tile.getLeft() >= 0 && tile.getRight() <= strip.getWidth());
            assertTrue("tile " + i + " whole: " + tile.getMeasuredWidth() + " of " + tile.getMinWidth(), tile.getMeasuredWidth() >= tile.getMinWidth());
            assertTrue("shrunk", tile.fit() < 1f);
        }
        assertFalse("nothing clipped: no module list", strip.getChildAt(0).performLongClick());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        size(200);
        assertTrue("too narrow even shrunk: the module list", strip.getChildAt(0).performLongClick());
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void continuousDragStillZoomsAndDoesNotSelectAnotherModuleOrOpenADialog() {
        size(200);
        List<Float> drag = new ArrayList<>();
        strip.setZoomDrag(drag::add);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20, 50, 0);
        MotionEvent move = MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, 60, 50, 0);
        strip.onTouchEvent(down);
        strip.onTouchEvent(move);
        assertEquals(Arrays.asList(40f), drag);
        assertEquals("back0", ModuleRegistry.active());
        assertTrue(selected.isEmpty());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        down.recycle();
        move.recycle();
    }
}
