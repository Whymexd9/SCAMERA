package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The viewfinder renderer's ISP latch, its SurfaceTexture across contexts and the RAW draw count (MainRenderer). */
public class MainRendererLatchTest {
    private static String src() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/ui/camera/views/viewfinder/MainRenderer.java").toPath()), StandardCharsets.UTF_8);
    }

    private static String body(String s, String signature) {
        final int start = s.indexOf(signature);
        assertTrue(signature, start > 0);
        return s.substring(start, s.indexOf("\n    }\n", start));
    }

    /**
     * Bug viewfinder-ui-latch-without-egl: a latch queued by onFrameAvailable runs even while the GL thread is paused (GLThread
     * takes its events before the pause check), with no EGL context - updateTexImage threw on the GL thread (a crash).
     */
    @Test
    public void noLatchWithoutAContext() throws Exception {
        assertFalse(MainRenderer.latchable(true, true, false));
        assertFalse(MainRenderer.latchable(false, true, true));
        assertFalse(MainRenderer.latchable(true, false, true));
        assertTrue(MainRenderer.latchable(true, true, true));
        final String latch = body(src(), "private synchronized void latchIsp()");
        final int check = latch.indexOf("latchable(mUpdateST, mGLInit, !android.opengl.EGL14.EGL_NO_CONTEXT.equals(android.opengl.EGL14.eglGetCurrentContext()))");
        final int update = latch.indexOf("mSTexture.updateTexImage();");
        assertTrue("the context is checked before the latch", check > 0 && update > check);
        assertTrue("a failed latch is caught", latch.indexOf("catch (RuntimeException e)", update) > update);
        assertTrue("mUpdateST is cleared only after a latch", latch.indexOf("mUpdateST = false;") > latch.indexOf("catch (RuntimeException e)"));
    }

    /** Missed bug: each new context made a new SurfaceTexture and left the old one (its BufferQueue, the last preview buffer). */
    @Test
    public void theOldSurfaceTextureIsReleased() throws Exception {
        final String created = body(src(), "public void onSurfaceCreated(GL10 gl, EGLConfig config)");
        final int release = created.indexOf("old.release();");
        assertTrue(created.indexOf("final SurfaceTexture old = mSTexture;") > 0 && release > 0);
        assertTrue(created.indexOf("old.setOnFrameAvailableListener(null);") < release);
        assertTrue("before the new one is made", release < created.indexOf("mSTexture = new SurfaceTexture(hTex[0]);"));
    }

    /**
     * Review: the pause destroys the EGL context (not preserved), so onSurfaceCreated makes a new SurfaceTexture on resume and
     * releases the old one; a GLPreview still reporting the old one as available let resumeCamera open the camera against it.
     */
    @Test
    public void aPauseDropsTheSurfaceTexture() throws Exception {
        final String preview = body(new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/ui/camera/views/viewfinder/GLPreview.java").toPath()), StandardCharsets.UTF_8),
                "public void onPause()");
        final int dropped = preview.indexOf("surfaceReady = false;");
        assertTrue(dropped > 0 && dropped < preview.indexOf("super.onPause();"));
    }

    /** Bug viewfinder-ui-freeze-raw-only: every developed RAW frame on screen is counted (the frozen viewfinder watches it). */
    @Test
    public void rawDrawsAreCounted() throws Exception {
        final String s = src();
        final int shown = s.indexOf("mRawShownNs = rawActive ? System.nanoTime() : Long.MIN_VALUE / 2;");
        assertTrue(shown > 0);
        final int count = s.indexOf("sRawDraws++;", shown);
        assertTrue(count > shown && count < s.indexOf("mSubjectGrabber.afterDraw();", shown));
        assertTrue(s.substring(shown, count).contains("if (rawActive) {"));
    }
}
