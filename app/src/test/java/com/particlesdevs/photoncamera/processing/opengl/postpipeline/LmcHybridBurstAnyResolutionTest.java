package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.graphics.Point;
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.capture.CameraResumeTest;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.GLLimits;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P27 any resolution: the Hybrid takes any RAW resolution (no 16 MP cap) and merges it at its own resolution: the Sabre 2x grid
 * only where it fits (input up to 16 MP within the GPU's side), otherwise the sensor grid; only the hard limits refuse a stream.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, shadows=CameraResumeTest.ShadowAllocator.class,
        instrumentedPackages="com.particlesdevs.photoncamera.util")
public class LmcHybridBurstAnyResolutionTest {
    private static final GLLimits ADRENO_750 = GLLimits.of(16384, 16384, 16384, 16384);
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.PhotonCamera> photon;
    private long clock = 1_000_000_000L;
    private String outputMode = "2x";

    @Before public void setup() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager settings = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        photon = mockStatic(com.particlesdevs.photoncamera.app.PhotonCamera.class);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getAppContext).thenReturn(context);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getSettingsManagerStatic).thenReturn(settings);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        photon.when(() -> com.particlesdevs.photoncamera.app.PhotonCamera.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.hybridSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridValue(anyString(), anyFloat())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        prefs.when(PreferenceKeys::hybridOutputMode).thenAnswer(i -> outputMode);
        prefs.when(PreferenceKeys::hybridDownsamplerName).thenReturn("test");
        prefs.when(() -> PreferenceKeys.hybridFinalSize(anyInt(), anyInt())).thenAnswer(i -> new Point(i.getArgument(0), i.getArgument(1)));
        GLLimits.setForTesting(ADRENO_750);
    }
    @After public void cleanup() { GLLimits.setForTesting(null); prefs.close(); photon.close(); }

    private ImageFrame frame(int w, int h, ByteBuffer buffer) {
        ImageFrame f = mock(ImageFrame.class);
        f.width = w; f.height = h; f.buffer = buffer;
        f.timestamp = clock += 33_000_000L; f.number = (int) (clock / 33_000_000L);
        f.measuredExposure = 10_000_000L; f.measuredIso = 100;
        f.noiseSlope = .00015f; f.noiseOffset = .000002f; f.sharpness = 1f;
        when(f.getCaptureRole()).thenReturn(ImageFrame.CaptureRole.NORMAL);
        CaptureResult metadata = mock(CaptureResult.class);
        when(f.getMatchedCaptureMetadata()).thenReturn(metadata);
        return f;
    }
    private static Parameters parameters(int w, int h) {
        Parameters p = new Parameters();
        p.rawSize = new Point(w, h); p.cfaPattern = 0; p.whiteLevel = 1023; p.blackLevel = new float[]{64, 64, 64, 64};
        return p;
    }
    private static LmcHybridBurst burst(List<ImageFrame> frames, Parameters p) throws Exception {
        Constructor<LmcHybridBurst> c = LmcHybridBurst.class.getDeclaredConstructor(List.class, Parameters.class, boolean.class);
        c.setAccessible(true);
        try { return c.newInstance(frames, p, false); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    @SuppressWarnings("unchecked")
    private static List<ImageFrame> frames(LmcHybridBurst burst) throws Exception {
        Field f = LmcHybridBurst.class.getDeclaredField("frames"); f.setAccessible(true); return (List<ImageFrame>) f.get(burst);
    }

    @Test public void aFiftyMegapixelStreamIsAcceptedAndMergedOnTheSensorGrid() throws Exception {
        // 8160 x 6120 (50 MP): refused by the old 16 MP guard. With "2x" asked for, the 2x grid (16320 x 12240, 2.4 GB RGB) does
        // not fit: the stream is merged at its own resolution.
        final int w = 8160, h = 6120;
        LmcHybridBurst b = burst(List.of(frame(w, h, ByteBuffer.allocate(w * h * 2))), parameters(w, h));
        assertEquals(w, b.width());
        assertEquals(h, b.height());
        assertEquals(w, b.outputWidth());
        assertEquals(h, b.outputHeight());
        assertEquals(1f, b.outputScale(), 0f);
        assertEquals(1, frames(b).size());
    }

    @Test public void upTo16MegapixelsThe2xChoiceIsUnchanged() throws Exception {
        LmcHybridBurst b = burst(List.of(frame(64, 64, ByteBuffer.allocate(64 * 64 * 2))), parameters(64, 64));
        assertEquals(128, b.outputWidth());
        assertEquals(128, b.outputHeight());
        outputMode = "sensor";
        assertEquals(64, burst(List.of(frame(64, 64, ByteBuffer.allocate(64 * 64 * 2))), parameters(64, 64)).outputWidth());
        for (GLLimits gpu : new GLLimits[]{ADRENO_750, GLLimits.UNKNOWN}) {
            final int side = gpu.maxSide();
            assertTrue(LmcHybridBurst.sabre2xFits(4080, 3060, side));    // 12.5 MP
            assertTrue(LmcHybridBurst.sabre2xFits(4608, 3456, side));    // 15.9 MP
            assertTrue(LmcHybridBurst.sabre2xFits(4000, 4000, side));    // 16.0 MP exactly
            assertFalse(LmcHybridBurst.sabre2xFits(4624, 3472, side));   // 16.05 MP
            assertFalse(LmcHybridBurst.sabre2xFits(8160, 6120, side));   // 50 MP
        }
        // A GPU whose textures stop at 8192: 2w x 2h must fit them.
        assertTrue(LmcHybridBurst.sabre2xFits(4080, 3060, 8192));
        assertFalse(LmcHybridBurst.sabre2xFits(4608, 3456, 8192));
    }

    @Test public void onlyTheHardLimitsRefuseAStream() {
        // 16384 x 12288 (201 MP): its sensor-grid RGB (2.3 GB) is above one Java buffer; RawBin bins it before the hybrid.
        IOException rgb = assertThrows(IOException.class, () -> burst(List.of(frame(16384, 12288, null)), parameters(16384, 12288)));
        assertTrue(rgb.getMessage(), rgb.getMessage().contains("above 2 GB"));
        // A side above the GPU's limit.
        GLLimits.setForTesting(GLLimits.of(8192, 8192, 8192, 8192));
        IOException side = assertThrows(IOException.class, () -> burst(List.of(frame(8320, 6240, null)), parameters(8320, 6240)));
        assertTrue(side.getMessage(), side.getMessage().contains("GPU"));
    }

    @Test public void theMemoryBudgetCapsNOnlyAbove16Megapixels() throws Exception {
        assertEquals(Integer.MAX_VALUE, LmcHybridBurst.memoryFrameBudget(4080, 3060, 1, 1L << 30));
        assertEquals(Integer.MAX_VALUE, LmcHybridBurst.memoryFrameBudget(4000, 4000, 1, 1L << 30));
        assertEquals("unknown memory: no cap", Integer.MAX_VALUE, LmcHybridBurst.memoryFrameBudget(8160, 6120, 1, -1));
        final int six = LmcHybridBurst.memoryFrameBudget(8160, 6120, 1, 6L << 30);
        final int four = LmcHybridBurst.memoryFrameBudget(8160, 6120, 1, 4L << 30);
        assertTrue(six + " vs " + four, six > four && four > 0);
        assertEquals("50 MP: the fixed cost alone is above 60 % of 3 GB", 0, LmcHybridBurst.memoryFrameBudget(8160, 6120, 1, 3L << 30));
        assertTrue("RAW CA on every frame: a corrected copy per plain-Bayer frame lowers the budget",
                LmcHybridBurst.memoryFrameBudget(8160, 6120, 1, 6L << 30, true) < six);
        assertEquals("a mosaic is corrected in place", LmcHybridBurst.memoryFrameBudget(8160, 6120, 2, 6L << 30),
                LmcHybridBurst.memoryFrameBudget(8160, 6120, 2, 6L << 30, true));
        assertEquals("16 MP or less: never capped", Integer.MAX_VALUE, LmcHybridBurst.memoryFrameBudget(4080, 3060, 1, 1L << 30, true));
        assertTrue("the worker's copy of a mosaic frame counts",
                LmcHybridBurst.memoryFrameBudget(8160, 6120, 2, 6L << 30) < six);

        // A 16.3 MP burst of ten N frames with memory for five: the five newest N frames are merged.
        final int w = 4800, h = 3400;
        final double perFrame = LmcHybridBurst.perFrameBytes(w, h, 1);
        final long available = (long) Math.ceil((LmcHybridBurst.FIXED_BYTES_PER_PIXEL * w * h + 5.5 * perFrame) / LmcHybridBurst.MEMORY_SHARE);
        assertEquals(5, LmcHybridBurst.memoryFrameBudget(w, h, 1, available));
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        info.availMem = available; info.totalMem = 12L << 30;
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Shadows.shadowOf((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(info);
        ByteBuffer shared = ByteBuffer.allocate(w * h * 2);
        List<ImageFrame> burst = new ArrayList<>();
        for (int i = 0; i < 10; i++) burst.add(frame(w, h, shared));
        List<ImageFrame> merged = frames(burst(burst, parameters(w, h)));
        assertEquals(5, merged.size());
        for (int i = 5; i < 10; i++) assertTrue("newest N frame " + i + " kept", merged.contains(burst.get(i)));
        // The same memory at 16 MP or less: no cap.
        ByteBuffer small = ByteBuffer.allocate(64 * 64 * 2);
        List<ImageFrame> smallBurst = new ArrayList<>();
        for (int i = 0; i < 10; i++) smallBurst.add(frame(64, 64, small));
        assertEquals(10, frames(burst(smallBurst, parameters(64, 64))).size());
    }

    @Test public void theWorkerWaitGrowsOnlyAbove16Megapixels() {
        assertEquals(900, LmcHybridBurst.workerTimeoutSeconds(4080, 3060));
        assertEquals(900, LmcHybridBurst.workerTimeoutSeconds(4000, 4000));
        assertEquals(2810, LmcHybridBurst.workerTimeoutSeconds(8160, 6120));
        assertEquals(3600, LmcHybridBurst.workerTimeoutSeconds(15000, 11900));
    }
}
