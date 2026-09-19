package android.graphics;
public class Bitmap {
 public enum Config { ARGB_8888 }
 private int w,h;private int[] pixels;
 public static Bitmap createBitmap(int w,int h,Config c){Bitmap b=new Bitmap();b.w=w;b.h=h;b.pixels=new int[w*h];return b;}
 public int getWidth(){return w;}public int getHeight(){return h;}public void recycle(){}
 public void getPixels(int[] dst,int o,int stride,int x,int y,int width,int height){for(int j=0;j<height;j++)System.arraycopy(pixels,(y+j)*w+x,dst,o+j*stride,width);}
 public void setPixels(int[] src,int o,int stride,int x,int y,int width,int height){for(int j=0;j<height;j++)System.arraycopy(src,o+j*stride,pixels,(y+j)*w+x,width);}
}
