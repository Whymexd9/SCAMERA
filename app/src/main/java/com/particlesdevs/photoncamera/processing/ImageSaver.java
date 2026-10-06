package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.media.ImageReader;
import com.particlesdevs.photoncamera.util.Log;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;


import static com.particlesdevs.photoncamera.processing.ImageSaverSelector.getImageSaver;
import static com.particlesdevs.photoncamera.processing.ImageSaverSelector.init;

public class ImageSaver {
    /**
     * Image frame buffer
     */
    private static final String TAG = "ImageSaver";


    public SaverImplementation implementation;
    /** P30: the shot's arena the RAWs of this burst are copied into (ShotArena), or null. */
    public volatile com.particlesdevs.photoncamera.util.ShotArena shotArena;
    private int imageFormat;
    private int frameCounter = 0;
    private int desiredFrameCount = 0;
    public boolean newBurst = false;

    public void setFrameCount(int desiredFrameCount){
        this.desiredFrameCount = desiredFrameCount;
    }

    public void setImageFormat(int imageFormat) {
        this.imageFormat = imageFormat;
    }

    public void updateFrameCount(int desiredFrameCount){
        this.desiredFrameCount = desiredFrameCount;
        this.implementation.frameCount = desiredFrameCount;
    }

    public synchronized int bufferSize(){
        return SaverImplementation.IMAGE_BUFFER.size();
    }

    public synchronized ArrayList<ImageFrame> snapshotFrames() {
        return new ArrayList<>(SaverImplementation.IMAGE_BUFFER);
    }

    /**
     * Moves this burst's frames and exposure pairs out of the shared buffers
     * so the camera can capture the next shot while this one is processed.
     */
    public synchronized void detachForQueue() {
        detachForQueue(null);
    }

    /** P27: {@code hybridRoute} is the merge route the shot was captured for; processing keeps it even if the setting changes. */
    public synchronized void detachForQueue(Boolean hybridRoute) {
        if (!(implementation instanceof DefaultSaver))
            throw new IllegalStateException("Queued processing requires the RAW saver");
        DefaultSaver saver = (DefaultSaver) implementation;
        saver.ownedHybridRoute = hybridRoute;
        saver.ownedFrames = new ArrayList<>(SaverImplementation.IMAGE_BUFFER);
        saver.ownedPairs = new ArrayList<>(com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector.fullpairs);
        SaverImplementation.IMAGE_BUFFER.clear();
        // Named at capture time, like the stock camera, not when processing starts.
        String suffix = uniqueSuffix(ImagePath.newImageFilePath().getFileName().toString());
        saver.ownedDngFile = withSuffix(ImagePath.newDNGFilePath(), suffix);
        saver.ownedImageFile = withSuffix(ImagePath.newImageFilePath(), suffix);
    }

    // Names have one-second resolution; queued shots can share a second.
    private static String lastReservedName = "";
    private static int lastReservedCount = 0;
    private static synchronized String uniqueSuffix(String name) {
        if (name.equals(lastReservedName)) return "_" + (++lastReservedCount);
        lastReservedName = name;
        lastReservedCount = 0;
        return "";
    }
    private static java.nio.file.Path withSuffix(java.nio.file.Path path, String suffix) {
        if (suffix.isEmpty()) return path;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return path.resolveSibling(dot < 0 ? name + suffix : name.substring(0, dot) + suffix + name.substring(dot));
    }

    /** Removes and closes the given frames of this burst (post-shutter RAWs the hybrid dropped). */
    public synchronized void removeFrames(java.util.Collection<ImageFrame> frames) {
        for (ImageFrame frame : frames) if (SaverImplementation.IMAGE_BUFFER.remove(frame) && frame != null) frame.close();
    }

    public synchronized void discardFrames() {
        desiredFrameCount = 0;
        for (ImageFrame frame : SaverImplementation.IMAGE_BUFFER) frame.close();
        SaverImplementation.IMAGE_BUFFER.clear();
    }

    public ImageSaver(ProcessingEventsListener processingEventsListener) {
        implementation = new DefaultSaver(processingEventsListener);
        init(implementation);
    }

