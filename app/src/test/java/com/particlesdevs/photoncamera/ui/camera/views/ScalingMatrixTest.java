package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.ManualChipView;
import com.particlesdevs.photoncamera.databinding.CameraFragmentBinding;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.CameraFragment;
import com.particlesdevs.photoncamera.ui.camera.CameraUIViewImpl;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;
import com.particlesdevs.photoncamera.ui.camera.model.AuxButtonsModel;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * SIMPLE_UI §6 scaling matrix: the camera screen (Pro open, the LUT and lock pills shown) on the real camera_fragment layout
 * at the seven window sizes of the task - w320dp-h690dp (display size «largest» on a small phone), w360dp-h780dp,
 * w392dp-h850dp (the reference), w411dp-h905dp (X200 Ultra), w480dp-h1056dp («smallest» on a 1440 px panel), w392dp-h700dp
 * (a short window) and w600dp-h960dp - each at font scale 1.0, 1.3 and 2.0. Asserted:
 * <ul>
 * <li>none of these overlap: the top bar, the viewfinder, the lens row, the shutter row, the Pro panel, and the LUT pill with
 * the lock pill (both inside the viewfinder);</li>
 * <li>packed texts (Pro chips, lens labels, the pills) are one line and not cut;</li>
 * <li>k = short side / 392dp clamped at 0.85 and 1.35; where it is not clamped, the shutter width, the top bar height and the
 * lens row width over the screen width are within ±6 % of the reference's; where it is clamped, they follow the clamped k.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScalingMatrixTest {
    static final String[] WINDOWS = {"w320dp-h690dp", "w360dp-h780dp", "w392dp-h850dp", "w411dp-h905dp", "w480dp-h1056dp",
            "w392dp-h700dp", "w600dp-h960dp"};
    static final float[] FONTS = {1f, 1.3f, 2f};
    static final float TOLERANCE = 0.06f;

    private MockedStatic<ScameraApp> camera;
    private float density;
    private View screen;

    @Before
    public void setUp() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
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
        camera.close();
        RuntimeEnvironment.setFontScale(1f);
    }

    /** The proportions of one layout (shares of the screen width). */
    static final class Shares {
        float shutter, topBar, lensRow, k;
    }

    @Test
    public void theCameraScreenAtEveryWindowAndFontSize() throws Exception {
        RuntimeEnvironment.setQualifiers("w392dp-h850dp-xxhdpi");
        Shares reference = check("w392dp-h850dp", 1f);
        List<String> report = new ArrayList<>(), failures = new ArrayList<>();
        for (String window : WINDOWS) {
            RuntimeEnvironment.setQualifiers(window + "-xxhdpi");
            for (float font : FONTS) {
                Shares s = check(window, font);
                String at = window + " font " + font + ": ";
                float shortSide = Math.min(Float.parseFloat(window.substring(1, window.indexOf("dp"))),
                        Float.parseFloat(window.substring(window.indexOf("-h") + 2, window.lastIndexOf("dp"))));
                float expectK = Math.max(0.85f, Math.min(1.35f, shortSide / 392f));
                assertEquals(at + "k clamped at 0.85 / 1.35", expectK, s.k, 1e-3f);
                // the share a k-scaled size has against the reference: k / (k at the reference) x (392 / width)
                float width = Float.parseFloat(window.substring(1, window.indexOf("dp")));
                float factor = (s.k / reference.k) * (392f / width);
                share(failures, at + "shutter", reference.shutter * factor, s.shutter);
                share(failures, at + "top bar", reference.topBar * factor, s.topBar);
                share(failures, at + "lens row", reference.lensRow * factor, s.lensRow);
                report.add(String.format(Locale.US, "%s k %.3f shutter %.3f top %.3f lens %.3f", at, s.k, s.shutter, s.topBar, s.lensRow));
            }
        }
        for (String line : report) System.out.println(line);
        assertTrue(String.join(" | ", failures), failures.isEmpty());
    }

    /** A share within ±6 % of the expected one, else a failure line. */
    private static void share(List<String> failures, String what, float expected, float actual) {
        if (Math.abs(actual - expected) > expected * TOLERANCE)
            failures.add(String.format(Locale.US, "%s share %.4f, expected %.4f (%+.1f %%)", what, actual, expected, 100f * (actual / expected - 1f)));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private Rect at(View v) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        screen.getLocationInWindow(b);
        int x = a[0] - b[0], y = a[1] - b[1];
        return new Rect(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private static boolean overlap(Rect a, Rect b) {
        return a.width() > 0 && a.height() > 0 && b.width() > 0 && b.height() > 0 && Rect.intersects(a, b)
                && Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1;
    }

    private static void oneLine(String at, TextView t) {
        assertNotNull(at + " laid out", t.getLayout());
        assertEquals(at + " one line", 1, t.getLayout().getLineCount());
        assertEquals(at + " not cut", 0, t.getLayout().getEllipsisCount(0));
    }

    private Shares check(String window, float fontScale) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            Activity activity = controller.get();
            Context context = new ContextThemeWrapper(activity, R.style.Theme_Scamera_SettingsActivity);
            density = context.getResources().getDisplayMetrics().density;
            int w = context.getResources().getDisplayMetrics().widthPixels, h = context.getResources().getDisplayMetrics().heightPixels;
            String at = window + " (" + Math.round(w / density) + "x" + Math.round(h / density) + "dp) font " + fontScale + ": ";

            CameraFragmentBinding binding = CameraFragmentBinding.inflate(LayoutInflater.from(context));
            CameraFragmentModel model = new CameraFragmentModel();
            model.setScreenAspectRatio((float) h / w);
            model.setDummyAspectRatio("3:4");
            binding.setUimodel(model);
            binding.layoutTopbar.setUimodel(model);
            AuxButtonsModel lenses = new AuxButtonsModel();
            List<CameraLensData> back = new ArrayList<>();
            float[] zoom = {.6f, 1f, 3f, 10f};
            for (int i = 0; i < zoom.length; i++) {
                CameraLensData lens = new CameraLensData(String.valueOf(i));
                lens.setZoomFactor(zoom[i]);
                back.add(lens);
            }
            lenses.setBackCameras(back);
            lenses.setFrontCameras(new ArrayList<>());
            binding.setAuxmodel(lenses);
            binding.executePendingBindings();
            CameraUIViewImpl.bindBadges(binding.layoutTopbar);
            AuxButtonsLayout strip = binding.layoutBottombar.auxButtonsContainer;
            strip.setAuxButtonsModel(lenses);
            strip.setActiveId("1");
            binding.layoutViewfinder.texture.setAspectRatio(w, w * 4 / 3);
            // the LUT pill with a long name and the lock pill, both shown
            TextView lut = binding.layoutViewfinder.lutPill, lock = binding.layoutViewfinder.lockPill;
            lut.setText("Прохладная 160 · Pro-Mist");
            lut.setEllipsize(android.text.TextUtils.TruncateAt.END);
            CameraFragment.styleLockPill(lock);
            lock.setVisibility(View.VISIBLE);

            ExpandingManualPanel panel = (ExpandingManualPanel) binding.manualMode;
            panel.setExpanded(true, false);
            int[] chipIds = {R.id.iso_option_tv, R.id.exposure_option_tv, R.id.ev_option_tv, R.id.focus_option_tv, R.id.wb_option_tv};
            String[] values = {"12800", "1/8000", "+1 1/3", "1,2 м", "10000K"};
            for (int i = 0; i < chipIds.length; i++) ((ManualChipView) panel.findViewById(chipIds[i])).bind(values[i], ManualChipView.MANUAL);

            FrameLayout root = new FrameLayout(context);
            root.addView(binding.getRoot(), new FrameLayout.LayoutParams(w, h));
            screen = binding.getRoot();
            activity.setContentView(root);
            CameraFragment.attachBottomChrome(binding, null);
            settle(root, w, h);
            CameraFragment.placeViewfinderPills(binding);
            settle(root, w, h);

            View row = binding.layoutBottombar.bottomButtons.getRoot();
            View shutter = row.findViewById(R.id.shutter_button_container);
            Rect topBar = at(binding.layoutTopbar.getRoot()), viewfinder = at(binding.layoutViewfinder.texture),
                    lensRow = at(strip), shutterRow = at(row), pro = at(panel.findViewById(R.id.buttons_container)),
                    lutBox = at(lut), lockBox = at(lock);
            String geometry = " top=" + topBar + " vf=" + viewfinder + " lens=" + lensRow + " row=" + shutterRow + " pro=" + pro
                    + " lut=" + lutBox + " lock=" + lockBox;

            // The 3:4 viewfinder under the top bar wherever the window holds it and both bars; a window too short for that
            // (600 x 960dp) lets the top bar lie over the preview's top, as in 16:9 - then nothing of the pills under the bar.
            boolean fits = topBar.height() + w * 4 / 3 + (h - lensRow.top) <= h;
            if (fits) assertTrue(at + "the viewfinder under the top bar" + geometry, viewfinder.top >= topBar.bottom - 1);
            else {
                assertTrue(at + "LUT pill clear of the top bar" + geometry, lutBox.top >= topBar.bottom);
                assertTrue(at + "lock pill clear of the top bar" + geometry, lockBox.top >= topBar.bottom);
                System.out.println("SHORT " + at + "the top bar over the viewfinder's top (no room for 3:4 and both bars)");
            }
            assertFalse(at + "lens row / shutter row" + geometry, overlap(lensRow, shutterRow));
            assertFalse(at + "Pro / lens row" + geometry, overlap(pro, lensRow));
            assertFalse(at + "Pro / shutter row" + geometry, overlap(pro, shutterRow));
            assertFalse(at + "Pro / top bar" + geometry, overlap(pro, topBar));
            assertFalse(at + "LUT pill / lock pill" + geometry, overlap(lutBox, lockBox));
            assertTrue(at + "pills inside the viewfinder" + geometry, viewfinder.contains(lutBox) && viewfinder.contains(lockBox));
            assertTrue(at + "rows inside the screen" + geometry, shutterRow.bottom <= h && lensRow.left >= 0 && lensRow.right <= w);
            assertEquals(at + "lock pill 20dp", dp(20), lockBox.height(), 1);
            oneLine(at + "lock pill", lock);
            assertTrue(at + "LUT pill one line", lut.getLayout() != null && lut.getLayout().getLineCount() == 1);
            for (int id : chipIds) assertTrue(at + "chip text cut", ((ManualChipView) panel.findViewById(id)).textFits());
            for (int i = 0; i < strip.getChildCount(); i++) {
                View lens = strip.getChildAt(i);
                if (lens instanceof TextView && ((TextView) lens).getLayout() != null)
                    assertEquals(at + "lens label one line", 1, ((TextView) lens).getLayout().getLineCount());
            }
            Shares s = new Shares();
            s.k = UiScale.k(binding.getRoot().getContext());
            s.shutter = shutter.getWidth() / (float) w;
            s.topBar = topBar.height() / (float) w;
            s.lensRow = lensRow.width() / (float) w;
            return s;
        }
    }

    private static void settle(View root, int w, int h) {
        for (int i = 0; i < 4; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, w, h);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        }
    }
}
