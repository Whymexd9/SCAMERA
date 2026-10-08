package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.console.ManualModeConsoleImpl;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel;
import com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel;
import com.particlesdevs.photoncamera.circularbarlib.ui.ManualPanelState;
import com.particlesdevs.photoncamera.circularbarlib.ui.ManualSummaryView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.ManualChipView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The card-style manual panel on a real console and models (MANUAL_TASK.md §1, §2, §5): five chips with the right
 * states, long press back to auto, the EV lock, «Всё на авто», the toggle's dot, the summary, no overflowing chip text at
 * 360dp, and the panel hiding and returning with the shade in the same state.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ManualPanelUiTest {
    private ActivityController<Activity> controller;
    private ManualHarness h;
    private ManualModeConsoleImpl console;
    private int width;
    private final int[] chipIds = {R.id.iso_option_tv, R.id.exposure_option_tv, R.id.ev_option_tv, R.id.focus_option_tv, R.id.wb_option_tv};

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class);
        controller.setup();
        float density = controller.get().getResources().getDisplayMetrics().density;
        width = Math.round((360 - 2 * 12) * density); // a 360dp phone, 12dp side margins
        h = new ManualHarness(controller.get(), width);
        h.init();
        console = (ManualModeConsoleImpl) h.console;
        h.layout(width);
    }

    @After
    public void tearDown() {
        controller.close();
    }

    private ManualChipView chip(int param) {
        return h.find(chipIds[param]);
    }

    private LinearScaleView ruler() {
        return h.find(R.id.linearScaleView);
    }

    private ManualParamModel params() {
        return h.console.getManualParamModel();
    }

    /** Opens the parameter's ruler and steps it {@code steps} stops right (a key press each), as a user would. */
    private void set(int param, int steps) {
        if (console.selectedParam() != param) chip(param).performClick();
        assertEquals(param, console.selectedParam());
        for (int i = 0; i < steps; i++)
            ruler().onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
        h.layout(width);
    }

    private String last() {
        return h.messages.get(h.messages.size() - 1).toString();
    }

    @Test
    public void stripRendersFiveChipsWithTheirStates() throws Exception {
        ((ExpandingManualPanel) h.panel).setExpanded(true, false);
        View chips = h.find(R.id.buttons_container);
        assertEquals(5, ((android.view.ViewGroup) chips).getChildCount());
        String[] names = {"ISO", "Выдержка", "Экспокоррекция", "Фокус", "Баланс белого"};
        for (int p = 0; p < 5; p++) {
            assertEquals(ManualChipView.AUTO, chip(p).getChipState());
            assertEquals("авто", chip(p).getStateView().getText().toString());
            assertTrue(chip(p).getContentDescription().toString().startsWith(names[p] + ": авто"));
            assertNotNull(chip(p).getIcon().getDrawable());
            // icons only: no name on the chip itself
            assertFalse(chip(p).getValueView().getText().toString().contains(names[p]));
        }
        // in auto the chips show the camera's metered values, in MUTED
        h.console.setMeteredValues(640, 1_000_000_000L / 125, 0.5f, 5200);
        h.layout(width);
        assertEquals("640", chip(ManualPanelState.ISO).getValueView().getText().toString());
        assertEquals("1/125", chip(ManualPanelState.SHUTTER).getValueView().getText().toString());
        assertEquals("0", chip(ManualPanelState.EV).getValueView().getText().toString());
        assertEquals("2 м", chip(ManualPanelState.FOCUS).getValueView().getText().toString());
        assertEquals("5200K", chip(ManualPanelState.WB).getValueView().getText().toString());
        assertEquals(0xFFB2BAC9, chip(ManualPanelState.ISO).getValueView().getCurrentTextColor());
        assertEquals("ISO: авто, сейчас 640", chip(ManualPanelState.ISO).getContentDescription().toString());
        ExpandingManualPanel panel = (ExpandingManualPanel) h.panel;
        assertFalse(panel.isManualDotShown());
        // a tap selects the chip and opens its ruler; the header pill shows the metered value; the first key press
        // starts from it
        chip(ManualPanelState.ISO).performClick();
        assertTrue(chip(ManualPanelState.ISO).isChosen());
        assertEquals(View.VISIBLE, h.find(R.id.knobViewContainer).getVisibility());
        assertEquals("640", ((TextView) h.find(R.id.manual_ruler_value)).getText().toString());
        assertEquals(View.GONE, h.find(R.id.manual_all_auto).getVisibility());
        set(ManualPanelState.ISO, 1);
        assertEquals(ManualChipView.MANUAL, chip(ManualPanelState.ISO).getChipState());
        assertEquals("800", chip(ManualPanelState.ISO).getValueView().getText().toString());
        assertEquals("ручн.", chip(ManualPanelState.ISO).getStateView().getText().toString());
        assertEquals(800, params().getCurrentISOValue(), 0);
        assertTrue(panel.isManualDotShown());
        assertEquals(View.VISIBLE, h.find(R.id.manual_all_auto).getVisibility());
        assertEquals("800", ((TextView) h.find(R.id.manual_ruler_value)).getText().toString());
        h.layout(width);
        render("manual-panel-ruler-360.png");
        // a second tap on the selected chip closes the ruler
        chip(ManualPanelState.ISO).performClick();
        assertEquals(View.GONE, h.find(R.id.knobViewContainer).getVisibility());
        assertFalse(chip(ManualPanelState.ISO).isChosen());
        // the summary shows the applied manual value
        ManualSummaryView summary = h.find(R.id.manual_summary);
        assertEquals(View.VISIBLE, summary.getVisibility());
        assertEquals(1, summary.itemCount());
        for (int p = 0; p < 5; p++) assertTrue(chip(p).textFits());
        render("manual-panel-360.png");
    }

    @Test
    public void longPressReturnsTheParameterToAuto() {
        set(ManualPanelState.WB, 3);
        assertEquals(ManualChipView.MANUAL, chip(ManualPanelState.WB).getChipState());
        assertTrue(params().getWhiteBalanceKelvin() > 0);
        assertTrue(chip(ManualPanelState.WB).performLongClick());
        assertEquals(ManualChipView.AUTO, chip(ManualPanelState.WB).getChipState());
        assertEquals(0, params().getWhiteBalanceKelvin());
        assertEquals("Баланс белого: авто", last());
        ManualModel<?> wb = console.models()[ManualPanelState.WB];
        assertEquals(0, wb.getCurrentInfo().value, 0);
        // the ruler (still open) follows to auto
        assertTrue(ruler().isAuto());
    }

    @Test
    public void evLocksWithIsoAndShutterManualAndComesBack() {
        set(ManualPanelState.EV, 2);
        double storedEv = params().getCurrentEvValue();
        assertNotEquals(0, storedEv, 0);
        assertEquals(ManualChipView.MANUAL, chip(ManualPanelState.EV).getChipState());
        set(ManualPanelState.ISO, 1);
        set(ManualPanelState.SHUTTER, 1);
        // locked: dimmed, «—», «выкл.», disabled for accessibility, the stored value kept
        ManualChipView ev = chip(ManualPanelState.EV);
        assertEquals(ManualChipView.LOCKED, ev.getChipState());
        assertEquals(.4f, ev.getAlpha(), 1e-6f);
        assertEquals("—", ev.getValueView().getText().toString());
        assertEquals("выкл.", ev.getStateView().getText().toString());
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        ev.onInitializeAccessibilityNodeInfo(info);
        assertFalse(info.isEnabled());
        assertEquals("Экспокоррекция: недоступна, ISO и выдержка заданы вручную", ev.getContentDescription().toString());
        assertEquals(storedEv, params().getCurrentEvValue(), 0);
        // a tap or a long press explains, opens nothing and resets nothing
        ev.performClick();
        assertEquals("Экспокоррекция недоступна: ISO и выдержка заданы вручную", last());
        assertNotEquals(ManualPanelState.EV, console.selectedParam());
        ev.performLongClick();
        assertEquals("Экспокоррекция недоступна: ISO и выдержка заданы вручную", last());
        assertEquals(storedEv, params().getCurrentEvValue(), 0);
        // left out of the summary
        ManualSummaryView summary = h.find(R.id.manual_summary);
        assertEquals(2, summary.itemCount());
        // ISO back to auto: EV is applied again and shows its value
        chip(ManualPanelState.ISO).performLongClick();
        assertEquals(ManualChipView.MANUAL, ev.getChipState());
        assertEquals(1f, ev.getAlpha(), 1e-6f);
        assertNotEquals("—", ev.getValueView().getText().toString());
        assertEquals(2, summary.itemCount()); // shutter and EV
    }

    @Test
    public void theOpenEvRulerClosesWhenTheLockSetsIn() {
        set(ManualPanelState.ISO, 1);
        set(ManualPanelState.EV, 2);
        assertEquals(ManualPanelState.EV, console.selectedParam());
        // the shutter turns manual while the EV ruler is open (as the stored value of another module would)
        ManualModel<?> shutter = console.models()[ManualPanelState.SHUTTER];
        shutter.onSelectedKnobItemChanged(null, null, shutter.getKnobInfoList().get(3));
        assertEquals(-1, console.selectedParam());
        assertEquals(View.GONE, h.find(R.id.knobViewContainer).getVisibility());
        assertEquals(ManualChipView.LOCKED, chip(ManualPanelState.EV).getChipState());
    }

    @Test
    public void allAutoResetsEveryParameter() {
        set(ManualPanelState.ISO, 2);
        set(ManualPanelState.FOCUS, 4);
        set(ManualPanelState.WB, 5);
        set(ManualPanelState.EV, 2);
        View allAuto = h.find(R.id.manual_all_auto);
        assertEquals(View.VISIBLE, allAuto.getVisibility());
        allAuto.performClick();
        for (int p = 0; p < 5; p++) {
            assertEquals(ManualChipView.AUTO, chip(p).getChipState());
            assertFalse(com.particlesdevs.photoncamera.circularbarlib.ui.ViewObserver.isManual(console.models()[p], p));
        }
        assertFalse(params().isManualMode());
        assertEquals("Все параметры на авто", last());
        assertEquals(View.GONE, allAuto.getVisibility());
        assertFalse(((ExpandingManualPanel) h.panel).isManualDotShown());
        assertEquals(View.GONE, h.<View>find(R.id.manual_summary).getVisibility());
    }

    @Test
    public void noChipTextOverflowsAt360dp() {
        String[][] values = {{"12800", "102400", "Авто"}, {"1/8000", "1/16000", "1,3 с", "30 с"},
                {"+1 1/3", "−2/3", "−1 2/3", "0"}, {"∞", "1,2 м", "30 см", "15 м"}, {"5200K", "10000K", "Авто"}};
        for (int p = 0; p < 5; p++)
            for (String v : values[p]) {
                chip(p).bind(v, ManualChipView.MANUAL);
                h.layout(width);
                assertTrue(v + " cut", chip(p).textFits());
                assertEquals(v, chip(p).getValueView().getText().toString());
            }
        // the spec's values keep at least 13sp
        String[] spec = {"1/8000", "5200K", "1,2 м", "+1 1/3"};
        int[] params = {ManualPanelState.SHUTTER, ManualPanelState.WB, ManualPanelState.FOCUS, ManualPanelState.EV};
        for (int i = 0; i < spec.length; i++) {
            chip(params[i]).bind(spec[i], ManualChipView.MANUAL);
            h.layout(width);
            assertTrue(spec[i] + " at " + chip(params[i]).valueSp() + "sp", chip(params[i]).valueSp() >= 13f);
        }
        for (int p = 0; p < 5; p++) {
            chip(p).bind("x", ManualChipView.LOCKED);
            h.layout(width);
            assertTrue(chip(p).textFits()); // «ВЫКЛ.»
        }
    }

    @Test
    public void thePanelHidesAndReturnsWithTheShadeInTheSameState() {
        ExpandingManualPanel panel = (ExpandingManualPanel) h.panel;
        panel.setExpanded(true, false);
        set(ManualPanelState.SHUTTER, 2);
        // the shade leaves HIDDEN: a short fade and slide, then INVISIBLE
        panel.setShadeShown(false, true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(View.INVISIBLE, panel.getVisibility());
        assertEquals(0f, panel.getAlpha(), 1e-6f);
        assertTrue(panel.getTranslationY() > 0);
        // back to HIDDEN: the same state (expanded, the shutter ruler open)
        panel.setShadeShown(true, true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(View.VISIBLE, panel.getVisibility());
        assertEquals(1f, panel.getAlpha(), 1e-6f);
        assertEquals(0f, panel.getTranslationY(), 1e-6f);
        assertTrue(panel.isExpanded());
        assertEquals(ManualPanelState.SHUTTER, console.selectedParam());
        assertEquals(View.VISIBLE, h.find(R.id.knobViewContainer).getVisibility());
        // collapsing closes the ruler and forgets the selection
        panel.setExpanded(false, false);
        assertEquals(-1, console.selectedParam());
        assertEquals(View.GONE, h.find(R.id.knobViewContainer).getVisibility());
        assertTrue(panel.isManualDotShown());
    }

    private void render(String name) throws java.io.IOException {
        View root = (View) h.panel.getParent();
        Bitmap image = Bitmap.createBitmap(root.getWidth(), Math.max(1, h.panel.getHeight()), Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF45525B);
        h.panel.draw(new Canvas(image));
        java.io.File dir = new java.io.File("build/reports/viewfinder");
        dir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
