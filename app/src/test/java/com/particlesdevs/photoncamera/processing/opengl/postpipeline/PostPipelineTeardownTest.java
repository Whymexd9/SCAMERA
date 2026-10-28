package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLInterface;

import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * processing-hdr-context-leak: RunHDRGainMap gave the gain-map pass a context of its own and dropped Run()'s; close() closed
 * only the new one. The context kept for the process (P353) stayed taken for good, and every later HDR shot leaked its own
 * context. Run()'s processing is now closed by close(), after the gain-map one (closing it earlier SEGV'd on Adreno).
 */
public class PostPipelineTeardownTest {
    @Test public void runContextClosedAfterTheGainMapContext() {
        final GLCoreBlockProcessing run = mock(GLCoreBlockProcessing.class), gainMap = mock(GLCoreBlockProcessing.class);
        final PostPipeline pipeline = new PostPipeline();
        pipeline.glint = new GLInterface(run);
        pipeline.useGainMapContext(gainMap);
        verify(run, times(0)).close();
        pipeline.close();
        final InOrder order = inOrder(gainMap, run);
        order.verify(gainMap).close();
        order.verify(run).close();
        assertNull(pipeline.glint);
        // once only: a second close has nothing left
        pipeline.close();
        verify(run, times(1)).close();
        verify(gainMap, times(1)).close();
    }

    @Test public void withoutTheGainMapPassOneContextIsClosed() {
        final GLCoreBlockProcessing run = mock(GLCoreBlockProcessing.class);
        final PostPipeline pipeline = new PostPipeline();
        pipeline.glint = new GLInterface(run);
        pipeline.close();
        verify(run, times(1)).close();
    }

    @Test public void aFailingRetiredCloseIsLoggedNotThrown() {
        final GLCoreBlockProcessing run = mock(GLCoreBlockProcessing.class), gainMap = mock(GLCoreBlockProcessing.class);
        doThrow(new IllegalStateException("EGL_BAD_CONTEXT")).when(run).close();
        final PostPipeline pipeline = new PostPipeline();
        pipeline.glint = new GLInterface(run);
        pipeline.useGainMapContext(gainMap);
        pipeline.close();
        verify(gainMap).close();
        verify(run).close();
    }

    /** processing-failed-post-leak: a failed shot's open pipeline goes as a deferred teardown (closeAll on its context, then close). */
    @Test public void failedPipelineTornDownOnItsContext() {
        final GLCoreBlockProcessing run = mock(GLCoreBlockProcessing.class);
        final PostPipeline pipeline = new PostPipeline();
        pipeline.glint = new GLInterface(run);
        pipeline.deferTeardown = true;
        pipeline.finishDeferredTeardown();
        final InOrder order = inOrder(run);
        order.verify(run).makeCurrent();
        order.verify(run).close();
        assertNull(pipeline.glint);
    }
}
