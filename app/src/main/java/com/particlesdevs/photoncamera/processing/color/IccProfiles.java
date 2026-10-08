package com.particlesdevs.photoncamera.processing.color;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Display P3 ICC profile the photo files carry when «Цветовое пространство» is Display P3 (P46): an ICC v4.3 display
 * ('mntr') matrix / TRC profile, RGB -> XYZ (D50 PCS), built here from the same primaries as {@link OutputColour}:
 * <pre>
 * desc, cprt  'mluc' "Display P3" / "No copyright, use freely"
 * wtpt        D50 (ICC v4: the media white of a display profile is the PCS illuminant)
 * chad        Bradford D65 -> D50
 * r/g/bXYZ    the P3 primaries (D65 white) adapted to D50 with chad
 * r/g/bTRC    one 'para' curve of type 3: the sRGB transfer (gamma 2.4, a 1/1.055, b 0.055/1.055, c 1/12.92, d 0.04045)
 * </pre>
 * The profile ID is the MD5 of the profile (ICC.1:2022 7.2.18). The same tags as Apple's and Android's (Skia)
 * Display P3 profiles, so decoders recognise it as Display P3 (Android: ColorSpace.Named.DISPLAY_P3).
 */
public final class IccProfiles {
    private IccProfiles() {}

    /** PCS illuminant D50 of ICC (s15Fixed16-exact values of the specification). */
    static final double[] D50 = {0.9642, 1.0, 0.8249};
    /** Bradford cone response matrix. */
    static final double[] BRADFORD = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
    /** sRGB transfer as an ICC parametric curve of type 3: g, a, b, c, d. */
    static final double[] SRGB_PARA = {2.4, 1.0 / 1.055, 0.055 / 1.055, 1.0 / 12.92, 0.04045};

    private static volatile byte[] displayP3;

    /** The Display P3 profile (a copy). */
    public static byte[] displayP3() {
        byte[] p = displayP3;
        if (p == null) {
            p = build("Display P3", OutputColour.P3_XY);
            displayP3 = p;
        }
        return p.clone();
    }

    /** Bradford chromatic adaptation D65 -> D50 (row-major 3 x 3). */
    public static double[] chadD65ToD50() {
        final double[] white = {OutputColour.WHITE_X / OutputColour.WHITE_Y, 1.0,
                (1.0 - OutputColour.WHITE_X - OutputColour.WHITE_Y) / OutputColour.WHITE_Y};
        final double[] src = OutputColour.multiply(BRADFORD, white), dst = OutputColour.multiply(BRADFORD, D50);
        final double[] scale = {dst[0] / src[0], 0, 0, 0, dst[1] / src[1], 0, 0, 0, dst[2] / src[2]};
        return OutputColour.multiply(OutputColour.invert(BRADFORD), OutputColour.multiply(scale, BRADFORD));
    }

    /** The D50 colorants (columns: red, green, blue XYZ) of a space with these primaries and the D65 white. */
    public static double[] colorantsD50(double[] primariesXy) {
        return OutputColour.multiply(chadD65ToD50(), OutputColour.rgbToXyz(primariesXy));
    }

