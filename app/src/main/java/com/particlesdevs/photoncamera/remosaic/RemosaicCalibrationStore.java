package com.particlesdevs.photoncamera.remosaic;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Signed FPN residuals; profiles are isolated by module, mosaic, dimensions and exposure. */
public final class RemosaicCalibrationStore {
    private static native boolean nativeCalibrate(ByteBuffer[] frames,int width,int height,ByteBuffer out);
    private static final int MAGIC=0x4d465031;
    static final class Profile { ByteBuffer map; float scale; }
    private static File dir() throws IOException {
        File d=new File(PhotonCamera.getAppContext().getFilesDir(),"mfsr-calibration-v1");
        if(!d.isDirectory() && !d.mkdirs())throw new IOException("Не удалось создать каталог калибровки");
        return d;
    }
    static String key(String camera,int block,String cfa,int w,int h) {
        return camera.replaceAll("[^a-zA-Z0-9_-]","_")+"_"+block+"_"+cfa+"_"+w+"x"+h;
    }
    static void save(String key,ByteBuffer[] frames,int w,int h,int iso,long exposure) throws IOException {
        ByteBuffer map=Allocator.allocate(w*h*2);
        if(map==null)throw new IOException("Нет памяти для калибровки");
        File tmp=null;
        try {
            if(!nativeCalibrate(frames,w,h,map))throw new IOException("Ошибка тёмной калибровки");
            File file=new File(dir(),key+"_"+iso+"_"+exposure+".fpn");
            tmp=File.createTempFile("profile-",".tmp",dir());
            try(FileOutputStream stream=new FileOutputStream(tmp); DataOutputStream header=new DataOutputStream(stream)) {
                header.writeInt(MAGIC);header.writeInt(iso);header.writeLong(exposure);header.writeInt(w*h*2);header.flush();
                map.clear();while(map.hasRemaining())stream.getChannel().write(map);
                stream.getFD().sync();
            }
            Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Allocator.free(map);if(tmp!=null)Files.deleteIfExists(tmp.toPath());}
    }
    static Profile load(String key,int iso,long exp,int bytes) {
        try {
            File[] files=dir().listFiles((d,n)->n.startsWith(key+"_")&&n.endsWith(".fpn"));
            File best=null;double distance=Double.POSITIVE_INFINITY;int bestIso=iso;
            if(files==null)return null;
            for(File f:files)try(DataInputStream in=new DataInputStream(new FileInputStream(f))) {
                if(f.length()!=20L+bytes || in.readInt()!=MAGIC)continue;
                int pi=in.readInt();long pe=in.readLong();if(in.readInt()!=bytes || pi<=0 || pe<=0)continue;
                double a=Math.log((double)iso/pi),b=Math.log((double)exp/pe),d=a*a+.35*b*b;
                if(d<distance){distance=d;best=f;bestIso=pi;}
            }
            if(best==null)return null;
            Profile p=new Profile();
            try(FileChannel ch=new FileInputStream(best).getChannel()) {
                p.map=ch.map(FileChannel.MapMode.READ_ONLY,20,bytes).order(ByteOrder.nativeOrder());
            }
            p.scale=Math.max(.7f,Math.min(1.4f,(float)iso/bestIso));return p;
        }catch(Exception e){Log.w("RAW_MFSR","Калибровка недоступна: "+e.getMessage());return null;}
    }
}
