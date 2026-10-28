package com.particlesdevs.photoncamera.processing.processor;

import android.app.Application;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.MosaicBlockStore;
import com.particlesdevs.photoncamera.processing.PedestalMemory;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P386: the shot's black is scanned on its darkest frame at the colour block the merge takes (ScamHybridBurst.knownBlock: here the
 * module's declared Quad, block 2) and as a frame of the shot's stream (MosaicBlockStore.shotKey, PedestalMemory). Read as plain
 * Bayer (block 1) every channel of the Quad frame below is its green, which never goes dark: the black stays ~45 DN.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class ScamProcessorBlackFloorTest {
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> appStatic;
    private static final String STREAM = "5|4096x3072|p386-processor|5";

    @Before public void setup() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager settings = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        appStatic = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(PreferenceKeys::isRawBlackFromData).thenReturn(true);
        prefs.when(() -> PreferenceKeys.scamDevSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
    }

    @After public void cleanup() {
        MosaicBlockStore.setShotKey("", -1);
        PedestalMemory.clear(STREAM, "test");
        prefs.close();
        appStatic.close();
    }

    private static Parameters parameters() throws Exception {
        Parameters p = new Parameters();
        p.whiteLevel = 1023; p.blackLevel = new float[]{64, 64, 64, 64};
        Field override = Parameters.class.getDeclaredField("blackLevelOverride");
        override.setAccessible(true); override.setFloat(p, -1f);
        Field white = Parameters.class.getDeclaredField("whiteLevelOverride");
        white.setAccessible(true); white.setInt(p, -1);
        return p;
    }

    /**
     * A 1024x768 GRBG Quad (block 2) frame without its pedestal: green 45 DN or more everywhere, red / blue a sunlit wall (19 / 31)
     * and, when {@code shade}, 64 px tiles of deep shade (red / blue ~1 DN) - the shade the green sites never see.
     */
    private static ByteBuffer quad(boolean shade, long seed) {
        final int w = 1024, h = 768;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final int phase = ((y / 2) & 1) * 2 + ((x / 2) & 1);
            final boolean dark = shade && y >= 400 && ((x / 64) + (y / 64)) % 2 == 0;
            final double signal = phase == 1 ? (dark ? 1 : 19) : phase == 2 ? (dark ? 1.5 : 31) : 45 + 30 * ((x / 128) % 2);
            final long v = Math.round(signal + Math.sqrt(0.133 + 0.0678 * signal) * r.nextGaussian());
            s.put(y * w + x, (short) Math.max(0, Math.min(1023, v)));
        }
        return b;
    }

    private static ImageFrame frame(ByteBuffer buffer, long exposure) {
        final ImageFrame f = mock(ImageFrame.class);
        f.buffer = buffer;
        f.width = 1024;
        f.height = 768;
        f.measuredExposure = exposure;
        f.measuredIso = 100;
        return f;
    }

    @Test public void theDarkestFrameIsScannedAtTheShotsColourBlock() throws Exception {
        MosaicBlockStore.setShotKey(STREAM, 2);
        final ArrayList<ImageFrame> frames = new ArrayList<>();
        frames.add(frame(quad(false, 3), 4_000_000L));
        frames.add(frame(quad(true, 4), 1_000_000L));  // the shortest exposure: the darkest frame
        final Parameters p = parameters();
        ScamProcessor.refineBlackFromDarkestFrame(p, frames);
        for (float v : p.blackLevel) assertTrue("the Quad's red / blue sites prove the lost pedestal: black " + v, v < 3f);
        assertTrue(p.levelSources(), p.levelSources().contains("colour block 2"));
        // the same frame read as plain Bayer (what passing block 1 gives): only its green sites, ~45 DN
        final Parameters plain = parameters();
        plain.refineBlackLevel(frames.get(1).buffer, 1024, 768, 1);
        assertTrue("read as plain Bayer: " + plain.blackLevel[0], plain.blackLevel[0] > 30f);
    }

    @Test public void theShotsStreamKeepsItsFloorForAFrameWithoutShade() throws Exception {
        // the shot's stream (MosaicBlockStore.shotKey) proved without its pedestal by one shot; the next shot of that stream has no
        // deep shade: its own floor (~19 DN, the wall's red) would take 19 DN off every channel - it takes the stream's
        MosaicBlockStore.setShotKey(STREAM, 2);
        final ArrayList<ImageFrame> first = new ArrayList<>();
        first.add(frame(quad(true, 5), 1_000_000L));
        ScamProcessor.refineBlackFromDarkestFrame(parameters(), first);
        final ArrayList<ImageFrame> next = new ArrayList<>();
        next.add(frame(quad(false, 6), 1_000_000L));
        final Parameters p = parameters();
        ScamProcessor.refineBlackFromDarkestFrame(p, next);
        for (float v : p.blackLevel) assertTrue("the stream's floor: black " + v, v < 3f);
        // a shot of another stream proves nothing by it
        MosaicBlockStore.setShotKey("5|4096x3072|p386-other|5", 2);
        final Parameters other = parameters();
        ScamProcessor.refineBlackFromDarkestFrame(other, next);
        assertTrue("another stream: its own floor " + other.blackLevel[0], other.blackLevel[0] > 15f);
    }
}
