package com.particlesdevs.photoncamera.processing.render;

import android.app.Application;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
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
 * RAW black level from the data floor (Parameters.refineBlackLevel). vivo X200 Pro (MediaTek, owner's log 2026-10-07): a
 * 4096x3072 RAW_SENSOR holds the image in 4000x3000 and zeros elsewhere; the zeros set black 0 and every photo came out pink.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class ParametersBlackFloorTest {
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.PhotonCamera> photon;

    @Before public void setup() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager settings = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        photon = mockStatic(com.particlesdevs.photoncamera.app.PhotonCamera.class);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getAppContext).thenReturn(context);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getSettingsManagerStatic).thenReturn(settings);
        photon.when(com.particlesdevs.photoncamera.app.PhotonCamera::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(PreferenceKeys::isRawBlackFromData).thenReturn(true);
    }
    @After public void cleanup() { prefs.close(); photon.close(); }

    private static Parameters parameters() throws Exception {
        Parameters p = new Parameters();
        p.whiteLevel = 1023; p.blackLevel = new float[]{64, 64, 64, 64};
        Field override = Parameters.class.getDeclaredField("blackLevelOverride");
        override.setAccessible(true); override.setFloat(p, -1f);
        return p;
    }

    /** w x h RAW16, floor + noise inside validW x validH, padValue outside. */
    private static ByteBuffer raw(int w, int h, int validW, int validH, int floor, int padValue) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(7);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = x < validW && y < validH ? floor + r.nextInt(5) - 2 + ((x / 256 + y / 256) % 3) * 40 : padValue;
            s.put(y * w + x, (short) v);
        }
        return b;
    }

    @Test public void zeroPaddingDoesNotDriveBlackToZero() throws Exception {
        Parameters p = parameters();
        // 4096x3072 with the image in 4000x3000, like camera 2 of the X200 Pro (scaled by 1/4 both ways)
        p.refineBlackLevel(raw(1024, 768, 1000, 750, 64, 0), 1024, 768);
        for (float b : p.blackLevel) assertEquals(64f, b, 0f);
    }

    @Test public void realFloorBelowReportedBlackStillLowersIt() throws Exception {
        Parameters p = parameters();
        // OPPO Find X8 Ultra: the reported 64 sits above the data floor (~60): lowered as before, padding or not
        p.refineBlackLevel(raw(1024, 768, 1000, 750, 60, 0), 1024, 768);
        for (float b : p.blackLevel) assertEquals(60f, b, 1.01f);
        Parameters q = parameters();
        q.refineBlackLevel(raw(1024, 768, 1024, 768, 60, 0), 1024, 768);
        for (float b : q.blackLevel) assertEquals(60f, b, 1.01f);
    }
}
