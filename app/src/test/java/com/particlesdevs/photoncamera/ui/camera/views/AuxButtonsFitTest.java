package com.particlesdevs.photoncamera.ui.camera.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.particlesdevs.photoncamera.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P181 (owner, 2026-10-16: «у нас модули выходят за границы экрана»): the lens strip keeps every tile on the screen - the
 * tiles shrink together when they do not fit, and keep their own size when they do.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w360dp-h792dp-xxxhdpi")
public class AuxButtonsFitTest {
    private static final String[] X200_ULTRA = {"0,4×", "0,8×", "1×", "2×", "2,4×", "3,7×", "4,8×", "9,6×", "7,4×", "14,8×"};

    private AuxButtonsLayout strip(int count, int selected) {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        AuxButtonsLayout strip = new AuxButtonsLayout(context, null);
        for (int i = 0; i < count; i++) strip.addNewButton("back" + i, X200_ULTRA[i]);
        strip.getChildAt(selected).setSelected(true);
        return strip;
    }

    /** Lays the strip out at most {@code width} px wide (as the bottom bar's constrained wrap_content does). */
    private static void layout(AuxButtonsLayout strip, int width) {
        strip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        strip.layout(0, 0, strip.getMeasuredWidth(), strip.getMeasuredHeight());
    }

    @Test
    public void tenLensesStayOnTheScreen() {
        int width = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().widthPixels;
        AuxButtonsLayout strip = strip(10, 2);
        layout(strip, width);
        assertTrue("strip " + strip.getMeasuredWidth() + " of " + width, strip.getMeasuredWidth() <= width);
        for (int i = 0; i < strip.getChildCount(); i++) {
            View tile = strip.getChildAt(i);
            assertTrue("tile " + i + " " + tile.getLeft() + ".." + tile.getRight(), tile.getLeft() >= 0 && tile.getRight() <= strip.getWidth());
            assertTrue("tile " + i + " visible", tile.getWidth() > 0);
            assertTrue("tile " + i + " whole: " + tile.getMeasuredWidth() + " of " + ((LensButton) tile).getMinWidth(),
                    tile.getMeasuredWidth() >= ((LensButton) tile).getMinWidth());
            assertTrue("shrunk", ((LensButton) tile).fit() < 1f);
        }
        // a relayout keeps the same fit (no layout loop)
        float fit = ((LensButton) strip.getChildAt(0)).fit();
        layout(strip, width);
        assertEquals(fit, ((LensButton) strip.getChildAt(0)).fit(), 0f);
    }

    @Test
    public void fewLensesKeepTheirOwnSize() {
        int width = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().widthPixels;
        AuxButtonsLayout strip = strip(4, 1);
        layout(strip, width);
        for (int i = 0; i < strip.getChildCount(); i++) assertEquals(1f, ((LensButton) strip.getChildAt(i)).fit(), 0f);
    }

    @Test
    public void theFitIsTheShareThatFits() {
        assertEquals(1f, AuxButtonsLayout.fit(500f, 1000f), 0f);
        assertEquals(1f, AuxButtonsLayout.fit(1000f, 1000f), 0f);
        assertEquals(998f / 1200f, AuxButtonsLayout.fit(1200f, 1000f), 1e-6f);
        assertEquals(LensButton.MIN_FIT, AuxButtonsLayout.fit(5000f, 1000f), 0f);
    }
}
