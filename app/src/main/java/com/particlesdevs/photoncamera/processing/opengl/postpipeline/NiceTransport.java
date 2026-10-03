package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** A RAW burst the native worker consumes through `--nice-capture` (NCH v9 7-slot or the LMC hybrid v10 transport). */
interface NiceTransport {
    int width();
    int height();
    /** Size of the RGB the worker returns (the LMC hybrid can merge on the Sabre 2x grid and resize); default = sensor. */
    default int outputWidth() { return width(); }
    default int outputHeight() { return height(); }
    int cfa();
    /** Whether the worker should also return the merged Bayer RAW for the DNG. */
    boolean mergedDng();
    boolean diagnostics();
    /**
     * Whether the worker should append the per-pixel clip flags (uint8 per output pixel, after the effective-frame map;
     * LMC hybrid only, header flag 4). The client then accepts that third trailer.
     */
    default boolean clipFlags() { return false; }
    void write(FileChannel out) throws IOException;
    void write(File file) throws IOException;
}
