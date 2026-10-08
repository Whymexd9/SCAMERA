package com.particlesdevs.photoncamera.ui.camera.views;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.camera.ManualStops;
import com.particlesdevs.photoncamera.circularbarlib.ui.ManualFormat;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The restyled ruler (MANUAL_TASK.md §3) keeps LinearScaleView's behaviour: a drag commits each crossed stop at once, a
 * release with velocity flings and stops at the ends, then snaps onto the stop; the first touch on a parameter in auto
 * starts from its metered value; the arrow keys step by one stop; a drag never reaches the parent (shade, lens strip).
 * The marker is fixed in the centre in the accent. mdpi: 1dp = 1px, a stop is 16px.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ManualRulerTest {
    private static final int ACCENT = 0xFF90C7FF;
    private LinearScaleView ruler;
    private List<KnobItemInfo> items;
    private KnobItemInfo auto;
    private final List<Double> committed = new ArrayList<>();
    private int autoRequests;

    @Before
    public void setUp() {
        Context c = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        ruler = new LinearScaleView(c);
        ruler.setAccent(ACCENT);
        items = new ArrayList<>();
        auto = new KnobItemInfo(null, "Auto", 0, 0);
        items.add(auto);
        for (long iso : ManualStops.iso(50, 12800)) {
            KnobItemInfo item = new KnobItemInfo(null, "" + iso, items.size(), iso);
            item.majorTick = ManualStops.majorIso(iso);
            items.add(item);
        }
        ruler.setMode(LinearScaleView.MODE_ISO, ManualFormat.EN);
        ruler.setListener(new LinearScaleView.OnValueChangedListener() {
            @Override public void onValueChanged(KnobItemInfo item, boolean fromUser) { committed.add(item.value); }
            @Override public void onDragStateChanged(boolean dragging) {}
            @Override public void onAutoRequested() { autoRequests++; ruler.setSelectedItem(auto); }
        });
        ruler.measure(View.MeasureSpec.makeMeasureSpec(336, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(49, View.MeasureSpec.EXACTLY));
        ruler.layout(0, 0, 336, 49);
    }

    private KnobItemInfo iso(long value) {
        for (KnobItemInfo item : items) if (item.value == value) return item;
        throw new AssertionError(value);
    }

    private static MotionEvent ev(int action, long t, float x) {
        return MotionEvent.obtain(0, t, action, x, 30, 0);
    }

    @Test
    public void dragCommitsEveryCrossedStopInOrder() {
        ruler.setItems(items, iso(800), auto);
        long t = 0;
        ruler.onTouchEvent(ev(MotionEvent.ACTION_DOWN, t, 250));
        // 48px to the left in slow 4px steps: three stops, each committed as it is crossed
        for (int i = 1; i <= 12; i++) ruler.onTouchEvent(ev(MotionEvent.ACTION_MOVE, t += 200, 250 - 4 * i));
        assertEquals(java.util.Arrays.asList(1000.0, 1280.0, 1600.0), committed);
        ruler.onTouchEvent(ev(MotionEvent.ACTION_UP, t += 200, 202)); // no movement in the last 80 ms: no fling
        assertFalse(ruler.isFlinging());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(1600, ruler.getSelected().value, 0);
        assertEquals(0f, ruler.getOffset(), 0.01f);
        // back to the right: two stops down
        committed.clear();
        ruler.onTouchEvent(ev(MotionEvent.ACTION_DOWN, t += 10, 100));
        for (int i = 1; i <= 8; i++) ruler.onTouchEvent(ev(MotionEvent.ACTION_MOVE, t += 200, 100 + 4 * i));
        ruler.onTouchEvent(ev(MotionEvent.ACTION_UP, t += 200, 132));
        assertEquals(java.util.Arrays.asList(1280.0, 1000.0), committed);
    }

    @Test
    public void flingDecaysStopsAtTheEndThenSnaps() {
        ruler.setItems(items, iso(800), auto);
        long t = 0;
        ruler.onTouchEvent(ev(MotionEvent.ACTION_DOWN, t, 320));
        for (int i = 1; i <= 6; i++) ruler.onTouchEvent(ev(MotionEvent.ACTION_MOVE, t += 8, 320 - 20 * i));
        ruler.onTouchEvent(ev(MotionEvent.ACTION_UP, t += 8, 200));
        assertTrue("a fast release flings", ruler.isFlinging());
        int before = committed.size();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertFalse(ruler.isFlinging());
        assertTrue("the fling moved on", committed.size() > before);
        assertEquals("a long fling stops at the end", 12800, ruler.getSelected().value, 0);
        assertEquals("then snaps onto the stop", 0f, ruler.getOffset(), 0.01f);
        // every stop on the way was committed once, in order
        for (int i = 1; i < committed.size(); i++) assertTrue(committed.get(i) > committed.get(i - 1));
    }

    @Test
    public void shortFlingSnapsOntoAStopInBetween() {
        ruler.setItems(items, iso(400), auto);
        long t = 0;
        ruler.onTouchEvent(ev(MotionEvent.ACTION_DOWN, t, 200));
        for (int i = 1; i <= 3; i++) ruler.onTouchEvent(ev(MotionEvent.ACTION_MOVE, t += 16, 200 - 6 * i));
        ruler.onTouchEvent(ev(MotionEvent.ACTION_UP, t += 16, 182));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertFalse(ruler.isFlinging());
        assertEquals(0f, ruler.getOffset(), 0.01f);
        double v = ruler.getSelected().value;
        assertTrue(v > 400 && v < 12800);
        assertEquals(v, committed.get(committed.size() - 1), 0);
    }

    @Test
    public void firstTouchInAutoStartsFromTheMeteredValue() {
        ruler.setItems(items, auto, auto);
        ruler.setMeteredValue(612); // metered ISO 612: the nearest stop is 640
        assertTrue(ruler.isAuto());
        assertEquals(640, ruler.getMarkerItem().value, 0);
        long t = 0;
        ruler.onTouchEvent(ev(MotionEvent.ACTION_DOWN, t, 200));
        for (int i = 1; i <= 4; i++) ruler.onTouchEvent(ev(MotionEvent.ACTION_MOVE, t += 200, 200 - 4 * i));
        ruler.onTouchEvent(ev(MotionEvent.ACTION_UP, t += 200, 184));
        assertEquals("one stop past the metered 640", java.util.Collections.singletonList(800.0), committed);
        assertFalse(ruler.isAuto());
        // «Авто» returns it to auto; the scale stands at the metered value again
        float autoX = ruler.autoWidth() / 2;
        ruler.onTouchEvent(ev(MotionEvent.ACTION_DOWN, t += 10, autoX));
        ruler.onTouchEvent(ev(MotionEvent.ACTION_UP, t += 10, autoX));
        assertEquals(1, autoRequests);
        assertTrue(ruler.isAuto());
        ruler.setMeteredValue(3100);
        assertEquals(3200, ruler.getMarkerItem().value, 0);
    }

    @Test
    public void arrowKeysStepOneStop() {
        ruler.setItems(items, iso(800), auto);
        assertTrue(ruler.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
        assertTrue(ruler.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
        assertTrue(ruler.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)));
        assertEquals(java.util.Arrays.asList(1000.0, 1280.0, 1000.0), committed);
        // from auto: the first key press starts at the metered stop
        committed.clear();
        ruler.setSelectedItem(auto);
        ruler.setMeteredValue(200);
        ruler.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(java.util.Collections.singletonList(160.0), committed);
    }

    /** A parent that records whether it still intercepts and whether touches fall through to it. */
    static final class Parent extends FrameLayout {
        int intercepts, ownTouches;

        Parent(Context c) { super(c); }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) { intercepts++; return false; }

        @Override public boolean onTouchEvent(MotionEvent ev) { ownTouches++; return true; }
    }

    @Test
    public void horizontalDragsStayOnTheRuler() {
        ruler.setItems(items, iso(800), auto);
        Parent parent = new Parent(ruler.getContext());
        parent.addView(ruler, new FrameLayout.LayoutParams(336, 49));
        parent.measure(View.MeasureSpec.makeMeasureSpec(336, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(49, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, 336, 49);
        long t = 0;
        assertTrue(parent.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, t, 250)));
        int afterDown = parent.intercepts;
        for (int i = 1; i <= 10; i++) parent.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, t += 200, 250 - 5 * i));
        parent.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, t += 200, 200));
        assertEquals("the ruler asked its parents not to intercept the drag", afterDown, parent.intercepts);
        assertEquals("no touch fell through to the parent", 0, parent.ownTouches);
        assertFalse(committed.isEmpty());
    }

    @Test
    public void markerIsFixedInTheCentreInTheAccent() {
        ruler.setItems(items, iso(800), auto);
        Bitmap bitmap = Bitmap.createBitmap(336, 49, Bitmap.Config.ARGB_8888);
        ruler.draw(new Canvas(bitmap));
        float left = ruler.autoWidth() + 8, centre = (left + 336) / 2f;
        // the 2dp marker line between h-23 and h-4, in the accent (not the old amber)
        int pixel = bitmap.getPixel(Math.round(centre), 49 - 10);
        assertEquals(Integer.toHexString(pixel), ACCENT & 0xFFFFFF, pixel & 0xFFFFFF);
        // the triangle on top of it
        int tip = bitmap.getPixel(Math.round(centre), 49 - 26);
        assertEquals(Integer.toHexString(tip), ACCENT & 0xFFFFFF, tip & 0xFFFFFF);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir(), "manual-ruler.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        // after a drag the marker stays where it is, the scale moved under it
        long t = 0;
        ruler.onTouchEvent(ev(MotionEvent.ACTION_DOWN, t, 250));
        ruler.onTouchEvent(ev(MotionEvent.ACTION_MOVE, t += 200, 245));
        bitmap.eraseColor(0);
        ruler.draw(new Canvas(bitmap));
        assertEquals(ACCENT & 0xFFFFFF, bitmap.getPixel(Math.round(centre), 49 - 10) & 0xFFFFFF);
        ruler.onTouchEvent(ev(MotionEvent.ACTION_UP, t += 200, 245));
    }

    private static java.io.File dir() {
        java.io.File dir = new java.io.File("build/reports/viewfinder");
        dir.mkdirs();
        return dir;
    }
}
