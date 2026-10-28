package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import com.particlesdevs.photoncamera.util.Log;

import androidx.annotation.NonNull;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private int[] hTex;
    private final FloatBuffer pVertex;
    private final FloatBuffer pTexCoord;
    private final float[] mTexRotateMatrix = new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

    private SurfaceTexture mSTexture;

    private boolean mGLInit = false;
    private boolean mUpdateST = false;
    private Boolean mLastRawActive;
    private volatile boolean mMirrorPreview;

    private final GLPreview mView;
    /** Measures the live stream's brightness for the manual panel's software shutter / ISO priority (SceneLuma). */
    private final LiveSceneMeter mSceneMeter = new LiveSceneMeter();
    /** Develops the preview RAW stream; replaces the ISP image when it has a frame. */
    private final LiveRawRenderer mRawRenderer = new LiveRawRenderer();
    private final LiveLensFilter mLensFilter = new LiveLensFilter();
    /**
     * P300 (owner, 2026-10-20: «исправь лайв превью для ISZ, isp поток для isz fullres отключаем»): the ISZ and FULLRES sessions
     * have no ISP stream, so their viewfinder is the developed RAW, which had no lens filter. With a filter chosen the RAW frame
     * is drawn off screen and goes through the ISP viewfinder's own filter shaders as a 2D texture (its LUT is in the RAW frame
     * already, rawdevelop_fs: none here; so the filter comes after the LUT there, before it on the ISP path).
     */
    private final LiveLensFilter mRawLensFilter = new LiveLensFilter(false);
    /** P301: the photo's WHY tone on the developed-RAW viewfinder (the RAW frame's linear values through the photo's shaders). */
    private final com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone mWhyTone =
            new com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone();
    private boolean mWhyLogged;
    /**
     * P312: the developed-RAW viewfinder's programs linked in this context (with the WHY tone's: mRawProgramsWhy, P314 the
     * LiveWhyTone mode whose set was asked for, 0 none); asked at.
     */
    private boolean mRawProgramsReady;
    private int mRawProgramsWhy;
    private long mRawProgramsAsked;
    /** P312: when an ISP frame was latched last (a RAW-only session - ISZ, FULLRES - never latches one). */
    private long mIspLatchNs;
    /** P312: after this long the draws stop waiting for the background compile (a stuck one: they compile as before P312). */
    static final long RAW_PROGRAMS_WAIT_MS = 8000;
    private int hProgram2d, vPosition2d, enablePeak2d, uGain2d, mirror2d, resolution2d;
    private int rawFbo, rawTex, rawW, rawH;
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static final String VS_2D = "in vec2 vPosition;\nout vec2 texCoord;\nvoid main() {\n    texCoord = vPosition * 0.5 + 0.5;\n"
            + "    gl_Position = vec4(vPosition, 0.0, 1.0);\n}\n";
    /** P42: downscaled copies of the drawn frame for the tracking autofocus (only while it asks for them). */
    private final SubjectFrameGrabber mSubjectGrabber = new SubjectFrameGrabber();
    private ManualModeConsole mManualModeConsole;

    public void setManualModeConsole(ManualModeConsole console) {
        this.mManualModeConsole = console;
    }

    MainRenderer(GLPreview view) {
        mView = view;
        pVertex = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] vtmp = { 1.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f };
        pVertex.put(vtmp);
        pVertex.position(0);
        pTexCoord = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] ttmp = { 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f };
        pTexCoord.put(ttmp);
        pTexCoord.position(0);
        setOrientation(180);
    }

    public void onDrawFrame(GL10 unused) {
        if (!mGLInit)
            return;
        final long t0 = System.nanoTime();
        drawFrame();
        VfDrawMeter.frame(System.nanoTime() - t0); // P57
    }

    /** P111: sensor time of the camera frame the viewfinder showed last (the «Движение» shot's moment at the touch). */
    private static volatile long sShownTimestamp;

    public static long shownTimestamp() {
        return sShownTimestamp;
    }

    /**
     * P345: developed RAW frames drawn so far (GL thread the only writer): a RAW-only session (ISZ, FULLRES without an ISP stream)
     * never latches an ISP frame, so its viewfinder's first frame ends the frozen picture by this count, not shownTimestamp.
     */
    private static volatile long sRawDraws;

    public static long rawDraws() {
        return sRawDraws;
    }

    /** P319: the key of the tone in rawFbo (Long.MIN_VALUE: none), when it was drawn, and the redraws that reused it. */
    private long mToneKey = Long.MIN_VALUE, mToneAtNs, mToneReused;
    /** P319: the VF_RAW line every 2 s while the tone draws: new toned frames, reused redraws, frames the hand-off dropped. */
    private long mVfRawAtNs, mVfRawToned, mVfRawReused, mVfRawDropped0;
    /** P319: a tone older than this is drawn again even for the same frame (a stalled stream falls back as before). */
    static final long TONE_REUSE_NS = 300_000_000L;
    /**
     * P365: the GPU passes of the last tone for VF_RAW (1 the fused pass straight to the screen, 2 the fused pass and the filter /
     * peaking pass, 3 P314's linear frame, tone and filter pass) and the way the last tone was logged (the same numbers, 0 none).
     */
    private int mVfRawPasses, mFusedLogged;
    /**
     * P365 step 7 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»;
     * scam_dev vf_series_hold): while the shot's series is taken (CaptureController.seriesInFlight: the RAW frames stop from the
     * press until its frames are in, 0.38-0.40 s on the OPPO) the ISP frames are only latched for up to this long after the last
     * RAW draw - the screen keeps the last RAW frame instead of the ISP look appearing after RAW_SHOWN_NS and going again (a draw
     * asked for meanwhile draws that RAW frame, up to this old). After it, or with the switch off, as before.
     */
    static final long SERIES_HOLD_NS = 2_000_000_000L;
    /** P365: the RAW frame of the fused pass (LiveWhyTone.drawFused) with this renderer's vertices, rotation and mirror. */
    private final com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone.RawSource mFusedRaw =
            new com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone.RawSource() {
                @Override
                public boolean bind(int program) {
                    return mRawRenderer.bindFrame(program, mTexRotateMatrix, mMirrorPreview);
                }

                @Override
                public boolean fresh() {
                    return mRawRenderer.boundFresh();
                }

                @Override
                public com.particlesdevs.photoncamera.processing.LiveWhyMeter.View view() {
                    return mRawRenderer.lastView();
                }

                @Override
                public void draw() {
                    mRawRenderer.drawBound(pVertex, pTexCoord);
                }
            };

    /**
     * P365: whether the fused tone goes straight to the screen in one pass - the tone at the viewport's size (a 1x Bayer frame; a
     * mosaic's colour grid is smaller: drawn into the tone target, the filter pass scales it up), no lens filter and no focus
     * peaking (their pass reads the tone target).
     */
    static boolean directPass(int toneW, int toneH, int viewportW, int viewportH, boolean lensFilter, int peaking) {
        return toneW == viewportW && toneH == viewportH && !lensFilter && peaking == 0;
    }

    /** P365 step 7: whether the ISP frames wait (vf_series_hold on, a series in flight, a RAW frame drawn less than SERIES_HOLD_NS ago). */
    static boolean holdsSeries(boolean on, boolean inFlight, long sinceRawNs) {
        return on && inFlight && sinceRawNs >= 0 && sinceRawNs < SERIES_HOLD_NS;
    }

    private boolean seriesHold(long now) {
        final long since = now - mRawShownNs;
        // the cheap tests first: the ISP frames' thread asks this ~30 times a second
        return since >= 0 && since < SERIES_HOLD_NS && CaptureController.seriesInFlight()
                && holdsSeries(com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("vf_series_hold", true), true, since);
    }

    /**
     * P319: the RAW frame (session, version), the tone target and the geometry the WHY tone in rawFbo was drawn for; P369: and the
     * look's table (a film's scene table or the chosen LUT once it is loaded, the strengths: LiveWhyTone.lookKey).
     */
    private long toneKey() {
        long k = ((long) com.particlesdevs.photoncamera.processing.LiveRawFrame.currentSession() << 32)
                ^ (com.particlesdevs.photoncamera.processing.LiveRawFrame.getVersion() & 0xffffffffL);
        k = 31 * k + rawW;
        k = 31 * k + rawH;
        k = 31 * k + rawFbo;
        k = 31 * k + java.util.Arrays.hashCode(mTexRotateMatrix);
        k = 31 * k + mWhyTone.lookKey();
        return 31 * k + (mMirrorPreview ? 1 : 0);
    }

    private void drawFrame() {
        // GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        latchIsp();
        applyPendingTransform();
        // Developed RAW takes over the viewfinder when a frame is available, so
        // what is framed is what the pipeline produces rather than the ISP's own
        // rendering. Falls through to the ISP path whenever a frame is missing,
        // the shaders failed, or nothing asks for it (the setting off and a session
        // with its ISP stream and no mosaic: CaptureController forces the RAW
        // viewfinder without them) - the viewfinder must never go blank because of this.
        // P13: a colour-block mosaic stream (sensor mode without remosaic) has a purple ISP preview: developed RAW then too
        final boolean rawLook =
                com.particlesdevs.photoncamera.settings.PreferenceKeys.isLiveViewfinderRawEnabled()
                        || com.particlesdevs.photoncamera.processing.LiveRawFrame.isEnabled()
                        || com.particlesdevs.photoncamera.processing.LiveRawFrame.isMosaicPreview();
        // P301 (owner, 2026-10-20: «Результат должен совпадать с итоговым фото»): the photo's WHY tone - the RAW frame's linear
        // values through the photo's shaders into the off-screen target, then the filter / peaking pass puts it on screen
        boolean rawActive = false;
        // P312: the developed RAW only once its programs are linked (from their driver binaries; a missing one is compiled in the
        // background, or here one per frame): until then the ISP frame stays - the viewfinder no longer stops for a compile, and
        // goes from the ISP frame straight to the WHY tone
        final boolean rawReady = rawLook && rawProgramsReady();
        // a RAW-only session has no ISP frame to keep: its plain RAW look as soon as rawdevelop is linked, the WHY tone after
        final boolean rawPlain = rawReady || (rawLook && mRawRenderer.ready() && System.nanoTime() - mIspLatchNs > 300_000_000L);
        // P365 step 7: while a series is held the RAW frame drawn last stays drawable for SERIES_HOLD_NS (MAX_AGE_NS otherwise)
        mRawRenderer.setMaxAge(seriesHold(System.nanoTime()) ? SERIES_HOLD_NS : LiveRawRenderer.MAX_AGE_NS);
        // P314: the tone at the frame's colour resolution (LiveRawRenderer.toneSize), the filter / peaking pass scales it up
        final boolean whyOn = rawReady && hProgram2d != 0
                && com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone.wanted() && !mWhyTone.failed();
        // P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»; scam_dev
        // vf_fused): vf_why 1 in one fused pass from the RAW texture - straight to the screen when the tone is the viewport's size
        // and nothing reads it afterwards (no lens filter, no peaking: one pass, no target of its own), else into the tone target
        // and the filter / peaking pass (two). Failing, it is off for the context and P314's passes draw once their programs are
        // built (the viewfinder's own look or the ISP picture meanwhile, as at a start).
        final int[] tone = whyOn ? toneSizeNow() : null;
        final boolean fused = whyOn && mWhyTone.fused();
        final int peaking = getPeakEnabled();
        final boolean direct = fused && directPass(tone[0], tone[1], mViewport[2], mViewport[3], mRawLensFilter.active(), peaking);
        final boolean why = whyOn && (direct || rawTarget(tone[0], tone[1]));
        boolean fusedFailedNow = false;
        if (why) {
            // P319 (user report: «Лайв-вью работает гораздо тормознее»): an ISP frame wakes this thread too (1x sessions keep the
            // ISP stream), and each such draw toned the same RAW frame again - up to half the draws. The tone in rawFbo is reused
            // while the RAW frame, the target and the geometry are the same and it is fresh; only the filter / peaking pass runs.
            // A setting change shows with the next RAW frame. scam_dev vf_tone_reuse 0: every draw tones (as before).
            // P365: the direct pass has no tone to reuse: a redraw of the same frame draws it again from its texture (no upload,
            // no AE), counted as reused
            final long now = System.nanoTime();
            if (direct) {
                rawActive = mWhyTone.drawFused(mFusedRaw, 0, mViewport[0], mViewport[1], mViewport[2], mViewport[3]);
                mToneKey = Long.MIN_VALUE;
                if (rawActive) {
                    mVfRawPasses = 1;
                    if (mRawRenderer.boundFresh()) mVfRawToned++;
                    else mVfRawReused++;
                    releaseRawTarget();
                }
            } else {
                final long key = toneKey();
                if (key == mToneKey && now - mToneAtNs < TONE_REUSE_NS
                        && com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("vf_tone_reuse", true)) {
                    rawActive = true;
                    mToneReused++;
                    mVfRawReused++;
                } else {
                    if (fused) {
                        rawActive = mWhyTone.drawFused(mFusedRaw, rawFbo, 0, 0, rawW, rawH);
                        if (rawActive) mVfRawPasses = 2;
                    } else {
                        rawActive = mWhyTone.draw(() -> mRawRenderer.drawLinear(pVertex, pTexCoord, mTexRotateMatrix, mMirrorPreview),
                                mRawRenderer::lastView, rawFbo, rawW, rawH);
                        if (rawActive) mVfRawPasses = 3;
                    }
                    mToneKey = rawActive ? key : Long.MIN_VALUE;
                    mToneAtNs = now;
                    if (rawActive) mVfRawToned++;
                }
                if (rawActive) drawRawFiltered(peaking);
            }
            fusedFailedNow = fused && mWhyTone.fusedFailed();
            if (fusedFailedNow) {
                // P365: P314's programs are asked for (their background compile), this frame takes the fall-back below
                mRawProgramsReady = false;
                mFusedLogged = 0;
            }
            if (now - mVfRawAtNs >= 2_000_000_000L) {
                final long dropped = com.particlesdevs.photoncamera.processing.LiveRawFrame.droppedFrames();
                if (mVfRawAtNs != 0) {
                    final double s = (now - mVfRawAtNs) / 1e9;
                    // P365: the passes of the tone and the camera thread's copies per frame (LiveRawFrame.copyStats)
                    Log.i("VF_RAW", String.format(java.util.Locale.ROOT, "toned %.1f/s reused %.1f/s dropped %.1f/s tone %dx%d passes=%d %s",
                            mVfRawToned / s, mVfRawReused / s, (dropped - mVfRawDropped0) / s, tone[0], tone[1], mVfRawPasses,
                            com.particlesdevs.photoncamera.processing.LiveRawFrame.copyStats()));
                }
                mVfRawAtNs = now;
                mVfRawToned = mVfRawReused = 0;
                mVfRawDropped0 = dropped;
            }
            if (rawActive && mFusedLogged != mVfRawPasses) {
                mFusedLogged = mVfRawPasses;
                Log.d("MainRenderer", "viewfinder: " + (mVfRawPasses == 1 ? "fused WHY tone on (direct)"
                        : mVfRawPasses == 2 ? "fused WHY tone on (2 passes: tone " + rawW + "x" + rawH + ", filter / peaking)"
                        : "the WHY tone in P314's passes" + (com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone.fusedSwitch()
                        && com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone.mode()
                        == com.particlesdevs.photoncamera.processing.opengl.postpipeline.LiveWhyTone.POINT ? " (the fused pass failed)" : "")));
            }
            if (!mWhyLogged || !rawActive) {
                if (!mWhyLogged) Log.d("MainRenderer", "viewfinder: the photo's WHY tone " + (rawActive ? "on" : "not drawn ("
                        + (mWhyTone.failed() ? "failed" : fusedFailedNow ? "the fused pass failed" : "no frame") + ")"));
                mWhyLogged = rawActive;
            }
        }
        // P300: with a lens filter the RAW frame is drawn off screen first, the filter pass puts it on screen
        // P314: not after the WHY tone found no frame (the plain draw has none either; its viewport-sized target would be made
        // anew, and the tone's again with the next frame) - only when the tone failed (P365: or its fused pass, this frame)
        if (!rawActive && (!why || mWhyTone.failed() || fusedFailedNow)) {
            final boolean rawFilter = rawPlain && hProgram2d != 0 && mRawLensFilter.active() && rawTarget();
            if (rawFilter) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rawFbo);
            rawActive = rawPlain && mRawRenderer.draw(pVertex, pTexCoord, mTexRotateMatrix, mMirrorPreview, getPeakEnabled());
            if (rawFilter) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                if (rawActive) drawRawFiltered(0);
            }
        }
        if (mLastRawActive == null || mLastRawActive != rawActive) {
            Log.d("MainRenderer", "viewfinder requestedRAW="+rawLook+" displaying="+(rawActive?"developed RAW":"ISP (RAW disabled, waiting, stale or unavailable)"));
            mLastRawActive = rawActive;
        }
        com.particlesdevs.photoncamera.control.subject.SubjectFrames.setRawDisplayed(rawActive);
        mRawShownNs = rawActive ? System.nanoTime() : Long.MIN_VALUE / 2;
        if (rawActive) {
            sRawDraws++; // P345: a RAW-only session's frames end the frozen viewfinder too
            mSubjectGrabber.afterDraw();
            GLES20.glUseProgram(hProgramHandle);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
            return;
        }

        // P228: the live lens filter's blur pyramid of this frame (its own program and framebuffer), before the main pass
        mLensFilter.prepass(pVertex, pTexCoord, mTexRotateMatrix, hTex[0], mMirrorPreview, mFrameGain);
        // Every pass owns its GL state: RAW and histogram passes use other programs/attributes.
        GLES20.glUseProgram(hProgramHandle);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
        GLES20.glEnableVertexAttribArray(vPosition);
        GLES20.glEnableVertexAttribArray(vTexCoord);
        pVertex.position(0);
        pTexCoord.position(0);
        GLES20.glUniformMatrix4fv(uTexRotateMatrix, 1, false, mTexRotateMatrix, 0);
        int peakEnabled = getPeakEnabled();
        GLES20.glUniform1i(enablePeak, peakEnabled);
        GLES20.glUniform1i(mirror, mMirrorPreview ? 1 : 0);

        GLES20.glUniform1f(uGain, mFrameGain);
        mLensFilter.apply();

        GLES20.glVertexAttribPointer(vPosition, 2, GLES20.GL_FLOAT, false, 4 * 2, pVertex);
        GLES20.glVertexAttribPointer(vTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 2, pTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // Measure after drawing, so a failure here cannot leave the viewfinder
        // blank, and re-bind this program afterwards since the meter uses its
        // own. Only runs on one frame in eight.
        // P82: while the manual panel's software shutter / ISO priority needs the viewfinder's brightness
        if (com.particlesdevs.photoncamera.manual.SceneLuma.wanted()) {
            mSceneMeter.measure(pVertex, pTexCoord, hTex[0]);
            GLES20.glUseProgram(hProgramHandle);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
        }
        mSubjectGrabber.afterDraw();
        // GLES20.glFlush();
    }

    private int hProgramHandle;
    private int uTexRotateMatrix;

    private int vPosition;
    private int vTexCoord;
    private int enablePeak;
    private int mirror;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        mGLInit = false;
        // P57: the viewfinder's GL thread above the processing threads (the post pipeline, encoders, the worker run at the
        // default priority and took its CPU time after a shot: the viewfinder stuttered while the camera kept 30 fps)
        // (scam_dev.txt "vf_priority 0": as before, for A/B)
        try {
            if (com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("vf_priority", 1f) != 0f)
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (RuntimeException e) {
            Log.w("MainRenderer", "viewfinder thread priority not raised: " + e);
        }
        mUpdateST = false;
        mRawRenderer.onContextCreated();
        mWhyTone.onContextCreated();
        mWhyLogged = false;
        mFusedLogged = 0;
        mRawProgramsReady = false;
        mRawProgramsWhy = 0;
        mRawProgramsAsked = 0;
        mIspLatchNs = 0;
        mSceneMeter.onContextCreated();
        mSubjectGrabber.onContextCreated();
        initTex();
        // the previous context's SurfaceTexture (its context is gone; the camera was closed before the pause and opens again on
        // the new one): released, not left to the finalizer with its BufferQueue and last preview buffer
        final SurfaceTexture old = mSTexture;
        if (old != null) {
            old.setOnFrameAvailableListener(null);
            old.release();
        }
        mSTexture = new SurfaceTexture(hTex[0]);
        // P90: frame callbacks on their own thread, not the main looper (a SurfaceTexture made on the GL thread, which has no
        // looper, posts them to the main thread: every busy moment of the UI during processing held the viewfinder back)
        mSTexture.setOnFrameAvailableListener(this, frameHandler());

        String vss_default = ScameraApp.getAssetLoader().getString("shaders/preview/main_vs.glsl");
        String fss_default = LiveLensFilter.splice(ScameraApp.getAssetLoader().getString("shaders/preview/main_fs.glsl"),
                ScameraApp.getAssetLoader().getString("shaders/preview/lensfilter_live.glsl"));
        int hProgram = loadShader(vss_default, fss_default);
        hProgramHandle = hProgram;
        GLES20.glUseProgram(hProgram);
        uTexRotateMatrix = GLES20.glGetUniformLocation(hProgram, "uTexRotateMatrix");
        GLES20.glUniformMatrix4fv(uTexRotateMatrix, 1, false, mTexRotateMatrix, 0);
        vPosition = GLES20.glGetAttribLocation(hProgram, "vPosition");
        vTexCoord = GLES20.glGetAttribLocation(hProgram, "vTexCoord");
        enablePeak = GLES20.glGetUniformLocation(hProgram, "enablePeak");
        uGain = GLES20.glGetUniformLocation(hProgram, "uGain");
        mirror = GLES20.glGetUniformLocation(hProgram, "mirror");
        mLensFilter.locate(hProgram);
        mLensFilter.locatePrep(loadShader(vss_default, ScameraApp.getAssetLoader().getString("shaders/preview/lensfilter_prep.glsl")));
        // P300: the same filter for the developed-RAW frame drawn off screen (a 2D texture, the screen's layout)
        rawFbo = rawTex = rawW = rawH = 0;
        hProgram2d = loadShader(VS_2D, LiveLensFilter.to2d(fss_default));
        if (hProgram2d != 0) {
            vPosition2d = GLES20.glGetAttribLocation(hProgram2d, "vPosition");
            enablePeak2d = GLES20.glGetUniformLocation(hProgram2d, "enablePeak");
            uGain2d = GLES20.glGetUniformLocation(hProgram2d, "uGain");
            mirror2d = GLES20.glGetUniformLocation(hProgram2d, "mirror");
            resolution2d = GLES20.glGetUniformLocation(hProgram2d, "resolution");
            mRawLensFilter.locate(hProgram2d);
            mRawLensFilter.locatePrep(loadShader(VS_2D, LiveLensFilter.to2d(ScameraApp.getAssetLoader().getString("shaders/preview/lensfilter_prep.glsl"))));
        }
        GLES20.glUseProgram(hProgram);
        GLES20.glVertexAttribPointer(vPosition, 2, GLES20.GL_FLOAT, false, 4 * 2, pVertex);
        GLES20.glVertexAttribPointer(vTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 2, pTexCoord);
        GLES20.glEnableVertexAttribArray(vPosition);
        GLES20.glEnableVertexAttribArray(vTexCoord);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(hProgram, "resolution"), mView.getWidth(), mView.getHeight());
        mGLInit = hProgram != 0;
        mView.fireOnSurfaceTextureAvailable(mSTexture, 0, 0);
        // P312: with the RAW viewfinder on, its programs are linked (or their background compile started) while the camera opens
        if (com.particlesdevs.photoncamera.settings.PreferenceKeys.isLiveViewfinderRawEnabled()) rawProgramsReady();
    }

    /**
     * P312: links the developed-RAW viewfinder's programs (rawdevelop, and the photo's WHY tone when it is wanted) before the
     * first RAW frame needs them: their driver binaries at once; the ones without a binary are compiled by ViewfinderPrograms in
     * the background (asked once per context), else here, at most one per call. True when they are all linked (or failed: the
     * draw falls back as before).
     */
    private boolean rawProgramsReady() {
        // P314: which of the tone's program sets (vf_why 1 per pixel / 2 the guided-filter chain; 0 none); P365: the fused pass's
        // set or P314's (LiveWhyTone.programSet)
        final int whyMode = hProgram2d != 0 && !mWhyTone.failed() ? mWhyTone.programSet() : 0;
        final boolean why = whyMode != 0;
        if (mRawProgramsReady && (!why || mRawProgramsWhy == whyMode)) return true;
        final long now = android.os.SystemClock.elapsedRealtime();
        try {
            if (mRawProgramsAsked == 0 || (why && mRawProgramsWhy != whyMode)) {
                // first ask in this context, or the WHY tone wanted now (vf_why switched on, or to the other set): its programs built
                // in the background too
                mRawProgramsReady = false;
                mRawProgramsAsked = now;
                mRawProgramsWhy = whyMode;
                final java.util.ArrayList<String> keys = new java.util.ArrayList<>();
                keys.add(LiveRawRenderer.programKey());
                if (why) keys.addAll(mWhyTone.programKeys());
                ViewfinderPrograms.prepare(keys);
            }
            final boolean waiting = now - mRawProgramsAsked < RAW_PROGRAMS_WAIT_MS;
            final java.util.function.Predicate<String> elsewhere = k -> waiting && ViewfinderPrograms.pending(k);
            if (waiting && ViewfinderPrograms.busy()) {
                // no binary is loaded while the background thread links (a driver's compiler lock would hold this thread), except
                // rawdevelop's in a RAW-only session: it has no ISP frame to show meanwhile, the plain RAW look draws until the WHY tone
                if (System.nanoTime() - mIspLatchNs > 300_000_000L) mRawRenderer.prepare(elsewhere);
                return false;
            }
            final java.util.function.Predicate<String> sendOnce = k -> {
                if (!waiting || ViewfinderPrograms.tried(k)) return false;
                ViewfinderPrograms.prepare(java.util.Collections.singletonList(k));
                return true;
            };
            mRawProgramsReady = mRawRenderer.prepare(elsewhere) && (!why || mWhyTone.prepare(elsewhere, sendOnce));
        } catch (RuntimeException e) {
            Log.w("MainRenderer", "viewfinder programs: " + e);
            mRawProgramsReady = true;   // the draws fall back as before
        } finally {
            // the programs' set-up leaves its program and texture units bound
            GLES20.glUseProgram(hProgramHandle);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
        if (mRawProgramsReady)
            Log.d("MainRenderer", "viewfinder: RAW programs linked " + (android.os.SystemClock.elapsedRealtime() - mRawProgramsAsked)
                    + " ms after they were asked for (WHY " + why + ", set " + whyMode + ": vf_why " + whyMode / 2
                    + (whyMode % 2 == 1 ? " fused" : "") + ")");
        return mRawProgramsReady;
    }

    /** P300: the off-screen target of the developed-RAW frame, the viewport's size (made or resized here); false without one. */
    private boolean rawTarget() {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, mViewport, 0);
        return rawTarget(mViewport[2], mViewport[3]);
    }

    private final int[] mViewport = new int[4];

    /**
     * P314: the size of the WHY tone: the visible crop's colour grid of the frame drawn last (LiveRawRenderer.toneSize: a 2x ISZ
     * Quad frame 810 x 1080 instead of the 1440 x 1920 viewport, a 1x Bayer frame the viewport), the viewport before a first frame;
     * P365: only the size (the fused pass straight to the screen makes no target), mViewport read.
     */
    private int[] toneSizeNow() {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, mViewport, 0);
        final com.particlesdevs.photoncamera.processing.LiveWhyMeter.View v = mRawRenderer.lastView();
        if (v == null) return new int[]{Math.max(1, mViewport[2]), Math.max(1, mViewport[3])};
        // the crop fitted to the screen, not the one drawn last: that one was fitted to the previous target's rounded aspect, and
        // the two could flip each other every frame (review: 139 of ~84k viewport / frame / zoom cases in a 2-cycle)
        final boolean quarterTurn = Math.abs(mTexRotateMatrix[1]) > Math.abs(mTexRotateMatrix[0]);
        final int[] crop = LiveRawRenderer.visibleCrop(v.frame, mViewport[2], mViewport[3], quarterTurn);
        final int[] size = LiveRawRenderer.toneSize(mViewport[2], mViewport[3], crop[2], crop[3], v.frame.cfaBlock, quarterTurn);
        return new int[]{Math.max(1, size[0]), Math.max(1, size[1])};
    }

    /** P365: the off-screen target goes while the fused tone is drawn straight to the screen (11 MB at 1440 x 1920). */
    private void releaseRawTarget() {
        if (rawFbo == 0) return;
        GLES30.glDeleteFramebuffers(1, new int[]{rawFbo}, 0);
        GLES20.glDeleteTextures(1, new int[]{rawTex}, 0);
        rawFbo = rawTex = rawW = rawH = 0;
    }

    /** P300 / P314: the off-screen target at w x h (made or resized here); false without one. */
    private boolean rawTarget(int width, int height) {
        int w = Math.max(1, width), h = Math.max(1, height);
        if (rawFbo != 0 && w == rawW && h == rawH) return true;
        if (rawFbo != 0) GLES30.glDeleteFramebuffers(1, new int[]{rawFbo}, 0);
        if (rawTex != 0) GLES20.glDeleteTextures(1, new int[]{rawTex}, 0);
        rawFbo = rawTex = rawW = rawH = 0;
        int[] t = new int[1], f = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES30.glGenFramebuffers(1, f, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w("MainRenderer", "RAW filter target incomplete: 0x" + Integer.toHexString(status));
            GLES30.glDeleteFramebuffers(1, f, 0);
            GLES20.glDeleteTextures(1, t, 0);
            hProgram2d = 0;   // no filter on the RAW viewfinder in this context, as before
            return false;
        }
        rawFbo = f[0];
        rawTex = t[0];
        rawW = w;
        rawH = h;
        return true;
    }

    /**
     * P300: the off-screen RAW frame through the live lens filter onto the screen (no gain or mirror: the RAW pass did them);
     * P301: the focus peaking here when the frame came from the WHY tone (the RAW pass drew none).
     */
    private void drawRawFiltered(int peaking) {
        mRawLensFilter.prepass(pVertex, pTexCoord, IDENTITY, rawTex, GLES20.GL_TEXTURE_2D, false, 1f);
        GLES20.glUseProgram(hProgram2d);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex);
        GLES20.glUniform1i(enablePeak2d, peaking);
        GLES20.glUniform1i(mirror2d, 0);
        GLES20.glUniform1f(uGain2d, 1f);
        GLES20.glUniform2f(resolution2d, rawW, rawH);
        mRawLensFilter.apply();
        pVertex.position(0);
        GLES20.glEnableVertexAttribArray(vPosition2d);
        GLES20.glVertexAttribPointer(vPosition2d, 2, GLES20.GL_FLOAT, false, 4 * 2, pVertex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(vPosition2d);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES30.glViewport(0, 0, width, height);
        mSubjectGrabber.onSurfaceChanged(width, height);
    }

    public SurfaceTexture getmSTexture() {
        return mSTexture;
    }

    private void initTex() {
        hTex = new int[1];
        GLES20.glGenTextures(1, hTex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    }

    private static android.os.Handler sFrameHandler;

    private static synchronized android.os.Handler frameHandler() {
        if (sFrameHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("VfFrames", android.os.Process.THREAD_PRIORITY_DISPLAY);
            t.start();
            sFrameHandler = new android.os.Handler(t.getLooper());
        }
        return sFrameHandler;
    }

    /** P90: the gain of the frame on the texture (a series frame scaled to the viewfinder's exposure), and its uniform. */
    private float mFrameGain = 1f;
    private int uGain = -1;

    public synchronized void onFrameAvailable(SurfaceTexture st) {
        mUpdateST = true;
        // P353: while the developed RAW frame is on screen (drawn less than RAW_SHOWN_NS ago) an ISP frame is only latched (its
        // buffer goes back to the camera) - each such wake-up drew the same RAW frame again, ~25 of the ~55 draws a second on the
        // OPPO, on the GPU the merge needs. The RAW frames ask for their own draws; without one for RAW_SHOWN_NS the ISP frames
        // draw again (the fall-back to the ISP picture). scam_dev vf_isp_latch 0: every ISP frame draws (as before).
        final long now = System.nanoTime();
        if (now - mRawShownNs < RAW_SHOWN_NS
                && com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("vf_isp_latch", true)) {
            mView.queueEvent(this::latchIsp);
            return;
        }
        // P365 step 7: while the shot's series is taken the last RAW frame stays on the screen (up to SERIES_HOLD_NS)
        if (seriesHold(now)) {
            mView.queueEvent(this::latchIsp);
            return;
        }
        mView.requestRender();
    }

    /** P353: when the developed RAW frame was put on screen last (System.nanoTime). */
    private volatile long mRawShownNs = Long.MIN_VALUE / 2;
    static final long RAW_SHOWN_NS = 300_000_000L;

    /**
     * Whether the pending ISP frame can be latched: one is pending, the context is set up and current - a latch queued by
     * onFrameAvailable (queueEvent) runs even while the GL thread is paused (GLThread takes its events before the pause check),
     * with no context: updateTexImage threw there, on the GL thread.
     */
    static boolean latchable(boolean update, boolean glInit, boolean contextCurrent) {
        return update && glInit && contextCurrent;
    }

    /** The pending ISP frame onto the texture (GL thread). */
    private synchronized void latchIsp() {
        // no context: mUpdateST stays, the next draw latches the frame
        if (!latchable(mUpdateST, mGLInit, !android.opengl.EGL14.EGL_NO_CONTEXT.equals(android.opengl.EGL14.eglGetCurrentContext())))
            return;
        try {
            mSTexture.updateTexImage();
        } catch (RuntimeException e) {
            Log.w("MainRenderer", "ISP latch skipped: " + e);
            return;
        }
        mUpdateST = false;
        mHaveFrame = true;
        mIspLatchNs = System.nanoTime();
        mFrameGain = ViewfinderGain.gainFor(mSTexture.getTimestamp()); // P90
        sShownTimestamp = mSTexture.getTimestamp(); // P111
    }

    private static String GetSupportedVersion() {
        return "#version 300 es";
    }

    private static int loadShader(String vss, String fss) {
        String SupportedVersion = GetSupportedVersion();
        vss = SupportedVersion + "\n #line 1\n" + vss;
        fss = SupportedVersion + "\n #line 1\n" + fss;
        // P312: from the kept driver binary after the first start (these were compiled before the first frame at every start)
        final int program = com.particlesdevs.photoncamera.processing.opengl.GLProg.viewfinderProgram(
                com.particlesdevs.photoncamera.processing.opengl.GLProg.viewfinderKey(vss, fss));
        if (program == 0) Log.e("MainRenderer", "viewfinder program failed (GLProgram logged why)");
        return program;
    }

    public void setMirror(boolean mirrorPreview) {
        mMirrorPreview = mirrorPreview;
    }

    // P36: the viewfinder transform of a new camera (sensor orientation 90 vs 270, the front mirror) is applied from
    // the new camera's first frame. Applied at once, the frame on screen - still the previous camera's - was redrawn
    // upside down (and mirrored) until the new camera delivered: the 180 degree flip on a switch to the front camera.
    private final Object mPendingLock = new Object();
    private float[] mPendingMatrix;
    private boolean mPendingMirror;
    private int mPendingOrientation;
    private long mPendingSetNs, mPendingArmNs, mPendingFromTs = Long.MAX_VALUE;
    private int mPendingRawSession;
    private int mOrientation = 180;
    private volatile boolean mHaveFrame;
    private int mFramesToLog;
    /** Without a first frame of the new session (no capture start seen) the transform still goes on after this. */
    private static final long PENDING_UNARMED_NS = 3_000_000_000L, PENDING_ARMED_NS = 700_000_000L;

    /**
     * P36: orientation (degrees for the texture rotation) and mirror of the camera about to open. Before any frame was
     * drawn, or when nothing changes, it applies at once; otherwise it waits for {@link #armPendingTransform} and the
     * first frame with a timestamp at or after the new session's first capture.
     */
    public void setViewfinderTransform(int orientation, boolean mirror) {
        synchronized (mPendingLock) {
            if (!mHaveFrame || (orientation % 360 == mOrientation % 360 && mirror == mMirrorPreview)) {
                mPendingMatrix = null;
                if (orientation % 360 != mOrientation % 360) setOrientation(orientation);
                mMirrorPreview = mirror;
                return;
            }
            // The same transform again (configureTransform, then setUpCameraOutputs' UI post): an armed one stays armed.
            if (mPendingMatrix != null && orientation % 360 == mPendingOrientation % 360 && mirror == mPendingMirror) return;
            float[] m = new float[16];
            android.opengl.Matrix.setRotateM(m, 0, orientation, 0f, 0f, 1f);
            mPendingMatrix = m;
            mPendingOrientation = orientation;
            mPendingMirror = mirror;
            mPendingSetNs = System.nanoTime();
            mPendingArmNs = 0;
            mPendingFromTs = Long.MAX_VALUE;
            mPendingRawSession = com.particlesdevs.photoncamera.processing.LiveRawFrame.currentSession();
            Log.d("MainRenderer", "viewfinder transform pending orientation=" + orientation + " mirror=" + mirror
                    + " (now " + mOrientation + "/" + mMirrorPreview + ")");
        }
    }

    /** P36: the new session's first capture started at this sensor timestamp; frames from it on get the new transform. */
    public void armPendingTransform(long sensorTimestampNs) {
        synchronized (mPendingLock) {
            if (mPendingMatrix == null || mPendingArmNs != 0) return;
            mPendingFromTs = sensorTimestampNs;
            mPendingArmNs = System.nanoTime();
        }
    }

    /** GL thread, before drawing: the frame in the texture (or the RAW frame) belongs to the new camera. */
    private void applyPendingTransform() {
        synchronized (mPendingLock) {
            if (mPendingMatrix == null) {
                if (mFramesToLog > 0 && mSTexture != null) {
                    mFramesToLog--;
                    Log.d("MainRenderer", "viewfinder frame ts=" + mSTexture.getTimestamp() + " orientation=" + mOrientation + " mirror=" + mMirrorPreview);
                }
                return;
            }
            long now = System.nanoTime();
            long ts = mSTexture == null ? 0 : mSTexture.getTimestamp();
            String reason = null;
            if (mPendingArmNs != 0 && ts >= mPendingFromTs) reason = "first frame of the new session ts=" + ts;
            else if (com.particlesdevs.photoncamera.processing.LiveRawFrame.peekSession() > mPendingRawSession) reason = "first RAW frame of the new session";
            else if (mPendingArmNs != 0 && now - mPendingArmNs > PENDING_ARMED_NS) reason = "timeout after the first capture";
            else if (now - mPendingSetNs > PENDING_UNARMED_NS) reason = "timeout";
            if (reason == null) {
                Log.d("MainRenderer", "viewfinder frame ts=" + ts + " keeps orientation=" + mOrientation + " mirror=" + mMirrorPreview + " (previous camera)");
                return;
            }
            System.arraycopy(mPendingMatrix, 0, mTexRotateMatrix, 0, 16);
            mOrientation = mPendingOrientation;
            mMirrorPreview = mPendingMirror;
            mPendingMatrix = null;
            mFramesToLog = 3;
            Log.d("MainRenderer", "viewfinder transform applied orientation=" + mOrientation + " mirror=" + mMirrorPreview + " on " + reason
                    + " (" + (now - mPendingSetNs) / 1_000_000 + " ms after it was set)");
        }
    }

    private int getPeakEnabled() {
        int focusPeakSetting = ScameraApp.getSettings().focusPeak;
        if (focusPeakSetting == 1) {
            return 1; // On
        } else if (focusPeakSetting == 2) {
            // Auto: show peaking when manual focus mode is active OR when focus parameter
            // is selected via UI
            if (mManualModeConsole != null) {
                return (mManualModeConsole.isManualFocusModeActive() || mManualModeConsole.isFocusParameterSelected())
                        ? 1
                        : 0;
            }
            return 0;
        }
        return 0; // Off
    }

    public void setOrientation(int or) {
        android.opengl.Matrix.setRotateM(mTexRotateMatrix, 0, or, 0f, 0f, 1f);
        mOrientation = or;
    }

    public void setTransform(@NonNull android.graphics.Matrix matrix) {
        Log.d("MainRenderer", "setTransform: " + matrix + " " + Arrays.toString(mTexRotateMatrix));
        matrix.getValues(mTexRotateMatrix);
    }

    RectF mLastImageRect = new RectF();
    RectF inputRect = new RectF();

    public void scale(int in_width, int in_height, int out_width, int out_height, int rotation) {
        int difw = out_width - in_width;
        int difh = out_height - in_height;

        inputRect.left = (int) (difw / 2);
        inputRect.top = (int) (difh / 2);
        inputRect.right = in_width;
        inputRect.bottom = in_height;
        if (mLastImageRect != inputRect) {
            GLES20.glViewport((int) inputRect.left, (int) inputRect.top, (int) inputRect.width(),
                    (int) inputRect.height());

            mLastImageRect.set(inputRect);
        }

    }
}
