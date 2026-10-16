package com.particlesdevs.photoncamera.ui.camera;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.databinding.CameraFragmentBinding;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;
import com.particlesdevs.photoncamera.ui.camera.model.AuxButtonsModel;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;
import com.particlesdevs.photoncamera.ui.camera.views.AuxButtonsLayout;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.SettingsBarLayout;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeHost;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;

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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P173 / P176: the real camera screen with a 16:9 preview running on under the controls - the shade's HIDDEN handle is a pill
 * in the middle above the lens strip (not the full-width edge across the preview), and the shade grows out of it. Renders
 * the screen hidden, the opening's frames and the open shade to build/reports/viewfinder/shade-169.png (a picture put at
 * build/reports/viewfinder/preview.jpg is drawn as the preview, if there is one).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Shade169Test {
    private MockedStatic<ScameraApp> camera;
    private SharedPreferences prefs;
    private SettingsManager manager;

    @Before
    public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        ShadeCatalog.setFlashAvailable(true);
    }

    @After
    public void tearDown() {
        camera.close();
    }

    @Test
    public void theShadeGrowsOutOfACentredPillAt169() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            Activity activity = controller.get();
            Context context = new ContextThemeWrapper(activity, R.style.Theme_Scamera_SettingsActivity);
            int w = context.getResources().getDisplayMetrics().widthPixels, h = context.getResources().getDisplayMetrics().heightPixels;
            CameraFragmentBinding binding = CameraFragmentBinding.inflate(LayoutInflater.from(context));
            CameraFragmentModel model = new CameraFragmentModel();
            model.setScreenAspectRatio((float) h / w);
            model.setDummyAspectRatio("3:4");
            model.setSheetLevel(CameraFragmentModel.SHEET_HIDDEN);
            binding.setUimodel(model);
            binding.layoutTopbar.setUimodel(model);
            AuxButtonsModel lenses = new AuxButtonsModel();
            List<CameraLensData> back = new ArrayList<>();
            float[] zoom = {.6f, 1f, 2.8f, 5.9f};
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
            // 16:9: the preview runs on under the bottom bar
            binding.layoutViewfinder.texture.setAspectRatio(w, w * 16 / 9);
            var row = binding.layoutBottombar.bottomButtons;
            int accent = ShadeStyle.accent(context);
            row.shutterButton.setBackground(CameraUIViewImpl.shutter(context, accent));
            SettingsBarLayout sheet = binding.settingsBar;
            sheet.setCatalog(new ShadeCatalog(context, prefs));
            sheet.attach(new ShadeHost() {
                @Override public void applyCameraControl(SettingType type, int value) { }
                @Override public void onSettingWritten(ShadeCatalog.Entry entry) { }
                @Override public void showMessage(CharSequence text) { }
                @Override public void openCatalog() { }
                @Override public void openSettings() { }
                @Override public void onValuesChanged() { }
            });
            sheet.setHiddenHandle(binding.settingsSheetHandle, null);
            FrameLayout root = new FrameLayout(context);
            root.addView(binding.getRoot(), new FrameLayout.LayoutParams(w, h));
            activity.setContentView(root);
            CameraFragment.attachBottomChrome(binding, null);
            sheet.prewarm();
            sheet.setSheetLevel(SettingsBarLayout.LEVEL_HIDDEN);
            layout(root, w, h);
            // the handle floats over the preview: a pill, in the middle, above the strip
            assertTrue("a pill at 16:9", sheet.isPillHandle());
            View pill = binding.settingsSheetHandle;
            Rect pillBox = box(pill, root), stripBox = box(strip, root), preview = box(binding.layoutViewfinder.texture, root);
            assertEquals(View.VISIBLE, pill.getVisibility());
            assertEquals("in the middle", w / 2f, pillBox.exactCenterX(), 2f);
            assertTrue("narrow", pillBox.width() < w / 3);
            assertTrue("above the strip", pillBox.bottom <= stripBox.top);
            assertTrue("over the preview", pillBox.top > preview.top && pillBox.bottom < preview.bottom);
            Bitmap photo = photo(preview);
            // the preview shows through: nothing above the TextureView paints its area
            for (View v = binding.layoutViewfinder.texture; v != null && v != root; v = v.getParent() instanceof View ? (View) v.getParent() : null)
                v.setBackground(null);
            Bitmap hidden = draw(root, w, h, photo, preview);
            // the frames' ground: the pill goes as the sheet starts out of it
            pill.setVisibility(View.GONE);
            Bitmap ground = draw(root, w, h, photo, preview);
            pill.setVisibility(View.VISIBLE);
            // open: the sheet at PEEK (Robolectric runs the zoom to its end at once; the frames are drawn below)
            sheet.setSheetLevel(SettingsBarLayout.LEVEL_PEEK);
            layout(root, w, h);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            layout(root, w, h);
            assertEquals(BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.from(sheet).getState());
            assertEquals(View.GONE, pill.getVisibility());
            Bitmap open = draw(root, w, h, photo, preview);
            // the sheet alone, its visible part, for the frames
            View container = (View) sheet.getParent();
            Rect sheetBox = box(sheet, root), containerBox = box(container, root);
            Bitmap shade = Bitmap.createBitmap(sheet.getWidth(), Math.max(1, containerBox.bottom - sheetBox.top), Bitmap.Config.ARGB_8888);
            sheet.draw(new Canvas(shade));
            RectF pillF = new RectF(pillBox), visible = new RectF(sheetBox.left, sheetBox.top, sheetBox.right, containerBox.bottom);
            long[] at = {0, 50, 100, 170, 260, 420};
            Bitmap[] frames = new Bitmap[at.length + 2];
            frames[0] = hidden;
            for (int i = 0; i < at.length; i++) {
                Bitmap frame = ground.copy(Bitmap.Config.ARGB_8888, true);
                Canvas c = new Canvas(frame);
                float t = com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeZoom.at(at[i]);
                float[] f = com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeZoom.frame(pillF, visible, t);
                c.save();
                c.translate(sheetBox.left + f[1], sheetBox.top + f[2]);
                c.scale(f[0], f[0]);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                p.setAlpha(Math.round(f[3] * 255));
                c.drawBitmap(shade, 0, 0, p);
                c.restore();
                frames[1 + i] = frame;
            }
            frames[frames.length - 1] = open;
            int fw = w / 2, fh = h / 2, gap = 12;
            Bitmap sheetImage = Bitmap.createBitmap(frames.length * (fw + gap) - gap, fh + 40, Bitmap.Config.ARGB_8888);
            sheetImage.eraseColor(0xFF000000);
            Canvas c = new Canvas(sheetImage);
            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(0xFFE6E8EA);
            label.setTextSize(22);
            for (int i = 0; i < frames.length; i++) {
                c.drawBitmap(Bitmap.createScaledBitmap(frames[i], fw, fh, true), i * (fw + gap), 40, null);
                String name = i == 0 ? "скрыта" : i == frames.length - 1 ? "открыта" : at[i - 1] + " мс";
                c.drawText(name, i * (fw + gap) + 8, 28, label);
            }
            File dir = new File("build/reports/viewfinder");
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, "shade-169.png"))) {
                sheetImage.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }

    private static void layout(View root, int w, int h) {
        for (int i = 0; i < 4; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, w, h);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        }
    }

    /** The preview: a picture put there by hand (cropped to the preview), else a plain tone. */
    private static Bitmap photo(Rect preview) {
        File file = new File("build/reports/viewfinder/preview.jpg");
        Bitmap b = Bitmap.createBitmap(Math.max(1, preview.width()), Math.max(1, preview.height()), Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF45525B);
        if (!file.isFile()) return b;
        Bitmap src = BitmapFactory.decodeFile(file.getPath());
        float scale = Math.max(b.getWidth() / (float) src.getWidth(), b.getHeight() / (float) src.getHeight());
        Canvas c = new Canvas(b);
        c.translate((b.getWidth() - src.getWidth() * scale) / 2f, (b.getHeight() - src.getHeight() * scale) / 2f);
        c.scale(scale, scale);
        c.drawBitmap(src, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return b;
    }

    /** The screen: the preview picture where the TextureView is (it draws nothing here), the camera screen over it. */
    private static Bitmap draw(View root, int w, int h, Bitmap photo, Rect preview) {
        Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF101416);
        Canvas c = new Canvas(image);
        c.drawBitmap(photo, preview.left, preview.top, null);
        root.draw(c);
        return image;
    }

    private static Rect box(View v, View screen) {
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        screen.getLocationInWindow(b);
        int x = a[0] - b[0], y = a[1] - b[1];
        return new Rect(x, y, x + v.getWidth(), y + v.getHeight());
    }
}
