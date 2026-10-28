package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.util.ParallelWork;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Audit 2026-10-27 (lab-app-3): the lab's preference overlay applied to the whole process, so the camera and the settings read a
 * lab shot's values while a lab render ran in the background (a photo taken meanwhile was captured with them, a settings switch
 * equal to the overlaid value was not stored). It is the render's own: its thread, the threads it starts and the pool items it
 * hands out read it; every other thread reads the stored values.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class LabScopeTest {
    private static final String KEY = "pref_lab_scope_test";
    private SharedPreferences prefs;
    private ExecutorService camera;

    @Before
    public void setUp() throws Exception {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("lab_scope_test", Context.MODE_PRIVATE);
        prefs.edit().clear().putString(KEY, "50").commit();
        camera = Executors.newSingleThreadExecutor();
        camera.submit(() -> { }).get(); // its thread runs before any render
    }

    @After
    public void tearDown() {
        PreferenceValue.overlay(null, null);
        camera.shutdownNow();
    }

    private Object onCamera() throws Exception {
        return camera.submit(() -> PreferenceValue.get(prefs, KEY)).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void overlayIsInvisibleToOtherThreads() throws Exception {
        PreferenceValue.overlay(prefs, Collections.singletonMap(KEY, "90"));
        assertEquals("the render's thread", "90", PreferenceValue.get(prefs, KEY));
        assertTrue(PreferenceValue.overlaid());
        assertEquals("the camera reads the stored value", "50", onCamera());
        assertFalse(camera.submit(PreferenceValue::overlaid).get());
        final Object[] started = new Object[1];
        final CountDownLatch read = new CountDownLatch(1), next = new CountDownLatch(1);
        final Object[] later = new Object[1];
        final Thread helper = new Thread(() -> {
            started[0] = PreferenceValue.get(prefs, KEY);
            read.countDown();
            try {
                next.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            later[0] = PreferenceValue.get(prefs, KEY);
        });
        helper.start();
        assertTrue(read.await(5, TimeUnit.SECONDS));
        assertEquals("a thread the render starts", "90", started[0]);
        PreferenceValue.overlay(null, null);
        assertEquals("50", PreferenceValue.get(prefs, KEY));
        // the next render (another overlay): a thread kept from the earlier one is not its own
        PreferenceValue.overlay(prefs, Collections.singletonMap(KEY, "70"));
        next.countDown();
        helper.join(5000);
        assertEquals("a thread of the earlier render reads the stored value", "50", later[0]);
        assertEquals("70", PreferenceValue.get(prefs, KEY));
        PreferenceValue.overlay(null, null);
    }

    @Test
    public void labScopeFollowsTheCallerIntoTheParallelPool() throws Exception {
        final int n = ParallelWork.threads() * 8;
        PreferenceValue.overlay(prefs, Collections.singletonMap(KEY, "90"));
        final AtomicReferenceArray<Object> seen = new AtomicReferenceArray<>(n);
        // the pool's threads are made now, by the render's thread
        ParallelWork.forEach(n, i -> {
            seen.set(i, PreferenceValue.get(prefs, KEY));
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (int i = 0; i < n; i++) assertEquals("item " + i, "90", seen.get(i));
        // the camera's pass on the same pool threads meanwhile reads the stored value
        final AtomicReferenceArray<Object> other = new AtomicReferenceArray<>(n);
        camera.submit(() -> ParallelWork.forEach(n, i -> other.set(i, PreferenceValue.get(prefs, KEY)))).get(10, TimeUnit.SECONDS);
        for (int i = 0; i < n; i++) assertEquals("camera item " + i, "50", other.get(i));
        PreferenceValue.overlay(null, null);
        ParallelWork.forEach(n, i -> seen.set(i, PreferenceValue.get(prefs, KEY)));
        for (int i = 0; i < n; i++) assertEquals("after the render, item " + i, "50", seen.get(i));
    }
}
