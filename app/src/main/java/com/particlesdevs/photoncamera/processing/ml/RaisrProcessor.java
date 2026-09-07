package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

/* loaded from: classes9.dex */
public final class RaisrProcessor {
    private static final long MAX_OUTPUT_PIXELS = 100663296;
    private static final String TAG = "NanoRAISR";

    private static native boolean nativeRun(AssetManager assetManager, String str, int[] iArr, int i, int i2, int i3, int i4, int i5, int i6, int i7, boolean z, int[] iArr2, int i8, int i9);

    static {
        System.loadLibrary("ncnnMl");
    }

    private RaisrProcessor() {
    }

    public static Bitmap process(Context context, Bitmap source) {
        if (source == null || !PreferenceKeys.isRaisrEnabled() || PreferenceKeys.getRaisrStrength() <= 0) {
            return source;
        }
        int bankScale = Math.max(2, Math.min(4, PreferenceKeys.getRaisrFilterScale()));
        int scaleTenths = Math.max(10, Math.min(40, PreferenceKeys.getRaisrOutputScale()));
        int outW = Math.max(1, Math.round((source.getWidth() * scaleTenths) / 10.0f));
        int outH = Math.max(1, Math.round((source.getHeight() * scaleTenths) / 10.0f));
        if (outW * outH > MAX_OUTPUT_PIXELS) {
            throw new IllegalArgumentException("RAISR output " + outW + "x" + outH + " exceeds the safe 96 MP test limit");
        }
        int[] input = new int[source.getWidth() * source.getHeight()];
        int[] output = new int[outW * outH];
        source.getPixels(input, 0, source.getWidth(), 0, 0, source.getWidth(), source.getHeight());
        String bank = "models/raisr/nano_raisr_" + bankScale + "x_q12.bin";
        long started = System.currentTimeMillis();
        boolean ok = nativeRun(context.getAssets(), bank, input, source.getWidth(), source.getHeight(), bankScale, scaleTenths, PreferenceKeys.getRaisrStrength(), PreferenceKeys.getRaisrHaloProtection(), PreferenceKeys.getRaisrAliasingSuppression(), "quality".equals(PreferenceKeys.getRaisrMode()), output, outW, outH);
        if (!ok) {
            throw new IllegalStateException("Native Nano RAISR failed");
        }
        Bitmap result = Bitmap.createBitmap(output, outW, outH, Bitmap.Config.ARGB_8888);
        Log.i(TAG, "Applied bank x" + bankScale + " at " + (scaleTenths / 10.0f) + "x in " + (System.currentTimeMillis() - started) + " ms");
        return result;
    }
}
