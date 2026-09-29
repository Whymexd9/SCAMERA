package com.particlesdevs.photoncamera.capture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VivoNiceAeContext {
    final int runMode, hdrFlags, tableType, tableId;
    final long sceneMode;

    VivoNiceAeContext(byte[] input, byte[] state, byte[] plan) throws IOException {
        if (input == null || input.length != 0xe8 || state == null || state.length != 0x930
                || plan == null || plan.length != 100)
            throw new IOException("SCAM HDR AE: неполный контекст сцены");
        runMode = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).getInt(0xc4);
        sceneMode = ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getLong(0xb8);
        ByteBuffer output = ByteBuffer.wrap(plan).order(ByteOrder.LITTLE_ENDIAN);
        hdrFlags = output.getInt(72);
        tableType = output.getInt(76);
        tableId = output.getInt(80);
        // The generic vendor solver also returns seven usable exposures. Only
        // modes 9..13 use the NICE tuning bank; arithmetic parity alone is insufficient.
        if (runMode < 9 || runMode > 13 || (hdrFlags & 0x10000) == 0 || sceneMode == 0)
            throw new IOException("SCAM HDR AE: стоковый контекст сцены недоступен (scene=0x"
                    + Long.toHexString(sceneMode) + ", runMode=" + runMode + ")");
    }

    String describe() {
        return "scene=0x" + Long.toHexString(sceneMode) + " runMode=" + runMode
                + " hdrFlags=0x" + Integer.toHexString(hdrFlags)
                + " tableType=" + tableType + " tableId=" + tableId;
    }
}
