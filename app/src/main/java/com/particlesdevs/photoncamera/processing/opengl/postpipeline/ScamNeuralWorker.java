package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Verifies bundled assets, then execs QNN outside the Java linker namespace. */
@androidx.annotation.Keep
public final class ScamNeuralWorker {
    /**
     * The SHDR merge job's runtime: the PD2454 CRE motion. P129: SCAM HDR's forward and tone networks (nice-main-forward,
     * nice-tone-*) are gone with SCAM HDR (still verified in the private bundle, tools/ci_neural_assets.py BUNDLE_ONLY).
     */
    public static final String[][] SCAM_FILES = {
        // PD2454 CRE motion (same pinned file as /vendor) plus its runtime for non-scam
        // devices; the three vivo libraries it links are scam-cre-compat.cpp stubs.
        {"libvivo_nice_cre.so","41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e"},
        {"libc++_shared.so","f9992c4ba6b7c5a716e3a202fceb1ce029d6a2b0605838ac6b3219f489dd7970"},
        {"libvivolog.so","1dd79c9a92d3856fcf5e5f93ee532c98559ae82f6adff8f0810e20a04e3db704"},
        {"libvivo_platform_common.so","173945c6ebda5ff6cfea6e5b56c90818fd85cc531438d8304a4fb478e5c537b1"},
        {"libvivo.mempool.so","51e3029a6f32ca537033bb9f5ea4bdcbb3ba573626dd8ae830d2a0354ebe3d4c"}
    };
    /**
     * The QNN runtime the SHDR merge job links next to the CRE motion (shipped as native libraries). P175: the neural
     * remosaic's networks (the HexQuad and Quad 2x2 contexts, the tele576 set with its own QNN build) are gone (still verified
     * in the private bundle, tools/ci_neural_assets.py BUNDLE_ONLY).
     */
    public static final String[][] HEX_FILES = {
        {"libQnnSystem.so","4250e5366a7f7b3c692a184649929e4d763a979c47691bfec49e8511af993842"},
        {"libQnnHtp.so","3c71c06536c7f42966aff0ed12cb40fe0ada57132ef2b4b4fa1e72d3aaa0be1d"},
        {"libQnnHtpV79Stub.so","2dfaabe735cdd3f6a23e9089d9b93c04cee2818e8168fdad7a864535ff620656"},
        {"libQnnHtpV79Skel.so","eaf6f153c814f1f4d544ae17b37ee25835f678ed4b81d43e30a25e65396c5a8d"}
    };
    /** The su launch of the SHDR merge job: {@code <job dir> --scam-capture <burst> <result>}. */
    public static void main(String[] args) {
        int exit=1;
        try {
            System.out.println("SCAMERA SCAM Neural bundled; path=SHDR capture root="+android.os.Process.myUid());
            if(args.length!=4 || !args[1].equals("--scam-capture"))throw new IllegalArgumentException("Worker argument count");
            java.util.ArrayList<String[]> required=new java.util.ArrayList<>();
            java.util.Collections.addAll(required,SCAM_FILES);
            java.util.Collections.addAll(required,HEX_FILES);
            // vivo CRE motion is optional: without it (non-scam devices) the native
            // worker aligns with SCAMERA's own tile alignment. If present it must match.
            if(new File("/vendor/lib64/libvivo_nice_cre.so").isFile())
                required.add(new String[]{"/vendor/lib64/libvivo_nice_cre.so",
                    "41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e"});
            for(String[] item:required){
                File file=item[0].startsWith("/")?new File(item[0]):new File(args[0],item[0]);
                System.out.println("VERIFY RESOURCE: "+item[0]);
                if(file.length()<=0||file.length()>128L*1024*1024)throw new IllegalStateException("Unavailable model/runtime resource: "+file);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(FileInputStream in=new FileInputStream(file)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)digest.update(buf,0,n);}
                StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
                if(!item[1].contentEquals(hash))throw new IllegalStateException("Unknown model/runtime resource "+item[0]+": "+hash);
            }
            File executable=new File(args[0],"scam-neural-worker");
            if(!executable.isFile()||!executable.canExecute())throw new IllegalStateException("Native executable unavailable");
            java.util.ArrayList<String> command=new java.util.ArrayList<>();
            command.add(executable.getCanonicalPath());
            command.add("--scam-capture");command.add(args[0]);command.add(args[2]);command.add(args[3]);
            System.out.println("EXEC: native worker, bundled model/runtime, no JNI namespace");
            System.out.flush();
            Process child=new ProcessBuilder(command).inheritIO().start();
            exit=child.waitFor();
            if(exit!=0)System.out.println("STOP: native worker exit="+exit);
        } catch(Throwable e){System.out.println("STOP: "+e);}
        System.out.flush();System.err.flush();System.exit(exit);
    }
}
