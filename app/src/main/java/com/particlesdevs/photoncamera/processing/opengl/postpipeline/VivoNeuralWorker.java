package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Verifies bundled assets, then execs QNN outside the Java linker namespace. */
@androidx.annotation.Keep
public final class VivoNeuralWorker {
    static final String[][] FILES = {
        {"tele576-v79.bin","32983328ace406ffbfb165a09b1bfd69015e5da224a6d4b3e1f7f0cac74d1c9b"},
        {"libQnnSystem.so","4c221cdd15eedfda218ed3c020e71c6c4f373f4f7149753b5eded92ef3c127e6"},
        {"libQnnHtpV79Stub.so","77c35c65a6c6f3b059223575689d4294fb486ba8e062a9ad63a9ac15380076a5"},
        {"libQnnHtp.so","73683f1dabfafe1199ff922b43cf748198bbc793783d50585aeeb22e0e14caa2"},
        {"libQnnHtpV79Skel.so","3353856643575df6ff215ca430e0d9274e5c8ba62a172907b7bc5a5c716f6494"}
    };
    public static void main(String[] args) {
        int exit=1;
        try {
            System.out.println("SCAMERA Vivo Neural capture v2 bundled; root="+android.os.Process.myUid());
            if(args.length!=1 && args.length!=6)throw new IllegalArgumentException("Worker argument count");
            for(String[] item:FILES){
                File file=new File(args[0],item[0]);
                System.out.println("VERIFY APK ASSET: "+item[0]);
                if(file.length()<=0||file.length()>128L*1024*1024)throw new IllegalStateException("Unavailable bundled asset: "+file);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(FileInputStream in=new FileInputStream(file)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)digest.update(buf,0,n);}
                StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
                if(!item[1].contentEquals(hash))throw new IllegalStateException("Unknown bundled asset "+item[0]+": "+hash);
            }
            File executable=new File(args[0],"vivo-neural-worker");
            if(!executable.isFile()||!executable.canExecute())throw new IllegalStateException("Native executable unavailable");
            java.util.ArrayList<String> command=new java.util.ArrayList<>();
            command.add(executable.getCanonicalPath());
            java.util.Collections.addAll(command,args);
            System.out.println("EXEC: native worker, bundled model/runtime, no JNI namespace");
            System.out.flush();
            Process child=new ProcessBuilder(command).inheritIO().start();
            exit=child.waitFor();
            if(exit!=0)System.out.println("STOP: native worker exit="+exit);
        } catch(Throwable e){System.out.println("STOP: "+e);}
        System.out.flush();System.err.flush();System.exit(exit);
    }
}
