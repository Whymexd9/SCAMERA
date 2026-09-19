package com.particlesdevs.photoncamera.processing.ml;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Original extracted Google saliency model, CPU LiteRT; no donor JNI or hooks.
 * Returns attention probabilities, NOT person/sky/skin semantic classes.
 */
public final class SaliencyProtection {
    public static final int WIDTH=512, HEIGHT=384;
    private SaliencyProtection() {}

    public static ByteBuffer infer(ByteBuffer rgba) throws IOException {
        if (rgba.remaining()!=WIDTH*HEIGHT*16) throw new IllegalArgumentException("RGBA float thumbnail size");
        byte[] bytes;
        try(InputStream stream=PhotonCamera.getAssetLoader().getInputStream("models/arkcam_saliency.tflite")) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] chunk=new byte[8192];int n;
            while((n=stream.read(chunk))!=-1)out.write(chunk,0,n);
            bytes=out.toByteArray();
        }
        ByteBuffer model=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        model.put(bytes).rewind();
        try(Interpreter interpreter=new Interpreter(model,new Interpreter.Options().setNumThreads(2))) {
            if(!Arrays.equals(interpreter.getInputTensor(0).shape(),new int[]{1,HEIGHT,WIDTH,3})
                    ||!Arrays.equals(interpreter.getOutputTensor(0).shape(),new int[]{1,HEIGHT,WIDTH,1})
                    ||interpreter.getInputTensor(0).dataType()!=DataType.FLOAT32
                    ||interpreter.getOutputTensor(0).dataType()!=DataType.FLOAT32)
                throw new IllegalStateException("Unexpected saliency model contract");
            ByteBuffer in=ByteBuffer.allocateDirect(WIDTH*HEIGHT*12).order(ByteOrder.nativeOrder());
            ByteBuffer pixels=rgba.duplicate().order(ByteOrder.nativeOrder());
            for(int i=0;i<WIDTH*HEIGHT;i++) {
                for(int c=0;c<3;c++)in.putFloat(pixels.getFloat());
                pixels.getFloat();
            }
            in.rewind();
            ByteBuffer result=ByteBuffer.allocateDirect(WIDTH*HEIGHT*4).order(ByteOrder.nativeOrder());
            interpreter.run(in,result);
            for(int i=0;i<WIDTH*HEIGHT;i++) {
                float value=result.getFloat(i*4);
                if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite saliency mask");
                result.putFloat(i*4,Math.max(0,Math.min(1,value)));
            }
            result.rewind();return result;
        }
    }
}
