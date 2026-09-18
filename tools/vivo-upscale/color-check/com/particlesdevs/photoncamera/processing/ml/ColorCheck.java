package com.particlesdevs.photoncamera.processing.ml;
import android.graphics.Bitmap;
import java.io.File;
import java.nio.file.Files;
public class ColorCheck {
 public static void main(String[] args)throws Exception {
  int[] colors={0xff000000,0xffffffff,0xffff0000,0xff00ff00,0xff0000ff,0xff808080};
  int[][] golden={{16,128,128},{235,128,128},{82,240,90},{144,34,54},{41,110,240},{126,128,128}};
  for(int k=0;k<colors.length;k++){
   Bitmap b=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);int c=colors[k];b.setPixels(new int[]{c,c,c,c},0,2,0,0,2,2);
   File f=File.createTempFile("vivo-color-",".yuv");
   try{
    VivoRaisrProcessor.writeNv21(b,f);byte[] bytes=Files.readAllBytes(f.toPath());
    if(bytes.length!=6 || (bytes[0]&255)!=golden[k][0] || (bytes[4]&255)!=golden[k][1] || (bytes[5]&255)!=golden[k][2])throw new AssertionError("NV21 fixture "+k);
    Bitmap decoded=VivoRaisrProcessor.readNv21(f,2,2);int[] rgb=new int[4];decoded.getPixels(rgb,0,2,0,0,2,2);
    for(int p:rgb)for(int shift:new int[]{0,8,16})if(Math.abs(((p>>shift)&255)-((c>>shift)&255))>2)throw new AssertionError("RGB roundtrip "+k);
   }finally{f.delete();}
  }
  System.out.println("PASS: six BT.601/NV21 color fixtures and RGB roundtrips (in-memory Bitmap test double; no vendor inference)");
 }
}