    public void initProcess(ImageReader reader) {
        Image image;
        try { image = reader.acquireNextImage(); } catch (Exception ignored) { return; }
        initProcess(image);
    }

    /** Accepts an owned image after preview/still routing by sensor timestamp. */
    public synchronized void initProcess(Image image) {
        if (image == null) return;
        if (frameCounter < desiredFrameCount || desiredFrameCount == -1) {
            imageFormat = image.getFormat();
            implementation = getImageSaver(imageFormat, implementation);
            implementation.shotArena = shotArena; // the implementation can change with the format (P30)
            implementation.frameCount = desiredFrameCount;
            implementation.newBurst = newBurst;
            implementation.addImage(image);
        } else image.close();
        frameCounter++;
    }

    public void runRaw(CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
        implementation.runRaw(imageFormat,characteristics,captureResult, captureRequest,burstShakiness,cameraRotation, exposures);
    }

    public void processStart(CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, int cameraRotation) {
        implementation = ImageSaverSelector.getImageSaver(ImageFormat.RAW_SENSOR, implementation);
        implementation.processStart(imageFormat,characteristics,captureResult, captureRequest,cameraRotation);
    }

    public void processEnd() {
        implementation.processEnd();
    }

    public static class Util {
        /** Output buffer of the JPEG writers: Bitmap.compress hands its encoder output over in 4 KB chunks. */
        public static final int SAVE_BUFFER_BYTES = 256 * 1024;

        /**
         * Encodes {@code img} straight into the file (no in-memory JPEG copy) and
         * always recycles it, as soon as the encoder is done and also on failure:
         * at 50 MP the bitmap is ~200 MB of native memory that must not wait for
         * a GC. EXIF is written afterwards; the bitmap is gone by then. A file
         * whose image data could not be written completely is deleted, so a
         * truncated JPEG never reaches the gallery.
         */
        public static boolean saveBitmapAsJPG(Path fileToSave, Bitmap img, int jpgQuality, ParseExif.ExifData exifData) {
            exifData.COMPRESSION = ParseExif.COMPRESSION_JPEG;
            boolean encoded = false;
            try {
                // jpegli 4:4:4 first; if it fails, the file is rewritten from the start by Android's encoder (4:2:0).
                if (JpegliEncoder.available()) {
                    try (OutputStream outputStream = new java.io.BufferedOutputStream(Files.newOutputStream(fileToSave), SAVE_BUFFER_BYTES)) {
                        JpegliEncoder.compress(img, jpgQuality, outputStream);
                        outputStream.flush();
                        encoded = true;
                    } catch (IOException | RuntimeException e) {
                        Log.w(TAG, "jpegli save failed, Android encoder (4:2:0): " + e);
                    }
                }
                if (!encoded) {
                    try (OutputStream outputStream = new java.io.BufferedOutputStream(Files.newOutputStream(fileToSave), SAVE_BUFFER_BYTES)) {
                        JpegliEncoder.compressFallback(img, jpgQuality, outputStream);
                        outputStream.flush();
                        encoded = true;
                    } catch (IOException | RuntimeException e) {
                        Log.e(TAG, "JPEG save failed: " + Log.getStackTraceString(e));
                    }
                }
            } finally {
                if (!img.isRecycled()) img.recycle();
            }
            if (!encoded) {
                try { Files.deleteIfExists(fileToSave); } catch (IOException ignored) {}
                return false;
            }
            try {
                ExifInterface inter = ParseExif.setAllAttributes(fileToSave.toFile(), exifData);
                if (inter != null) inter.saveAttributes();
            } catch (IOException | RuntimeException e) {
                // The image itself is complete; missing EXIF must not report the shot as lost.
                Log.e(TAG, "EXIF write failed: " + Log.getStackTraceString(e));
            }
            return true;
        }

