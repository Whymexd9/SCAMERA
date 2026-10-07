package android.graphics;

/** Host stand-in for android.graphics.Bitmap: the ARGB int pixels GainMapComputer reads and writes (tools/check_ultrahdr.py). */
public class Bitmap {
    public enum Config { ARGB_8888 }

    private final int width, height;
    private final int[] pixels;

    private Bitmap(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(width, height);
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void getPixels(int[] out, int offset, int stride, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++)
            System.arraycopy(pixels, (y + row) * width + x, out, offset + row * stride, w);
    }

    public void setPixels(int[] in, int offset, int stride, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++)
            System.arraycopy(in, offset + row * stride, pixels, (y + row) * width + x, w);
    }

    public void recycle() {}
}
