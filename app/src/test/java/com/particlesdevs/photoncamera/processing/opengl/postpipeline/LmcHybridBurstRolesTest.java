package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;
import android.graphics.Point;
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.capture.CameraResumeTest;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
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
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P27: one frame never costs the Hybrid shot; frames that missed their plan are merged in the role their exposure gives. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, shadows=CameraResumeTest.ShadowAllocator.class,
        instrumentedPackages="com.particlesdevs.photoncamera.util")
public class LmcHybridBurstRolesTest {
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.PhotonCamera> photon;
    private long clock = 1_000_000_000L;

    @Before public void setup() {
        // PreferenceKeys' class initialisation reads strings through PhotonCamera (as in CameraResumeTest).
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
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
        prefs.when(PreferenceKeys::hybridOutputMode).thenReturn("sensor");
        prefs.when(PreferenceKeys::hybridDownsamplerName).thenReturn("test");
        prefs.when(() -> PreferenceKeys.hybridFinalSize(anyInt(), anyInt())).thenAnswer(i -> new Point(i.getArgument(0), i.getArgument(1)));
    }
    @After public void cleanup() { prefs.close(); photon.close(); }

    private ImageFrame frame(ImageFrame.CaptureRole role, long ns, int iso) {
        ImageFrame f = mock(ImageFrame.class);
        f.width = 64; f.height = 64; f.buffer = ByteBuffer.allocate(64 * 64 * 2);
        f.timestamp = clock += 33_000_000L; f.number = (int) (clock / 33_000_000L);
        f.measuredExposure = ns; f.measuredIso = iso;
        f.noiseSlope = .00015f; f.noiseOffset = .000002f; f.sharpness = 1f;
        when(f.getCaptureRole()).thenReturn(role);
        CaptureResult metadata = mock(CaptureResult.class);
        when(f.getMatchedCaptureMetadata()).thenReturn(metadata);
        return f;
    }
    private static Parameters parameters() {
        Parameters p = new Parameters();
        p.rawSize = new Point(64, 64); p.cfaPattern = 0; p.whiteLevel = 1023; p.blackLevel = new float[]{64, 64, 64, 64};
        p.quadCfa = true; // P27 (H14): ignored by the hybrid, it measures the stream itself
        return p;
    }
    private static Object burst(List<ImageFrame> frames) throws Exception {
        Constructor<LmcHybridBurst> c = LmcHybridBurst.class.getDeclaredConstructor(List.class, Parameters.class, boolean.class);
        c.setAccessible(true);
        try { return c.newInstance(frames, parameters(), false); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Object burst, String name) throws Exception {
        Field f = LmcHybridBurst.class.getDeclaredField(name); f.setAccessible(true); return (List<T>) f.get(burst);
    }
    private static ImageFrame base(Object burst) throws Exception {
        Field f = LmcHybridBurst.class.getDeclaredField("base"); f.setAccessible(true); return (ImageFrame) f.get(burst);
    }

    @Test public void longFramesDeliveredAtNAreMergedAsN() throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) frames.add(frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 320));
        for (int i = 0; i < 5; i++) frames.add(frame(ImageFrame.CaptureRole.LONG, 10_000_000L, 320));
        Object b = burst(frames);
        List<Integer> roles = list(b, "roles");
        assertEquals(8, roles.size());
        for (int role : roles) assertEquals(1, role);
    }

    @Test public void noNFrameMakesTheClosestFrameTheBase() throws Exception {
        // X300 Ultra 2026-10-06 shape: every N request lost, L x4.29, S x0.25 and ES x0.0625 arrived.
        List<ImageFrame> frames = new ArrayList<>();
        ImageFrame l = frame(ImageFrame.CaptureRole.LONG, 42_900_000L, 100);
        frames.add(l);
        frames.add(frame(ImageFrame.CaptureRole.EXTRA_SHORT, 2_500_000L, 100));
        frames.add(frame(ImageFrame.CaptureRole.EXTRA_SHORT, 625_000L, 100));
        Object b = burst(frames);
        assertSame(l, base(b));
        assertSame(l, list(b, "frames").get(0));
        assertEquals(1, (int) list(b, "roles").get(0));
    }

    @Test public void oneFrameIsEnoughAndBadFramesAreDroppedNotFatal() throws Exception {
        assertEquals(1, list(burst(List.of(frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100))), "frames").size());
        List<ImageFrame> frames = new ArrayList<>();
        ImageFrame good = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100);
        frames.add(good);
        ImageFrame packed = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); packed.rawPayloadError = "RAW is not plain 16-bit";
        frames.add(packed);
        ImageFrame noData = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); noData.buffer = null;
        frames.add(noData);
        ImageFrame noRole = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); when(noRole.getCaptureRole()).thenReturn(null);
        frames.add(noRole);
        ImageFrame tiny = frame(ImageFrame.CaptureRole.EXTRA_SHORT, 10_000L, 50);   // x1/2000: outside the merge range
        frames.add(tiny);
        frames.add(null);
        Object b = burst(frames);
        List<ImageFrame> used = list(b, "frames");
        assertEquals(1, used.size());
        assertSame(good, used.get(0));
    }

    @Test public void missingNoiseProfileFallsBackInsteadOfFailing() throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        ImageFrame a = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 400); a.noiseSlope = Float.NaN; a.noiseOffset = Float.NaN;
        ImageFrame b = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 200);
        frames.add(a); frames.add(b);
        Object burst = burst(frames);
        List<float[]> noise = list(burst, "noise");
        assertEquals(2, noise.size());
        for (float[] n : noise) { assertTrue(n[0] > 0 && Float.isFinite(n[0])); assertTrue(n[1] >= 0 && Float.isFinite(n[1])); }
    }

    @Test public void nothingUsableStillFailsClearly() {
        ImageFrame noData = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); noData.buffer = null;
        assertThrows(java.io.IOException.class, () -> burst(List.of(noData)));
    }
}