        /*public static boolean saveBitmapAsAVIF(Path fileToSave, Bitmap img, int jpgQuality, ParseExif.ExifData exifData) {
            exifData.COMPRESSION = String.valueOf(jpgQuality);
            try {
                OutputStream outputStream = Files.newOutputStream(fileToSave);
                //img.compress(Bitmap.CompressFormat.JPEG, jpgQuality, outputStream);
                HeifCoder coder = new HeifCoder();
                var buffer = coder.encodeAvif(img, jpgQuality, PreciseMode.LOSSY, AvifSpeed.EIGHT);
                outputStream.write(buffer);
                outputStream.flush();
                outputStream.close();
                img.recycle();
                //ExifInterface inter = ParseExif.setAllAttributes(fileToSave.toFile(), exifData);
                //inter.saveAttributes();
                return true;
            } catch (IOException e) {
                //e.printStackTrace();
                Log.d(TAG,"AVIF save error:"+Log.getStackTraceString(e));
                return false;
            }
        }*/

        public static boolean saveStackedRaw(Path dngFilePath,
                                             ByteBuffer buffer, Parameters parameters) {
            return saveSingleRaw(dngFilePath, buffer, parameters);
        }

        public static boolean saveSyntheticMosaicRaw(Path dngFilePath, ByteBuffer buffer,
                                                     Parameters parameters, int width, int height,
                                                     String reconstructionLabel) {
            DngCreator dngCreator = new DngCreator();
            dngCreator.setParameters(parameters);
            dngCreator.setQuadCFAMetadata(false);
            dngCreator.setCFAPattern(parameters.baseCfaPattern);
            dngCreator.setDescription("SCAMERA Synthetic Mosaic SR; computational CFA reconstruction; "
                    + reconstructionLabel + "; not original sensor RAW");
            dngCreator.setUniqueCameraModel(android.os.Build.MODEL + " SCAMERA Synthetic Mosaic SR");
            // The sensor lens-shading map no longer has the same sampling grid.
            // Replace it with a neutral map instead of writing mismatched geometry.
            dngCreator.setGainMap(new float[]{1f, 1f, 1f, 1f}, 0, 0, width, height, 1, 1);
            dngCreator.setCompression(false);
            try (OutputStream outputStream = Files.newOutputStream(dngFilePath)) {
                dngCreator.writeBuffer(outputStream, buffer, width, height);
                return true;
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Synthetic mosaic DNG save failed: " + Log.getStackTraceString(e));
                return false;
            } finally {
                dngCreator.close();
            }
        }
        public static boolean saveSingleRaw(Path dngFilePath,
                                            ImageFrame image,
                                            CameraCharacteristics characteristics,
                                            CaptureResult captureResult,
                                            int cameraRotation) {
            Parameters parameters = new Parameters();

            parameters.FillConstParameters(characteristics, new Point(image.width, image.height));
            int iso = captureResult.get(CaptureResult.SENSOR_SENSITIVITY);
            parameters.FillDynamicParameters(captureResult, null, iso);
            parameters.refineBlackLevel(image.buffer, image.width, image.height);
            parameters.cameraRotation = cameraRotation;
            Log.d(TAG, "Camera rotation: " + parameters.cameraRotation);
            Log.d(TAG, "activearr:" + characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
            Log.d(TAG, "precorr:" + characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE));
            return saveSingleRaw(dngFilePath, image.buffer, parameters);
        }

        public static boolean saveSingleRaw(Path dngFilePath,
                                            ByteBuffer buffer, Parameters parameters) {
            DngCreator dngCreator = new DngCreator();
            dngCreator.setParameters(parameters);
            dngCreator.setCompression(com.particlesdevs.photoncamera.settings.PreferenceKeys.isDngLossless());
            //dngCreator.setBinning(true);
            // P27: a DNG that cannot be written (I/O, or a DngCreator RuntimeException) costs only the DNG, never the JPEG.
            try (OutputStream outputStream = Files.newOutputStream(dngFilePath)) {
                dngCreator.writeBuffer(outputStream, buffer, parameters.rawSize.x, parameters.rawSize.y);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "DNG save failed: " + Log.getStackTraceString(e));
                return false;
            }
            return true;
        }
    }
}
