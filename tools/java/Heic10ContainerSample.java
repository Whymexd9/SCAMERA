import com.particlesdevs.photoncamera.processing.heif.HeifContainerWriter;
import com.particlesdevs.photoncamera.processing.heif.HevcNal;
import com.particlesdevs.photoncamera.processing.heif.TileGrid;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Host side of tools/check_heic10.py: wraps HEVC tile bitstreams the way Heic10Encoder does on the phone - MediaCodec's
 * codec config (Annex-B VPS/SPS/PPS) and one Annex-B access unit per tile, split into NAL units, parameter sets collected
 * into hvcC, tile samples length-prefixed - with the app's own HeifContainerWriter.
 *
 * java Heic10ContainerSample DIR WIDTH HEIGHT TILE OUT.heic [srgb|p3|hlg]
 *   the colour the container declares (P46): srgb (default, nclx 1/13/1), p3 (nclx 12/13/1 + the Display P3 ICC), hlg (9/18/9)
 *   DIR: csd.bin (may be empty), tile_0.bin .. tile_N-1.bin (raster order), exif.bin (optional, "Exif\0\0" + TIFF)
 * Prints the SPS of the stream.
 */
public final class Heic10ContainerSample {
    public static void main(String[] args) throws Exception {
        final Path dir = Paths.get(args[0]);
        final int width = Integer.parseInt(args[1]), height = Integer.parseInt(args[2]), tile = Integer.parseInt(args[3]);
        final TileGrid grid = new TileGrid(width, height, tile);
        final HevcNal.ParameterSets sets = new HevcNal.ParameterSets();
        final Path csd = dir.resolve("csd.bin");
        if (Files.exists(csd)) sets.collect(HevcNal.split(Files.readAllBytes(csd)));
        final byte[][] samples = new byte[grid.count()][];
        for (int i = 0; i < grid.count(); i++) {
            final List<byte[]> nals = HevcNal.split(Files.readAllBytes(dir.resolve("tile_" + i + ".bin")));
            sets.collect(nals); // an encoder may repeat the parameter sets in front of each IDR
            samples[i] = HevcNal.toLengthPrefixed(nals);
        }
        if (!sets.complete()) throw new IllegalStateException("no VPS/SPS/PPS in the stream");
        final HevcNal.Sps sps = HevcNal.parseSps(sets.sps.get(0));
        System.out.println("SPS " + sps);
        final HeifContainerWriter writer = new HeifContainerWriter(grid, sets.hvcC(), sps.bitDepthLuma);
        for (byte[] s : samples) writer.addTile(s);
        final Path exif = dir.resolve("exif.bin");
        if (Files.exists(exif)) writer.setExif(Files.readAllBytes(exif));
        final String colour = args.length > 5 ? args[5] : "srgb";
        if (colour.equals("p3")) writer.setColour(com.particlesdevs.photoncamera.processing.color.OutputColour.Signal.displayP3());
        else if (colour.equals("hlg")) writer.setColour(com.particlesdevs.photoncamera.processing.color.OutputColour.Signal.HLG);
        try (OutputStream out = Files.newOutputStream(Paths.get(args[4]))) {
            writer.writeTo(out);
        }
    }
}
