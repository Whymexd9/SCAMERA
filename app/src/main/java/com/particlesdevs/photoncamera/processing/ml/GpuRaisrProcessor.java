package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLImage;
import com.particlesdevs.photoncamera.processing.opengl.GLOneScript;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes9.dex */
public final class GpuRaisrProcessor {
    private static final long MAX_OUTPUT_PIXELS = 100663296;
    private static final String TAG = "GpuNanoRAISR";

    private GpuRaisrProcessor() {
    }

    public static Bitmap process(Context context, final Bitmap source) throws Exception {
        final int bankScale = Math.max(2, Math.min(4, PreferenceKeys.getRaisrFilterScale()));
        final int scaleTenths = Math.max(10, Math.min(40, PreferenceKeys.getRaisrOutputScale()));
        final int outW = Math.max(1, Math.round((source.getWidth() * scaleTenths) / 10.0f));
        final int outH = Math.max(1, Math.round((source.getHeight() * scaleTenths) / 10.0f));
        if (outW * outH > MAX_OUTPUT_PIXELS) {
            throw new IllegalArgumentException("GPU RAISR output exceeds 96 MP");
        }
        final boolean quality = "quality".equals(PreferenceKeys.getRaisrMode());
        int tapStep = quality ? 1 : 2;
        final int kernelCount = bankScale * bankScale * 24 * 3 * 3;
        int coefficientCount = kernelCount * 25;
        String bank = "models/raisr/nano_raisr_" + bankScale + "x_q12.bin";
        final ByteBuffer coefficients = ByteBuffer.allocateDirect(coefficientCount * 4).order(ByteOrder.nativeOrder());
        try (DataInputStream in = new DataInputStream(context.getAssets().open(bank))) {
            byte[] raw = new byte[coefficientCount * 2];
            in.readFully(raw);
            ByteBuffer q12 = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] kernel = new float[25];
            for (int k = 0; k < kernelCount; k++) {
                float sum = 0.0f;
                for (int tap = 0; tap < 25; tap++) {
                    kernel[tap] = q12.getShort() / 4096.0f;
                }
                for (int y = -2; y <= 2; y += tapStep) {
                    for (int x = -2; x <= 2; x += tapStep) {
                        sum += kernel[((y + 2) * 5) + x + 2];
                    }
                }
                if (Math.abs(sum) < 0.05f) {
                    sum = 1.0f;
                }
                for (int tap = 0; tap < 25; tap++) {
                    coefficients.putFloat(kernel[tap] / sum);
                }
            }
        }
        coefficients.rewind();
        final Point outputSize = new Point(outW, outH);
        final GLFormat rgba8 = new GLFormat(GLFormat.DataType.UNSIGNED_8, 4);
        GLImage output = new GLImage(outputSize, rgba8, false);
        ByteBuffer outputBytes = ByteBuffer.allocateDirect(outW * outH * 4);
        GLDrawParams.TileSize = Math.min(GLDrawParams.TileSize, 128);
        long started = System.currentTimeMillis();
        GLOneScript script = new GLOneScript(outputSize, output, rgba8, "RAISR/raisr", TAG) {
                GLTexture filterTexture;
                GLTexture inputTexture;

                @Override // com.particlesdevs.photoncamera.processing.opengl.GLOneScript
                public void Compile() {
                    this.glOne.glProgram.setDefine("INPUT_SIZE", source.getWidth() + "," + source.getHeight());
                    this.glOne.glProgram.setDefine("OUTPUT_SIZE", outW + "," + outH);
                    this.glOne.glProgram.setDefine("BANK_SCALE", bankScale);
                    this.glOne.glProgram.setDefine("OUTPUT_SCALE", scaleTenths / 10.0f);
                    this.glOne.glProgram.setDefine("EFFECT", PreferenceKeys.getRaisrStrength() / 100.0f);
                    this.glOne.glProgram.setDefine("HALO", PreferenceKeys.getRaisrHaloProtection() / 100.0f);
                    this.glOne.glProgram.setDefine("ALIAS", PreferenceKeys.getRaisrAliasingSuppression() / 100.0f);
                    this.glOne.glProgram.setDefine("QUALITY", quality);
                    super.Compile();
                }

                @Override // com.particlesdevs.photoncamera.processing.opengl.GLOneScript
                public void StartScript() {
                    this.inputTexture = new GLTexture(new GLImage(source), 9729, 33071, 0);
                    this.filterTexture = new GLTexture(new Point(25, kernelCount), new GLFormat(GLFormat.DataType.FLOAT_32, 1), coefficients, 9728, 33071);
                    this.glOne.glProgram.setTexture("InputBuffer", this.inputTexture);
                    this.glOne.glProgram.setTexture("FilterBank", this.filterTexture);
                    this.WorkingTexture = new GLTexture(outputSize, rgba8);
                }
        };
        script.Output = outputBytes;
        try {
            script.Run();
            Bitmap result = output.getBufferedImage();
            Log.i(TAG, "GPU RAISR x" + bankScale + " " + (scaleTenths / 10.0f)
                    + "x completed in " + (System.currentTimeMillis() - started) + " ms");
            return result;
        } finally {
            try {
                GLTexture.closeAll();
            } catch (Throwable ignored) {
            }
            script.close();
        }
    }
}
