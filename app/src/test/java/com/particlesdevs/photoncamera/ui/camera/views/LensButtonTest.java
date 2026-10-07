package com.particlesdevs.photoncamera.ui.camera.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * P32: a lens button of the strip keeps its portrait shape (and the selected pill) in every orientation; only the label
 * turns upright, centred, and shrinks when the turned label is taller than the button. OPPO Find X7 Ultra metrics.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w360dp-h792dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LensButtonTest {
    private static final int[] ORIENTATIONS = {0, 90, 180, 270, -90};
    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    @Test
    public void labelsTurnTheShortWayToEveryOrientation() {
        assertEquals(270f, LensButton.normalize(-90f), 0f);
        assertEquals(0f, LensButton.normalize(360f), 0f);
        assertEquals(90f, LensButton.normalize(450f), 0f);
        assertEquals(-90f, LensButton.shortestTurn(0f, -90f), 0f);
        assertEquals(-90f, LensButton.shortestTurn(0f, 270f), 0f);
        assertEquals(360f, LensButton.shortestTurn(270f, 0f), 0f);
        assertEquals(270f, LensButton.shortestTurn(90f, -90f), 0f);
        for (float from = 0; from < 360; from += 90)
            for (int to : ORIENTATIONS) {
                float end = LensButton.shortestTurn(from, to);
                assertTrue(from + " -> " + to, Math.abs(end - from) <= 180f);
                assertEquals(from + " -> " + to, LensButton.normalize(to), LensButton.normalize(end), 0f);
            }
    }

    @Test
    public void aTurnedLabelShrinksOnlyWhenItIsTallerThanTheButton() {
        // «1× (2)»: 38 x 16 in a 56 x 40 button with 4dp room on each side (48 x 32).
        for (int o : new int[]{0, 180, 360}) assertEquals(1f, LensButton.labelScale(o, 38, 16, 48, 32), 1e-6f);
        for (int o : new int[]{90, 270, -90}) assertEquals(32f / 38f, LensButton.labelScale(o, 38, 16, 48, 32), 1e-4f);
        // «2×» fits turned as it is.
        assertEquals(1f, LensButton.labelScale(90, 18, 16, 40, 32), 1e-6f);
        // Half way round (the turn animation) the label shrinks no more than it has to.
        float half = LensButton.labelScale(45, 38, 16, 48, 32);
        assertTrue(half < 1f && half > 0.6f);
        double c = Math.cos(Math.PI / 4);
        assertTrue((38 * c + 16 * c) * half <= 32 + 1e-3);
        assertEquals(1f, LensButton.labelScale(90, 0, 0, 0, 0), 0f);
    }

    @Test
    public void theSelectedPillKeepsItsPortraitShapeAndTheLabelStaysInside() throws Exception {
        LensButton button = new LensButton(context);
        button.setText("1× (2)");
        button.setTypeface(null, Typeface.BOLD);
        button.setSelected(true);
        int accent = AccentPalette.camera(context);
        measure(button);
        int width = button.getWidth(), height = button.getHeight();
        assertEquals(dp(40), height);
        assertTrue("a pill wider than tall", width > height);
        for (int o : ORIENTATIONS) {
            button.turnLabel(o, 0);
            measure(button);
            assertEquals(0f, button.getRotation(), 0f);
            assertEquals(LensButton.normalize(o), LensButton.normalize(button.getLabelRotation()), 0f);
            assertEquals("same width at " + o, width, button.getWidth());
            assertEquals("same height at " + o, height, button.getHeight());
            int margin = dp(24);
            Bitmap image = Bitmap.createBitmap(width + 2 * margin, height + 2 * margin, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.translate(margin, margin);
            button.draw(canvas);
            File dir = new File("build/reports/viewfinder");
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, "lens-button-" + (int) LensButton.normalize(o) + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            // Nothing is drawn outside the button: no clipped label, no turned capsule.
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    boolean inside = x >= margin && x < margin + width && y >= margin && y < margin + height;
                    if (!inside) assertEquals("ink outside the button at " + o + " (" + x + "," + y + ")", 0, Color.alpha(image.getPixel(x, y)));
                }
            // The pill: both round ends and the middle of the top edge are in the accent, as in portrait.
            assertEquals(accent, image.getPixel(margin + dp(3), margin + height / 2));
            assertEquals(accent, image.getPixel(margin + width - 1 - dp(3), margin + height / 2));
            assertEquals(accent, image.getPixel(margin + width / 2, margin + dp(1)));
            // The label: drawn, centred, turned with the phone (taller than wide in landscape) and clear of the edges.
            int left = Integer.MAX_VALUE, right = -1, top = Integer.MAX_VALUE, bottom = -1, ink = 0;
            for (int y = margin; y < margin + height; y++)
                for (int x = margin; x < margin + width; x++) {
                    int p = image.getPixel(x, y);
                    // Dark text on the pill; the transparent corners around the pill's round ends are no ink.
                    if (Color.alpha(p) < 200 || Color.red(p) + Color.green(p) + Color.blue(p) >= 300) continue;
                    ink++;
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            assertTrue("label drawn at " + o, ink > 100);
            int inkWidth = right - left + 1, inkHeight = bottom - top + 1;
            if (o % 180 == 0) assertTrue("upright label at " + o, inkWidth > inkHeight);
            else assertTrue("turned label at " + o, inkHeight > inkWidth);
            assertTrue("label clear of the top at " + o, top - margin >= dp(LensButton.LABEL_INSET_DP) - 2);
            assertTrue("label clear of the bottom at " + o, margin + height - 1 - bottom >= dp(LensButton.LABEL_INSET_DP) - 2);
            assertEquals("label centred across at " + o, margin + width / 2f, (left + right) / 2f, dp(3));
            assertEquals("label centred down at " + o, margin + height / 2f, (top + bottom) / 2f, dp(4));
        }
    }

    private void measure(LensButton button) {
        button.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(dp(40), View.MeasureSpec.EXACTLY));
        button.layout(0, 0, button.getMeasuredWidth(), button.getMeasuredHeight());
    }
}
