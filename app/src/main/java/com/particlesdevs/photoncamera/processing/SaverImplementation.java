package com.particlesdevs.photoncamera.processing;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.media.ImageReader;
import java.nio.ByteBuffer;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.processor.ProcessorBase;
import com.particlesdevs.photoncamera.util.Allocator;

import java.util.ArrayList;
import java.util.HashMap;

public class SaverImplementation {
    private static final String TAG = "SaverImplementation";
    public volatile boolean bufferLock = true;
    public volatile boolean newBurst = false;
    public static ArrayList<ImageFrame> IMAGE_BUFFER = new ArrayList<>();
    /** P30: the arena of the shot this saver collects (post-shutter RAWs are copied into it), or null. */
    public volatile com.particlesdevs.photoncamera.util.ShotArena shotArena;
    public int frameCount = 0;
    private int imageFormat;
    public final ProcessingEventsListener processingEventsListener;

    public ImageFrame getFrame(Image image){
        try {
            image.getFormat();
        } catch (Exception e) {
            // This image is not valid, skip it
            return null;
        }
        // Post-shutter frames are kept but marked: the burst writers drop or refuse a frame that is not plain 16-bit.
        RawPayloadCheck.Result payload = RawPayloadCheck.check(image,
                RawPayloadCheck.whiteLevel(CaptureController.mCameraCharacteristics, null));
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        int format = image.getFormat();
        int rowStride = image.getPlanes()[0].getRowStride();
        int width;
        int height = image.getHeight();
        int offset = 0;
        int capacity = buffer.capacity();
        if (format == 0x25) {
            width = image.getWidth();
        } else {
            width = rowStride / image.getPlanes()[0].getPixelStride();
        }
        if (!payload.plain() && format == ImageFormat.RAW_SENSOR) {
            // Xiaomi 17 Ultra tele through logical camera 0: packed MIPI RAW10 (mode 4) or RAW14 (in-sensor zoom, mode 9, P72)
            // behind RAW_SENSOR. The frame is read in that format with the packed rows' own stride and size (the RAW16 capacity
            // made a 4080x4915 frame the merge refused).
            RawPayloadCheck.Layout layout = RawPayloadCheck.packedLayout(buffer, image.getWidth(), height,
                    RawPayloadCheck.blackLevel(CaptureController.mCameraCharacteristics),
                    RawPayloadCheck.whiteLevel(CaptureController.mCameraCharacteristics, null));
            if (layout != null) {
                com.particlesdevs.photoncamera.util.Log.w(TAG, "post-shutter RAW_SENSOR holds " + layout.describe() + ", unpacking to plain uint16");
                format = layout.format;
                width = image.getWidth();
                rowStride = layout.stride;
                capacity = layout.stride * height;
                payload = new RawPayloadCheck.Result(null, payload.impossibleShare, payload.zeroRowShare, true);
            }
        }
        if (!payload.plain()) {
            com.particlesdevs.photoncamera.util.Log.w(TAG, "post-shutter " + payload.error);
            RawPayloadCheck.dumpOnce(image, payload, PhotonCamera.getSettings().mCameraID);
        }
        if(PhotonCamera.getSettings().aspect169){
            if(width > height){
                height = width * 9 / 16;
                int offsetH = (image.getHeight() - height) / 2;
                offsetH -= offsetH % 2;
                offset = rowStride * offsetH;
                capacity = rowStride * height;
            }
        }
        Allocator.binning = PhotonCamera.getSettings().binning;
        ImageFrame frame = new ImageFrame(buffer, format, width, rowStride, offset, capacity, shotArena);
        frame.rawPayloadError = payload.error;
        frame.sourceFormat = format;
        frame.timestamp = image.getTimestamp();

        if (Allocator.binning) {
            frame.width = width / 2;
            frame.height = height / 2;
        } else {
            frame.width = width;
            frame.height = height;
        }

        return frame;
    }

    final ProcessorBase.ProcessingCallback processingCallback = new ProcessorBase.ProcessingCallback() {
        @Override
        public void onStarted() {
            CaptureController.isProcessing = true;
        }

        @Override
        public void onFailed() {
            onFinished();
        }

        @Override
        public void onFinished() {
            //clearImageReader(imageReader);
            CaptureController.isProcessing = false;
        }
    };
    public SaverImplementation(ProcessingEventsListener processingEventsListener){
        this.processingEventsListener = processingEventsListener;
    }

    public void addImage(Image image) {
        //image.close();
    }

    void addRAW10(Image image){
        //image.close();
    }
    protected void clearImageReader(ImageReader reader) {
        while (true) {
            try {
                reader.acquireNextImage().close();
            } catch (Exception ignored){
                break;
            }
        }
        //reader.close();
    }

    public void runRaw(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
        this.imageFormat = imageFormat;
    }
    public void processStart(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, int cameraRotation) {
        this.imageFormat = imageFormat;
    }
    public void processEnd(){
    }
}
