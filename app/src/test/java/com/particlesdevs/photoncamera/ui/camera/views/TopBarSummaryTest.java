package com.particlesdevs.photoncamera.ui.camera.views;

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
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.circularbarlib.ui.ManualSummaryView;
import com.particlesdevs.photoncamera.circularbarlib.ui.ViewObserver;
import com.particlesdevs.photoncamera.databinding.CameraFragmentBinding;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.CameraUIViewImpl;
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
 * The manual summary in the top bar (MANUAL_TASK.md §4) on the real camera_fragment layout at 360dp and 412dp with the
 * most applied manual values (four: ISO, shutter, focus, WB; or shutter, EV, focus, WB): it crosses neither the gear
 * nor the route and format icons, no item lies outside its card, nothing is ellipsized, and the preview frame starts
 * below the top bar's actual height.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TopBarSummaryTest {
    private Context context;
    private MockedStatic<PhotonCamera> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> PhotonCamera.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        camera.when(PhotonCamera::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PreferenceKeys.initialise(manager);
    }

    @After
    public void tearDown() {
        camera.close();
    }

    private static Rect inRoot(View v, View root) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        root.getLocationInWindow(b);
        int x = a[0] - b[0], y = a[1] - b[1];
        return new Rect(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private void check(int widthDp, String name, int[] params, String[] values) throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            Activity activity = controller.get();
            CameraFragmentBinding binding = CameraFragmentBinding.inflate(LayoutInflater.from(new ContextThemeWrapper(activity,
                    R.style.Theme_Photon_SettingsActivity)));
            CameraFragmentModel model = new CameraFragmentModel();
            float density = context.getResources().getDisplayMetrics().density;
            model.setScreenAspectRatio(20f / 9f); // a tall phone: the preview starts under the top bar
            binding.setUimodel(model);
            binding.layoutTopbar.setUimodel(model);
            binding.executePendingBindings();
            CameraUIViewImpl.bindBadges(binding.layoutTopbar);
            ManualSummaryView summary = binding.getRoot().findViewById(R.id.manual_summary);
            assertNotNull(summary);
            int[] icons = new int[params.length];
            for (int i = 0; i < params.length; i++) icons[i] = ViewObserver.ICONS[params[i]];
            summary.setItems(icons, values, "summary");
            summary.setVisibility(View.VISIBLE);
            activity.setContentView(binding.getRoot());
            int w = Math.round(widthDp * density), h = Math.round(widthDp * 20f / 9f * density);
            View root = binding.getRoot();
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, w, h);
            View top = binding.layoutTopbar.getRoot();
            Rect gear = inRoot(top.findViewById(R.id.settings_button), root);
            Rect group = inRoot(top.findViewById(R.id.topbar_badges), root);
            Rect card = inRoot(summary, root);
            String at = widthDp + "dp " + name + ": ";
            assertTrue(at + "summary under the gear " + card + " " + gear, card.right <= gear.left);
            assertTrue(at + "summary over the group " + card + " " + group, card.left >= group.right);
            assertTrue(at + "summary outside the top bar", card.top >= inRoot(top, root).top && card.bottom <= inRoot(top, root).bottom);
            assertEquals(at + "gear pinned right", w - Math.round(12 * density), gear.right);
            assertTrue(at + "an item outside the card", summary.itemsInside());
            for (int i = 0; i < summary.itemCount(); i++) {
                TextView value = (TextView) ((ViewGroup) summary.item(i)).getChildAt(1);
                assertEquals(at + values[i], values[i], value.getText().toString());
                assertEquals(at + "ellipsis", 0, value.getLayout().getEllipsisCount(0));
                assertEquals(at + "wrapped inside an item", 1, value.getLayout().getLineCount());
            }
            View container = binding.cameraContainer;
            assertTrue(at + "preview frame " + container.getTop() + " starts under the top bar " + top.getBottom(),
                    container.getTop() >= top.getBottom());
            assertTrue(at + "top bar at least 56dp", top.getHeight() >= Math.round(56 * density));
            System.out.println(at + "summary lines=" + summary.lineCount() + " card=" + card + " top bar height="
                    + Math.round(top.getHeight() / density) + "dp");
            Bitmap image = Bitmap.createBitmap(w, Math.round(120 * density), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            java.io.File dir = new java.io.File("build/reports/viewfinder");
            dir.mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "topbar-summary-" + widthDp + "-" + name + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }

    private static final int ISO = 0, SHUTTER = 1, EV = 2, FOCUS = 3, WB = 4;

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp-xxhdpi")
    public void fourValuesFitAt360dp() throws Exception {
        check(360, "iso", new int[]{ISO, SHUTTER, FOCUS, WB}, new String[]{"12800", "1/8000", "30 см", "10000K"});
        check(360, "ev", new int[]{SHUTTER, EV, FOCUS, WB}, new String[]{"1/8000", "−1 2/3", "1,2 м", "5200K"});
    }

    @Test
    @Config(qualifiers = "ru-w412dp-h915dp-xxhdpi")
    public void fourValuesFitAt412dp() throws Exception {
        check(412, "iso", new int[]{ISO, SHUTTER, FOCUS, WB}, new String[]{"12800", "1/8000", "30 см", "10000K"});
        check(412, "ev", new int[]{SHUTTER, EV, FOCUS, WB}, new String[]{"1/8000", "−1 2/3", "1,2 м", "5200K"});
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    public void fourValuesFitAt360dpInEnglish() throws Exception {
        check(360, "iso-en", new int[]{ISO, SHUTTER, FOCUS, WB}, new String[]{"12800", "1/8000", "30 cm", "10000K"});
        check(360, "ev-en", new int[]{SHUTTER, EV, FOCUS, WB}, new String[]{"1/8000", "−1 2/3", "1.2 m", "5200K"});
    }
}
