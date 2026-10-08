import com.particlesdevs.photoncamera.processing.color.HlgRendition;
import com.particlesdevs.photoncamera.processing.color.IccEmbed;
import com.particlesdevs.photoncamera.processing.color.IccProfiles;
import com.particlesdevs.photoncamera.processing.color.OutputColour;
import com.particlesdevs.photoncamera.processing.heif.HeifColourPatch;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Host side of tools/check_colour_output.py (P46): the app's own colour code on files written by other encoders.
 *
 *   java ColourOutputSample icc OUT.icc                    the Display P3 profile
 *   java ColourOutputSample jpeg IN.jpg OUT.jpg            the profile into a JPEG (IccEmbed.jpegInserting)
 *   java ColourOutputSample webp IN.webp OUT.webp W H      the profile into a WebP (IccEmbed.addToWebp)
 *   java ColourOutputSample heif IN.heic OUT.heic          the profile into a HEIF (HeifColourPatch, nclx -> P3)
 *   java ColourOutputSample matrices                       the matrices and luma weights as JSON
 *   java ColourOutputSample hlg IN.txt OUT.txt             per line "r g b" (BT.2020 display light, 1 = SDR white): the HLG signal
 *   java ColourOutputSample render IN.txt OUT.txt          per line "tenBit space gMin gMax cap pixel gain": the packed HLG pixel
 * HlgRendition needs android.jar on the class path (its Bitmap driver is not run).
 */
public final class ColourOutputSample {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "icc":
                Files.write(Paths.get(args[1]), IccProfiles.displayP3());
                break;
            case "jpeg": {
                final byte[] in = Files.readAllBytes(Paths.get(args[1]));
                try (OutputStream out = Files.newOutputStream(Paths.get(args[2]))) {
                    final OutputStream tagged = IccEmbed.jpegInserting(out, IccProfiles.displayP3());
                    // in odd pieces, as an encoder writes
                    for (int i = 0; i < in.length; i += 777) tagged.write(in, i, Math.min(777, in.length - i));
                    tagged.close();
                }
                break;
            }
            case "webp": {
                final Path out = Paths.get(args[2]);
                Files.copy(Paths.get(args[1]), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                IccEmbed.addToWebp(out, IccProfiles.displayP3(), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                break;
            }
            case "heif": {
                final Path out = Paths.get(args[2]);
                Files.copy(Paths.get(args[1]), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!HeifColourPatch.patch(out, IccProfiles.displayP3(), OutputColour.PRIMARIES_P3)) throw new IllegalStateException("not patched");
                break;
            }
            case "matrices":
                System.out.println("{\"srgbToP3\":" + Arrays.toString(OutputColour.srgbToP3()) + ",\"p3ToSrgb\":" + Arrays.toString(OutputColour.p3ToSrgb())
                        + ",\"srgbToBt2020\":" + Arrays.toString(OutputColour.srgbToBt2020()) + ",\"p3ToBt2020\":" + Arrays.toString(OutputColour.p3ToBt2020())
                        + ",\"lumaP3\":" + Arrays.toString(OutputColour.lumaP3()) + "}");
                break;
            case "hlg": {
                final StringBuilder out = new StringBuilder();
                final double[] v = new double[3];
                for (String line : Files.readAllLines(Paths.get(args[1]))) {
                    if (line.trim().isEmpty()) continue;
                    final String[] p = line.trim().split("\\s+");
                    HlgRendition.encode(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), v);
                    out.append(v[0]).append(' ').append(v[1]).append(' ').append(v[2]).append('\n');
                }
                Files.write(Paths.get(args[2]), out.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                break;
            }
            case "render": {
                final StringBuilder out = new StringBuilder();
                for (String line : Files.readAllLines(Paths.get(args[1]))) {
                    if (line.trim().isEmpty()) continue;
                    final String[] p = line.trim().split("\\s+");
                    final HlgRendition.Renderer r = new HlgRendition.Renderer(p[0].equals("1"), OutputColour.Space.parse(p[1]),
                            Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]));
                    out.append(r.pixel((int) Long.parseLong(p[5]), Float.parseFloat(p[6]))).append('\n');
                }
                Files.write(Paths.get(args[2]), out.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                break;
            }
            default:
                throw new IllegalArgumentException(args[0]);
        }
    }
}
