import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;
import com.particlesdevs.photoncamera.processing.ultrahdr.UltraHdrContainer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * Builds Ultra HDR sample files with the app's own container code (UltraHdrContainer, GainMapComputer) on the host, for
 * tools/check_ultrahdr.py:
 * <ul>
 * <li>{@code <dir>/stream.jpg}: the streaming writer the camera uses (UltraHdrEncoder.encodeToFile), with an EXIF APP1
 *     and the MPF directory patched in place in a file;</li>
 * <li>{@code <dir>/memory.jpg}: the in-memory assembly {@link UltraHdrContainer#encode} of the same images.</li>
 * </ul>
 * The primary and the gain map are real baseline JPEGs (javax.imageio); after writing, both images of each file are cut
 * out by the MPF entries and decoded again with ImageIO, so a wrong offset fails here as well as in the Python check.
 * The gain map comes from GainMapComputer.compute on a synthetic GPU map (log2 boost ramp), so its metadata is the real
 * normalisation's.
 */
public class UltraHdrSample {
    static final int W = 320, H = 240;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : ".");
        Files.createDirectories(dir);
        byte[] primary = jpeg(primaryImage(), 0.9f);
        GainMapComputer.Result gm = GainMapComputer.compute(gpuGainMap(W, H), 1, GainMapComputer.SCALE);
        byte[] gainJpeg = jpeg(toImage(gm.gainMap), 0.9f);
        byte[] exifApp1 = exifApp1();

        Path stream = dir.resolve("stream.jpg");
        try (RandomAccessFile file = new RandomAccessFile(stream.toFile(), "rw")) {
            file.setLength(0);
            OutputStream out = new OutputStream() {
                @Override public void write(int b) throws IOException { file.write(b); }
                @Override public void write(byte[] b, int off, int len) throws IOException { file.write(b, off, len); }
            };
            UltraHdrContainer.write(out, (offset, bytes) -> {
                long end = file.getFilePointer();
                file.seek(offset);
                file.write(bytes);
                file.seek(end);
            }, o -> {
                // The encoder hands its output over in small chunks, as Bitmap.compress / jpegli do.
                for (int i = 0; i < primary.length; i += 4096) o.write(primary, i, Math.min(4096, primary.length - i));
            }, exifApp1, gainJpeg, gm.gainMapMin, gm.gainMapMax, gm.hdrCapacityMax);
        }
        Path memory = dir.resolve("memory.jpg");
        Files.write(memory, UltraHdrContainer.encode(primary, gainJpeg, gm.gainMapMin, gm.gainMapMax, gm.hdrCapacityMax));

        for (Path p : new Path[]{stream, memory}) decodeBoth(p);
        System.out.println("Ultra HDR samples: " + stream + " " + memory + " (gain map min=" + gm.gainMapMin + " max=" + gm.gainMapMax + ")");
    }

    /** SDR base: a horizontal grey ramp with a bright, clipped band (where a real gain map boosts). */
    static BufferedImage primaryImage() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int v = y < H / 4 ? 255 : 255 * x / (W - 1);
                img.setRGB(x, y, (v << 16) | (v << 8) | (v >> 1));
            }
        return img;
    }

    /** The GPU pass's output: v = log2(boost) / SCALE in R=G=B; 3 stops in the clipped band, a ramp of 0..1 stop elsewhere. */
    static Bitmap gpuGainMap(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float stops = y < h / 4 ? 3f : (float) x / (w - 1);
                int v = Math.round(stops / GainMapComputer.SCALE * 255f);
                px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    static BufferedImage toImage(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        BufferedImage img = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < b.getHeight(); y++)
            for (int x = 0; x < b.getWidth(); x++) {
                int v = (px[y * b.getWidth() + x] >> 16) & 0xFF;
                img.getRaster().setSample(x, y, 0, v);
            }
        return img;
    }

    static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Minimal EXIF APP1: "Exif\0\0", little-endian TIFF header, IFD0 with Make = "SCAMERA". */
    static byte[] exifApp1() {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4 + 8).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x010F).putShort((short) 2).putInt(8).putInt(8 + 2 + 12 + 4); // Make, ASCII, 8 bytes
        tiff.putInt(0);
        tiff.put("SCAMERA\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] t = tiff.array();
        int len = 2 + 6 + t.length;
        ByteBuffer seg = ByteBuffer.allocate(2 + len);
        seg.put((byte) 0xFF).put((byte) 0xE1).putShort((short) len).put("Exif\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put(t);
        return seg.array();
    }

    /** Cuts both images out by the MPF entries (little- or big-endian) and decodes them with ImageIO. */
    static void decodeBoth(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        int pos = 2, mpfBase = -1;
        while (pos + 4 <= data.length && (data[pos] & 0xFF) == 0xFF) {
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xDA) break;
            int len = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (marker == 0xE2 && data[pos + 4] == 'M' && data[pos + 5] == 'P' && data[pos + 6] == 'F' && data[pos + 7] == 0) {
                mpfBase = pos + 8;
                break;
            }
            pos += 2 + len;
        }
        if (mpfBase < 0) throw new IllegalStateException(file + ": no MPF segment");
        ByteBuffer mpf = ByteBuffer.wrap(data, mpfBase, data.length - mpfBase).slice();
        mpf.order(data[mpfBase] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int ifd = mpf.getInt(4), count = mpf.getShort(ifd) & 0xFFFF, entries = -1;
        for (int i = 0; i < count; i++) {
            int e = ifd + 2 + 12 * i;
            if ((mpf.getShort(e) & 0xFFFF) == 0xB002) entries = mpf.getInt(e + 8);
        }
        if (entries < 0) throw new IllegalStateException(file + ": no MPEntry");
        int primarySize = mpf.getInt(entries + 4);
        int gainSize = mpf.getInt(entries + 16 + 4), gainOffset = mpf.getInt(entries + 16 + 8);
        int gainStart = mpfBase + gainOffset;
        BufferedImage p = ImageIO.read(new ByteArrayInputStream(Arrays.copyOfRange(data, 0, primarySize)));
        BufferedImage g = ImageIO.read(new ByteArrayInputStream(Arrays.copyOfRange(data, gainStart, gainStart + gainSize)));
        if (p == null || p.getWidth() != W || p.getHeight() != H) throw new IllegalStateException(file + ": primary does not decode");
        if (g == null || g.getWidth() != W || g.getHeight() != H) throw new IllegalStateException(file + ": gain map does not decode");
    }
}
