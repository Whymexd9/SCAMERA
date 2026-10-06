package com.particlesdevs.photoncamera.ui.camera.views;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;

import com.particlesdevs.photoncamera.app.PhotonCamera;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Lens strip and zoom dial: the decimal comma in the Russian UI (owner's answer 11), the point in English. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class LensLabelLocaleTest {
    private MockedStatic<PhotonCamera> camera;

    @Before public void context() {
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(RuntimeEnvironment.getApplication());
    }
    @After public void cleanup() { camera.close(); }

    @Test
    @Config(qualifiers = "ru")
    public void russianUiUsesTheDecimalComma() {
        assertEquals("0,6×", AuxButtonsLayout.display("0.6×"));
        assertEquals("1× (2)", AuxButtonsLayout.display("1× (2)"));
        assertEquals("2,5", ZoomDialView.format(2.5f));
        assertEquals("3", ZoomDialView.format(3f));
    }

    @Test
    public void englishUiUsesTheDecimalPoint() {
        assertEquals("0.6×", AuxButtonsLayout.display("0.6×"));
        assertEquals("10х", AuxButtonsLayout.display("10х"));   // a module name typed by the user stays as it is
        assertEquals("2.5", ZoomDialView.format(2.5f));
        assertEquals("3", ZoomDialView.format(3f));
    }
}
