package com.particlesdevs.photoncamera.remosaic;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;

/** One CAL operation, streamed as ten four-frame groups to bound full-resolution RAW memory. */
public final class CalibrationSession {
    public static volatile CalibrationSession active;
    public final CalibrationPlan plan;
    public final String camera;
    public volatile int completed;
    private String key;
    private File staging;
    private boolean cancelled;
    public CalibrationSession(CalibrationPlan plan,String camera) {this.plan=plan;this.camera=camera;}
    public synchronized void save(String key,ByteBuffer[] frames,int w,int h,int iso,long exposure) throws IOException {
        if(cancelled || active!=this || completed>=10 || frames.length!=4)
            throw new IOException("Калибровка отменена или серия неполна");
        if(this.key!=null && !this.key.equals(key))throw new IOException("Модуль или формат RAW изменился во время CAL");
        this.key=key;
        if(staging==null)staging=Files.createTempDirectory(RemosaicCalibrationStore.dir().toPath(),key+"-bank-").toFile();
        RemosaicCalibrationStore.save(staging,"profile"+completed,frames,w,h,iso,exposure);
        if(completed==9) {
            File pointer=new File(RemosaicCalibrationStore.dir(),key+".bank");
            File tmp=File.createTempFile("bank-",".tmp",pointer.getParentFile());
            try {
                try(FileOutputStream out=new FileOutputStream(tmp)) {
                    out.write(staging.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();
                }
                Files.move(tmp.toPath(),pointer.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally {Files.deleteIfExists(tmp.toPath());}
            // Old banks can be large; reclaim them only after the new manifest is durable.
            File[] oldBanks=pointer.getParentFile().listFiles((d,n)->n.startsWith(key+"-bank-"));
            if(oldBanks!=null)for(File old:oldBanks)if(!old.equals(staging) && old.isDirectory()) {
                File[] files=old.listFiles();if(files!=null)for(File file:files)file.delete();old.delete();
            }
        }
        completed++;
    }
    public synchronized void cancel() {
        cancelled=true;
        if(completed<10 && staging!=null) {
            File[] files=staging.listFiles();if(files!=null)for(File f:files)f.delete();staging.delete();
        }
        if(active==this)active=null;
    }
}
