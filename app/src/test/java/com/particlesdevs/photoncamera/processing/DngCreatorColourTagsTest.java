package com.particlesdevs.photoncamera.processing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.Application;
import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.render.Parameters;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * P370 (A3, review): what DngCreator.setParameters writes of the camera's colour (P243 off: no JPEG profile): placeholder forward
 * matrices (sRGB -> XYZ) beside real colour matrices are left out, real ones written; an all-zero ColorMatrix (a camera without the
 * tags) or a singular one is not written. The native writer is a mock (its setters record the calls).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = DngCreatorColourTagsTest.NoNativeDng.class,
        instrumentedPackages = "com.particlesdevs.photoncamera.processing")
public class DngCreatorColourTagsTest {
    @Implements(DngCreator.class)
    public static class NoNativeDng {
        @Implementation protected static void __staticInitializer__() { }
    }

    private static final float[] SRGB_TO_XYZ_D50 = {0.4360747f, 0.3850649f, 0.1430804f, 0.2225045f, 0.7168786f, 0.0606169f,
            0.0139322f, 0.0971045f, 0.7141733f};
    private static final float[] CM = {0.6178f, -0.1002f, -0.0970f, -0.4729f, 1.4118f, 0.0345f, -0.0861f, 0.2593f, 0.4441f};
    private static final float[] FM1 = {70 / 128f, 40 / 128f, 14 / 128f, 30 / 128f, 90 / 128f, 8 / 128f, 4 / 128f, 20 / 128f, 82 / 128f};

    private static Parameters parameters(float[] cm, float[] fm1, float[] fm2) {
        final Parameters p = mock(Parameters.class);
        p.ColorMatrix1 = cm.clone();
        p.ColorMatrix2 = cm.clone();
        p.ForwardTransform1 = fm1.clone();
        p.ForwardTransform2 = fm2.clone();
        p.calibrationTransform1 = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        p.calibrationTransform2 = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        p.calibrationIlluminant1 = 21;
        p.calibrationIlluminant2 = 17;
        p.whitePoint = new float[]{0.5f, 1f, 0.6f};
        p.blackLevel = new float[]{64, 64, 64, 64};
        p.gainMap = new float[]{1, 1, 1, 1};
        p.mapSize = new Point(1, 1);
        p.rawSize = new Point(64, 48);
        doCallRealMethod().when(p).dngForwardMatrices();
        return p;
    }

    private static DngCreator writer(Parameters p) {
        final DngCreator dng = mock(DngCreator.class);
        doCallRealMethod().when(dng).setParameters(any());
        doCallRealMethod().when(dng).toDouble(any());
        try {
            dng.setParameters(p);
        } catch (RuntimeException outsideTheColour) {
            // a field this mock of Parameters does not fill; the colour tags are written before the frame's own metadata
        }
        return dng;
    }

    @Test public void placeholderForwardMatricesAreLeftOut() {
        final DngCreator dng = writer(parameters(CM, SRGB_TO_XYZ_D50, SRGB_TO_XYZ_D50));
        verify(dng).setColorMatrix1(any(double[].class));
        verify(dng, never()).setForwardMatrix1(any(double[].class));
        verify(dng, never()).setForwardMatrix2(any(double[].class));
        final DngCreator real = writer(parameters(CM, FM1, FM1));
        verify(real).setForwardMatrix1(any(double[].class));
        verify(real).setForwardMatrix2(any(double[].class));
    }

    @Test public void anAllZeroOrSingularColourMatrixIsNotWritten() {
        final DngCreator zero = writer(parameters(new float[9], FM1, FM1));
        verify(zero, never()).setColorMatrix1(any(double[].class));
        verify(zero, never()).setColorMatrix2(any(double[].class));
        final DngCreator singular = writer(parameters(new float[]{1, 1, 1, 1, 1, 1, 1, 1, 1}, FM1, FM1));
        verify(singular, never()).setColorMatrix1(any(double[].class));
    }
}
