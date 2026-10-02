package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** A RAW burst the native worker consumes through `--nice-capture` (NCH v9 7-slot or the LMC hybrid v10 transport). */
interface NiceTransport {
    int width();
    int height();
    int cfa();
    /** Whether the worker should also return the merged Bayer RAW for the DNG. */
    boolean mergedDng();
    boolean diagnostics();
    void write(FileChannel out) throws IOException;
    void write(File file) throws IOException;
}
