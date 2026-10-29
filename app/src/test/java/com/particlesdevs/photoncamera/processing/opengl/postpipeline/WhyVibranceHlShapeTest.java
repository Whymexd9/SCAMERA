package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P393 (owner, 2026-10-29: «У нас что-то с желтыми цветом ... В последней паре более жёлтое фото наше»): the light-tone vibrance's
 * shape for pale colour (why/combine.glsl vibHlShapeU) - the ramp OKLab L 0.50 .. 0.85 and the cast gate 0.010 .. 0.025 by default,
 * P341's 0.45 .. 0.75 / 0.002 .. 0.008 with scam_dev "vibrance_hl_shape 0"; the photo and the RAW viewfinder set the same, the
 * shader's unset default is the Java one, and the line is on «Для разработчика». The pixels: tools/check_vibrance_hl.py.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class WhyVibranceHlShapeTest {
    private MockedStatic<PreferenceKeys> prefs;
    private static boolean shapeOn = true;

    @Before public void setUp() {
        shapeOn = true;
        prefs = mockStatic(PreferenceKeys.class, invocation -> {
            if (invocation.getMethod().getName().equals("scamDevSwitch") && "vibrance_hl_shape".equals(invocation.getArgument(0)))
                return shapeOn;
            return invocation.callRealMethod();
        });
    }

    @After public void tearDown() {
        prefs.close();
    }

    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void theDefaultIsTheLaterRampAndTheCastGate() {
        assertArrayEquals(new float[]{0.50f, 0.85f, 0.010f, 0.025f}, WhyTone.vibranceHlShape(), 0f);
        // a copy: the caller cannot change the constant
        float[] s = WhyTone.vibranceHlShape();
        assertNotSame(WhyTone.VIBRANCE_HL_SHAPE, s);
        s[0] = 9f;
        assertArrayEquals(new float[]{0.50f, 0.85f, 0.010f, 0.025f}, WhyTone.vibranceHlShape(), 0f);
    }

    @Test
    public void theDevLineOffIsP341sShape() {
        shapeOn = false;
        assertArrayEquals(new float[]{0.45f, 0.75f, 0.002f, 0.008f}, WhyTone.vibranceHlShape(), 0f);
        // P341's literals in why/combine.glsl before P393 (its ramp and gate)
        assertArrayEquals(new float[]{0.45f, 0.75f, 0.002f, 0.008f}, WhyTone.VIBRANCE_HL_SHAPE_P341, 0f);
    }

    @Test
    public void theShaderTheViewfinderAndThePageAgree() throws IOException {
        final String shader = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue("the shader's unset default is WhyTone.VIBRANCE_HL_SHAPE",
                shader.contains(": vec4(0.50, 0.85, 0.010, 0.025);"));
        assertTrue("the shader declares vibHlShapeU", shader.contains("uniform vec4 vibHlShapeU;"));
        final String src = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";
        assertTrue("the photo sets it", read(src + "WhyCombine.java").contains("glProg.setVar(\"vibHlShapeU\", WhyTone.vibranceHlShape());"));
        final String live = read(src + "LiveWhyTone.java");
        assertTrue("the RAW viewfinder takes it", live.contains("vibHlShape = WhyTone.vibranceHlShape();")
                && live.contains("loc(p, \"vibHlShapeU\"), vibHlShape[0], vibHlShape[1], vibHlShape[2], vibHlShape[3]"));
        assertTrue("«Для разработчика» lists the line",
                read("src/main/java/com/particlesdevs/photoncamera/settings/DevKeys.java").contains("\"vibrance_hl_shape\", true"));
    }
}
