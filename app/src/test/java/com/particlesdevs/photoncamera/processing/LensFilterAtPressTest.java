package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.content.Context;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.LensFilter;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline;
import com.particlesdevs.photoncamera.processing.processor.ScamProcessor;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Owner, 2026-10-28 («"фильтры объектива": в видоискателе вижу эффект, на конечном фото нет»; log of build e8100faa: the
 * viewfinder drew Glimmerglass up to a second after the press at 10:10:10, the photo's pipeline 5 s later had no LensFilter node):
 * the pipeline read the filter from the settings after the merge, so a filter changed in the tray meanwhile was the photo's.
 * The shot keeps the filter of its press - the one the viewfinder showed - through the queue to its pipeline.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class LensFilterAtPressTest {
    private MockedStatic<ScameraApp> app;
    private MockedStatic<ImagePath> paths;
    private MockedStatic<ParseExif> exif;
    private MockedConstruction<ScamProcessor> processors;
    private SettingsManager manager;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        // the settings keys resolve their names through it: never null under the mock
        app.when(() -> ScameraApp.getStringStatic(anyInt()))
                .thenAnswer(i -> RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(ScameraApp::getSettings).thenReturn(new Settings());
        PreferenceKeys.initialise(manager);
        processors = mockConstruction(ScamProcessor.class);
        paths = mockStatic(ImagePath.class);
        paths.when(ImagePath::newBaseName).thenReturn("filter-test");
        paths.when(() -> ImagePath.dngFile(anyString())).thenReturn(Paths.get("filter-test.dng"));
        paths.when(() -> ImagePath.imageFile(anyString())).thenReturn(Paths.get("filter-test.jpg"));
        exif = mockStatic(ParseExif.class);
        SaverImplementation.IMAGE_BUFFER.clear();
    }

    @After public void cleanup() {
        SaverImplementation.IMAGE_BUFFER.clear();
        exif.close();
        paths.close();
        processors.close();
        app.close();
    }

    /** As LutTray.chooseFilter writes it. */
    private void tray(String id) {
        manager.set(SettingsManager.SCOPE_GLOBAL, LensFilters.KEY, id);
        if (!id.isEmpty()) manager.set(SettingsManager.SCOPE_GLOBAL, LensFilters.STRENGTH_KEY, "50");
    }

    /** The shot pressed with {@code press}, queued, the tray changed meanwhile, then processed: the processor's filter. */
    private LensFilter.Choice queued(LensFilter.Choice press, String trayDuringTheMerge) {
        ImageSaver saver = new ImageSaver(mock(ProcessingEventsListener.class));
        saver.lensFilterAtPress = press;
        DefaultSaver implementation = (DefaultSaver) saver.implementation;
        saver.detachForQueue(true);
        tray(trayDuringTheMerge);
        saver.runRaw(null, null, null, new ArrayList<>(), 0, new HashMap<>());
        return implementation.scamProcessor.ownedLensFilter;
    }

    @Test public void theShotKeepsTheFilterTheViewfinderShowedAtThePress() {
        tray(LensFilters.GLIMMERGLASS);
        manager.set(SettingsManager.SCOPE_GLOBAL, LensFilters.tuneKey(LensFilters.GLIMMERGLASS), "50,50,80,50,50");
        final LensFilter.Choice press = LensFilter.Choice.now();
        assertEquals(LensFilters.GLIMMERGLASS, press.id);
        assertEquals(.5f, press.strength, 1e-6f);
        assertEquals(.8f, press.tune.radius, 1e-6f);
        // the owner's shot: the filter switched off in the tray while the shot merged
        final LensFilter.Choice processed = queued(press, "");
        assertSame("through the queue to the processing", press, processed);
        assertNull("the settings now have none", LensFilter.of(LensFilter.Choice.now()));
        final PostPipeline pipeline = new PostPipeline();
        pipeline.lensFilter = processed;
        assertSame("the pipeline builds the press's filter", press, pipeline.lensFilterOfShot());
        assertNotNull(LensFilter.of(pipeline.lensFilterOfShot()));
    }

    @Test public void aFilterChosenDuringTheMergeIsNotTheShots() {
        tray("");
        final LensFilter.Choice press = LensFilter.Choice.now();
        assertSame(LensFilter.Choice.NONE, press);
        final PostPipeline pipeline = new PostPipeline();
        pipeline.lensFilter = queued(press, LensFilters.BLACK_PRO_MIST);
        assertEquals(LensFilters.BLACK_PRO_MIST, LensFilter.Choice.now().id);
        assertNull("no node: the press had no filter", LensFilter.of(pipeline.lensFilterOfShot()));
    }

    @Test public void withoutAPressThePipelineReadsTheSettings() {
        // a lab render (its own pipeline, the settings under its overlay) and a saver no press filled
        tray(LensFilters.CINEBLOOM);
        assertEquals(LensFilters.CINEBLOOM, new PostPipeline().lensFilterOfShot().id);
        assertNull(queued(null, LensFilters.CINEBLOOM));
    }

    @Test public void everyShotTakesItsFilterWithThePress() throws Exception {
        final String capture = src("capture/CaptureController.java");
        final int manualWb = capture.indexOf("mShutterManualWb = paramController.manualWhiteBalanceGains();");
        final String pressBlock = capture.substring(manualWb, capture.indexOf("captureStillPicture();", manualWb));
        final int press = pressBlock.indexOf("mShutterLensFilter = com.particlesdevs.photoncamera.processing.opengl.postpipeline.LensFilter.Choice.now();");
        assertTrue("with the press's other values, before either capture path",
                press > 0 && press < pressBlock.indexOf("mBackgroundHandler.post(this::captureVivoSdkFullRes);"));
        assertTrue("the ZSL / bracket shot's saver", capture.contains("mImageSaver.lensFilterAtPress = mShutterLensFilter;"));
        assertTrue("the FULLRES series' saver", capture.contains("saver.lensFilterAtPress = mShutterLensFilter;"));
        assertTrue(src("processing/DefaultSaver.java").contains("scamProcessor.ownedLensFilter = ownedFrames != null ? ownedLensFilter : lensFilterAtPress;"));
        final String processor = src("processing/processor/ScamProcessor.java");
        assertTrue(processor.indexOf("pipeline.lensFilter = lensFilterAtPress;") > processor.indexOf("PostPipeline pipeline = new PostPipeline();"));
        final String post = src("processing/opengl/postpipeline/PostPipeline.java");
        assertTrue(post.contains("LensFilter.of(shotFilter)"));
        assertFalse("the node never from the settings after the merge", post.contains("LensFilter.of(LensFilter.Choice.now())"));
        assertTrue("the A/B's old side too", src("processing/opengl/postpipeline/PostAb.java").contains("old.lensFilter = pipeline.lensFilter;"));
    }

    private static String src(String path) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/com/particlesdevs/photoncamera/" + path).toPath()), StandardCharsets.UTF_8);
    }
}
