package com.particlesdevs.photoncamera.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import com.particlesdevs.photoncamera.processing.ProcessingKeepAlive;
import com.particlesdevs.photoncamera.ui.SplashActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Audit 2026-10-27 (lab-app-2): loading or resetting a config restarted the process one second later even while a shot was
 * still merged and saved, and the photo was lost. The restart waits for the shot (ProcessingKeepAlive.busy) and says so once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScameraAppRestartTest {
    private BooleanSupplier busyBefore;
    private Runnable exitBefore;
    private final AtomicInteger exits = new AtomicInteger();

    @Before
    public void setUp() {
        busyBefore = ProcessingKeepAlive.busy;
        exitBefore = ScameraApp.exit;
        ScameraApp.exit = exits::incrementAndGet;
    }

    @After
    public void tearDown() {
        ProcessingKeepAlive.busy = busyBefore;
        ScameraApp.exit = exitBefore;
        ScameraApp.restarting = false;
    }

    private static void idleFor(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void restartWaitsWhileAShotIsProcessed() {
        final Application app = RuntimeEnvironment.getApplication();
        final AtomicBoolean busy = new AtomicBoolean(true);
        ProcessingKeepAlive.busy = busy::get;
        ScameraApp.restartWithDelay(app, 1000);
        idleFor(5000);
        assertNull("no restart while the shot is processed", shadowOf(app).getNextStartedActivity());
        assertEquals(0, exits.get());
        assertEquals("said once", 1, ShadowToast.shownToastCount());
        busy.set(false);
        idleFor(1000);
        final Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(SplashActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(1, exits.get());
        assertEquals(1, ShadowToast.shownToastCount());
    }

    /** A busy flag that never clears does not hold the written config back for ever: the restart comes after the longest wait. */
    @Test
    public void aStuckShotDoesNotHoldTheRestartForEver() {
        final Application app = RuntimeEnvironment.getApplication();
        ProcessingKeepAlive.busy = () -> true;
        ScameraApp.restartWithDelay(app, 1000);
        idleFor(1000 + ScameraApp.RESTART_MAX_WAIT_MS - 1000);
        assertEquals("still waiting", 0, exits.get());
        idleFor(2000);
        assertNotNull(shadowOf(app).getNextStartedActivity());
        assertEquals(1, exits.get());
        assertEquals("said once", 1, ShadowToast.shownToastCount());
    }

    @Test
    public void restartWithoutAShotIsAsBefore() {
        final Application app = RuntimeEnvironment.getApplication();
        ProcessingKeepAlive.busy = () -> false;
        ScameraApp.restartWithDelay(app, 1000);
        idleFor(999);
        assertEquals(0, exits.get());
        idleFor(2);
        assertNotNull(shadowOf(app).getNextStartedActivity());
        assertEquals(1, exits.get());
        assertEquals("nothing to wait for, nothing said", 0, ShadowToast.shownToastCount());
    }
}
