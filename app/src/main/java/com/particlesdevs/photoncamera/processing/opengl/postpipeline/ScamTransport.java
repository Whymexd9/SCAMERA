package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** A RAW burst the native worker consumes through `--scam-capture` (NCH v9 7-slot or the SCAM Hybrid v10 transport). */
interface ScamTransport {
    int width();
    int height();
    /** Size of the RGB the worker returns (the SCAM Hybrid can merge on the Sabre 2x grid and resize); default = sensor. */
    default int outputWidth() { return width(); }
    default int outputHeight() { return height(); }
    int cfa();
    /** Whether the worker should also return the merged Bayer RAW for the DNG. */
    boolean mergedDng();
    boolean diagnostics();
    /**
     * Whether the worker should append the per-pixel clip flags (uint8 per output pixel, after the effective-frame map;
     * SCAM Hybrid only, header flag 4). The client then accepts that third trailer.
     */
    default boolean clipFlags() { return false; }
    /**
     * P30: the burst already lies in shared memory (the shot's arena): the transport header is written into it and a
     * descriptor of that memfd is returned (the caller owns it); null: write() the burst as before.
     */
    default android.os.ParcelFileDescriptor sharedBurst() { return null; }
    void write(FileChannel out) throws IOException;
    void write(File file) throws IOException;
}