    static byte[] build(String description, double[] primariesXy) {
        final double[] colorants = colorantsD50(primariesXy);
        final List<String> sigs = new ArrayList<>();
        final List<byte[]> data = new ArrayList<>();
        final byte[] para = para(SRGB_PARA);
        sigs.add("desc"); data.add(mluc(description));
        sigs.add("cprt"); data.add(mluc("No copyright, use freely"));
        sigs.add("wtpt"); data.add(xyz(D50[0], D50[1], D50[2]));
        sigs.add("chad"); data.add(sf32(chadD65ToD50()));
        sigs.add("rXYZ"); data.add(xyz(colorants[0], colorants[3], colorants[6]));
        sigs.add("gXYZ"); data.add(xyz(colorants[1], colorants[4], colorants[7]));
        sigs.add("bXYZ"); data.add(xyz(colorants[2], colorants[5], colorants[8]));
        sigs.add("rTRC"); data.add(para);
        sigs.add("gTRC"); data.add(para); // the three TRC tags share one element (same offset and size)
        sigs.add("bTRC"); data.add(para);

        final int tableSize = 4 + 12 * sigs.size();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final int[] offsets = new int[sigs.size()];
        for (int i = 0; i < sigs.size(); i++) {
            final int shared = data.indexOf(data.get(i)); // identical arrays are the same object only for the TRCs
            if (shared < i) {
                offsets[i] = offsets[shared];
                continue;
            }
            offsets[i] = 128 + tableSize + body.size();
            body.write(data.get(i), 0, data.get(i).length);
            while (body.size() % 4 != 0) body.write(0);
        }
        final int size = 128 + tableSize + body.size();
        final ByteBuffer b = ByteBuffer.allocate(size); // big-endian
        b.putInt(size);
        b.putInt(0); // preferred CMM
        b.putInt(0x04300000); // version 4.3
        b.put(ascii("mntr")).put(ascii("RGB ")).put(ascii("XYZ "));
        b.putShort((short) 2026).putShort((short) 10).putShort((short) 8).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        b.put(ascii("acsp"));
        b.putInt(0); // primary platform
        b.putInt(0); // flags
        b.putInt(0).putInt(0); // device manufacturer, model
        b.putLong(0); // attributes
        b.putInt(0); // rendering intent: perceptual
        b.putInt(s15(D50[0])).putInt(s15(D50[1])).putInt(s15(D50[2])); // PCS illuminant
        b.putInt(0); // creator
        b.position(128); // profile ID (84..99) and reserved bytes stay 0 until the ID is computed
        b.putInt(sigs.size());
        for (int i = 0; i < sigs.size(); i++) {
            b.put(ascii(sigs.get(i)));
            b.putInt(offsets[i]);
            b.putInt(data.get(i).length);
        }
        b.put(body.toByteArray());
        final byte[] profile = b.array();
        final byte[] id = profileId(profile);
        System.arraycopy(id, 0, profile, 84, 16);
        return profile;
    }

    /** MD5 of the profile with the flags, rendering intent and profile ID fields set to 0 (ICC.1:2022 7.2.18). */
    static byte[] profileId(byte[] profile) {
        final byte[] copy = profile.clone();
        for (int i = 44; i < 48; i++) copy[i] = 0;
        for (int i = 64; i < 68; i++) copy[i] = 0;
        for (int i = 84; i < 100; i++) copy[i] = 0;
        try {
            return MessageDigest.getInstance("MD5").digest(copy);
        } catch (NoSuchAlgorithmException e) {
            return new byte[16]; // an all-zero ID means "not computed"
        }
    }

    static int s15(double v) {
        return (int) Math.round(v * 65536.0);
    }

    static double fromS15(int v) {
        return v / 65536.0;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] mluc(String text) {
        final byte[] utf16 = text.getBytes(StandardCharsets.UTF_16BE);
        final ByteBuffer b = ByteBuffer.allocate(28 + utf16.length);
        b.put(ascii("mluc")).putInt(0);
        b.putInt(1); // one record
        b.putInt(12); // record size
        b.put(ascii("en")).put(ascii("US"));
        b.putInt(utf16.length);
        b.putInt(28); // offset of the string from the start of the element
        b.put(utf16);
        return b.array();
    }

    private static byte[] xyz(double x, double y, double z) {
        final ByteBuffer b = ByteBuffer.allocate(20);
        b.put(ascii("XYZ ")).putInt(0).putInt(s15(x)).putInt(s15(y)).putInt(s15(z));
        return b.array();
    }

    private static byte[] sf32(double[] m) {
        final ByteBuffer b = ByteBuffer.allocate(8 + 4 * m.length);
        b.put(ascii("sf32")).putInt(0);
        for (double v : m) b.putInt(s15(v));
        return b.array();
    }

    private static byte[] para(double[] p) {
        final ByteBuffer b = ByteBuffer.allocate(12 + 4 * p.length);
        b.put(ascii("para")).putInt(0);
        b.putShort((short) 3).putShort((short) 0);
        for (double v : p) b.putInt(s15(v));
        return b.array();
    }
}
