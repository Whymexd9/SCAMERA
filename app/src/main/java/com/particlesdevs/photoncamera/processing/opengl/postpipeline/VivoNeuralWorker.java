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
    static final String[][] HEX_FILES = {
        {"hexquad-x1-v79.bin","e4519b2b8ee4ff1684c10d0e3006c6ab613972d107ed8f05c58543b833e17e22"},
        {"hexquad-x2-v79.bin","70e0a1c4e5c1316505a562badb85a8910cb6860106894b1d137077f2af357218"},
        {"libQnnSystem.so","4250e5366a7f7b3c692a184649929e4d763a979c47691bfec49e8511af993842"},
        {"libQnnHtp.so","3c71c06536c7f42966aff0ed12cb40fe0ada57132ef2b4b4fa1e72d3aaa0be1d"},
        {"libQnnHtpV79Stub.so","2dfaabe735cdd3f6a23e9089d9b93c04cee2818e8168fdad7a864535ff620656"},
        {"libQnnHtpV79Skel.so","eaf6f153c814f1f4d544ae17b37ee25835f678ed4b81d43e30a25e65396c5a8d"}
    };
    // Diagnostic-only: loads the softpqe 2x/4x upscale models and reports their
    // tensor descriptors. Never executes the graph; see docs/vivo-softpqe-upscale.md.
    static final String[][] SOFTPQE_FILES = {
        {"softpqe-y2x-v79.bin","381986ea8463a49f3bce9047c02a83ce9056a08dacb9765a90a46fde6215429b"},
        {"softpqe-y4x-v79.bin","b613899927af83cbf741ec6bbfed28de3f5bdf66aaceefbb8a12161f7a206e16"},
        {"libQnnSystem.so","57ee2bb5c0042a5bb212e79ab185915b3eba07d469ab09725529210b4a11b84b"},
        {"libQnnHtp.so","976c165553748118106773ac3db94f896806c39f49577eb8c95d70c2352bdcb7"},
        {"libQnnHtpV79Stub.so","e1f604549d645b3c71280a8bed83a71796bc5928013839a837023ddd695c83ce"},
        {"libQnnHtpV79Skel.so","cd3bb9982734dcc8ceb267b46449ba92e42d1f93b00270d7658cbac2ea5ba955"}
    };
    public static void main(String[] args) {
        int exit=1;
        try {
            boolean capture=args.length==4 && (args[1].equals("--hexquad-capture") || args[1].equals("--hexquad-capture-cached"));
            boolean hex=capture || (args.length==2 && args[1].equals("--hexquad"));
            boolean softpqe=args.length==2 && args[1].equals("--softpqe");
            System.out.println("SCAMERA Vivo Neural bundled; path="+(capture?"HP9 HexQuad capture":hex?"HP9 HexQuad check":softpqe?"softpqe upscale check":"TELE capture")+" root="+android.os.Process.myUid());
            if(!hex && !softpqe && args.length!=1 && args.length!=6)throw new IllegalArgumentException("Worker argument count");
            String[][] manifest=hex?HEX_FILES:softpqe?SOFTPQE_FILES:FILES;
            for(String[] item:manifest){
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
            if(capture){command.add(args[1]);command.add(args[0]);command.add(args[2]);command.add(args[3]);}
            else if(hex){command.add("--hexquad-check");command.add(args[0]);}
            else if(softpqe){command.add("--softpqe-check");command.add(args[0]);}
            else java.util.Collections.addAll(command,args);
            System.out.println("EXEC: native worker, bundled model/runtime, no JNI namespace");
            System.out.flush();
            Process child=new ProcessBuilder(command).inheritIO().start();
            exit=child.waitFor();
            if(exit!=0)System.out.println("STOP: native worker exit="+exit);
        } catch(Throwable e){System.out.println("STOP: "+e);}
        System.out.flush();System.err.flush();System.exit(exit);
    }
}
