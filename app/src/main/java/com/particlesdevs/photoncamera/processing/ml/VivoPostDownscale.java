package com.particlesdevs.photoncamera.processing.ml;

import android.graphics.Bitmap;
import com.particlesdevs.photoncamera.util.Log;

/** Final resize of a successful Vivo result, before gain-map generation / encoding. */
public final class VivoPostDownscale {
    private VivoPostDownscale() {}
    private static final class Native {
        static { System.loadLibrary("lanczosDownscale"); }
        static void load() {}
    }
    private static native boolean nativeResize(Bitmap source, Bitmap destination, int lobes);

    public static int[] outputSize(int width, int height, int originalWidth, int originalHeight, String size) {
        if (width < 1 || height < 1 || originalWidth < 1 || originalHeight < 1)
            throw new IllegalArgumentException("Invalid image dimensions");
        if ("original".equals(size)) {
            // Vivo keeps the aspect ratio. Never upscale an already smaller result.
            double scale = Math.min(1.0, Math.min(originalWidth/(double)width, originalHeight/(double)height));
            return new int[]{Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale))};
        }
        double scale;
        switch (size) {
            case "75": scale=.75; break;
            case "67": scale=2.0/3.0; break;
            case "50": scale=.5; break;
            case "33": scale=1.0/3.0; break;
            case "25": scale=.25; break;
            default: throw new IllegalArgumentException("Unknown downscale size: "+size);
        }
        // Ceiling avoids exceeding the supported 4:1 reduction on odd dimensions.
        return new int[]{Math.max(1,(int)Math.ceil(width*scale)),Math.max(1,(int)Math.ceil(height*scale))};
    }

    public static Bitmap process(Bitmap source, int originalWidth, int originalHeight, int lobes, String size) {
        if (lobes==0) return source;
        if (lobes<2 || lobes>5) throw new IllegalArgumentException("Lanczos lobes must be 2–5");
        int[] out=outputSize(source.getWidth(),source.getHeight(),originalWidth,originalHeight,size);
        if (out[0]==source.getWidth() && out[1]==source.getHeight()) return source;
        Native.load();
        Bitmap result=Bitmap.createBitmap(out[0],out[1],Bitmap.Config.ARGB_8888);
        boolean success=false;
        long started=System.nanoTime();
        try {
            if (!nativeResize(source,result,lobes)) throw new IllegalStateException("Lanczos downscale failed");
            success=true;
            Log.i("VivoDownscale","Lanczos "+lobes+" after Vivo: "+source.getWidth()+"x"+source.getHeight()+
                    " -> "+out[0]+"x"+out[1]+" linear sRGB, ms="+(System.nanoTime()-started)/1000000);
            return result;
        } finally {
            if (!success) result.recycle();
        }
    }
}
