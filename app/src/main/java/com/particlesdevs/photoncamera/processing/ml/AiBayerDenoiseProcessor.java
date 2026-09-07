package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/** Runs Raspberry Pi's Bayer denoise models on the merged linear RAW. */
public final class AiBayerDenoiseProcessor {
    private static final String TAG = "AiBayerDenoise";
    private static final String MODEL_QUALITY = "models/ai_denoise/nafnet_bayer_small.tflite";
    private static final String MODEL_FAST = "models/ai_denoise/unet_bayer_fast.tflite";
    private static final int BORDER = 16;

    private AiBayerDenoiseProcessor() {}

    /**
     * Denoises in place. Failure is deliberately non-fatal: the original RAW remains usable.
     * Returns true only when every patch was processed and committed.
     */
    public static boolean process(Context context, ByteBuffer raw, Parameters p, int strength,
                                  int lumaStrength, int chromaStrength, String modelMode) {
        if (context == null || raw == null || strength <= 0) return false;
        final float mix = Math.min(100, strength) / 100.0f;
        final float lumaMix = Math.max(0, Math.min(100, lumaStrength)) / 100.0f;
        final float chromaMix = Math.max(0, Math.min(100, chromaStrength)) / 100.0f;
        final boolean fast = !"quality".equals(modelMode);
        final String modelPath = fast ? MODEL_FAST : MODEL_QUALITY;
        final String modelName = fast ? "UNet Bayer Fast" : "NAFNet Bayer Small";
        final long started = System.currentTimeMillis();
        NnApiDelegate nnapi = null;
        Interpreter interpreter = null;
        try {
            MappedByteBuffer model = mapAsset(context, modelPath);
            Interpreter.Options options = new Interpreter.Options().setNumThreads(4);
            try {
                NnApiDelegate.Options nnOptions = new NnApiDelegate.Options()
                        .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER)
                        .setUseNnapiCpu(false)
                        .setAllowFp16(true)
                        .setCacheDir(context.getCodeCacheDir().getAbsolutePath())
                        .setModelToken(fast ? "hdrplus_unet_bayer_fast_v1"
                                : "hdrplus_nafnet_bayer_small_v1");
                nnapi = new NnApiDelegate(nnOptions);
                options.addDelegate(nnapi);
                interpreter = new Interpreter(model, options);
                Log.i(TAG, modelName + " initialized with NNAPI accelerator-only delegate");
            } catch (Throwable npuError) {
                if (interpreter != null) interpreter.close();
                if (nnapi != null) nnapi.close();
                interpreter = null;
                nnapi = null;
                Log.w(TAG, "NNAPI/NPU unavailable; explicit CPU fallback: " + npuError);
                interpreter = new Interpreter(model,
                        new Interpreter.Options().setNumThreads(Math.max(2,
                                Math.min(6, Runtime.getRuntime().availableProcessors()))));
            }

            int[] shape = interpreter.getInputTensor(0).shape();
            if (shape.length != 4 || shape[0] != 1 || shape[1] != shape[2] || shape[3] != 4) {
                throw new IllegalStateException("Unexpected model input shape");
            }
            int patch = shape[1];
            int stride = patch - 2 * BORDER;
            if (stride <= 0) throw new IllegalStateException("Model patch is too small");

            ByteBuffer input = ByteBuffer.allocateDirect(patch * patch * 4 * 4)
                    .order(ByteOrder.nativeOrder());
            ByteBuffer output = ByteBuffer.allocateDirect(patch * patch * 4 * 4)
                    .order(ByteOrder.nativeOrder());
            raw.order(ByteOrder.nativeOrder());
            int halfW = p.rawSize.x / 2;
            int halfH = p.rawSize.y / 2;
            int[][] sites = bayerSites(p.cfaPattern);
            float[] gains = new float[]{
                    safeGain(p.whitePoint, 2), safeGain(p.whitePoint, 1),
                    safeGain(p.whitePoint, 1), safeGain(p.whitePoint, 0)};
            float white = Math.max(1.0f, p.whiteLevel);

            for (int oy = 0; oy < halfH; oy += stride) {
                for (int ox = 0; ox < halfW; ox += stride) {
                    input.clear();
                    for (int py = 0; py < patch; py++) {
                        int sy = reflect(oy + py - BORDER, halfH);
                        for (int px = 0; px < patch; px++) {
                            int sx = reflect(ox + px - BORDER, halfW);
                            for (int c = 0; c < 4; c++) {
                                int rx = sx * 2 + sites[c][0];
                                int ry = sy * 2 + sites[c][1];
                                int value = raw.getShort((ry * p.rawSize.x + rx) * 2) & 0xffff;
                                input.putFloat(Math.max(0.0f, value / white) * gains[c]);
                            }
                        }
                    }
                    input.rewind();
                    output.clear();
                    interpreter.run(input, output);
                    output.rewind();

                    int writeH = Math.min(stride, halfH - oy);
                    int writeW = Math.min(stride, halfW - ox);
                    float[] original = new float[4];
                    float[] denoised = new float[4];
                    // B, Gb, Gr, R weights; the standard green luminance
                    // coefficient is shared by the two Bayer green sites.
                    final float[] lumaWeights = {0.0722f, 0.3576f, 0.3576f, 0.2126f};
                    for (int y = 0; y < writeH; y++) {
                        int py = y + BORDER;
                        for (int x = 0; x < writeW; x++) {
                            int px = x + BORDER;
                            int base = ((py * patch + px) * 4) * 4;
                            for (int c = 0; c < 4; c++) {
                                int rx = (ox + x) * 2 + sites[c][0];
                                int ry = (oy + y) * 2 + sites[c][1];
                                int offset = (ry * p.rawSize.x + rx) * 2;
                                original[c] = raw.getShort(offset) & 0xffff;
                                denoised[c] = output.getFloat(base + c * 4) / gains[c] * white;
                            }
                            float originalLuma = 0.0f;
                            float denoisedLuma = 0.0f;
                            for (int c = 0; c < 4; c++) {
                                originalLuma += original[c] * lumaWeights[c];
                                denoisedLuma += denoised[c] * lumaWeights[c];
                            }
                            float lumaDelta = (denoisedLuma - originalLuma) * lumaMix;
                            for (int c = 0; c < 4; c++) {
                                float originalChroma = original[c] - originalLuma;
                                float denoisedChroma = denoised[c] - denoisedLuma;
                                float componentTarget = original[c] + lumaDelta
                                        + (denoisedChroma - originalChroma) * chromaMix;
                                int blended = Math.round(original[c]
                                        + (componentTarget - original[c]) * mix);
                                int rx = (ox + x) * 2 + sites[c][0];
                                int ry = (oy + y) * 2 + sites[c][1];
                                int offset = (ry * p.rawSize.x + rx) * 2;
                                raw.putShort(offset, (short) Math.max(0, Math.min(p.whiteLevel, blended)));
                            }
                        }
                    }
                }
            }
            raw.rewind();
            Log.i(TAG, modelName + " RAW denoise completed: strength=" + strength
                    + ", luma=" + lumaStrength + ", chroma=" + chromaStrength
                    + ", elapsed=" + (System.currentTimeMillis() - started) + " ms");
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "NAFNet RAW denoise skipped after failure", error);
            raw.rewind();
            return false;
        } finally {
            if (interpreter != null) interpreter.close();
            if (nnapi != null) nnapi.close();
        }
    }

    private static MappedByteBuffer mapAsset(Context context, String path) throws Exception {
        try (AssetFileDescriptor fd = context.getAssets().openFd(path);
             FileInputStream stream = new FileInputStream(fd.getFileDescriptor())) {
            return stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private static float safeGain(float[] whitePoint, int color) {
        if (whitePoint == null || whitePoint.length <= color || whitePoint[color] <= 0.0f) return 1.0f;
        return Math.max(1.0f, 1.0f / whitePoint[color]);
    }

    private static int reflect(int v, int size) {
        if (size <= 1) return 0;
        while (v < 0 || v >= size) v = v < 0 ? -v : 2 * size - 2 - v;
        return v;
    }

    /** Channel order required by the upstream model: B, Gb, Gr, R. */
    private static int[][] bayerSites(byte cfa) {
        switch (cfa) {
            case 1: return new int[][]{{0,1}, {1,1}, {0,0}, {1,0}}; // GRBG
            case 2: return new int[][]{{1,0}, {0,0}, {1,1}, {0,1}}; // GBRG
            case 3: return new int[][]{{0,0}, {1,0}, {0,1}, {1,1}}; // BGGR
            default:return new int[][]{{1,1}, {0,1}, {1,0}, {0,0}}; // RGGB
        }
    }
}
