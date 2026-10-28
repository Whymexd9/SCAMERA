package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.content.Context;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ShotLook;
import com.particlesdevs.photoncamera.processing.processor.ScamProcessor;
import com.particlesdevs.photoncamera.settings.ParamHelp;
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
 * Owner, 2026-10-28 («плёнка, LUT, теплота и оттенок по-прежнему читаются при обработке, а не в момент нажатия … добавь в
 * план»): the shot keeps the film, the LUT and the «Цвет» rows of its press through the queue to its pipeline, as its lens
 * filter (LensFilterAtPressTest); changed in the tray while the shot merges, they are the next shot's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ShotLookAtPressTest {
    private MockedStatic<ScameraApp> app;
    private MockedStatic<ImagePath> paths;
    private MockedStatic<ParseExif> exif;
    private MockedConstruction<ScamProcessor> processors;
    private SettingsManager manager;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        app.when(() -> ScameraApp.getStringStatic(anyInt()))
                .thenAnswer(i -> RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(ScameraApp::getSettings).thenReturn(new Settings());
        PreferenceKeys.initialise(manager);
        processors = mockConstruction(ScamProcessor.class);
        paths = mockStatic(ImagePath.class);
        paths.when(ImagePath::newBaseName).thenReturn("look-test");
        paths.when(() -> ImagePath.dngFile(anyString())).thenReturn(Paths.get("look-test.dng"));
        paths.when(() -> ImagePath.imageFile(anyString())).thenReturn(Paths.get("look-test.jpg"));
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

    /** As the tray writes them: a film, a LUT at a strength, the warmth. */
    private void tray(String stock, String lut, String warmth) {
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmSim.STOCK_KEY, stock);
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, lut);
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, "80");
        manager.set(SettingsManager.SCOPE_GLOBAL, ParamHelp.WARMTH_KEY, warmth);
    }

    /** The shot pressed with {@code press}, queued, the tray changed meanwhile, then processed: the processor's look. */
    private ShotLook queued(ShotLook press) {
        ImageSaver saver = new ImageSaver(mock(ProcessingEventsListener.class));
        saver.lookAtPress = press;
        DefaultSaver implementation = (DefaultSaver) saver.implementation;
        saver.detachForQueue(true);
        tray("", "", "0");
        saver.runRaw(null, null, null, new ArrayList<>(), 0, new HashMap<>());
        return implementation.scamProcessor.ownedLook;
    }

    @Test public void theShotKeepsTheLookOfItsPress() {
        final String stock = FilmSim.STOCKS[1].id;
        tray(stock, "warm400", "40");
        final ShotLook press = ShotLook.now();
        assertNotNull(press.film);
        assertEquals(stock, press.film.stock.id);
        assertEquals("warm400", press.lut);
        assertEquals(.8f, press.lutStrength, 1e-6f);
        assertNotEquals("the warmth of the press", 0f, press.colour.warmth, 0f);
        final ShotLook processed = queued(press);
        assertSame("through the queue to the processing", press, processed);
        assertNull("the settings now have no film", ShotLook.now().film);
        final PostPipeline pipeline = new PostPipeline();
        pipeline.look = processed;
        assertSame(press.colour, pipeline.look.colour);
    }

    @Test public void theFilmSnapshotIsThePresss() {
        tray("", "warm400", "0");
        final ShotLook press = ShotLook.now();
        tray(FilmSim.STOCKS[1].id, "", "30");
        final PostPipeline pipeline = new PostPipeline();
        pipeline.look = press;
        pipeline.takeFilm();
        assertNull("no film at the press: none in the photo", pipeline.film);
        assertEquals("the press's LUT", "warm400", pipeline.filmUserLut);
        assertEquals(.8f, pipeline.filmUserStrength, 1e-6f);
        // without a press (the lab's renders): the settings now
        final PostPipeline lab = new PostPipeline();
        lab.takeFilm();
        assertEquals("", lab.filmUserLut);
    }

    @Test public void everyShotTakesItsLookWithThePress() throws Exception {
        final String capture = src("capture/CaptureController.java");
        final int filter = capture.indexOf("mShutterLensFilter = com.particlesdevs.photoncamera.processing.opengl.postpipeline.LensFilter.Choice.now();");
        assertTrue("with the press's lens filter", filter > 0
                && capture.indexOf("mShutterLook = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ShotLook.now();") > filter);
        assertTrue(capture.contains("mImageSaver.lookAtPress = mShutterLook;"));
        assertTrue(capture.contains("saver.lookAtPress = mShutterLook;"));
        assertTrue(src("processing/DefaultSaver.java").contains("scamProcessor.ownedLook = ownedFrames != null ? ownedLook : lookAtPress;"));
        final String processor = src("processing/processor/ScamProcessor.java");
        assertTrue("before the film snapshot", processor.indexOf("pipeline.look = lookAtPress;") > 0
                && processor.indexOf("pipeline.look = lookAtPress;") < processor.indexOf("pipeline.takeFilm();"));
        assertTrue("the «Цвет» of the photo", src("processing/opengl/postpipeline/WhyCombine.java").contains("WhyTone.colourLook(pipeline)"));
        assertTrue(src("processing/opengl/postpipeline/WhyTone.java").contains("colourLook(pipeline).whiteMatrix(p.sceneCct)"));
        assertTrue(src("processing/opengl/postpipeline/PostAb.java").contains("old.look = pipeline.look;"));
    }

    private static String src(String path) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/com/particlesdevs/photoncamera/" + path).toPath()), StandardCharsets.UTF_8);
    }
}
