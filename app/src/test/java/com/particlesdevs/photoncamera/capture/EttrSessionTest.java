package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.media.Image;
import android.util.Range;
import android.util.Rational;

import com.particlesdevs.photoncamera.api.CameraEventsListener;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.ViewfinderGain;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * P356 ETTR in the controller: the plain ZSL session meters it (the RAW viewfinder off), the boost ends with the session and with
 * the switch, and the «Движение» shutter ceiling holds it (bugs viewfinder-ui-ettr-needs-live-raw / capture-ettr-isp-noop,
 * viewfinder-ui-ettr-stale-boost / capture-ettr-stale-session, capture-ettr-motion-ceiling).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class EttrSessionTest {
    private CaptureController controller;
    private MockedStatic<ScameraApp> appStatic;
    private MockedStatic<PreferenceKeys> prefs;
    private MockedConstruction<CameraManager2> managers;
    private CameraCharacteristics previous;
    private boolean ettrOn = true;

    private static Object get(Object o, String name) throws Exception {
        Field f = CaptureController.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void put(Object o, String name, Object value) throws Exception {
        Field f = CaptureController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static CaptureRequest.Builder newRequestBuilder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "3", null);
    }

    @Before
    public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        SettingsManager settings = new SettingsManager(context);
        appStatic = mockStatic(ScameraApp.class);
        appStatic.when(ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(ScameraApp::getSettings).thenReturn(new Settings());
        ScameraApp app = mock(ScameraApp.class);
        when(app.getSettingsManager()).thenReturn(settings);
        appStatic.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        appStatic.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        PreferenceKeys.initialise(settings);
        prefs = mockStatic(PreferenceKeys.class, CALLS_REAL_METHODS);
        prefs.when(PreferenceKeys::isEttr).thenAnswer(i -> ettrOn);
        managers = mockConstruction(CameraManager2.class, (mock, c) -> when(mock.getCameraIdList()).thenReturn(new String[]{"3"}));
        Activity activity = mock(Activity.class);
        when(activity.getSystemService(Context.CAMERA_SERVICE)).thenReturn(mock(CameraManager.class));
        controller = new CaptureController(activity, mock(ExecutorService.class), mock(CameraEventsListener.class));
        CaptureController.isProcessing = false;
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenReturn(new Range<>(100, 6400));
        when(c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)).thenReturn(new Range<>(-24, 24));
        when(c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)).thenReturn(new Rational(1, 6));
        when(c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)).thenReturn(1023);
        when(c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)).thenReturn(new BlackLevelPattern(new int[]{64, 64, 64, 64}));
        previous = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = c;
    }

    @After
    public void cleanup() {
        CaptureController.mCameraCharacteristics = previous;
        CaptureController.mPreviewCaptureResult = null;
        CaptureController.mPreviewCaptureRequest = null;
        ViewfinderGain.setDisplayEv(0f);
        managers.close();
        prefs.close();
        appStatic.close();
    }

    /** A plain RAW16 frame whose brightest quads sit at {@code level} of white over black. */
    private static Image frame(long timestamp, float level) {
        final int w = 64, h = 64;
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        final short v = (short) Math.round(64 + level * (1023 - 64));
        for (int i = 0; i < w * h; i++) b.putShort(i * 2, v);
        Image image = mock(Image.class);
        Image.Plane plane = mock(Image.Plane.class);
        when(image.getTimestamp()).thenReturn(timestamp);
        when(image.getFormat()).thenReturn(android.graphics.ImageFormat.RAW_SENSOR);
        when(image.getWidth()).thenReturn(w);
        when(image.getHeight()).thenReturn(h);
        when(image.getPlanes()).thenReturn(new Image.Plane[]{plane});
        when(plane.getRowStride()).thenReturn(w * 2);
        when(plane.getPixelStride()).thenReturn(2);
        when(plane.getBuffer()).thenReturn(b);
        return image;
    }

    /** {@code n} plain ZSL frames at ISO {@code iso} through the plain path's ETTR meter (with their results). */
    @SuppressWarnings("unchecked")
    private void plainFrames(int n, float level, int iso) throws Exception {
        final Method plain = CaptureController.class.getDeclaredMethod("ettrPlain", Image.class);
        plain.setAccessible(true);
        final Map<Long, TotalCaptureResult> results = (Map<Long, TotalCaptureResult>) get(controller, "mHexZslResults");
        for (int i = 0; i < n; i++) {
            final long ts = 1000L + i + results.size() * 1000L;
            final TotalCaptureResult r = mock(TotalCaptureResult.class);
            when(r.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(iso);
            results.put(ts, r);
            plain.invoke(controller, frame(ts, level));
        }
    }

    private int compensation() {
        final Integer c = controller.mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
        return c == null ? 0 : c;
    }

    @Test
    public void thePlainZslSessionMetersAndTheSessionsEndClearsTheBoost() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        put(controller, "isCameraResumed", true);
        // p99.9 0.3 at the base ISO: +1.5 EV (log2 0.85 / 0.3), 9 steps of 1/6 EV, the ISP viewfinder x2^-1.5
        plainFrames(Ettr.METER_EVERY, 0.3f, 100);
        assertEquals(9, compensation());
        assertEquals(9, CaptureController.ettrIndex(controller, 0));
        assertEquals((float) Math.pow(2, -1.5), ViewfinderGain.gainFor(0L), 1e-4f);
        // the switch off where nothing meters: no stale boost on an EV change
        ettrOn = false;
        assertEquals(0, CaptureController.ettrIndex(controller, 0));
        assertEquals(-3, CaptureController.ettrIndex(controller, -3));
        // and the next plain frame clears it (the switch is no session key)
        plainFrames(1, 0.3f, 100);
        assertEquals(0, compensation());
        assertEquals(1f, ViewfinderGain.gainFor(0L), 0f);
        // on again: the camera closing ends the boost and the display gain
        ettrOn = true;
        plainFrames(Ettr.METER_EVERY, 0.3f, 100);
        assertEquals(9, CaptureController.ettrIndex(controller, 0));
        controller.closeCamera();
        assertEquals(0, CaptureController.ettrIndex(controller, 0));
        assertEquals(1f, ViewfinderGain.gainFor(0L), 0f);
    }

    @Test
    public void theBoostStaysWithinTheIsoHeadroom() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        put(controller, "isCameraResumed", true);
        // ISO 300 at base 100: 0.415 EV below the 4x ceiling - the boost stops there (not +1.5 EV, then 0, then +1.5 ...)
        plainFrames(Ettr.METER_EVERY, 0.3f, 300);
        assertEquals(Math.round(0.415f * 6), compensation());
    }

    @Test
    public void heldUnderTheMotionCeiling() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        put(controller, "isCameraResumed", true);
        plainFrames(Ettr.METER_EVERY, 0.3f, 100);
        assertEquals(9, compensation());
        final Object params = get(controller, "paramController");
        final Field motion = params.getClass().getDeclaredField("motionActive");
        motion.setAccessible(true);
        motion.setBoolean(params, true);
        plainFrames(1, 0.3f, 100);
        assertEquals("the AE-off ceiling ignores the compensation: no boost", 0, compensation());
        assertEquals(0, CaptureController.ettrIndex(controller, 0));
        assertEquals(1f, ViewfinderGain.gainFor(0L), 0f);
        plainFrames(3 * Ettr.METER_EVERY, 0.3f, 100);
        assertEquals("it does not climb meanwhile", 0, compensation());
    }

    @Test
    public void aBadPayloadIsNotMetered() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        put(controller, "isCameraResumed", true);
        put(controller, "mPayloadBad", true); // packed 10-bit as RAW16 (RawPayloadCheck): no plain samples
        plainFrames(2 * Ettr.METER_EVERY, 0.3f, 100);
        assertEquals(0, compensation());
        assertEquals(0, CaptureController.ettrIndex(controller, 0));
    }

    /**
     * The plain ZSL branch of the RAW listener meters before the ring copy closes the Image, not in a running merge's plain session;
     * every session starts from 0 (a missing call is -1: each found after its anchor).
     */
    @Test
    public void wiring() throws Exception {
        final String s = new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java").toPath()), StandardCharsets.UTF_8);
        final int teacher = s.indexOf("plainTeacher(img, null); // P212f");
        assertTrue(teacher > 0);
        final int meter = s.indexOf("ettrPlain(img);", teacher);
        final int merge = s.indexOf("if (mClosedForProcessing) { img.close(); return; }", teacher);
        assertTrue(merge > teacher && meter > merge
                && meter < s.indexOf("mZslRingBuffer.addLast(ringFrameLocked(img));", teacher));
        final int session = s.indexOf("LiveRawFrame.setEnabled(false); // invalidate the previous session");
        final int sessionReset = s.indexOf("resetEttr();", session);
        assertTrue(session > 0 && sessionReset > session
                && sessionReset < s.indexOf("LiveRawFrame.setEnabled(mLiveRawSession);", session));
        final int close = s.indexOf("public void closeCamera() {");
        final int closeReset = s.indexOf("resetEttr();", close);
        assertTrue(close > 0 && closeReset > close && closeReset < s.indexOf("mCameraOpenCloseLock.acquire();", close));
    }
}
