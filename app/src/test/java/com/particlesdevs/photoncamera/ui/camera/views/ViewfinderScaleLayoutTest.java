package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
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

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P43: the camera screen's controls on the real camera_fragment layout at the display sizes and font sizes a phone can be
 * set to (display size small / default / large = more or fewer dp on the same panel; font scale 0.85 / 1.0 / 1.3). Every
 * place and size is dp from the edges, the bottom bar and the shade's handle, as in the concept (CSS px = dp):
 * <ul>
 * <li>manual block 12dp from the left and right edges, its bottom 12dp above the HIDDEN handle (40dp above the handle's
 * bottom edge); the ruler card 8dp above the strip;</li>
 * <li>the toggle 52dp wide and at least 58dp high, the strip one row of five equal chips, its height growing with the
 * font size only up to {@link UiTokens#FONT_SCALE_CAP} (on the vivo it was about 95dp);</li>
 * <li>nothing of the manual block over the handle, the lens strip or the shutter row; the top bar, the lens strip and
 * the shutter row (gallery, shutter, flip) at their dp places.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ViewfinderScaleLayoutTest {
    private static final float[] FONT_SCALES = {0.85f, 1f, 1.3f};
    /** The strip's height may grow with the font size, but never toward the ~95dp seen on the vivo. */
    private static final float STRIP_MAX_DP = 84f;

    private MockedStatic<PhotonCamera> camera;
    private float density;
    private View screen;

    @Before
    public void setUp() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
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
        RuntimeEnvironment.setFontScale(1f);
    }

    /** Display size «small» on a 1440 px wide phone: more dp. */
    @Test
    @Config(qualifiers = "w480dp-h1066dp-xhdpi")
    public void smallDisplaySize() throws Exception {
        for (float scale : FONT_SCALES) check(scale, "small");
    }

    /** The vivo X200 Ultra's default display size. */
    @Test
    @Config(qualifiers = "ru-w411dp-h914dp-xxhdpi")
    public void defaultDisplaySize() throws Exception {
        for (float scale : FONT_SCALES) check(scale, "default");
    }

    /** Display size «large»: fewer dp, every dp bigger. */
    @Test
    @Config(qualifiers = "ru-w360dp-h800dp-xxxhdpi")
    public void largeDisplaySize() throws Exception {
        for (float scale : FONT_SCALES) check(scale, "large");
    }

    /** An English system at the default size: the longest state line («MAN.» / «OFF» / «AUTO»). */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void defaultDisplaySizeInEnglish() throws Exception {
        for (float scale : FONT_SCALES) check(scale, "default-en");
    }

    /** A capped text size: the value line at 1.3 grows to at most 14sp x the cap, the scale's text too. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void textGrowsWithTheFontSizeUpToTheCap() {
        RuntimeEnvironment.setFontScale(1.3f);
        Context context = RuntimeEnvironment.getApplication();
        float d = context.getResources().getDisplayMetrics().density;
        float system = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.getResources().getDisplayMetrics());
        assertTrue("the font scale applies: " + system / d, system / d > 14f * UiTokens.FONT_SCALE_CAP);
        assertEquals(14f * UiTokens.FONT_SCALE_CAP * d, UiTokens.spPx(context, 14f), .01f);
        RuntimeEnvironment.setFontScale(.85f);
        context = RuntimeEnvironment.getApplication();
        system = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.getResources().getDisplayMetrics());
        assertEquals("smaller font sizes apply in full", system, UiTokens.spPx(context, 14f), .01f);
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

    private void check(float fontScale, String name) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            Activity activity = controller.get();
            Context context = new ContextThemeWrapper(activity, R.style.Theme_Photon_SettingsActivity);
            assertEquals(fontScale, context.getResources().getConfiguration().fontScale, 1e-4f);
            density = context.getResources().getDisplayMetrics().density;
            int w = context.getResources().getDisplayMetrics().widthPixels, h = context.getResources().getDisplayMetrics().heightPixels;
            String at = name + " " + Math.round(w / density) + "x" + Math.round(h / density) + "dp @" + density + " font "
                    + fontScale + ": ";

            CameraFragmentBinding binding = CameraFragmentBinding.inflate(LayoutInflater.from(context));
            CameraFragmentModel model = new CameraFragmentModel();
            model.setScreenAspectRatio((float) h / w);
            model.setDummyAspectRatio("3:4");
            binding.setUimodel(model);
            binding.layoutTopbar.setUimodel(model);
            AuxButtonsModel lenses = new AuxButtonsModel();
            List<CameraLensData> back = new ArrayList<>();
            float[] zoom = {.4f, 1f, 2.4f, 5f, 10f};
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
            // The 3:4 preview as the camera sizes it (px).
            binding.layoutViewfinder.texture.setAspectRatio(w, w * 4 / 3);

            ExpandingManualPanel panel = (ExpandingManualPanel) binding.manualMode;
            panel.setExpanded(true, false);
            int[] chipIds = {R.id.iso_option_tv, R.id.exposure_option_tv, R.id.ev_option_tv, R.id.focus_option_tv, R.id.wb_option_tv};
            String[] values = {"12800", "1/8000", "+1 1/3", "1,2 м", "10000K"};
            int[] states = {ManualChipView.MANUAL, ManualChipView.MANUAL, ManualChipView.LOCKED, ManualChipView.AUTO, ManualChipView.MANUAL};
            for (int i = 0; i < 5; i++) ((ManualChipView) panel.findViewById(chipIds[i])).bind(values[i], states[i]);
            // The ruler card open over the strip.
            panel.findViewById(R.id.knobViewContainer).setVisibility(View.VISIBLE);
            panel.findViewById(R.id.linearScaleView).setVisibility(View.VISIBLE);

            FrameLayout root = new FrameLayout(context);
            root.addView(binding.getRoot(), new FrameLayout.LayoutParams(w, h));
            screen = binding.getRoot();
            activity.setContentView(root);
            CameraFragment.attachBottomChrome(binding, null);
            settle(root, w, h);

            View bar = binding.layoutBottombar.getRoot(), handle = binding.settingsSheetHandleSlot;
            View toggle = panel.findViewById(R.id.manual_toggle), chips = panel.findViewById(R.id.buttons_container);
            View ruler = panel.findViewById(R.id.knobViewContainer);
            View row = binding.layoutBottombar.bottomButtons.getRoot();
            Rect manual = at(panel), handleBox = at(handle), barBox = at(bar), toggleBox = at(toggle), chipsBox = at(chips),
                    rulerBox = at(ruler), stripBox = at(strip), rowBox = at(row), topBar = at(binding.layoutTopbar.getRoot());
            String geometry = " manual=" + manual + " handle=" + handleBox + " bar=" + barBox + " toggle=" + toggleBox
                    + " chips=" + chipsBox + " strip=" + stripBox + " row=" + rowBox;

            // The manual block: 12dp from the sides, 12dp above the handle (40dp above its bottom), the ruler 8dp above.
            assertEquals(at + "left margin" + geometry, dp(12), manual.left);
            assertEquals(at + "right margin" + geometry, dp(12), w - manual.right);
            assertEquals(at + "handle at the bar's top" + geometry, barBox.top, handleBox.top, 1);
            assertEquals(at + "12dp above the handle" + geometry, handleBox.top - dp(12), manual.bottom, 1);
            assertEquals(at + "40dp above the handle's bottom" + geometry, handleBox.bottom - dp(40), manual.bottom, 1);
            assertEquals(at + "ruler card 8dp above the strip" + geometry, chipsBox.top - dp(8), rulerBox.bottom);
            assertTrue(at + "the open ruler stays under the top bar" + geometry, rulerBox.top >= topBar.bottom);

            // Toggle and chips: one row, the toggle 52dp wide, 58dp..cap high.
            assertEquals(at + "toggle width" + geometry, dp(52), toggleBox.width(), 1);
            assertTrue(at + "toggle at least 58dp: " + toggleBox.height() / density, toggleBox.height() >= dp(58));
            assertTrue(at + "strip " + toggleBox.height() / density + "dp, at most " + STRIP_MAX_DP,
                    toggleBox.height() <= dp(STRIP_MAX_DP));
            // The concept's strip: 4 + (7 + 22 + 3 + value line + 3 + state line + 6) + 4 dp, lines 1.2 x the text size
            // (16.8 + 10.8 = 27.6dp at font scale 1, 76.6dp in all), whatever the font's own metrics.
            float lines = 1.2f * (UiTokens.spPx(context, 14f) + UiTokens.spPx(context, 9f));
            assertEquals(at + "strip height", dp(49) + lines, toggleBox.height(), 2f);
            if (fontScale == 1f) assertEquals(at + "strip as in the concept", 76.6f, toggleBox.height() / density, 1f);
            assertEquals(at + "toggle and chips share the row", toggleBox.top, chipsBox.top);
            assertEquals(at + "toggle and chips share the row", toggleBox.bottom, chipsBox.bottom);
            assertEquals(at + "8dp between toggle and chips", dp(8), chipsBox.left - toggleBox.right, 1);
            assertEquals(at + "chips card to the right margin", manual.right, chipsBox.right);
            Rect first = at(panel.findViewById(chipIds[0]));
            for (int i = 0; i < 5; i++) {
                ManualChipView chip = panel.findViewById(chipIds[i]);
                Rect box = at(chip);
                assertEquals(at + "chip " + i + " in the row", first.top, box.top);
                assertEquals(at + "chip " + i + " height", first.height(), box.height());
                assertEquals(at + "equal chips", first.width(), box.width(), 1);
                assertTrue(at + "chip " + i + " inside the card", box.left >= chipsBox.left + dp(4) - 1
                        && box.right <= chipsBox.right - dp(4) + 1 && box.bottom <= chipsBox.bottom - dp(4) + 1);
                if (i > 0) assertEquals(at + "4dp gap", dp(4), box.left - at(panel.findViewById(chipIds[i - 1])).right, 1);
                assertTrue(at + "chip " + i + " text cut: " + values[i], chip.textFits());
                assertEquals(at + "one line", 1, chip.getValueView().getLineCount());
                float valuePx = chip.getValueView().getTextSize();
                assertTrue(at + "value " + valuePx / density + "dp over the cap",
                        valuePx <= 14f * UiTokens.FONT_SCALE_CAP * density + .5f);
                assertTrue(at + "state line over the cap",
                        chip.getStateView().getTextSize() <= 9f * UiTokens.FONT_SCALE_CAP * density + .5f);
            }

            // Nothing of the manual block over the handle, the lens strip or the shutter row.
            assertTrue(at + "over the handle" + geometry, manual.bottom <= handleBox.top);
            assertTrue(at + "over the lens strip" + geometry, manual.bottom < stripBox.top);
            assertTrue(at + "over the shutter row" + geometry, manual.bottom < rowBox.top);
            assertTrue(at + "handle over the lens strip" + geometry, handleBox.bottom <= stripBox.top);

            // The other controls at their dp places.
            View gear = binding.layoutTopbar.settingsButton, badges = binding.layoutTopbar.topbarBadges;
            Rect gearBox = at(gear), badgeBox = at(badges);
            assertEquals(at + "gear 44dp", dp(44), gearBox.width());
            assertEquals(at + "gear 44dp", dp(44), gearBox.height());
            assertEquals(at + "gear 12dp from the right", dp(12), w - gearBox.right);
            assertEquals(at + "group card 12dp from the left", dp(12), badgeBox.left);
            assertEquals(at + "group card 44dp", dp(44), badgeBox.height());
            assertTrue(at + "preview under the top bar", at(binding.cameraContainer).top >= topBar.bottom);
            View gallery = row.findViewById(R.id.galery_button_container), shutter = row.findViewById(R.id.shutter_button_container),
                    flip = row.findViewById(R.id.camera_switch_container);
            Rect galleryBox = at(gallery), shutterBox = at(shutter), flipBox = at(flip);
            assertEquals(at + "gallery 52dp", dp(52), galleryBox.width());
            assertEquals(at + "flip 52dp", dp(52), flipBox.width());
            assertEquals(at + "shutter 72dp", dp(72), shutterBox.width());
            assertEquals(at + "gallery 20dp from the left", dp(20), galleryBox.left);
            assertEquals(at + "flip 20dp from the right", dp(20), w - flipBox.right);
            assertEquals(at + "shutter centred", w / 2f, shutterBox.exactCenterX(), 1f);
            assertEquals(at + "gallery, shutter, flip on one line", shutterBox.centerY(), galleryBox.centerY(), 1);
            assertEquals(at + "gallery, shutter, flip on one line", shutterBox.centerY(), flipBox.centerY(), 1);
            assertTrue(at + "shutter row 18dp or more over the bottom" + geometry, rowBox.bottom <= h - dp(18));
            assertEquals(at + "lens strip centred", w / 2f, stripBox.exactCenterX(), 1f);
            assertTrue(at + "lens strip inside the screen" + geometry, stripBox.left >= 0 && stripBox.right <= w);
            assertEquals(at + "lens strip 14dp over the shutter row" + geometry, dp(14), rowBox.top - stripBox.bottom, 1);
            for (int i = 0; i < strip.getChildCount(); i++) {
                View lens = strip.getChildAt(i);
                if (!(lens instanceof TextView)) continue;
                assertEquals(at + "lens pill 40dp", dp(40), lens.getHeight());
                TextView label = (TextView) lens;
                assertTrue(at + "lens label over the cap", label.getTextSize() <= 14f * UiTokens.FONT_SCALE_CAP * density + .5f);
                assertTrue(at + "lens label cut", label.getLayout() == null || label.getLayout().getLineCount() == 1);
            }

            System.out.println(String.format(Locale.US, "%sstrip %.1fdp, manual bottom %.1fdp above the handle, %.1fdp above "
                            + "the bar's bottom; top bar %.1fdp; bar %.1fdp", at, toggleBox.height() / density,
                    (handleBox.top - manual.bottom) / density, (handleBox.bottom - manual.bottom) / density,
                    topBar.height() / density, barBox.height() / density));
            render(root, w, h, "scale-" + name + "-" + fontScale + ".png");
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

    private static void render(View view, int w, int h, String name) throws Exception {
        Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF101416);
        view.draw(new Canvas(image));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
