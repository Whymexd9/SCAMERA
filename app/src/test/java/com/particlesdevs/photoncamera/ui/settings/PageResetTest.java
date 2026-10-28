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

import android.content.SharedPreferences;
import android.util.AttributeSet;

import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.settings.DevKeys;
import com.particlesdevs.photoncamera.settings.PhotoStyle;
import com.particlesdevs.photoncamera.settings.ScamDevFile;
import com.particlesdevs.photoncamera.ui.controls.DoubleTapReset;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmLutPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmSimPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.LensFilterPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.PhotoStylePreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

/** P357: the page reset's defaults of preferences.xml, a row back to it, and the double tap of a slider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PageResetTest {
    @After
    public void tearDown() throws Exception {
        forgetDefaults();
    }

    /** PageReset keeps the defaults of the process: another device's need a new read. */
    private static void forgetDefaults() throws Exception {
        final java.lang.reflect.Field f = PageReset.class.getDeclaredField("defaults");
        f.setAccessible(true);
        f.set(null, null);
    }

    /** A page holding {@code rows}, its preferences cleared first; the page's preferences. */
    private static SharedPreferences page(Context c, androidx.preference.Preference... rows) {
        final PreferenceManager pm = new PreferenceManager(c);
        pm.getSharedPreferences().edit().clear().commit();
        final PreferenceScreen screen = pm.createPreferenceScreen(c);
        pm.setPreferences(screen);
        for (androidx.preference.Preference p : rows) screen.addPreference(p);
        return pm.getSharedPreferences();
    }

    /**
     * Audit 2026-10-27: the film, LUT, lens filter and style rows keep their state in keys of their own; the page reset changed
     * none of them and said «Всё уже по умолчанию».
     */
    @Test
    public void filmLutFilterAndStylePagesReset() {
        final Context c = RuntimeEnvironment.getApplication();
        final FilmSimPreference film = new FilmSimPreference(c);
        film.setKey(FilmSim.STOCK_KEY);
        SharedPreferences prefs = page(c, film);
        prefs.edit().putString(FilmSim.STOCK_KEY, "portra400").putString(FilmSim.GRAIN_KEY, "200").commit();
        assertEquals(1, PageReset.reset(c, film.getParent()));
        assertFalse(prefs.contains(FilmSim.STOCK_KEY));
        assertFalse(prefs.contains(FilmSim.GRAIN_KEY));
        assertEquals("at its default already", 0, PageReset.reset(c, film.getParent()));

        final FilmLutPreference lut = new FilmLutPreference(c);
        lut.setKey(FilmLut.KEY);
        prefs = page(c, lut);
        prefs.edit().putString(FilmLut.KEY, "x.cube").commit();
        assertEquals(1, PageReset.reset(c, lut.getParent()));
        assertEquals("", lut.chosen());
        assertEquals(0, PageReset.reset(c, lut.getParent()));

        final LensFilterPreference filter = new LensFilterPreference(c);
        filter.setKey(LensFilters.KEY);
        prefs = page(c, filter);
        prefs.edit().putString(LensFilters.KEY, LensFilters.GLIMMERGLASS).putString(LensFilters.STRENGTH_KEY, "80")
                .putString(LensFilters.tuneKey(LensFilters.GLIMMERGLASS), "x").commit();
        assertEquals(LensFilters.GLIMMERGLASS, filter.chosen());
        assertEquals(1, PageReset.reset(c, filter.getParent()));
        assertEquals("", filter.chosen());
        assertFalse(prefs.contains(LensFilters.STRENGTH_KEY));
        assertFalse(prefs.contains(LensFilters.tuneKey(LensFilters.GLIMMERGLASS)));
        assertEquals(0, PageReset.reset(c, filter.getParent()));

        final PhotoStylePreference style = new PhotoStylePreference(c);
        style.setKey("photo_style");
        prefs = page(c, style);
        PhotoStyle.apply(prefs, PhotoStyle.MONO);
        assertEquals(PhotoStyle.MONO, PhotoStyle.current(prefs));
        assertEquals("a non-persistent row resets itself", 1, PageReset.reset(c, style.getParent()));
        assertEquals(PhotoStyle.NORMAL, PhotoStyle.current(prefs));
        assertEquals(0, PageReset.reset(c, style.getParent()));
    }

    /** Audit 2026-10-27: a row another setting disables (the «Движение» rows while the mode is off) is reset too. */
    @Test
    public void disabledRowIsReset() {
        final Context c = RuntimeEnvironment.getApplication();
        final SwitchPreferenceCompat s = new SwitchPreferenceCompat(c);
        s.setKey("pref_scam_hybrid_mm_bento");
        final TunableSeekBarPreference t = new TunableSeekBarPreference(c);
        t.setKey("pref_tunable_test_value");
        t.setDefaultValue(1f);
        final SharedPreferences prefs = page(c, s, t);
        s.setChecked(true);
        prefs.edit().putFloat("pref_tunable_test_value", 3f).commit();
        s.setEnabled(false);
        t.setEnabled(false);
        assertFalse("its own double tap waits for the row", t.resetToDefault());
        assertTrue(PageReset.resetRow(s, "false"));
        assertFalse(s.isChecked());
        assertTrue(PageReset.resetRow(t, null));
        assertFalse(prefs.contains("pref_tunable_test_value"));
    }

    /** Audit 2026-10-27: the page reset and a slider's default are this device's factory values, as after the full reset. */
    @Test
    public void deviceDefaultsAreTheResetTarget() throws Exception {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PHY110");
        forgetDefaults();
        Map<String, String> d = PageReset.defaults(RuntimeEnvironment.getApplication());
        assertEquals("true", d.get("pref_camera_package_spoof_enabled"));
        assertEquals("raw10", d.get("pref_raw_stream_format"));
        assertEquals("not a device value: the XML's", "false", d.get("pref_ettr"));

        ShadowBuild.setManufacturer("vivo");
        ShadowBuild.setModel("V2454A");
        ShadowBuild.setSystemOnChipModel("MT6991");
        forgetDefaults();
        d = PageReset.defaults(RuntimeEnvironment.getApplication());
        assertEquals("true", d.get("pref_live_viewfinder_raw_key"));
        assertEquals("auto", d.get("pref_raw_stream_format"));

        // the Redmi Note 11 Pro merges 12 frames by default: the slider's double tap and «changed» mark take it
        ShadowBuild.setSystemOnChipModel("MT6781");
        ShadowBuild.setManufacturer("Xiaomi");
        ShadowBuild.setDevice("viva");
        final AttributeSet attrs = Robolectric.buildAttributeSet()
                .addAttribute(android.R.attr.key, "pref_scam_hybrid_zsl_frames")
                .addAttribute(android.R.attr.defaultValue, "20")
                .addAttribute(R.attr.minValue, "4").addAttribute(R.attr.maxValue, "44")
                .addAttribute(R.attr.stepPerUnit, "1").addAttribute(R.attr.isFloat, "false").build();
        final UniversalSeekBarPreference frames = new UniversalSeekBarPreference(RuntimeEnvironment.getApplication(), attrs);
        assertEquals(12f, frames.defaultNumber(), 0f);
    }

    /** Audit 2026-10-27: the developer page's rows write scam_dev.txt; its page reset removes the page's lines, as its own row does. */
    @Test
    public void developerPageResetRemovesItsLines() {
        final Context c = RuntimeEnvironment.getApplication();
        final String key = DevKeys.all().get(0).key;
        assertTrue(ScamDevFile.set(c, key, "1"));
        assertTrue(ScamDevFile.set(c, "not_a_page_line", "2"));
        final PreferenceManager pm = new PreferenceManager(c);
        final PreferenceScreen root = pm.createPreferenceScreen(c);
        pm.setPreferences(root);
        final PreferenceScreen dev = pm.createPreferenceScreen(c);
        dev.setKey(DeveloperPreferences.SCREEN);
        root.addPreference(dev);
        DeveloperPreferences.populate(root, c);
        assertEquals("nothing for the preferences", 0, PageReset.reset(c, dev));
        assertEquals(1, DeveloperPreferences.resetAll(root, c));
        assertNull(ScamDevFile.get(c, key));
        assertEquals("another line stays", "2", ScamDevFile.get(c, "not_a_page_line"));
        assertEquals(0, DeveloperPreferences.resetAll(root, c));
        ScamDevFile.set(c, "not_a_page_line", null);
    }
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
