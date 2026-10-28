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
import com.particlesdevs.photoncamera.manual.ParamController;
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
 * viewfinder-ui-ettr-stale-boost / capture-ettr-stale-session, capture-ettr-motion-ceiling). P377: the boost is the shutter.
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

    /** {@code n} plain ZSL frames at ISO {@code iso} through the plain path's ETTR meter (with their results), 4 ms of the AE. */
    private void plainFrames(int n, float level, int iso) throws Exception {
        plainFrames(n, level, iso, 4_000_000L, true);
    }

    /** P377: the frames' shutter {@code exposureNs}, {@code aeOn} whether the camera's AE exposed them. */
    @SuppressWarnings("unchecked")
    private void plainFrames(int n, float level, int iso, long exposureNs, boolean aeOn) throws Exception {
        final Method plain = CaptureController.class.getDeclaredMethod("ettrPlain", Image.class);
        plain.setAccessible(true);
        final Map<Long, TotalCaptureResult> results = (Map<Long, TotalCaptureResult>) get(controller, "mHexZslResults");
        for (int i = 0; i < n; i++) {
            final long ts = 1000L + i + results.size() * 1000L;
            final TotalCaptureResult r = mock(TotalCaptureResult.class);
            when(r.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(iso);
            when(r.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(exposureNs);
            when(r.get(CaptureResult.CONTROL_AE_MODE)).thenReturn(aeOn ? CaptureResult.CONTROL_AE_MODE_ON : CaptureResult.CONTROL_AE_MODE_OFF);
            // the camera's AE settled (its frames are metered at once); AE off: still frames decide (ETTR_STILL_FRAMES)
            when(r.get(CaptureResult.CONTROL_AE_STATE)).thenReturn(aeOn ? CaptureResult.CONTROL_AE_STATE_CONVERGED : null);
            results.put(ts, r);
            plain.invoke(controller, frame(ts, level));
        }
    }

    private ParamController params() throws Exception {
        return (ParamController) get(controller, "paramController");
    }

    private Integer aeMode() {
        return controller.mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_MODE);
    }

    private Long requestShutter() {
        return controller.mPreviewRequestBuilder.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
    }

    private String note() throws Exception {
        final Method m = CaptureController.class.getDeclaredMethod("ettrNote");
        m.setAccessible(true);
        return (String) m.invoke(controller);
    }

    private int compensation() {
        final Integer c = controller.mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
        return c == null ? 0 : c;
    }

    @Test
    public void thePlainZslSessionMetersAndTheSessionsEndClearsTheBoost() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        controller.mPreviewIso = 100;
        controller.mPreviewExposureTime = 4_000_000L;
        put(controller, "isCameraResumed", true);
        // the tail at 0.3 at the AE's 4 ms: +1.5 EV (log2 0.85 / 0.3), 9 steps of 1/6 EV, the ISP viewfinder x2^-1.5 - P377 as
        // light: shutter priority at 4 ms x 2^1.5 (the software loop here: AE off at it)
        plainFrames(Ettr.METER_EVERY, 0.3f, 100);
        assertEquals(9, compensation());
        assertEquals(9, CaptureController.ettrIndex(controller, 0));
        assertEquals((float) Math.pow(2, -1.5), ViewfinderGain.gainFor(0L), 1e-4f);
        final long boosted = Math.round(4_000_000L * Math.pow(0.85 / 0.3, 1.0));
        assertEquals(boosted, params().ettrShutterNs(), boosted * 2e-3);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF), aeMode());
        assertEquals(boosted, requestShutter(), boosted * 2e-3);
        assertEquals(" | ETTR +1.5", note());
        // the switch off where nothing meters: no stale boost on an EV change
        ettrOn = false;
        assertEquals(0, CaptureController.ettrIndex(controller, 0));
        assertEquals(-3, CaptureController.ettrIndex(controller, -3));
        assertEquals(null, note());
        // and the next plain frame clears it (the switch is no session key): the AE alone again
        plainFrames(1, 0.3f, 100);
        assertEquals(0, compensation());
        assertEquals(1f, ViewfinderGain.gainFor(0L), 0f);
        assertEquals(0L, params().ettrShutterNs());
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON), aeMode());
        // on again: the camera closing ends the boost, its shutter and the display gain
        ettrOn = true;
        plainFrames(Ettr.METER_EVERY, 0.3f, 100);
        assertEquals(9, CaptureController.ettrIndex(controller, 0));
        controller.closeCamera();
        assertEquals(0, CaptureController.ettrIndex(controller, 0));
        assertEquals(1f, ViewfinderGain.gainFor(0L), 0f);
        assertEquals(0L, params().ettrShutterNs());
    }

    /**
     * P377: the shutter grows at most to one ring frame of the viewfinder's AE range - review: a top other than 30 (the default) so
     * the range is what decides: 60 fps, 16.67 ms; 11 ms gets +0.6 EV, not +1.5.
     */
    @Test
    public void theBoostStaysWithinOneRingFrame() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        controller.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 60));
        put(controller, "isCameraResumed", true);
        plainFrames(Ettr.METER_EVERY, 0.3f, 300, 11_000_000L, true);
        assertEquals(Math.round(Math.log(16_666_666.0 / 11_000_000) / Math.log(2) * 6), compensation());
        assertTrue(requestShutter() + " ms", requestShutter() <= 16_666_666L && requestShutter() > 16_400_000L);
        // at the ring frame already: no room, the AE alone
        controller.closeCamera();
        controller.mPreviewRequestBuilder = newRequestBuilder();
        controller.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 60));
        plainFrames(2 * Ettr.METER_EVERY, 0.3f, 3200, 16_666_666L, true);
        assertEquals(0, compensation());
        assertEquals(0L, params().ettrShutterNs());
        assertEquals(" | ETTR ✗ room", note());
    }

    /**
     * P377 (review): a ceiling that came down under the boost (here the viewfinder's range: 66 fps, 15.2 ms) is a hard limit even
     * when the wish moves less than the minimal step: +1 at 16.67 ms over it - the boost comes down to what fits (here none: half
     * periods of the mains leave no whole step under 15.2 ms).
     */
    @Test
    public void aLowerCeilingIsEnforced() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        controller.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(20, 30));
        controller.mPreviewIso = 1883;
        controller.mPreviewExposureTime = 8_333_326L;
        put(controller, "isCameraResumed", true);
        plainFrames(Ettr.METER_EVERY, 0.35f, 1883, 8_333_326L, true);
        assertEquals(16_666_666L, params().ettrShutterNs());
        controller.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(20, 66));
        plainFrames(CaptureController.ETTR_STILL_FRAMES + Ettr.SETTLE_FRAMES + Ettr.METER_EVERY, 0.70f, 1883, 16_666_666L, false);
        assertTrue(params().ettrShutterNs() + " ns", params().ettrShutterNs() <= 1_000_000_000L / 66);
        assertEquals(0, compensation());
    }

    /**
     * P377, the owner's log (X200 Ultra 2026-10-28 13:29, «ETTR вообще не работает»): the AE at 8.33 ms and ISO 1883 indoors.
     * Before, the compensation went into the ISO (the HAL kept 1/120 s) or none at all; now 16.67 ms, whole 60 Hz half periods,
     * +1 EV of light, the stamp says so. A frame of the boosted request (AE off) does not move the AE's reference.
     */
    @Test
    public void theOwnersIndoorSceneGetsTheLight() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        controller.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(20, 30));
        controller.mPreviewIso = 1883;
        controller.mPreviewExposureTime = 8_333_326L;
        put(controller, "isCameraResumed", true);
        plainFrames(Ettr.METER_EVERY, 0.35f, 1883, 8_333_326L, true);
        assertEquals(16_666_666L, params().ettrShutterNs());
        assertEquals(Long.valueOf(16_666_666L), requestShutter());
        assertEquals(6, compensation());
        assertEquals(" | ETTR +1.0", note());
        // the boosted frames (the tail doubled) keep it: inside the minimal step, the reference stays the AE's 8.33 ms
        plainFrames(CaptureController.ETTR_STILL_FRAMES + Ettr.SETTLE_FRAMES + Ettr.METER_EVERY, 0.70f, 1883, 16_666_666L, false);
        assertEquals(16_666_666L, params().ettrShutterNs());
        assertEquals(6, compensation());
        // review: the loop's frames on their way (the same exposure at the longer shutter, the tail as before, the ISO moving) are
        // not metered - against the boost asked for they wanted 2 EV
        for (int iso : new int[]{941, 1100, 1300, 1494, 1650, 1800, 941, 1100, 1300, 1494, 1650, 1800, 941, 1100, 1300, 1494})
            plainFrames(1, 0.35f, iso, 16_666_666L, false);
        assertEquals(16_666_666L, params().ettrShutterNs());
        assertEquals(6, compensation());
        // review: a scene a stop darker, the priority AE at twice the ISO, the tail held: the boost stays +1 (not +2, clipped)
        plainFrames(2 * Ettr.METER_EVERY, 0.70f, 3766, 16_666_666L, true);
        assertEquals(16_666_666L, params().ettrShutterNs());
        assertEquals(6, compensation());
        // a lamp switched on (the tail clipped): from the AE again
        plainFrames(Ettr.METER_EVERY, 1f, 1883, 16_666_666L, true);
        assertEquals(0L, params().ettrShutterNs());
        assertEquals(0, compensation());
        assertEquals(" | ETTR ✗ clip", note());
    }

    /** P377 (review): a tap or the 3A reset writes AE on over the loop's AE-off request: ETTR starts over (no gain-only boost). */
    @Test
    public void aeOnOverTheLoopStartsOver() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        controller.mPreviewIso = 1883;
        controller.mPreviewExposureTime = 8_333_326L;
        put(controller, "isCameraResumed", true);
        plainFrames(Ettr.METER_EVERY, 0.35f, 1883, 8_333_326L, true);
        assertEquals(16_666_666L, params().ettrShutterNs());
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF), aeMode());
        controller.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); // TouchFocus
        plainFrames(1, 0.35f, 1883, 16_666_666L, true);
        assertEquals(0L, params().ettrShutterNs());
        assertEquals(0, compensation());
        assertEquals(0, CaptureController.ettrIndex(controller, 0));
    }

    /**
     * P377: without the camera's priority AE the viewfinder loop holds the ETTR shutter: it learns the brightness at the AE's
     * exposure (the shutter took the boost at the same ISO x shutter), then brightens by the boost - the ISO back toward the AE's,
     * not the RAW kept at the old brightness with the boost climbing to its limit.
     */
    @Test
    public void theSoftwareLoopBrightensByTheBoost() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        controller.mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(20, 30));
        controller.mPreviewIso = 1883;
        controller.mPreviewExposureTime = 8_333_326L;
        put(controller, "isCameraResumed", true);
        plainFrames(Ettr.METER_EVERY, 0.35f, 1883, 8_333_326L, true);
        assertEquals(16_666_666L, params().ettrShutterNs());
        final int start = controller.mPreviewRequestBuilder.get(CaptureRequest.SENSOR_SENSITIVITY);
        assertEquals("the same exposure at the longer shutter", 1883 / 2.0, start, 2);
        final Field applied = ParamController.class.getDeclaredField("appliedAtNs");
        applied.setAccessible(true);
        final CaptureResult r = mock(CaptureResult.class);
        int iso = start;
        for (int i = 0; i < 8; i++) {
            applied.setLong(params(), 0L);
            // the viewfinder's display-encoded brightness of the exposure in force (0.4 at the AE's)
            com.particlesdevs.photoncamera.manual.SceneLuma.publish(0.4 * Math.pow((double) iso / start, 1 / 2.2));
            params().onPreviewResult(r);
            iso = controller.mPreviewRequestBuilder.get(CaptureRequest.SENSOR_SENSITIVITY);
            if (i == 0) assertEquals("the first measurement learns the target", start, iso);
        }
        assertEquals("the boost's light at the AE's gain", 1883, iso, 1883 * 0.1);
        assertEquals(Long.valueOf(16_666_666L), requestShutter());
        // review: the RAW viewfinder's meter takes over (the target learned again, at the boosted exposure): the boost not twice
        final int settled = iso;
        for (int i = 0; i < 6; i++) {
            applied.setLong(params(), 0L);
            com.particlesdevs.photoncamera.manual.SceneLuma.publishRaw(0.3 * Math.pow((double) iso / settled, 1 / 2.2));
            params().onPreviewResult(r);
            iso = controller.mPreviewRequestBuilder.get(CaptureRequest.SENSOR_SENSITIVITY);
        }
        assertEquals("the exposure stays where it was", settled, iso, settled * 0.05);
    }

    /**
     * P377 (review): the AE at the lowest ISO (100 here): the loop's start cannot lower it by the boost, so its first frames are
     * already brighter by it; the target learned against the AE's own exposure - the loop holds there, not +1 again on top (4x the
     * AE, the tail clipped, back to the AE, again: pumping).
     */
    @Test
    public void theLoopAtTheLowestIsoDoesNotAddTheBoostTwice() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        controller.mPreviewIso = 100;
        controller.mPreviewExposureTime = 4_000_000L;
        put(controller, "isCameraResumed", true);
        plainFrames(Ettr.METER_EVERY, 0.425f, 100, 4_000_000L, true);
        assertEquals(8_000_000L, params().ettrShutterNs(), 8_000);
        final int start = controller.mPreviewRequestBuilder.get(CaptureRequest.SENSOR_SENSITIVITY);
        assertEquals("ISO 50 asked, the lowest is 100: already 2x the AE", 100, start);
        final Field applied = ParamController.class.getDeclaredField("appliedAtNs");
        applied.setAccessible(true);
        final CaptureResult r = mock(CaptureResult.class);
        int iso = start;
        for (int i = 0; i < 8; i++) {
            applied.setLong(params(), 0L);
            com.particlesdevs.photoncamera.manual.SceneLuma.publish(0.5 * Math.pow((double) iso / start, 1 / 2.2));
            params().onPreviewResult(r);
            iso = controller.mPreviewRequestBuilder.get(CaptureRequest.SENSOR_SENSITIVITY);
        }
        assertEquals("the boost the start already gave is not added again", start, iso, start * 0.1);
    }

    /**
     * P377 (owner: «что в ручных, что в авто»): with the ISO set by hand the panel's free shutter takes the boost through the
     * compensation (no ETTR shutter of its own); a hand-set shutter holds it - the light is the shutter, a boost would only raise
     * the ISO - and says so on the screen once.
     */
    @Test
    public void manualIsoTakesTheBoostAHandSetShutterHoldsIt() throws Exception {
        controller.mPreviewRequestBuilder = newRequestBuilder();
        put(controller, "isCameraResumed", true);
        final com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel model =
                new com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel();
        model.reset();
        model.addObserver(params());
        model.setCurrentISOValue(400);
        // metered once the panel's free shutter holds still (ETTR_STILL_FRAMES), not on its way (review: a moving one first)
        for (int i = 0; i < 3 * Ettr.METER_EVERY; i++) plainFrames(1, 0.3f, 400, 8_000_000L + (i % 2) * 1_000_000L, false);
        assertEquals("a shutter on its way is not metered", 0, compensation());
        plainFrames(Ettr.METER_EVERY, 0.3f, 400, 8_000_000L, false);
        assertEquals("not still long enough yet", 0, compensation());
        plainFrames(Ettr.METER_EVERY, 0.3f, 400, 8_000_000L, false);
        assertEquals(9, compensation());
        assertEquals("no ETTR shutter: the panel's priority owns the exposure", 0L, params().ettrShutterNs());
        assertEquals(Integer.valueOf(400), controller.mPreviewRequestBuilder.get(CaptureRequest.SENSOR_SENSITIVITY));
        assertEquals(" | ETTR +1.5", note());
        // the shutter by hand too: held, and the request's compensation is the panel's (both by hand: 0)
        model.setCurrentExposureValue(1_000_000_000L / 60);
        plainFrames(1, 0.3f, 400, 16_666_666L, false);
        assertEquals(0, compensation());
        assertEquals(0, CaptureController.ettrIndex(controller, 0));
        assertEquals(" | ETTR ✗ man", note());
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
        assertEquals("and no ETTR shutter under it", 0L, params().ettrShutterNs());
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
