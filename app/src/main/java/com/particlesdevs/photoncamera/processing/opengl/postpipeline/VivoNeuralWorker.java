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
    public static final String[][] NICE_TONE_FILES = {
        {"nice-tone-fasttm-v79.bin","696317a4f1478fa5a8b048425dc45c03af525b2db53a8e05eb66666df5231e2b"},
        {"nice-tone-adams-v79.bin","6b484f11c748007978d928e14813ba6b98bddcbf53c3a8fef445094824e0d0fe"},
        {"nice-tone-adams-landscape-v79.bin","14185fa7ea113e8fd3fc205281b9b83ccea9f2fc6346e44b6e121d0902a80b51"},
        {"nice-tone-hdrnet-coeff-v79.bin","9bee14bddfd9dc0a7820c5e20087700d1a3a09c13939a4f8795f6a6cc52f3297"},
        {"nice-tone-hdrnet-weight-v79.bin","ae050107939723b545c194f41fa70245dcd3c25f358e6c9cc1b7e6d8435cdb56"}
    };
    public static final String[][] NICE_FILES = {
        {"nice-main-forward-v79.bin","a551304d938af0cab76091557414f46a64cae05aaf68ef8030c1bcc42decac8c"},
        // PD2454 CRE motion (same pinned file as /vendor) plus its runtime for non-vivo
        // devices; the three vivo libraries it links are vivo-cre-compat.cpp stubs.
        {"libvivo_nice_cre.so","41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e"},
        {"libc++_shared.so","f9992c4ba6b7c5a716e3a202fceb1ce029d6a2b0605838ac6b3219f489dd7970"},
        {"libvivolog.so","1dd79c9a92d3856fcf5e5f93ee532c98559ae82f6adff8f0810e20a04e3db704"},
        {"libvivo_platform_common.so","173945c6ebda5ff6cfea6e5b56c90818fd85cc531438d8304a4fb478e5c537b1"},
        {"libvivo.mempool.so","51e3029a6f32ca537033bb9f5ea4bdcbb3ba573626dd8ae830d2a0354ebe3d4c"}
    };
    /** Hexagon v75 (SM8650) runtime, QAIRT 2.28, and the distilled fp16 student of the NICE forward network. */
    public static final String[][] NICE75_FILES = {
        {"nice-student-v75.bin","cb1be549b49243e89aa91f705bd2acd26c2952911f4a53a6e6b788c172ef8e23"},
        {"libQnnHtp228.so","c8609ab8917fe641133bf602f6e464cb7f05f44242b9e5c4ba9899102ba6743d"},
        {"libQnnHtpV75Stub.so","8b11cd9ca7cca268c516ede9f868d8a7363d3ff77d1057a05629d5924338520e"},
        {"libQnnHtpV75Skel.so","f1c08a179e8b2ff6abfd573b5ac04203e662af4ccc2be2fcd1a0aa73db00fe0e"}
    };
    public static final String[][] HEX_FILES = {
        {"hexquad-x1-v79.bin","e4519b2b8ee4ff1684c10d0e3006c6ab613972d107ed8f05c58543b833e17e22"},
        {"hexquad-x2-v79.bin","70e0a1c4e5c1316505a562badb85a8910cb6860106894b1d137077f2af357218"},
        {"libQnnSystem.so","4250e5366a7f7b3c692a184649929e4d763a979c47691bfec49e8511af993842"},
        {"libQnnHtp.so","3c71c06536c7f42966aff0ed12cb40fe0ada57132ef2b4b4fa1e72d3aaa0be1d"},
        {"libQnnHtpV79Stub.so","2dfaabe735cdd3f6a23e9089d9b93c04cee2818e8168fdad7a864535ff620656"},
        {"libQnnHtpV79Skel.so","eaf6f153c814f1f4d544ae17b37ee25835f678ed4b81d43e30a25e65396c5a8d"}
    };
    /** Main-camera IMX06C 2x2 Quad model (vendor nice_ldr_imx06c_general_quad_x1 context). */
    public static final String[][] QUAD_FILES = {
        {"quad-x1-v79.bin","7b42687974c53439fcb71ef3254b218eca26388c397138431f5a7a3daf6314aa"},
        // Tele HP9 2x ISZ: vendor nice_ldr_hp9_general_roi_quad_x1 context.
        {"quad-hp9-x1-v79.bin","c135a5b54a0637bdaa5b116c76ce667d73f0a9d9b8adae059d178a80d66bdbc3"},
        // Tele above ISO 2000 (vendor maxiso switch): roi_quad_x1_highdrc context.
        {"quad-hp9-highdrc-x1-v79.bin","7c09eca4b1522fed690631cc86174bf9a75a14dde8e3279d935d3f253974d812"}
    };
    /** Vivo VSR still SR contexts (vendor sr1x_m4, sr2x_m24, sr4x_m24; /vendor/camera3rd/nti/VSR). */
    public static final String[][] VSR_FILES = {
        {"vsr1x-v79.bin","db7eec6b89c9040e05be84bfacebb9d7dacb725817998047f7c51e199027b72c"},
        {"vsr2x-v79.bin","d392f3c23181bd792f766ab21b7635f966edbc188ff323d166ed6859a395d118"},
        {"vsr4x-v79.bin","cc6d236a3f6da5214c687d93e16f35715fbeffd36c52eef417ad68f2a0dcb83e"}
    };
    public static String[] vsrFile(int scale) {
        return VSR_FILES[scale==4?2:scale==2?1:0];
    }
    public static void main(String[] args) {
        int exit=1;
        try {
            boolean niceCapture=args.length==4 && args[1].equals("--nice-capture");
            boolean niceTone=args.length==2 && args[1].equals("--nice-tone-check");
            boolean nice=niceTone || niceCapture || (args.length==2 && args[1].equals("--nice"));
            boolean quad=args.length==4 && args[1].equals("--quad-capture");
            boolean vsr=args.length==8 && args[1].equals("--vsr-capture");
            boolean capture=quad || (args.length==4 && (args[1].equals("--hexquad-capture") || args[1].equals("--hexquad-capture-cached")));
            boolean hex=capture || (args.length==2 && args[1].equals("--hexquad"));
            System.out.println("SCAMERA Vivo Neural bundled; path="+(niceTone?"NICE tone runtime check":niceCapture?"SCAM HDR capture":nice?"SCAM HDR runtime check":quad?"Quad 2x2 capture":capture?"HP9 HexQuad capture":hex?"HP9 HexQuad check":"TELE capture")+" root="+android.os.Process.myUid());
            if(!nice && !hex && !vsr && args.length!=1 && args.length!=6)throw new IllegalArgumentException("Worker argument count");
            java.util.ArrayList<String[]> required=new java.util.ArrayList<>();
            if(nice){
                java.util.Collections.addAll(required,niceTone?NICE_TONE_FILES:NICE_FILES);
                for(String[] item:HEX_FILES)if(item[0].endsWith(".so"))required.add(item);
            } else if(vsr){
                for(String[] item:HEX_FILES)if(item[0].endsWith(".so"))required.add(item);
                required.add(vsrFile(Integer.parseInt(args[2])));
            } else if(quad){
                for(String[] item:HEX_FILES)if(item[0].endsWith(".so"))required.add(item);
                java.util.Collections.addAll(required,QUAD_FILES);
            } else java.util.Collections.addAll(required,hex?HEX_FILES:FILES);
            // vivo CRE motion is optional: without it (non-vivo devices) the native
            // worker aligns with SCAMERA's own tile alignment. If present it must match.
            if(niceCapture && new File("/vendor/lib64/libvivo_nice_cre.so").isFile())
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
            File executable=new File(args[0],"vivo-neural-worker");
            if(!executable.isFile()||!executable.canExecute())throw new IllegalStateException("Native executable unavailable");
            java.util.ArrayList<String> command=new java.util.ArrayList<>();
            command.add(executable.getCanonicalPath());
            if(vsr){command.add("--vsr-capture");command.add(args[0]);for(int i=2;i<8;i++)command.add(args[i]);}
            else if(niceTone){command.add("--nice-tone-check");command.add(args[0]);}
            else if(niceCapture){command.add("--nice-capture");command.add(args[0]);command.add(args[2]);command.add(args[3]);}
            else if(nice){command.add("--nice-check");command.add(args[0]);}
            else if(capture){command.add(args[1]);command.add(args[0]);command.add(args[2]);command.add(args[3]);}
            else if(hex){command.add("--hexquad-check");command.add(args[0]);}
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
