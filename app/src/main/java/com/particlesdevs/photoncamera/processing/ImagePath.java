package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.FileManager;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ImagePath {
    public static String generateNewFileName(String prefix) {
        return prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    /** P119: the name (no extension) of a new photo from «Название фото» (FileNamePattern; SCA_yyyyMMdd_HHmmss by default). */
    public static String newBaseName() {
        String pattern;
        try { pattern = PreferenceKeys.getFileNamePattern(); } catch (RuntimeException e) { pattern = FileNamePattern.DEFAULT; }
        return FileNamePattern.format(pattern, System.currentTimeMillis());
    }

    /** The photo {@code name} (no extension yet) in the camera folder. */
    public static Path imageFile(String name) {
        return Paths.get(FileManager.sDCIM_CAMERA.getAbsolutePath(), name);
    }

    /** The DNG of the photo {@code name} in the RAW folder. */
    public static Path dngFile(String name) {
        return Paths.get(FileManager.sRAW_DIR.getAbsolutePath(), name + ".dng");
    }

    public static Path newDNGFilePath() {
        return getNewImageFilePath("dng");
    }

    public static Path newImageFilePath() {
        return getNewImageFilePath("");
    }

    public static Path getNewImageFilePath(String extension) {
        File dir = FileManager.sDCIM_CAMERA;
        if (extension.equalsIgnoreCase("dng")) {
            dir = FileManager.sRAW_DIR;
        }
        if(!extension.isEmpty()) {
            return Paths.get(dir.getAbsolutePath(), newBaseName() + '.' + extension);
        } else {
            return Paths.get(dir.getAbsolutePath(), newBaseName());
        }
    }

    public static Path getNewImageFolderPath() {
        File dir = FileManager.sRAW_DIR;
        return Paths.get(dir.getAbsolutePath(), newBaseName());
    }

    public static Path getNewVideoFolderPath() {
        File dir = FileManager.sRAW_DIR;
        return Paths.get(dir.getAbsolutePath(), generateNewFileName("VID"));
    }
}
