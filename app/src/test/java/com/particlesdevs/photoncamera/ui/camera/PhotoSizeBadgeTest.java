package com.particlesdevs.photoncamera.ui.camera;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;

/**
 * P319 (user report 2026-10-22: «надпись "12 Мп" даже, если камера имеет большее или меньшее разрешение (не забываем про
 * фронталки)»): the size pill names the photo the lens really writes - its stream, the «16:9» crop, «Разрешение».
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PhotoSizeBadgeTest {
    @Test public void theOutputFollowsTheStreamTheCropAndTheResolution() {
        // a 50 MP main binned to 4096 x 3072: 12.6 MP, not «12»
        assertArrayEquals(new int[]{4096, 3072}, PhotoSize.output(4096, 3072, false, "sensor"));
        // an 8 MP front stays 8 MP; with 2x it is 32 MP
        assertArrayEquals(new int[]{3264, 2448}, PhotoSize.output(3264, 2448, false, "sensor"));
        assertArrayEquals(new int[]{6528, 4896}, PhotoSize.output(3264, 2448, false, "2x"));
        // «16:9»: 4096 x 2304 (whole colour blocks)
        assertArrayEquals(new int[]{4096, 2304}, PhotoSize.output(4096, 3072, true, "sensor"));
        // «Разрешение» 16 / 20 on a 4:3 stream: the classic sizes
        assertArrayEquals(new int[]{4608, 3456}, PhotoSize.output(4096, 3072, false, "16"));
        assertArrayEquals(new int[]{5184, 3888}, PhotoSize.output(4096, 3072, false, "20"));
        // a FULLRES 50 MP stream at the sensor size
        assertArrayEquals(new int[]{8192, 6144}, PhotoSize.output(8192, 6144, false, "sensor"));
        // a portrait stream is never cropped to 16:9
        assertArrayEquals(new int[]{2448, 3264}, PhotoSize.output(2448, 3264, true, "sensor"));
    }
}
