package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IszAspectCropTest {
    @Test public void nativeFourThirdsNeedsNoCropAndWideSensorKeepsAnExactEvenCenter() {
        assertEquals(new Rect(0, 0, 4096, 3072), IszAspectCrop.bounds(4096, 3072));
        assertEquals(new Rect(344, 2, 3752, 2558), IszAspectCrop.bounds(4096, 2560));
        assertEquals(new Rect(0, 6, 4080, 3066), IszAspectCrop.bounds(4080, 3072));
        assertEquals(new Rect(2, 344, 2558, 3752), IszAspectCrop.bounds(2560, 4096));
    }

    @Test public void fourThirdsBitmapIsReturnedWithoutCopying() {
        Bitmap original = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
        assertSame(original, IszAspectCrop.crop(original, IszAspectCrop.bounds(80, 60)));
        assertFalse(original.isRecycled());
        original.recycle();
    }

    @Test public void cropCopiesNativeTenBitPixelsExactlyWithoutResamplingOrChangingTheSource() {
        int width = 80, height = 50;
        int[] input = new int[width * height];
        Random random = new Random(521);
        for (int i = 0; i < input.length; i++) input[i] = 0xc0000000 | random.nextInt(1 << 30);
        ByteBuffer words = ByteBuffer.allocateDirect(input.length * 4).order(ByteOrder.nativeOrder());
        words.asIntBuffer().put(input);
        Bitmap original = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_1010102);
        original.copyPixelsFromBuffer(words);
        Rect region = IszAspectCrop.bounds(width, height);
        Bitmap cropped = IszAspectCrop.crop(original, region);
        assertEquals(Bitmap.Config.RGBA_1010102, cropped.getConfig());
        assertEquals(64, cropped.getWidth());
        assertEquals(48, cropped.getHeight());
        ByteBuffer output = ByteBuffer.allocateDirect(cropped.getByteCount()).order(ByteOrder.nativeOrder());
        cropped.copyPixelsToBuffer(output);
        output.rewind();
        int[] actual = new int[cropped.getWidth() * cropped.getHeight()];
        output.asIntBuffer().get(actual);
        for (int y = 0; y < cropped.getHeight(); y++) for (int x = 0; x < cropped.getWidth(); x++)
            assertEquals(input[(region.top + y) * width + region.left + x], actual[y * cropped.getWidth() + x]);
        assertFalse(original.isRecycled());
        cropped.recycle();
        original.recycle();
    }
}
