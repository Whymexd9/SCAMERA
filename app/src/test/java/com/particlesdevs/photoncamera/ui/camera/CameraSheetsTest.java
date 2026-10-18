package com.particlesdevs.photoncamera.ui.camera;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.settings.FormatChoice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * SIMPLE_UI §2: «Формат файла» is two choices over the same 9 values - (X, Нет) -> X, (X, + RAW) -> RAW + X, (any, Только RAW)
 * -> RAW, «Только RAW» keeps the photo choice and disables the photo control, the summary says what is saved; «Размер фото»
 * dims a size the module lacks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CameraSheetsTest {
    @Test
    public void theTwoChoicesMapOntoTheNineValues() {
        assertEquals(FormatChoice.HEIC, CameraSheets.choice(PhotoFormat.HEIC, CameraSheets.RAW_NONE));
        assertEquals(FormatChoice.RAW_HEIC, CameraSheets.choice(PhotoFormat.HEIC, CameraSheets.RAW_PAIRED));
        assertEquals(FormatChoice.RAW, CameraSheets.choice(PhotoFormat.AVIF, CameraSheets.RAW_ONLY));
        assertEquals(FormatChoice.RAW_JPEG, CameraSheets.choice(PhotoFormat.JPEG, CameraSheets.RAW_PAIRED));
        assertEquals("RAW (DNG) + HEIC", CameraSheets.summary(PhotoFormat.HEIC, CameraSheets.RAW_PAIRED));
        assertEquals("RAW (DNG)", CameraSheets.summary(PhotoFormat.HEIC, CameraSheets.RAW_ONLY));
        assertEquals("WebP", CameraSheets.summary(PhotoFormat.WEBP, CameraSheets.RAW_NONE));
        // «Только RAW» keeps the photo codec: «+ RAW» restores it
        CameraSheets.FormatState state = new CameraSheets.FormatState(FormatChoice.RAW, PhotoFormat.HEIC);
        assertEquals(PhotoFormat.HEIC, state.photo);
        assertEquals(CameraSheets.RAW_ONLY, state.raw);
    }

    @Test
    public void theFormatSheetStoresEveryTapAndStaysOpen() throws Exception {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        List<FormatChoice> stored = new ArrayList<>();
        List<FormatChoice> offered = Arrays.asList(FormatChoice.values());
        BottomSheetDialog d = CameraSheets.format(activity, offered, FormatChoice.JPEG, PhotoFormat.JPEG, stored::add);
        View content = d.findViewById(android.R.id.content).findViewWithTag("format_sheet");
        assertNotNull(content);
        ViewGroup photo = (ViewGroup) ((ViewGroup) content).getChildAt(1), raw = (ViewGroup) ((ViewGroup) content).getChildAt(4);
        assertEquals(4, photo.getChildCount());
        assertEquals(3, raw.getChildCount());
        photo.getChildAt(1).performClick();   // HEIC
        content = d.findViewById(android.R.id.content).findViewWithTag("format_sheet");
        raw = (ViewGroup) ((ViewGroup) content).getChildAt(4);
        raw.getChildAt(1).performClick();     // + RAW
        content = d.findViewById(android.R.id.content).findViewWithTag("format_sheet");
        assertEquals(Arrays.asList(FormatChoice.HEIC, FormatChoice.RAW_HEIC), stored);
        assertTrue(((TextView) content.findViewWithTag("format_summary")).getText().toString().endsWith("RAW (DNG) + HEIC"));
        raw = (ViewGroup) ((ViewGroup) content).getChildAt(4);
        raw.getChildAt(2).performClick();     // Только RAW
        content = d.findViewById(android.R.id.content).findViewWithTag("format_sheet");
        photo = (ViewGroup) ((ViewGroup) content).getChildAt(1);
        assertFalse("the photo control is disabled", photo.getChildAt(0).isEnabled());
        assertEquals("Не сохраняется: выбрано «Только RAW»", ((TextView) content.findViewWithTag("format_photo_line")).getText().toString());
        assertEquals(FormatChoice.RAW, stored.get(stored.size() - 1));
        assertTrue("selecting does not close it", d.isShowing());
        render(content.getRootView(), "format-sheet.png");
    }

    @Test
    public void theSizeSheetDimsWhatTheModuleLacks() throws Exception {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        List<Integer> picked = new ArrayList<>();
        List<CharSequence> refused = new ArrayList<>();
        BottomSheetDialog d = CameraSheets.size(activity, PhotoSize.NORMAL, true, false, picked::add, refused::add);
        View sheet = d.findViewById(android.R.id.content);
        View max = sheet.findViewWithTag("size_" + PhotoSize.MAX);
        assertEquals(.45f, max.getAlpha(), 1e-4f);
        max.performClick();
        assertTrue(picked.isEmpty());
        assertEquals("200 Мп — нет на этом объективе", refused.get(0).toString());
        sheet.findViewWithTag("size_" + PhotoSize.FULL).performClick();
        assertEquals(Arrays.asList(PhotoSize.FULL), picked);
        render(sheet.getRootView(), "size-sheet.png");
    }

    private static void render(View root, String name) throws Exception {
        int w = root.getWidth() > 0 ? root.getWidth() : 1080, h = root.getHeight() > 0 ? root.getHeight() : 2400;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF45525B);
        root.draw(new Canvas(image));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
