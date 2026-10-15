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
 * Output dimensions, transport, memory planning and failure behavior across sensor and native Sabre 2x grids.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, shadows=CameraResumeTest.ShadowAllocator.class,
        instrumentedPackages="com.particlesdevs.photoncamera.util")
public class ScamHybridBurstAnyResolutionTest {
    private static final GLLimits ADRENO_750 = GLLimits.of(16384, 16384, 16384, 16384);
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> appStatic;
    private long clock = 1_000_000_000L;
    private String outputMode = "2x";

    @Before public void setup() {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager settings = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        appStatic = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        appStatic.when(() -> com.particlesdevs.photoncamera.app.ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.hybridSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridValue(anyString(), anyFloat())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.scamDevSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        prefs.when(PreferenceKeys::hybridOutputMode).thenAnswer(i -> outputMode);
        prefs.when(PreferenceKeys::hybridDownsamplerName).thenReturn("test");
        prefs.when(() -> PreferenceKeys.hybridFinalSize(anyInt(), anyInt())).thenAnswer(i -> new Point(i.getArgument(0), i.getArgument(1)));
        GLLimits.setForTesting(ADRENO_750);
    }
    @After public void cleanup() { GLLimits.setForTesting(null); prefs.close(); appStatic.close(); }

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
    private static ScamHybridBurst burst(List<ImageFrame> frames, Parameters p) throws Exception {
        Constructor<ScamHybridBurst> c = ScamHybridBurst.class.getDeclaredConstructor(List.class, Parameters.class, boolean.class);
        c.setAccessible(true);
        try { return c.newInstance(frames, p, false); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    @SuppressWarnings("unchecked")
    private static List<ImageFrame> frames(ScamHybridBurst burst) throws Exception {
        Field f = ScamHybridBurst.class.getDeclaredField("frames"); f.setAccessible(true); return (List<ImageFrame>) f.get(burst);
    }

    @Test public void sdk50RequestsTheReal201MegapixelGridAndForcesHalfTransport() throws Exception {
        final int w = 8192, h = 6144;
        memory(16L << 30);
        prefs.when(() -> PreferenceKeys.scamDevSwitch("hybrid_rgb_half", true)).thenReturn(false);
        ByteBuffer raw = ByteBuffer.allocate(w * h * 2);
        ScamHybridBurst b = burst(List.of(frame(w, h, raw), frame(w, h, raw), frame(w, h, raw)), parameters(w, h));
        assertEquals(w, b.width());
        assertEquals(h, b.height());
        assertEquals(16384, b.outputWidth());
        assertEquals(12288, b.outputHeight());
        assertEquals(2f, b.outputScale(), 0f);
        assertTrue(b.rgbHalfTransport());
        assertTrue(b.tuningOverride().endsWith("grid 2\nauto2x 0\n"));
        java.lang.reflect.Method header = ScamHybridBurst.class.getDeclaredMethod("header");
        header.setAccessible(true);
        ByteBuffer encoded = (ByteBuffer) header.invoke(b);
        assertEquals("native worker must not auto-resize a coarse merge", 8, encoded.getInt(44) & 8);
        assertEquals(2, encoded.getInt(52));
    }

    @Test public void upTo16MegapixelsThe2xChoiceIsUnchanged() throws Exception {
        ScamHybridBurst b = burst(List.of(frame(64, 64, ByteBuffer.allocate(64 * 64 * 2))), parameters(64, 64));
        assertEquals(128, b.outputWidth());
        assertEquals(128, b.outputHeight());
        outputMode = "sensor";
        assertEquals(64, burst(List.of(frame(64, 64, ByteBuffer.allocate(64 * 64 * 2))), parameters(64, 64)).outputWidth());
        for (GLLimits gpu : new GLLimits[]{ADRENO_750, GLLimits.UNKNOWN}) {
            final int side = gpu.maxSide();
            assertTrue(ScamHybridBurst.sabre2xFits(4080, 3060, side));    // 12.5 MP
            assertTrue(ScamHybridBurst.sabre2xFits(4608, 3456, side));    // 15.9 MP
            assertTrue(ScamHybridBurst.sabre2xFits(4000, 4000, side));    // 16.0 MP exactly
            assertTrue(ScamHybridBurst.sabre2xFits(4624, 3472, side));
            assertTrue(ScamHybridBurst.sabre2xFits(8192, 6144, side));
        }
        // A GPU whose textures stop at 8192: 2w x 2h must fit them.
        assertTrue(ScamHybridBurst.sabre2xFits(4080, 3060, 8192));
        assertFalse(ScamHybridBurst.sabre2xFits(4608, 3456, 8192));
        assertFalse("FP16 output above one ByteBuffer", ScamHybridBurst.sabre2xFits(12000, 9000, 32768));
        assertFalse(ScamHybridBurst.sabre2xFits(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(ScamHybridBurst.sabre2xFits(0, 6144, 16384));
    }

    @Test public void strict2xReportsGpuLimitsInsteadOfReturningTheSensorGrid() {
        GLLimits.setForTesting(GLLimits.of(100, 100, 100, 100));
        IOException failure = assertThrows(IOException.class, () -> burst(
                List.of(frame(64, 64, ByteBuffer.allocate(64 * 64 * 2))), parameters(64, 64)));
        assertTrue(failure.getMessage(), failure.getMessage().contains("Sabre 2x 128x128 exceeds GPU"));
    }

    @Test public void strict2xNeverRetriesAWorkerFailureOnTheSensorGrid() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        List<ImageFrame> input = List.of(frame(64, 64, ByteBuffer.allocate(64 * 64 * 2)));
        try (MockedStatic<ScamNeuralClient> worker = mockStatic(ScamNeuralClient.class)) {
            worker.when(() -> ScamNeuralClient.processScamBurst(eq(context), any(ScamTransport.class)))
                    .thenThrow(new IOException("GPU output allocation failed"));
            IOException failure = assertThrows(IOException.class, () -> ScamHybridBurst.process(context, input, parameters(64, 64), false));
            assertTrue(failure.getMessage(), failure.getMessage().contains("no sensor-grid or upscale fallback"));
            assertEquals("GPU output allocation failed", failure.getCause().getMessage());
            worker.verify(() -> ScamNeuralClient.processScamBurst(eq(context), any(ScamTransport.class)), times(1));
            assertEquals(new Point(128, 128), ScamHybridBurst.lastOutputSize);
        }
    }

    @Test public void halfTransportIsRequiredOnlyWhenFloat32CannotFitOrItWasSelected() {
        assertTrue(ScamHybridBurst.transportHalf(16384, 12288, false));
        assertFalse(ScamHybridBurst.transportHalf(8192, 6144, false));
        assertTrue(ScamHybridBurst.transportHalf(8192, 6144, true));
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
        outputMode = "sensor";
        assertEquals(Integer.MAX_VALUE, ScamHybridBurst.memoryFrameBudget(4080, 3060, 1, 1L << 30));
        assertEquals(Integer.MAX_VALUE, ScamHybridBurst.memoryFrameBudget(4000, 4000, 1, 1L << 30));
        assertEquals("unknown memory: no cap", Integer.MAX_VALUE, ScamHybridBurst.memoryFrameBudget(8160, 6120, 1, -1));
        final int six = ScamHybridBurst.memoryFrameBudget(8160, 6120, 1, 6L << 30);
        final int four = ScamHybridBurst.memoryFrameBudget(8160, 6120, 1, 4L << 30);
        assertTrue(six + " vs " + four, six > four && four > 0);
        assertEquals("50 MP: the fixed cost alone is above 60 % of 3 GB", 0, ScamHybridBurst.memoryFrameBudget(8160, 6120, 1, 3L << 30));
        assertEquals("16 MP or less: never capped", Integer.MAX_VALUE, ScamHybridBurst.memoryFrameBudget(4080, 3060, 1, 1L << 30));
        assertTrue("the worker's copy of a mosaic frame counts",
                ScamHybridBurst.memoryFrameBudget(8160, 6120, 2, 6L << 30) < six);

        // A 16.3 MP burst of ten N frames with memory for five: the five newest N frames are merged.
        final int w = 4800, h = 3400;
        final double perFrame = ScamHybridBurst.perFrameBytes(w, h, 1);
        final long available = (long) Math.ceil((ScamHybridBurst.FIXED_BYTES_PER_PIXEL * w * h + 5.5 * perFrame) / ScamHybridBurst.MEMORY_SHARE);
        assertEquals(5, ScamHybridBurst.memoryFrameBudget(w, h, 1, available));
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

    private static void memory(long available) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        info.availMem = available; info.totalMem = 24L << 30;
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Shadows.shadowOf((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(info);
    }

    @Test public void large2xBudgetCountsOutputPixelsAndRejectsImpossiblePostWorkingSet() {
        int one = ScamHybridBurst.memoryFrameBudget(8192, 6144, 1, 1, 16L << 30);
        int two = ScamHybridBurst.memoryFrameBudget(8192, 6144, 1, 2, 16L << 30);
        assertTrue(one + " vs " + two, one > two && two > 0);
        assertEquals(6L << 30, ScamHybridBurst.minimumPostBytes(16384, 12288));
        memory(1L << 30);
        IOException failure = assertThrows(IOException.class, () -> burst(
                List.of(frame(4800, 3400, ByteBuffer.allocate(4800 * 3400 * 2))), parameters(4800, 3400)));
        assertTrue(failure.getMessage(), failure.getMessage().contains("not enough available memory"));
    }

    @Test public void theWorkerWaitGrowsOnlyAbove16Megapixels() {
        assertEquals(900, ScamHybridBurst.workerTimeoutSeconds(4080, 3060));
        assertEquals(900, ScamHybridBurst.workerTimeoutSeconds(4000, 4000));
        assertEquals(2810, ScamHybridBurst.workerTimeoutSeconds(8160, 6120));
        assertEquals(3600, ScamHybridBurst.workerTimeoutSeconds(15000, 11900));
    }
}
