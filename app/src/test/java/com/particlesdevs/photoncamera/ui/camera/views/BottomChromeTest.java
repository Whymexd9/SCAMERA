package com.particlesdevs.photoncamera.ui.camera.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.particlesdevs.photoncamera.R;

import org.junit.Test;
import org.junit.runner.RunWith;
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

/**
 * P32: the bottom controls sit 10 % of the bottom panel higher, and the shade's HIDDEN handle starts at the viewfinder's
 * bottom edge, neither touching the other. The OPPO Find X7 Ultra screen: 1440 x 3168 px, 4 px per dp, a 272 px top bar
 * and a 1440 x 1920 (3:4) viewfinder, so the panel under it is 976 px tall.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w360dp-h792dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BottomChromeTest {
    private static final int WIDTH = 1440, HEIGHT = 3168, TOP_BAR = 272, VIEWFINDER = 1920;

    @Test
    public void theRaiseIsATenthOfThePanelWhileTheHandleKeepsItsRoom() {
        // OPPO: 976 px panel, 152dp of controls (18 + 72 + 14 + 48), the 28dp handle, the 8dp clearance.
        assertEquals(98, BottomChrome.raise(976, 608, 112, 32));
        // A taller panel: still a tenth.
        assertEquals(120, BottomChrome.raise(1200, 608, 112, 32));
        // Little room above the controls: the strip stops 8dp under the handle.
        assertEquals(48, BottomChrome.raise(800, 608, 112, 32));
        // No room at all (a 16:9 preview with the bar over it): no raise.
        assertEquals(0, BottomChrome.raise(656, 608, 112, 32));
        assertEquals(0, BottomChrome.raise(0, 608, 112, 32));
    }

    @Test
    public void theHandleStartsAtTheViewfinderBottomButNeverReachesTheStrip() {
        // The 3:4 viewfinder ends at the panel's top: the handle starts there.
        assertEquals(0, BottomChrome.handleTop(0, 270, 112, 32));
        // A viewfinder ending above the panel (a shorter preview): the panel's top.
        assertEquals(0, BottomChrome.handleTop(-40, 270, 112, 32));
        // A viewfinder 2 px into the panel (rounding of the preview size): under it.
        assertEquals(2, BottomChrome.handleTop(2, 270, 112, 32));
        // A 16:9 preview running on under the controls: just above the strip.
        assertEquals(126, BottomChrome.handleTop(640, 270, 112, 32));
        // A panel without room for the handle: it moves up over the preview rather than onto the strip.
        assertEquals(-96, BottomChrome.handleTop(640, 48, 112, 32));
    }

    @Test
    public void controlsRiseTogetherAndTheHandleUncoversTheViewfinder() throws Exception {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            // camera_fragment.xml in short: the viewfinder from the top bar down, the bottom bar under the 3:4 block
            // (dummy_reference_view), and the handle's slot laid out at the bar's top.
            FrameLayout screen = new FrameLayout(context);
            this.screen = screen;
            View viewfinder = new View(context);
            viewfinder.setBackgroundColor(0xFF45525B);
            FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(WIDTH, VIEWFINDER);
            vp.topMargin = TOP_BAR;
            screen.addView(viewfinder, vp);
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(new View(context), new LinearLayout.LayoutParams(WIDTH, TOP_BAR + VIEWFINDER));
            View bar = LayoutInflater.from(context).inflate(R.layout.layout_main_bottombar, column, false);
            column.addView(bar, new LinearLayout.LayoutParams(WIDTH, 0, 1f));
            screen.addView(column, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
            FrameLayout slot = new FrameLayout(context);
            View handle = new View(context);
            handle.setBackground(com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle.sheet(context));
            slot.addView(handle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(WIDTH, dp(context, 28));
            sp.topMargin = TOP_BAR + VIEWFINDER;
            screen.addView(slot, sp);
            FrameLayout root = new FrameLayout(context);
            root.addView(screen, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
            controller.get().setContentView(root);

            AuxButtonsLayout strip = bar.findViewById(R.id.aux_buttons_container);
            for (String label : new String[]{"0,6×", "1× (2)", "1×", "2,8×", "5,9×"}) {
                LensButton lens = new LensButton(context);
                lens.setText(label);
                strip.addView(lens, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40)));
            }
            View row = bar.findViewById(R.id.bottom_buttons), ruler = bar.findViewById(R.id.zoom_ruler_slot),
                    shutter = bar.findViewById(R.id.shutter_button_container);
            settle();
            assertEquals(976, bar.getHeight());
            int barTop = y(bar), stripTop = y(strip), rowTop = y(row), rulerTop = y(ruler), shutterTop = y(shutter);
            assertEquals(TOP_BAR + VIEWFINDER, barTop);

            BottomChrome.attach(bar, row, strip, slot, viewfinder);
            bar.requestLayout();
            settle();
            // A tenth of the panel (97.6 px), the same for all of them: the spacing between them stays.
            int raise = Math.round(976 * BottomChrome.RAISE_FRACTION);
            assertEquals(98, raise);
            assertEquals(rowTop - raise, y(row));
            assertEquals(shutterTop - raise, y(shutter));
            assertEquals(stripTop - raise, y(strip));
            assertEquals(rulerTop - raise, y(ruler));
            assertEquals(barTop, y(bar));
            // The handle starts at the viewfinder's bottom edge; the strip stays clear of it; the ruler's place stays
            // under the viewfinder.
            assertEquals(TOP_BAR + VIEWFINDER, y(slot));
            assertTrue(y(slot) + slot.getHeight() + dp(context, BottomChrome.CLEARANCE_DP) <= y(strip));
            assertTrue(y(ruler) >= TOP_BAR + VIEWFINDER);
            render(screen, "bottom-chrome-3x4.png");

            // A preview 40 px taller (its size rounded down the other way): the handle moves under it.
            viewfinder.getLayoutParams().height = VIEWFINDER + 40;
            viewfinder.requestLayout();
            settle();
            assertEquals(TOP_BAR + VIEWFINDER + 40, y(slot));
            // A 9:16 preview under the controls: the handle stops 8dp above the strip.
            viewfinder.getLayoutParams().height = WIDTH * 16 / 9;
            viewfinder.requestLayout();
            settle();
            assertEquals(y(strip) - dp(context, BottomChrome.CLEARANCE_DP), y(slot) + slot.getHeight());
            // A shorter preview: the handle stays at the bar's top.
            viewfinder.getLayoutParams().height = VIEWFINDER - 200;
            viewfinder.requestLayout();
            settle();
            assertEquals(TOP_BAR + VIEWFINDER, y(slot));
            // The controls never moved with the handle.
            assertEquals(stripTop - raise, y(strip));
            assertEquals(rowTop - raise, y(row));
        }
    }

    private static int dp(Context context, float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private View screen;

    /** Top of a view on the test screen (whatever the test window's decor adds above it). */
    private int y(View view) {
        int[] at = new int[2], base = new int[2];
        view.getLocationInWindow(at);
        screen.getLocationInWindow(base);
        return at[1] - base[1];
    }

    private static void settle() {
        for (int i = 0; i < 4; i++) Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
    }

    private static void render(View view, String name) throws Exception {
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF101416);
        view.draw(new Canvas(image));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
