package com.particlesdevs.photoncamera.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.particlesdevs.photoncamera.ui.controls.DoubleTapReset;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.robolectric.Shadows.shadowOf;

/** P357: the page reset's defaults of preferences.xml, a row back to it, and the double tap of a slider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PageResetTest {
    @Test
    public void defaultsComeFromPreferencesXml() {
        final Map<String, String> d = PageReset.defaults(RuntimeEnvironment.getApplication());
        assertEquals("false", d.get("pref_ettr"));
        assertEquals("0.1", d.get("pref_scam_hybrid_why_weight_dark2"));
        assertEquals("false", d.get("pref_motion_mode"));
    }

    @Test
    public void aSwitchGoesBackToItsDefault() {
        final Context c = RuntimeEnvironment.getApplication();
        final PreferenceManager pm = new PreferenceManager(c);
        final SwitchPreferenceCompat s = new SwitchPreferenceCompat(c);
        s.setKey("pref_ettr");
        pm.createPreferenceScreen(c).addPreference(s);
        s.setChecked(true);
        assertTrue(PageReset.resetRow(s, "false"));
        assertFalse(s.isChecked());
        assertFalse(PageReset.resetRow(s, "false")); // at its default already
        assertFalse(PageReset.resetRow(s, null)); // no XML default: left alone
    }

    @Test
    public void aDoubleTapOnASliderResets() {
        final Context c = RuntimeEnvironment.getApplication();
        final FrameLayout root = new FrameLayout(c);
        final SeekBar bar = new SeekBar(c);
        bar.setMax(100);
        root.addView(bar, new FrameLayout.LayoutParams(400, 60));
        root.measure(500, 100);
        root.layout(0, 0, 500, 100);
        final AtomicInteger resets = new AtomicInteger();
        DoubleTapReset.attach(bar, resets::incrementAndGet);
        final long t = SystemClock.uptimeMillis();
        tap(bar, t, 100);
        tap(bar, t + 120, 102);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, resets.get());
    }

    private static void tap(SeekBar bar, long t, float x) {
        final MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, 30, 0);
        bar.dispatchTouchEvent(down);
        down.recycle();
        final MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, 30, 0);
        bar.dispatchTouchEvent(up);
        up.recycle();
    }
}
