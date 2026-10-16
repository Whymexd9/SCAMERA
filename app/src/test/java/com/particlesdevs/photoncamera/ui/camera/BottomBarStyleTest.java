package com.particlesdevs.photoncamera.ui.camera;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.databinding.CameraFragmentBinding;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.model.AuxButtonsModel;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;
import com.particlesdevs.photoncamera.ui.camera.views.AuxButtonsLayout;
import com.particlesdevs.photoncamera.ui.camera.views.BottomChrome;
import com.particlesdevs.photoncamera.ui.camera.views.ProcessingRing;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

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

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * P157 / P158: the bottom bar of the camera screen on the owner's two phones (X300 Ultra, 434dp; X200 Ultra at a large
 * display size, 361dp): the same share of the screen (the shutter a fifth of the width), the tonal containers of the camera's
 * accent (no grey card with a line), the accent ring of the shutter, the processing ring round the gallery card, the last
 * shot's thumbnail inside the card with the same margin on every side (owner, 2026-10-16: the picture covered the card's
 * right edge). Renders to build/reports/viewfinder/bottom-style-*.png for review.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BottomBarStyleTest {
    private MockedStatic<ScameraApp> camera;

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
    }

    @Test
    @Config(qualifiers = "ru-w434dp-h962dp-430dpi")
    public void x300Ultra() throws Exception {
        check("434");
    }

    @Test
    @Config(qualifiers = "ru-w361dp-h794dp-640dpi")
    public void x200UltraLargeDisplaySize() throws Exception {
        check("361");
    }

    private void check(String name) throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            Activity activity = controller.get();
            Context context = new ContextThemeWrapper(activity, R.style.Theme_Scamera_SettingsActivity);
            float density = context.getResources().getDisplayMetrics().density;
            int w = context.getResources().getDisplayMetrics().widthPixels, h = context.getResources().getDisplayMetrics().heightPixels;
            CameraFragmentBinding binding = CameraFragmentBinding.inflate(LayoutInflater.from(context));
            CameraFragmentModel model = new CameraFragmentModel();
            model.setScreenAspectRatio((float) h / w);
            model.setDummyAspectRatio("3:4");
            binding.setUimodel(model);
            binding.layoutTopbar.setUimodel(model);
            AuxButtonsModel lenses = new AuxButtonsModel();
            List<CameraLensData> back = new ArrayList<>();
            float[] zoom = {.6f, 1f, 2f, 2.8f, 5.9f, 11.8f};
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
            int accent = ShadeStyle.accent(context);
            var row = binding.layoutBottombar.bottomButtons;
            row.shutterButton.setBackground(CameraUIViewImpl.shutter(context, accent));
            row.shutterButton.setActivated(true);
            row.flipCameraButton.setImageTintList(ColorStateList.valueOf(accent));
            row.galleryPlaceholder.setImageTintList(ColorStateList.valueOf(accent));
            // the last shot's thumbnail (the placeholder goes when there is one)
            Bitmap shot = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
            shot.eraseColor(0xFFC8B48C);
            row.galleryImageButton.setImageBitmap(shot);
            row.galleryPlaceholder.setVisibility(View.GONE);
            FrameLayout root = new FrameLayout(context);
            root.addView(binding.getRoot(), new FrameLayout.LayoutParams(w, h));
            activity.setContentView(root);
            CameraFragment.attachBottomChrome(binding, null);
            ProcessingRing ring = row.processingProgressBar;
            ring.showAt(.6f);
            for (int i = 0; i < 4; i++) {
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, w, h);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            }
            String at = name + "dp: ";
            Rect shutter = box(row.shutterButtonContainer, binding.getRoot());
            assertEquals(at + "the shutter a fifth of the width", w / 5f, shutter.width(), w * .02f);
            float s = BottomChrome.scale(w / density);
            // the tonal containers: the accent's tone over the card colour, no grey card
            int card = SettingsStyle.CARD;
            GradientDrawable stripBg = (GradientDrawable) strip.getBackground();
            assertEquals(at + "strip: tonal", BottomChrome.tone(card, accent, .08f), stripBg.getColor().getDefaultColor());
            GradientDrawable galleryBg = (GradientDrawable) row.galeryButtonContainer.getBackground();
            assertEquals(at + "gallery: tonal", BottomChrome.tone(card, accent, .10f), galleryBg.getColor().getDefaultColor());
            assertEquals(at + "gallery: large corners", BottomChrome.SIDE_RADIUS_DP * density * s, galleryBg.getCornerRadius(), .5f);
            assertTrue(at + "flip: a ripple on its tonal fill", row.flipCameraButton.getBackground() instanceof RippleDrawable);
            assertNotEquals(at + "the strip is not the grey card", card, stripBg.getColor().getDefaultColor());
            // P185: the strip a capsule round its lenses of one height, the active one a pill at least 54dp wide
            assertEquals(at + "strip: capsule corners", BottomChrome.STRIP_RADIUS_DP * density * s, stripBg.getCornerRadius(), .5f);
            for (int i = 0; i < strip.getChildCount(); i++) {
                View lens = strip.getChildAt(i);
                assertEquals(at + "lens height", Math.round(40 * density * s), lens.getHeight(), 1);
                if (lens.isSelected()) assertTrue(at + "the active lens a pill wider than tall", lens.getWidth() > lens.getHeight());
            }
            assertEquals(at + "the processing ring is shown", View.VISIBLE, ring.getVisibility());
            assertEquals(at + "the ring at 60 %", .6f, ring.shown(), 1e-4f);
            assertEquals(at + "the ring covers the gallery card", row.galeryButtonContainer.getWidth(), ring.getWidth());
            // the thumbnail: inside the card, the same margin (3dp times the scale) on every side
            Rect cardBox = box(row.galeryButtonContainer, binding.getRoot()), thumb = box(row.galleryImageButton, binding.getRoot());
            float margin = 3 * density * s;
            assertEquals(at + "thumbnail: left margin", margin, thumb.left - cardBox.left, 1f);
            assertEquals(at + "thumbnail: right margin", margin, cardBox.right - thumb.right, 1f);
            assertEquals(at + "thumbnail: top margin", margin, thumb.top - cardBox.top, 1f);
            assertEquals(at + "thumbnail: bottom margin", margin, cardBox.bottom - thumb.bottom, 1f);
            // render the bottom 40 % of the screen
            Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            image.eraseColor(0xFF101416);
            root.draw(new Canvas(image));
            int top = Math.round(h * .58f);
            Bitmap bottom = Bitmap.createBitmap(image, 0, top, w, h - top);
            File dir = new File("build/reports/viewfinder");
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, "bottom-style-" + name + ".png"))) {
                bottom.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }

    private static Rect box(View v, View screen) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        screen.getLocationInWindow(b);
        int x = a[0] - b[0], y = a[1] - b[1];
        return new Rect(x, y, x + v.getWidth(), y + v.getHeight());
    }
}
