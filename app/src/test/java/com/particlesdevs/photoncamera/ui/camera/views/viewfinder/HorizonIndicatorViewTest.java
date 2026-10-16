package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * P174: the horizon level in the camera's style: level within half a degree of the frame's horizontal in every orientation (a
 * degree to leave it), a light haptic tick on the way in; renders build/reports/viewfinder/horizon-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HorizonIndicatorViewTest {
    private HorizonIndicatorView view() {
        HorizonIndicatorView v = new HorizonIndicatorView(RuntimeEnvironment.getApplication(), null);
        int size = Math.round(420 * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density);
        v.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, size, size);
        return v;
    }

    @Test
    public void theTiltIsAgainstTheFramesHorizontalInEveryOrientation() {
        assertEquals(-3f, HorizonIndicatorView.tilt(3f, 0), 1e-4f);
        assertEquals(0f, HorizonIndicatorView.tilt(-90f, 90), 1e-4f);
        assertEquals(2f, HorizonIndicatorView.tilt(-92f, 90), 1e-4f);
        assertEquals(0f, HorizonIndicatorView.tilt(180f, 180), 1e-4f);
        assertEquals(0f, HorizonIndicatorView.tilt(90f, 270), 1e-4f);
    }

    @Test
    public void levelTurnsOnWithinHalfADegreeWithATickAndOffBeyondOne() {
        HorizonIndicatorView v = view();
        v.updateAngles(4f, 0, 0);
        assertFalse(v.isLevel());
        v.updateAngles(0.3f, 0, 0);
        assertTrue(v.isLevel());
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Shadows.shadowOf(v).lastHapticFeedbackPerformed());
        v.updateAngles(0.8f, 0, 0);
        assertTrue("within a degree it stays level", v.isLevel());
        v.updateAngles(1.4f, 0, 0);
        assertFalse(v.isLevel());
        // landscape: level when the line lies along the frame
        v.updateDisplayRotation(90);
        v.updateAngles(-90.2f, 0, 0);
        assertTrue(v.isLevel());
    }

    @Test
    public void tiltedAndLevelRenderedForReview() throws Exception {
        HorizonIndicatorView v = view();
        v.updateAngles(6f, 0, 0);
        Bitmap tilted = render(v, "horizon-tilted.png");
        v.updateAngles(0f, 0, 0);
        Bitmap flat = render(v, "horizon-level.png");
        int c = tilted.getWidth() / 2;
        assertNotEquals("the line closes and turns the accent when level", tilted.getPixel(c + 4, c), flat.getPixel(c + 4, c));
    }

    private static Bitmap render(View v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF6B7A6E);
        v.draw(new Canvas(b));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return b;
    }
}
