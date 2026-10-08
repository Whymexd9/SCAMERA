package com.particlesdevs.photoncamera.processing.heif;

import android.media.MediaFormat;

/** Package-private parts of the 10-bit HEIC writer for the P46 golden test (processing.color.DefaultOutputGoldenTest). */
public final class HeifContainerWriterTestAccess {
    private HeifContainerWriterTestAccess() {}

    /** HeifContainerWriterTest's 150 x 70 grid with its Exif block, written with the default colour. */
    public static byte[] defaultSampleFile() throws Exception {
        return HeifContainerWriterTest.sampleFile(HeifContainerWriterTest.EXIF);
    }

    /** The HEVC Main10 encoder format of the default (sRGB) colour. */
    public static MediaFormat format(int tile, boolean cq, int cqQuality, int bitrate) {
        return Heic10Encoder.format(tile, cq, cqQuality, bitrate);
    }
}
