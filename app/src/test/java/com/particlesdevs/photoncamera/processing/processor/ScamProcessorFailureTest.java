package com.particlesdevs.photoncamera.processing.processor;

import com.particlesdevs.photoncamera.processing.ProcessingEventsListener;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * processing-dng-partial: a failed optional DNG showed «The photo could not be saved» for a shot whose JPEG is saved a moment
 * later; the card stays for a DNG that is the shot (RAW only, the merged mosaic DNG alone). processing-failed-post-leak: the post
 * pipeline a failed shot left open is torn down as a deferred teardown.
 */
public class ScamProcessorFailureTest {
    static final class Events implements ProcessingEventsListener {
        final List<String> saved = new ArrayList<>(), notices = new ArrayList<>();

        @Override public void onProcessingStarted(String processName) {}
        @Override public void onProcessingChanged(Object obj) { if (obj instanceof String) notices.add((String) obj); }
        @Override public void onProcessingFinished(Object obj) {}
        @Override public void notifyImageSavedStatus(boolean ok, Path savedFilePath) { saved.add(ok + " " + savedFilePath); }
        @Override public void onProcessingError(Object obj) {}
    }

    private static final Path DNG = Paths.get("IMG_1.dng");

    @Test public void failedOptionalDngIsANoticeNotAFailedShot() {
        final Events e = new Events();
        ScamProcessor.reportDng(e, false, 1, false, DNG);
        assertEquals(0, e.saved.size());
        assertEquals(1, e.notices.size());
        assertTrue(e.notices.get(0), e.notices.get(0).contains("DNG"));
    }

    @Test public void aDngThatIsTheShotStillReportsItsFailure() {
        final Events rawOnly = new Events();
        ScamProcessor.reportDng(rawOnly, false, 2, false, DNG);
        assertEquals("[false IMG_1.dng]", rawOnly.saved.toString());
        assertEquals(0, rawOnly.notices.size());
        final Events mosaic = new Events();
        ScamProcessor.reportDng(mosaic, false, 1, true, DNG);
        assertEquals("[false IMG_1.dng]", mosaic.saved.toString());
    }

    @Test public void aSavedDngIsReportedAsBefore() {
        final Events e = new Events();
        ScamProcessor.reportDng(e, true, 1, false, DNG);
        assertEquals("[true IMG_1.dng]", e.saved.toString());
        assertEquals(0, e.notices.size());
    }

    @Test public void failedPipelineReleasedOnce() {
        final PostPipeline open = mock(PostPipeline.class);
        ScamProcessor.releaseFailedPipeline(open);
        assertTrue(open.deferTeardown);
        verify(open, times(1)).finishDeferredTeardown();
        ScamProcessor.releaseFailedPipeline(null);
        final PostPipeline failing = mock(PostPipeline.class);
        doThrow(new IllegalStateException("EGL_BAD_ACCESS")).when(failing).finishDeferredTeardown();
        ScamProcessor.releaseFailedPipeline(failing); // logged, the shot's failure is the one reported
        verify(failing, times(1)).finishDeferredTeardown();
    }
}
