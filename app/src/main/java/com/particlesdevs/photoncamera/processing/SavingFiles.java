package com.particlesdevs.photoncamera.processing;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P89 The photo files the camera is writing right now (owner, 2026-10-11: black photos in the gallery right after the
 * shot). A WebP takes 7-11 s and an AVIF HLG up to 37 s to encode, then the EXIF / ICC rewrite; a WebP read while it is being
 * written decodes "successfully" with the missing part black. The gallery (same process) waits for these files instead.
 */
public final class SavingFiles {
    private static final Set<String> WRITING = ConcurrentHashMap.newKeySet();

    private SavingFiles() {}

    public static void begin(Path file) {
        if (file != null) WRITING.add(file.toAbsolutePath().toString());
    }

    public static void end(Path file) {
        if (file != null) WRITING.remove(file.toAbsolutePath().toString());
    }

    /** Whether {@code absolutePath} (a MediaStore DATA path) is still being written by the camera. */
    public static boolean writing(String absolutePath) {
        return absolutePath != null && !WRITING.isEmpty() && WRITING.contains(absolutePath);
    }
}
