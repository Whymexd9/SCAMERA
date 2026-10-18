package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** P240 (speed-ux C4): an untested route says «experimental» once per install, then runs silently. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ExperimentalRoutesTest {
    @Test public void theNoticeComesOnceAndPerRoute() {
        final Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(ExperimentalRoutes.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        assertEquals("first", ExperimentalRoutes.firstUse(context, "p208_stock_200mp", "first"));
        assertNull("then silent", ExperimentalRoutes.firstUse(context, "p208_stock_200mp", "again"));
        assertEquals("another route has its own", "other", ExperimentalRoutes.firstUse(context, "other_route", "other"));
        assertNull("no context", ExperimentalRoutes.firstUse(null, "x", "y"));
    }
}
