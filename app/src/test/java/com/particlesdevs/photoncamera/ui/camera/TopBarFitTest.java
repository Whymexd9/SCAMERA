package com.particlesdevs.photoncamera.ui.camera;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.databinding.CameraFragmentBinding;
import com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding;
import com.particlesdevs.photoncamera.settings.FormatChoice;
import com.particlesdevs.photoncamera.settings.MotionMode;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * SIMPLE_UI §1 / §6: the top bar on the real camera_fragment layout. With nothing on it holds the concept (level 0); with
 * everything on (torch, 10 s timer, Motion, the longest format, DCG) at 320 - 480dp and the font scale 1.0 / 1.3 it steps down
 * but never overlaps, never ellipsizes a label, keeps the gear inside and the timer's seconds badge visible, and the preview
 * frame starts under it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TopBarFitTest {
    private Context context;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PreferenceKeys.initialise(manager);
    }

    @After
    public void tearDown() {
        TopBar.dcgAvailable(false);
        RuntimeEnvironment.setFontScale(1f);
        camera.close();
    }

    private static Rect inRoot(View v, View root) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        root.getLocationInWindow(b);
        int x = a[0] - b[0], y = a[1] - b[1];
        return new Rect(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private static String longestFormat() {
        String best = "";
        for (FormatChoice f : FormatChoice.values()) if (f.longLabel().length() > best.length()) best = f.longLabel();
        return best;
    }

    /** Lays the camera screen out at {@code widthDp}; returns the level the bar settled on. */
    private int check(int widthDp, float fontScale, boolean all, String name) throws Exception {
        return check(widthDp, fontScale, all, all, all, all, name);
    }

    private int check(int widthDp, float fontScale, boolean torchOn, boolean timerOn, boolean motionOn, boolean all, String name) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        PreferenceKeys.setAeMode(torchOn ? 0 : 1);
        PreferenceKeys.setCountdownTimerIndex(timerOn ? (all ? 2 : 1) : 0);
        MotionMode.set(motionOn);
        TopBar.dcgAvailable(all);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            Activity activity = controller.get();
            CameraFragmentBinding binding = CameraFragmentBinding.inflate(LayoutInflater.from(new ContextThemeWrapper(activity,
                    R.style.Theme_Scamera_SettingsActivity)));
            CameraFragmentModel model = new CameraFragmentModel();
            float density = activity.getResources().getDisplayMetrics().density;
            model.setScreenAspectRatio(20f / 9f);
            binding.setUimodel(model);
            binding.layoutTopbar.setUimodel(model);
            binding.executePendingBindings();
            LayoutMainTopbarBinding tb = binding.layoutTopbar;
            activity.setContentView(binding.getRoot());
            int w = Math.round(widthDp * density), h = Math.round(widthDp * 20f / 9f * density);
            View root = binding.getRoot();
            int exact = View.MeasureSpec.EXACTLY;
            root.measure(View.MeasureSpec.makeMeasureSpec(w, exact), View.MeasureSpec.makeMeasureSpec(h, exact));
            root.layout(0, 0, w, h);
            TopBar.bind(tb);
            if (all) {
                tb.formatBadge.setTag(R.id.format_badge, longestFormat());
                TopBar.fit(tb);
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(w, exact), View.MeasureSpec.makeMeasureSpec(h, exact));
            root.layout(0, 0, w, h);
            View top = tb.getRoot();
            Rect bar = inRoot(top, root);
            String at = widthDp + "dp x" + fontScale + " " + name + " level " + TopBar.level(tb) + ": ";
            View[] order = {tb.torchButton, tb.timerButton, tb.motionModeButton, tb.topbarBadges, tb.settingsButton};
            Rect prev = null;
            for (View v : order) {
                Rect r = inRoot(v, root);
                assertTrue(at + "outside the bar " + r, r.left >= bar.left && r.right <= bar.right && r.top >= bar.top && r.bottom <= bar.bottom);
                if (prev != null) assertTrue(at + "overlap " + prev + " " + r, prev.right <= r.left);
                prev = r;
            }
            assertEquals(at + "gear pinned right", w - top.getPaddingRight(), inRoot(tb.settingsButton, root).right);
            for (TextView t : new TextView[]{tb.formatBadge, tb.sizeBadge, tb.dcgBadge, tb.torchLabel, tb.motionLabel, tb.timerBadge}) {
                if (t.getVisibility() != View.VISIBLE) continue;
                assertNotNull(at + "laid out", t.getLayout());
                assertEquals(at + "ellipsis in " + t.getText(), 0, t.getLayout().getEllipsisCount(0));
                assertEquals(at + "wrapped " + t.getText(), 1, t.getLayout().getLineCount());
                assertTrue(at + "clipped " + t.getText(), t.getLayout().getLineWidth(0) <= t.getWidth() - t.getTotalPaddingLeft() - t.getTotalPaddingRight() + 1);
            }
            // the timer always, torch and Motion without their label: round (owner: «кружки при выборе должны быть круглые»)
            assertEquals(at + "timer round", tb.timerButton.getWidth(), tb.timerButton.getHeight());
            for (View b : new View[]{tb.torchButton, tb.motionModeButton}) {
                boolean labelled = ((ViewGroup) b).getChildAt(1).getVisibility() == View.VISIBLE;
                if (!labelled) assertEquals(at + "round", b.getWidth(), b.getHeight());
                else assertTrue(at + "a pill with its label", b.getWidth() > b.getHeight());
            }
            if (timerOn) {
                assertEquals(at + "the seconds badge", View.VISIBLE, tb.timerBadge.getVisibility());
                Rect badge = inRoot(tb.timerBadge, root);
                assertTrue(at + "the badge inside the bar", badge.top >= bar.top && badge.bottom <= bar.bottom);
            }
            assertEquals(at + "the bar is the concept's 64dp x k", UiScale.dp(activity, 64), top.getHeight());
            assertTrue(at + "preview frame under the bar", binding.cameraContainer.getTop() >= top.getBottom());
            System.out.println(at + "ok");
            Bitmap image = Bitmap.createBitmap(w, Math.max(1, top.getHeight()), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(0xFF2A3036);
            top.draw(canvas);
            java.io.File dir = new java.io.File("build/reports/viewfinder");
            dir.mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir,
                    "topbar-" + widthDp + "-" + fontScale + "-" + name + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return TopBar.level(tb);
        }
    }

    @Test
    @Config(qualifiers = "ru-w392dp-h871dp-xxhdpi")
    public void conceptWidthHoldsTheConcept() throws Exception {
        assertEquals("nothing on at 392dp: the concept as drawn", 0, check(392, 1f, false, "plain"));
        assertEquals("the concept's screen (timer 3 s): level 0", 0, check(392, 1f, false, true, false, false, "concept"));
        assertEquals("torch on keeps its label at 392dp", 0, check(392, 1f, true, false, false, false, "torch"));
        check(392, 1f, true, true, true, false, "three");
        check(392, 1f, true, "all");
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h711dp-xhdpi")
    public void everythingOnFitsAt320dp() throws Exception {
        check(320, 1f, true, "all");
        check(320, 1.3f, true, "all-font");
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp-xxhdpi")
    public void everythingOnFitsAt360dp() throws Exception {
        check(360, 1f, false, "plain");
        check(360, 1f, true, "all");
        check(360, 1.3f, true, "all-font");
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    public void everythingOnFitsAt360dpInEnglish() throws Exception {
        check(360, 1f, true, "all-en");
        check(360, 1.3f, true, "all-font-en");
    }

    @Test
    @Config(qualifiers = "ru-w412dp-h915dp-xxhdpi")
    public void everythingOnFitsAt412dp() throws Exception {
        check(412, 1f, true, "all");
        check(412, 1.3f, true, "all-font");
    }

    @Test
    @Config(qualifiers = "ru-w480dp-h1067dp-xxhdpi")
    public void everythingOnFitsAt480dp() throws Exception {
        check(480, 1f, true, "all");
        check(480, 1.3f, true, "all-font");
    }
}
