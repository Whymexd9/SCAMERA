package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Root app_process entry point. Uses the installed APK's dex and JNI only. */
@androidx.annotation.Keep
public final class VivoNeuralWorker {
    private static native void nativeRun(String in, String out, int w, int h, int redQuad);
    private static final String[][] FILES = {
        {"libremosaiclib_s5khp3.so","7495113303fb01cff07434eb9896253774846a7b2c5ff1e13017654c884c3aa5"},
        {"hw/libQnnSystem.so","4c221cdd15eedfda218ed3c020e71c6c4f373f4f7149753b5eded92ef3c127e6"},
        {"libcdsprpc.so","874a3df45641ebee875868c66417e334c4ae3bffa99ea6936d708e125e1bd920"},
        {"hw/libQnnHtpV79Stub.so","77c35c65a6c6f3b059223575689d4294fb486ba8e062a9ad63a9ac15380076a5"},
        {"hw/libQnnHtp.so","73683f1dabfafe1199ff922b43cf748198bbc793783d50585aeeb22e0e14caa2"},
        {"hw/libQnnHtpV79Skel.so","3353856643575df6ff215ca430e0d9274e5c8ba62a172907b7bc5a5c716f6494"}
    };
    public static void main(String[] args) {
        int exit=1;
        try {
            System.out.println("SCAMERA Vivo Neural capture v1; root="+android.os.Process.myUid());
            if(args.length!=1 && args.length!=6)throw new IllegalArgumentException("Worker argument count");
            for(String[] item:FILES){
                File file=new File("/vendor/lib64/"+item[0]);
                System.out.println("VERIFY: "+item[0]);
                if(file.length()<=0||file.length()>128L*1024*1024)throw new IllegalStateException("Unavailable firmware: "+file);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(FileInputStream in=new FileInputStream(file)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)digest.update(buf,0,n);}
                StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
                if(!item[1].contentEquals(hash))throw new IllegalStateException("Unknown firmware "+item[0]+": "+hash);
            }
            System.load(new File(args[0],"libvivoNeuralWorker.so").getCanonicalPath());
            nativeRun(args.length==6?args[1]:null,args.length==6?args[2]:null,
                    args.length==6?Integer.parseInt(args[3]):0,args.length==6?Integer.parseInt(args[4]):0,
                    args.length==6?Integer.parseInt(args[5]):0);
            exit=0;
        } catch(Throwable e){System.out.println("STOP: "+e);}
        System.out.flush();System.err.flush();System.exit(exit);
    }
}
