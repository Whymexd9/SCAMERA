package com.particlesdevs.photoncamera.capture;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class VivoVcf2Wire {
    static final int MAGIC = 0x56434632, VERSION = 1;
    static final int READY = 1, ARMED = 2, JPEG = 3, RESULT = 4, ERROR = 5;
    static final int ARM = 10, RETIRE = 11;
    static final int MAX_JPEG = 256 * 1024 * 1024;

    static void jpeg(DataOutputStream out, long id, byte[] bytes) throws IOException {
        validateJpeg(bytes);
        out.writeInt(JPEG);
        out.writeLong(id);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    static byte[] readJpeg(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 4 || length > MAX_JPEG) throw new IOException("Invalid VCF2 JPEG size");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        validateJpeg(bytes);
        return bytes;
    }

    static void validateJpeg(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_JPEG
                || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8 || bytes[2] != (byte) 0xff)
            throw new IOException("Invalid VCF2 JPEG payload");
    }
}
