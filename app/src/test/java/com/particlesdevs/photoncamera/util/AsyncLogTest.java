package com.particlesdevs.photoncamera.util;

import android.app.Application;
import android.os.Handler;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AsyncLogTest {
    private static Field field(String name) throws Exception {
        Field f=Log.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    @Test public void callerDoesNotQueryStorageAndQueueIsBounded() throws Exception {
        Log.setLogEnabled(true);
        Handler handler=(Handler)field("logHandler").get(null);
        // Pause the writer: equivalent to a stalled SAF provider. The caller
        // must only enqueue, even with an active SAF logging destination.
        shadowOf(handler.getLooper()).pause();
        // Exclude the recurring flush timer from this bounded-queue test.
        // idle() only drains up to its initial clock instant; log messages
        // posted one millisecond later could remain queued on slower CI hosts.
        handler.removeCallbacksAndMessages(null);
        // Removing a queued log callback also removes its finally block.
        // Reset accounting for discarded setup logs before the measured burst.
        ((AtomicInteger)field("pendingLines").get(null)).set(0);
        ((AtomicInteger)field("droppedLines").get(null)).set(0);
        field("logContext").set(null,RuntimeEnvironment.getApplication());
        field("currentDate").set(null,new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date()));
        StringWriter sink=new StringWriter();BufferedWriter writer=new BufferedWriter(sink);
        field("bufferedWriter").set(null,writer);
        try(MockedStatic<SimpleStorageHelper> storage=mockStatic(SimpleStorageHelper.class)) {
            for(int i=0;i<5000;i++)Log.d("UI", "entry "+i);
            storage.verifyNoInteractions(); // Old code queried SAF 5000 times here.
            int queued=((AtomicInteger)field("pendingLines").get(null)).get();
            assertTrue(queued>0);assertTrue(queued<=4096);
            assertEquals("",sink.toString());
            shadowOf(handler.getLooper()).idleFor(java.time.Duration.ofSeconds(1));
            writer.flush();
            assertTrue(sink.toString().contains("entry 0"));
            assertTrue(sink.toString().contains("dropped"));
            assertEquals(0,((AtomicInteger)field("pendingLines").get(null)).get());
            storage.verifyNoInteractions(); // Cached writer does not re-query SAF.
        } finally {
            field("bufferedWriter").set(null,null);field("logContext").set(null,null);
        }
    }
}
