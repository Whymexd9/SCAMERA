package com.particlesdevs.photoncamera.ui.camera.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.constraintlayout.widget.ConstraintLayout;

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
 * bottom edge, neither touching the other, nor the handle the manual palette or the shown zoom ruler. The OPPO Find X7
 * Ultra screen: 1440 x 3168 px, 4 px per dp, a 272 px top bar and a 1440 x 1920 (3:4) viewfinder, so the panel under it
 * is 976 px tall; with the 16:9 tile (and in video) the bar is 656 px over a 1440 x 2560 preview.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w360dp-h792dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BottomChromeTest {
    private static final int WIDTH = 1440, HEIGHT = 3168, TOP_BAR = 272, VIEWFINDER = 1920, BAR_169 = 656;

    @Test
    public void theRaiseIsATenthOfThePanelWithinTheRoomThereIs() {
        // OPPO 3:4: 976 px panel, 152dp of controls (18 + 72 + 14 + 48), the viewfinder ending at the panel's top, the
        // 28dp handle, the 8dp clearance.
        assertEquals(98, BottomChrome.raise(976, 608, 0, 112, 32));
        // A taller panel: still a tenth.
        assertEquals(120, BottomChrome.raise(1200, 608, 0, 112, 32));
        // Little room above the controls: the strip stops 8dp under the handle...
        assertEquals(48, BottomChrome.raise(800, 608, 0, 112, 32));
        assertEquals(48, BottomChrome.raise(800, 608, -100, 112, 32));
        // ...also under a handle that starts lower, under a viewfinder 40 px into the panel.
        assertEquals(8, BottomChrome.raise(800, 608, 40, 112, 32));
        // No room for the handle under a viewfinder that still ends above the strip: no raise (the handle then covers
        // the least of the viewfinder).
        assertEquals(0, BottomChrome.raise(656, 608, 0, 112, 32));
        assertEquals(0, BottomChrome.raise(800, 608, 100, 112, 32));
        // OPPO 16:9 (the 16:9 tile, video): the 656 px bar over a preview ending 320 px into it. The handle floats over
        // the preview above the strip anyway, so the raise only keeps the strip inside the bar: 48 of the 66 px.
        assertEquals(48, BottomChrome.raise(656, 608, 320, 112, 32));
        // A preview running on under the strip of a taller panel: the full tenth.
        assertEquals(98, BottomChrome.raise(976, 608, 640, 112, 32));
        assertEquals(0, BottomChrome.raise(0, 608, 0, 112, 32));
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
        // OPPO 16:9: the raised strip at the bar's top, the handle over the preview 8dp above it.
        assertEquals(-144, BottomChrome.handleTop(320, 0, 112, 32));
        // A panel without room for the handle: it moves up over the preview rather than onto the strip.
        assertEquals(-96, BottomChrome.handleTop(640, 48, 112, 32));
    }

    @Test
    public void theManualPaletteHangs12dpAboveTheHandle() {
        // P43 (concept): 12dp (48 px) above the handle, which starts at the panel's top under the 3:4 viewfinder.
        assertEquals(48, BottomChrome.manualMargin(0, 48));
        // A handle 40 px down under a taller preview: the palette stays 12dp over the panel's top, not lower.
        assertEquals(48, BottomChrome.manualMargin(40, 48));
        // A handle 28dp over the bar's top (the sheet's edge on the bar): 12dp above it, 40dp over the bar.
        assertEquals(160, BottomChrome.manualMargin(-112, 48));
        // OPPO 16:9: the handle floats 144 px over the bar's top, the palette moves up to stay 12dp above it.
        assertEquals(192, BottomChrome.manualMargin(-144, 48));
    }

    @Test
    public void theShownRulerMeetsTheHandleOnlyWhereItCanBeSeen() {
        // OPPO 3:4: the raised strip's top 270 px into the panel, so the ruler (48dp, 6dp over the strip) spans
        // 54..246 px, over the lower half of the handle (0..112 px).
        assertTrue(BottomChrome.rulerMeetsHandle(54, 246, 0, 112, 32));
        // A taller panel: the ruler stays more than 8dp under the handle.
        assertFalse(BottomChrome.rulerMeetsHandle(256, 448, 0, 112, 32));
        // Exactly 8dp apart: clear.
        assertFalse(BottomChrome.rulerMeetsHandle(144, 336, 0, 112, 32));
        assertTrue(BottomChrome.rulerMeetsHandle(143, 335, 0, 112, 32));
        // OPPO 16:9: the ruler hangs wholly over the bar's top, clipped away; the handle floats there in vain.
        assertFalse(BottomChrome.rulerMeetsHandle(-216, -24, -144, 112, 32));
        // Partly inside the panel, near a handle floating above the strip.
        assertTrue(BottomChrome.rulerMeetsHandle(-100, 92, -44, 112, 32));
    }

    @Test
    public void controlsRiseTogetherAndTheHandleUncoversTheViewfinder() throws Exception {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            // camera_fragment.xml's camera_container in short: the viewfinder from the top bar down, the bottom bar
            // under the 3:4 block (dummy_reference_view), the manual palette 12dp over the bar, the handle's slot laid
            // out at the bar's top.
            ConstraintLayout screen = new ConstraintLayout(context);
            this.screen = screen;
            View viewfinder = new View(context);
            viewfinder.setBackgroundColor(0xFF45525B);
            ConstraintLayout.LayoutParams vp = matchWidth(VIEWFINDER);
            vp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            vp.topMargin = TOP_BAR;
            screen.addView(viewfinder, vp);
            View dummy = new View(context);
            dummy.setId(View.generateViewId());
            ConstraintLayout.LayoutParams rp = matchWidth(TOP_BAR + VIEWFINDER);
            rp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            screen.addView(dummy, rp);
            View bar = LayoutInflater.from(context).inflate(R.layout.layout_main_bottombar, screen, false);
            View manual = new View(context);
            manual.setBackgroundColor(0xFF2A3238);
            ConstraintLayout.LayoutParams mp = matchWidth(dp(context, 48));
            mp.bottomToTop = bar.getId();
            mp.bottomMargin = dp(context, 12);
            mp.leftMargin = mp.rightMargin = dp(context, 20);
            screen.addView(manual, mp);
            FrameLayout slot = new FrameLayout(context);
            View handle = new View(context);
            handle.setBackground(com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle.sheet(context));
            slot.addView(handle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ConstraintLayout.LayoutParams sp = matchWidth(dp(context, 28));
            sp.topToTop = bar.getId();
            screen.addView(slot, sp);
            ConstraintLayout.LayoutParams bp = matchWidth(0);
            bp.topToBottom = dummy.getId();
            bp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            screen.addView(bar, bp);
            FrameLayout root = new FrameLayout(context);
            root.addView(screen, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
            controller.get().setContentView(root);

            AuxButtonsLayout strip = bar.findViewById(R.id.aux_buttons_container);
            for (String label : new String[]{"0,6×", "1× (2)", "1×", "2,8×", "5,9×"}) {
                LensButton lens = new LensButton(context);
                lens.setText(label);
                strip.addView(lens, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40)));
            }
            // The zoom ruler as CameraFragment.initZoomDial puts it in its slot.
            FrameLayout rulerSlot = bar.findViewById(R.id.zoom_ruler_slot);
            ZoomDialView ruler = new ZoomDialView(context);
            rulerSlot.addView(ruler, new FrameLayout.LayoutParams(dp(context, 232), dp(context, 48), Gravity.CENTER));
            View row = bar.findViewById(R.id.bottom_buttons), shutter = bar.findViewById(R.id.shutter_button_container);
            settle();
            assertEquals(976, bar.getHeight());
            int barTop = y(bar), stripTop = y(strip), rowTop = y(row), rulerTop = y(rulerSlot), shutterTop = y(shutter),
                    manualBottom = y(manual) + manual.getHeight();
            assertEquals(TOP_BAR + VIEWFINDER, barTop);
            assertEquals(barTop - dp(context, 12), manualBottom);

            BottomChrome.attach(bar, row, strip, slot, viewfinder, ruler, manual);
            bar.requestLayout();
            settle();
            // A tenth of the panel (97.6 px), the same for all of them: the spacing between them stays.
            int raise = Math.round(976 * BottomChrome.RAISE_FRACTION);
            assertEquals(98, raise);
            assertEquals(rowTop - raise, y(row));
            assertEquals(shutterTop - raise, y(shutter));
            assertEquals(stripTop - raise, y(strip));
            assertEquals(rulerTop - raise, y(rulerSlot));
            assertEquals(barTop, y(bar));
            // The handle starts at the viewfinder's bottom edge; the strip stays clear of it; the ruler's place stays
            // under the viewfinder; the manual palette keeps its place, 12dp above the handle (P43).
            assertEquals(TOP_BAR + VIEWFINDER, y(slot));
            assertTrue(y(slot) + slot.getHeight() + dp(context, BottomChrome.CLEARANCE_DP) <= y(strip));
            assertTrue(y(rulerSlot) >= TOP_BAR + VIEWFINDER);
            assertEquals(manualBottom, y(manual) + manual.getHeight());
            assertEquals(y(slot) - dp(context, BottomChrome.MANUAL_GAP_DP), manualBottom);
            assertEquals(1f, slot.getAlpha(), 0f);
            render(screen, "bottom-chrome-3x4.png");

            // The raised ruler reaches the handle's lower half: while it shows, the handle steps aside (no touches)...
            assertTrue(y(ruler) < y(slot) + slot.getHeight() + dp(context, BottomChrome.CLEARANCE_DP));
            ruler.poke();
            settle();
            assertEquals(View.VISIBLE, ruler.getVisibility());
            assertEquals(0f, slot.getAlpha(), 0f);
            assertFalse(handle.isEnabled());
            render(screen, "bottom-chrome-3x4-ruler.png");
            // ...and comes back once the ruler has faded out.
            idle(2600);
            assertEquals(View.INVISIBLE, ruler.getVisibility());
            assertEquals(1f, slot.getAlpha(), 0f);
            assertTrue(handle.isEnabled());

            // A preview 40 px taller (its size rounded down the other way): the handle moves under it.
            viewfinder.getLayoutParams().height = VIEWFINDER + 40;
            viewfinder.requestLayout();
            settle();
            assertEquals(TOP_BAR + VIEWFINDER + 40, y(slot));
            assertEquals(stripTop - raise, y(strip));
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
            assertEquals(manualBottom, y(manual) + manual.getHeight());

            // 16:9 (the tile, video): the 656 px bar over the lower part of a 9:16 preview. The strip rises to the bar's
            // top (48 px, the room inside the bar), the handle floats 8dp above it, the manual palette 12dp above that.
            dummy.getLayoutParams().height = HEIGHT - BAR_169;
            viewfinder.getLayoutParams().height = WIDTH * 16 / 9;
            dummy.requestLayout();
            viewfinder.requestLayout();
            settle();
            int clearance = dp(context, BottomChrome.CLEARANCE_DP);
            assertEquals(BAR_169, bar.getHeight());
            // About 152dp of controls: the bar leaves them about 48 px, less than the 66 px tenth.
            int controls = dp(context, 18) + row.getHeight() + dp(context, 14) + strip.getHeight();
            assertTrue(Math.abs(608 - controls) <= 2);
            assertTrue(BAR_169 - controls < Math.round(BAR_169 * BottomChrome.RAISE_FRACTION));
            assertEquals(y(bar), y(strip));
            assertEquals(y(bar) + BAR_169 - dp(context, 18) - (BAR_169 - controls), y(row) + row.getHeight());
            assertEquals(y(strip) - clearance, y(slot) + slot.getHeight());
            assertEquals(y(slot) - dp(context, BottomChrome.MANUAL_GAP_DP), y(manual) + manual.getHeight());
            // The ruler has no room inside the bar and is clipped away: the handle stays.
            assertTrue(y(rulerSlot) + rulerSlot.getHeight() <= y(bar));
            ruler.poke();
            settle();
            assertEquals(1f, slot.getAlpha(), 0f);
            assertTrue(handle.isEnabled());
            render(screen, "bottom-chrome-16x9.png");
            idle(2600);

            // Back to 3:4: everything where it was.
            dummy.getLayoutParams().height = TOP_BAR + VIEWFINDER;
            viewfinder.getLayoutParams().height = VIEWFINDER;
            dummy.requestLayout();
            viewfinder.requestLayout();
            settle();
            assertEquals(stripTop - raise, y(strip));
            assertEquals(rowTop - raise, y(row));
            assertEquals(TOP_BAR + VIEWFINDER, y(slot));
            assertEquals(manualBottom, y(manual) + manual.getHeight());
        }
    }

    private static ConstraintLayout.LayoutParams matchWidth(int height) {
        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(0, height);
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        return params;
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
        idle(200);
    }

    private static void idle(int millis) {
        for (int t = 0; t < millis; t += 50) Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
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
