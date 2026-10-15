package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IszGainMapCropTest {
    private Bitmap gradient() {
        Bitmap image = Bitmap.createBitmap(40, 25, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 25; y++) for (int x = 0; x < 40; x++)
            image.setPixel(x, y, Color.rgb(16 + y * 8, 16 + x * 4, 16));
        return image;
    }

    @Test public void fractionalMapOriginMatchesTheTwoPixelBaseCropInsteadOfTruncatingIt() {
        Bitmap original = gradient();
        PostPipeline.GainMapRaw map = new PostPipeline.GainMapRaw(original, 40, 25, 4, 8f);
        PostPipeline.GainMapRaw cropped = map.croppedTo(new Rect(16, 2, 144, 98), 160, 100);
        assertEquals(32, cropped.w);
        assertEquals(24, cropped.h);
        assertEquals(4, cropped.down);
        assertEquals(8f, cropped.scale, 0f);
        assertTrue(original.isRecycled());
        assertEquals(20, Color.red(cropped.bitmap.getPixel(0, 0)), 1);
        assertEquals(32, Color.green(cropped.bitmap.getPixel(0, 0)), 1);
        assertEquals(204, Color.red(cropped.bitmap.getPixel(31, 23)), 1);
        assertEquals(156, Color.green(cropped.bitmap.getPixel(31, 23)), 1);
        cropped.bitmap.recycle();
    }

    @Test public void noCropKeepsTheMapAndInvalidCropDoesNotDestroyIt() {
        Bitmap original = gradient();
        PostPipeline.GainMapRaw map = new PostPipeline.GainMapRaw(original, 40, 25, 4, 8f);
        assertSame(map, map.croppedTo(new Rect(0, 0, 160, 100), 160, 100));
        try {
            map.croppedTo(new Rect(0, 0, 161, 100), 160, 100);
            fail("outside SDR bounds");
        } catch (IllegalArgumentException expected) {
            assertFalse(original.isRecycled());
        }
        original.recycle();
    }

    @Test public void theCurrentFullSizeHdrMapRetainsEveryEncodedGainExactly() {
        Bitmap original = gradient();
        int[] expected = new int[32 * 24];
        original.getPixels(expected, 0, 32, 4, 0, 32, 24);
        PostPipeline.GainMapRaw map = new PostPipeline.GainMapRaw(original, 40, 25, 1, 8f);
        PostPipeline.GainMapRaw cropped = map.croppedTo(new Rect(4, 0, 36, 24), 40, 25);
        int[] actual = new int[32 * 24];
        cropped.bitmap.getPixels(actual, 0, 32, 0, 0, 32, 24);
        assertArrayEquals(expected, actual);
        assertEquals(1, cropped.down);
        assertEquals(8f, cropped.scale, 0f);
        cropped.bitmap.recycle();
    }
}
