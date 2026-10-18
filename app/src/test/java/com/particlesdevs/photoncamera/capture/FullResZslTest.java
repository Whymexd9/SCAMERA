package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.view.Surface;

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P198 (owner, 2026-10-17: «Так же сделай так чтобы 50мп снимались в zsl и только 200мп снимались в psl»): a native 50 MP FULLRES
 * shot takes its N from the ZSL ring and only the 12 MP Hybrid plan's frames after the shutter; 200 MP and the X200 Ultra's SDK
 * route keep the serial PSL series.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FullResZslTest {
    private MockedStatic<PreferenceKeys> prefs;

    @Before public void setup() {
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.hybridSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridValue(anyString(), anyFloat())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.scamDevSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        prefs.when(PreferenceKeys::getAntibandingHz).thenReturn(0);
    }

    @After public void cleanup() { prefs.close(); }

    @Test public void fiftyMpNativeIsZslTwoHundredMpAndTheSdkRouteStayPsl() {
        assertTrue("tele 50 MP Quad", VivoSdkFullResCapture.zslRoute(VivoSdkFullRes.TELE_50));
        assertTrue("main 50 MP Quad", VivoSdkFullResCapture.zslRoute(VivoSdkFullRes.nativeProfile("3")));
        assertTrue("P194: a 50 MP hardware-remosaic Bayer row of the native route", VivoSdkFullResCapture.zslRoute(new VivoSdkFullRes.Profile(8160, 6128, 2, 1)));
        assertTrue("an OPPO 50 MP Quad mode", VivoSdkFullResCapture.zslRoute(new VivoSdkFullRes.Profile(8192, 6144, 29, 2)));
        assertFalse("200 MP Tetra: PSL", VivoSdkFullResCapture.zslRoute(VivoSdkFullRes.TELE_200));
        assertFalse("an OPPO 200 MP Tetra mode: PSL", VivoSdkFullResCapture.zslRoute(new VivoSdkFullRes.Profile(16320, 12288, 7, 4)));
        assertFalse("the X200 Ultra's SDK route (JPEG reader): PSL", VivoSdkFullResCapture.zslRoute(VivoSdkFullRes.sdkProfile("3")));
        assertFalse(VivoSdkFullResCapture.zslRoute(VivoSdkFullRes.sdkProfile("5")));
        assertFalse(VivoSdkFullResCapture.zslRoute(null));
        // the two routes never overlap: the series that stops the preview is the 200 MP PSL one
        for (VivoSdkFullRes.Profile p : Arrays.asList(VivoSdkFullRes.TELE_50, VivoSdkFullRes.TELE_200, VivoSdkFullRes.nativeProfile("3")))
            assertFalse(VivoSdkFullResCapture.zslRoute(p) && VivoSdkFullResCapture.stopsPreview(p));
    }

    @Test public void theRingFramesFollowTheMergesSettingOrTheFiftyMpCount() {
        assertEquals("0 = the merge's frame setting, as for 12 MP", 20, VivoSdkFullResCapture.zslRequested(0, 20));
        assertEquals("«Движение»'s own merge frames", 8, VivoSdkFullResCapture.zslRequested(0, 8));
        assertEquals("«Кадров в снимке 50 Мп» N > 0", 12, VivoSdkFullResCapture.zslRequested(12, 20));
        assertEquals(64, VivoSdkFullResCapture.zslRequested(64, 20));
        assertEquals("at least one", 1, VivoSdkFullResCapture.zslRequested(0, 0));
        // the memory budget of the shot: the plan's stills after the shutter first, the newest ring frames with them, at least one
        assertEquals(10, VivoSdkFullResCapture.zslNormals(10, 6, 16));
        assertEquals(6, VivoSdkFullResCapture.zslNormals(10, 6, 12));
        assertEquals(1, VivoSdkFullResCapture.zslNormals(10, 6, 5));
        assertEquals(3, VivoSdkFullResCapture.zslNormals(3, 6, 64));
        assertEquals("no ring frame: none (the PSL series)", 0, VivoSdkFullResCapture.zslNormals(0, 6, 64));
    }

    /**
     * P232 (speed-ux A2): the memory budget is computed before the N after the shutter, so it shrinks those and keeps the ring's
     * zero-lag frames (owner's X7U 20:20:35: 5 ring + 7 N asked, a 14-frame budget with 6 extras kept 1 ring frame + 7 N + 6).
     */
    @Test public void theBudgetShrinksTheFramesAfterThePressNotTheRing() {
        int[] s = VivoSdkFullResCapture.zslSplit(5, 6, 12, all -> 14);
        assertEquals("all 5 ring frames stay", 5, s[0]);
        assertEquals("3 N after the press fill the budget", 3, s[1]);
        assertEquals(14, s[0] + s[1] + 6);
        assertEquals("the budget was asked for the whole shot", 14, s[2]);
        // with A1's 1 ultrashort + 1 long and the same budget the ring stays and 7 N follow
        s = VivoSdkFullResCapture.zslSplit(5, 2, 12, all -> 14);
        assertArrayEquals(new int[]{5, 7, 14}, s);
        // enough memory: the shot asked whole
        final int[] asked = new int[1];
        s = VivoSdkFullResCapture.zslSplit(5, 2, 12, all -> { asked[0] = all; return all; });
        assertEquals("12 N + 2 extras asked", 14, asked[0]);
        assertArrayEquals(new int[]{5, 7, 14}, s);
        // the ring holds every N asked: nothing after the press
        assertArrayEquals(new int[]{12, 0, 14}, VivoSdkFullResCapture.zslSplit(12, 2, 12, all -> all));
        // a tight budget: the ring frames go only after every N after the press is gone, one ring frame always stays
        assertArrayEquals(new int[]{4, 0, 6}, VivoSdkFullResCapture.zslSplit(5, 2, 12, all -> 6));
        assertArrayEquals(new int[]{1, 0, 2}, VivoSdkFullResCapture.zslSplit(5, 2, 12, all -> 2));
        // a budget above the ask is never used beyond it
        assertArrayEquals(new int[]{5, 7, 14}, VivoSdkFullResCapture.zslSplit(5, 2, 12, all -> 64));
    }

    /** P233 (speed-ux A5): «Снято K из N кадров» counts the N of the ring and those after the shutter (owner's shot 1: 1 + 7 = 8). */
    @Test public void theToastCountsTheRingAndTheNAfterTheShutter() {
        assertEquals(8, VivoSdkFullResCapture.zslTaken(1, 7));
        int[] s = VivoSdkFullResCapture.zslSplit(5, 6, 12, all -> 14);
        assertEquals("5 ring + 3 after the press, the 6 ultrashort / long stills not N", 8, VivoSdkFullResCapture.zslTaken(s[0], s[1]));
        assertEquals(12, VivoSdkFullResCapture.zslTaken(12, 0));
    }

    /** P234 (speed-ux A4): the instant-shot UI only while the stills after the press fit the 12 MP plan (6 or fewer). */
    @Test public void aLongZslSeriesShowsTheCaptureRing() {
        assertEquals("ScamZslCaptureStarted", CaptureController.zslStartTag(2));
        assertEquals("A1's 1 ultrashort + 1 long + 4 N", "ScamZslCaptureStarted", CaptureController.zslStartTag(6));
        assertEquals("owner's shot 1: 13 stills after the press", CaptureController.ZSL_SERIES_STARTED, CaptureController.zslStartTag(13));
        assertEquals(CaptureController.ZSL_SERIES_STARTED, CaptureController.zslStartTag(7));
    }

    /** P235 (speed-ux B3): the press arena only for a burst above 16 MP not already in one memfd (moveIntoArena's case). */
    @Test public void thePressArenaIsMadeOnlyWhenTheMergeWouldMoveTheBurst() {
        assertTrue("50 MP, stills beyond the ring's free slots", CaptureController.pressArenaWanted(false, 8192L * 6144, 14));
        assertFalse("all of it in the ring's memfd already", CaptureController.pressArenaWanted(true, 8192L * 6144, 14));
        assertFalse("12 MP: the merge never moves it", CaptureController.pressArenaWanted(false, 4096L * 3072, 14));
        assertFalse("beyond an arena's 64 frames", CaptureController.pressArenaWanted(false, 8192L * 6144, 65));
        prefs.when(() -> PreferenceKeys.scamDevSwitch(eq("fullres_press_arena"), anyBoolean())).thenReturn(false);
        assertFalse("scam_dev fullres_press_arena 0: as before P235", CaptureController.pressArenaWanted(false, 8192L * 6144, 14));
    }

    /** The ring copies take at most half of the viewfinder's thread: the next one waits twice the last copy's time. */
    @Test public void aRingCopyWaitsTwiceTheLastCopysTime() {
        assertTrue("the first frame", CaptureController.ringCopyDue(1_000_000_000L, 0, 0));
        final long start = 1_000_000_000L, copy = 45_000_000L; // 45 ms: longer than a 33 ms frame
        assertFalse("the next 30 fps frame", CaptureController.ringCopyDue(start + 33_333_333L, start, copy));
        assertFalse(CaptureController.ringCopyDue(start + 66_666_666L, start, copy));
        assertTrue("the third", CaptureController.ringCopyDue(start + 100_000_000L, start, copy));
        assertTrue("a 10 ms copy: every frame", CaptureController.ringCopyDue(start + 33_333_333L, start, 10_000_000L));
    }

    @Test public void ringFramesAreHeldToTheStillsModeRule() {
        VivoSdkFullRes.Profile quad = new VivoSdkFullRes.Profile(8192, 6144, 4, 2);
        assertTrue("not reported", VivoSdkFullResCapture.modeMatches(quad, null));
        assertTrue(VivoSdkFullResCapture.modeMatches(quad, new int[]{4}));
        assertFalse(VivoSdkFullResCapture.modeMatches(quad, new int[]{0}));
        assertFalse(VivoSdkFullResCapture.modeMatches(quad, new int[0]));
        VivoSdkFullRes.Profile sdk = VivoSdkFullRes.sdkProfile("3");
        assertFalse("the SDK route must report its mode", VivoSdkFullResCapture.modeMatches(sdk, null));
        assertTrue(VivoSdkFullResCapture.modeMatches(sdk, new int[]{13}));
    }

    /** The plan after the shutter is the 12 MP Hybrid's: 3 ultrashort frames for Bento, 3 bracketed for Shasta (app defaults). */
    @Test public void bentoGetsThreeUltrashortFramesAsAt12Mp() {
        HybridPlan plan = HybridPlan.build(20_000_000L, 200, 0f, camera(6400));
        int es = 0, longs = 0;
        for (HybridPlan.Request r : plan.requests) {
            if (r.role == ImageFrame.CaptureRole.EXTRA_SHORT) es++;
            if (r.role == ImageFrame.CaptureRole.LONG) longs++;
        }
        assertEquals(3, es);
        assertEquals(3, longs);
        assertTrue("within the 50 MP ring's slots for the shot", plan.requests.size() <= ZslRing.FULLRES_SHOT_SLOTS);
    }

    @Test public void aZslShotIsTheRingsNPlusThePlansStillsWithTheNativeKeys() throws Exception {
        Shot shot = shoot(-1);
        assertEquals(shot.failures.toString(), 1, shot.completed);
        assertEquals("all six stills after the shutter", 6, shot.sequence.boundFutureCount());
        assertEquals("P222: nothing missing", Arrays.asList(), shot.degraded);
        assertEquals("the ring's 4 N and the 6 stills", 10, shot.sequence.presentCount());
        assertEquals(10, shot.exposures.size());
        for (int i = 0; i < shot.requests.size(); i++) {
            CaptureRequest request = shot.requests.get(i);
            HybridPlan.Request planned = shot.plan.requests.get(i);
            assertEquals(planned.role, ((ImageFrame.ScamCaptureTag) request.getTag()).role);
            assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF), request.get(CaptureRequest.CONTROL_AE_MODE));
            assertEquals(Long.valueOf(planned.shutterNs), request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
            assertEquals(Integer.valueOf(planned.iso), request.get(CaptureRequest.SENSOR_SENSITIVITY));
            assertArrayEquals("the native mode on every still", new int[]{4}, request.get(new CaptureRequest.Key<>(VivoQcomIsz.KEY, int[].class)));
            assertEquals("the session key copied from the viewfinder", Integer.valueOf(0), request.get(HDR));
            assertTrue("a still longer than the viewfinder's frame time gets its own",
                    request.get(CaptureRequest.SENSOR_FRAME_DURATION) >= Math.min(planned.shutterNs, PREVIEW_FRAME_NS) );
            if (planned.shutterNs > PREVIEW_FRAME_NS)
                assertEquals(Long.valueOf(planned.shutterNs), request.get(CaptureRequest.SENSOR_FRAME_DURATION));
        }
    }

    /**
     * P219 (owner, 2026-10-17: «для фулреза 1 длинный достаточно»): the FULLRES plan after the shutter is the 3 ultrashort frames
     * and one bracketed frame (the first); the shot completes with the ring's N and those 4 stills, each with its planned role.
     */
    @Test public void aFullResShotTakesThreeUltrashortFramesAndOneLong() throws Exception {
        Shot shot = shoot(-1, 0, HybridPlan.fullResLongFrames(true, 2, true));
        assertEquals(shot.failures.toString(), 1, shot.completed);
        assertEquals("3 ultrashort + 1 bracketed after the shutter", 4, shot.sequence.boundFutureCount());
        assertEquals("the ring's 4 N and the 4 stills", 8, shot.sequence.presentCount());
        int us = 0, longs = 0;
        for (CaptureRequest request : shot.requests) {
            final ImageFrame.CaptureRole role = ((ImageFrame.ScamCaptureTag) request.getTag()).role;
            if (role == ImageFrame.CaptureRole.EXTRA_SHORT) us++;
            if (role == ImageFrame.CaptureRole.LONG) longs++;
        }
        assertEquals(3, us);
        assertEquals(1, longs);
        // with a top-up the N come first, then the same 4 stills
        Shot topped = shoot(-1, 2, 1);
        assertEquals(topped.failures.toString(), 1, topped.completed);
        assertEquals(6, topped.sequence.boundFutureCount());
    }

    /** A still whose exposure fits no role of the plan leaves the merge; the ring's N keep the shot (tolerant as at 12 MP). */
    /**
     * P198: the owner's X200 Ultra (2026-10-17): a 50 MP ZSL shot with 10 N topped up after the shutter (16 stills) failed with
     * «requires 1..64 past N and 0..8 bracket requests»; it is a whole shot now.
     */
    @Test public void aTopUpOfTenNAfterTheShutterIsOneShot() throws Exception {
        Shot shot = shoot(-1, 10);
        assertEquals(shot.failures.toString(), 1, shot.completed);
        assertEquals(16, shot.sequence.boundFutureCount());
    }

    @Test public void aStillOutsideEveryRoleLeavesTheMergeAndTheShotGoesOn() throws Exception {
        Shot shot = shoot(4);
        assertEquals(shot.failures.toString(), 1, shot.completed);
        assertEquals(5, shot.sequence.boundFutureCount());
        assertEquals(9, shot.sequence.presentCount());
        assertEquals(1, shot.sequence.droppedCount());
        assertEquals(9, shot.exposures.size());
        assertEquals("P222: an exposure outside every role is no missing still", Arrays.asList(), shot.degraded);
    }

    /**
     * P222: a still that came back whole yet whose RAW the shot lost leaves the tolerant ZSL shot as before, and is reported as a
     * missing frame of the series (the in-flight count's failure memory hears it) before the shot completes.
     */
    @Test public void aStillWhoseRawTheShotLostIsReportedMissing() throws Exception {
        Shot shot = shoot(-1, 0, Integer.MAX_VALUE, 2);
        assertEquals(shot.failures.toString(), 1, shot.completed);
        assertEquals(5, shot.sequence.boundFutureCount());
        assertEquals(Arrays.asList("1 of 6 stills after the shutter missing"), shot.degraded);
        assertEquals("before the completion", 1, shot.degradedBeforeComplete);
    }

    private static final CaptureRequest.Key<Integer> HDR = new CaptureRequest.Key<>("org.codeaurora.qcamera3.sessionParameters.EnableSHDR", Integer.class);
    private static final long PREVIEW_FRAME_NS = 33_333_333L;

    private static CameraCharacteristics camera(int isoMax) {
        CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)).thenReturn(new Range<>(10_000L, 500_000_000L));
        when(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenReturn(new Range<>(50, isoMax));
        return c;
    }

    private static CaptureRequest.Builder builder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "3", null);
    }

    private static final class Shot {
        HybridPlan plan;
        final List<CaptureRequest> requests = new ArrayList<>();
        final List<String> failures = new ArrayList<>(), degraded = new ArrayList<>();
        int completed, degradedBeforeComplete;
        ScamCaptureSequence sequence;
        HashMap<Long, Double> exposures;
    }

    /** A 512 x 512 Quad profile: 4 ring frames before the shutter (cutoff 10), the plan's stills after it ({@code offRole}: x1.3). */
    private Shot shoot(int offRole) throws Exception {
        return shoot(offRole, 0);
    }

    /** {@code topUp}: N frames the ring lacked, taken after the shutter ahead of the plan's stills (P198). */
    private Shot shoot(int offRole, int topUp) throws Exception {
        return shoot(offRole, topUp, Integer.MAX_VALUE);
    }

    /** {@code longs}: the plan's bracketed frames kept (P219: 1 for FULLRES, CaptureController.takeNativeZsl). */
    private Shot shoot(int offRole, int topUp, int longs) throws Exception {
        return shoot(offRole, topUp, longs, -1);
    }

    /** {@code lostRaw}: the still whose RAW the saver loses (P222; -1: none). */
    private Shot shoot(int offRole, int topUp, int longs, int lostRaw) throws Exception {
        final Shot shot = new Shot();
        final VivoSdkFullRes.Profile profile = new VivoSdkFullRes.Profile(512, 512, 4, 2);
        // ISO capped at the N gain: the bracketed frames lengthen the shutter (40 ms > the viewfinder's 33 ms frame time)
        shot.plan = HybridPlan.build(20_000_000L, 200, 0f, camera(200)).withLongFrames(longs).withLeadingNormals(topUp);
        final int count = shot.plan.requests.size();
        assertEquals(3 + Math.min(3, longs) + topUp, count);
        CaptureRequest.Builder previewBuilder = builder();
        previewBuilder.set(HDR, 0);
        previewBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, PREVIEW_FRAME_NS);
        previewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 1);
        CaptureRequest preview = previewBuilder.build();
        CameraDevice device = mock(CameraDevice.class);
        CameraCaptureSession session = mock(CameraCaptureSession.class);
        Surface raw = mock(Surface.class);
        ImageSaver saver = mock(ImageSaver.class);
        List<CameraCaptureSession.CaptureCallback> callbacks = new ArrayList<>();
        ArrayList<ImageFrame> frames = new ArrayList<>();
        List<ImageFrame> past = new ArrayList<>();
        for (long t = 1; t <= 4; t++) {
            ImageFrame f = mock(ImageFrame.class);
            CaptureResult r = mock(CaptureResult.class);
            when(r.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(t);
            when(f.getMatchedCaptureMetadata()).thenReturn(r);
            f.timestamp = t;
            f.fromZsl = true;
            f.measuredExposure = 20_000_000L;
            f.measuredIso = 200;
            past.add(f);
        }
        frames.addAll(past);
        when(device.createCaptureRequest(anyInt())).thenAnswer(call -> builder());
        when(session.capture(any(), any(), any())).thenAnswer(call -> {
            shot.requests.add(call.getArgument(0));
            callbacks.add(call.getArgument(1));
            return shot.requests.size();
        });
        doAnswer(call -> {
            Image image = call.getArgument(0);
            ImageFrame frame = mock(ImageFrame.class);
            frame.timestamp = image.getTimestamp();
            if (frame.timestamp != 100L + lostRaw) frames.add(frame);
            image.close();
            return null;
        }).when(saver).initProcess(any(Image.class));
        when(saver.snapshotFrames()).thenAnswer(call -> new ArrayList<>(frames));
        VivoSdkFullResCapture capture = new VivoSdkFullResCapture(device, session, mock(VivoSdkRawSession.class), raw, null, saver,
                new Handler(Looper.getMainLooper()), () -> 10L, 90, 1, 10, count, profile, preview,
                Arrays.asList(HDR, CaptureRequest.SENSOR_FRAME_DURATION),
                new VivoSdkFullResCapture.Listener() {
                    public void started() { }
                    public void result(int index, TotalCaptureResult result) { }
                    public void complete(ScamCaptureSequence sequence, HashMap<Long, Double> exposures) {
                        shot.sequence = sequence;
                        shot.exposures = exposures;
                        shot.completed++;
                    }
                    public void failed(String reason) { shot.failures.add(reason); }
                    @Override public void degraded(String reason) {
                        shot.degraded.add(reason);
                        if (shot.completed == 0) shot.degradedBeforeComplete++;
                    }
                });
        capture.plan(shot.plan);
        capture.zsl(past, true);
        capture.start();
        ByteBuffer pixels = ByteBuffer.allocateDirect(512 * 512 * 2).order(ByteOrder.LITTLE_ENDIAN);
        int[] levels = {130, 280, 280, 70};
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++)
            pixels.putShort((y * 512 + x) * 2, (short) (64 + levels[(y / 2 % 2) * 2 + x / 2 % 2]));
        for (int i = 0; i < count; i++) {
            CaptureRequest request = shot.requests.get(i);
            long timestamp = 100L + i;
            callbacks.get(i).onCaptureStarted(session, request, timestamp, i);
            TotalCaptureResult result = mock(TotalCaptureResult.class);
            when(result.getRequest()).thenReturn(request);
            when(result.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
            when(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
            int iso = request.get(CaptureRequest.SENSOR_SENSITIVITY);
            when(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(i == offRole ? Math.round(iso * 0.65f) : iso);
            callbacks.get(i).onCaptureCompleted(session, request, result);
            callbacks.get(i).onCaptureSequenceCompleted(session, i + 1, i);
            Image image = mock(Image.class);
            when(image.getTimestamp()).thenReturn(timestamp);
            when(image.getFormat()).thenReturn(ImageFormat.RAW_SENSOR);
            when(image.getWidth()).thenReturn(512);
            when(image.getHeight()).thenReturn(512);
            Image.Plane plane = mock(Image.Plane.class);
            when(plane.getBuffer()).thenReturn(pixels.duplicate());
            when(plane.getRowStride()).thenReturn(1024);
            when(plane.getPixelStride()).thenReturn(2);
            when(image.getPlanes()).thenReturn(new Image.Plane[]{plane});
            capture.image(image);
        }
        if (offRole >= 0) verify(saver).removeFrames(argThat(c -> c.size() == 1));
        return shot;
    }
}
