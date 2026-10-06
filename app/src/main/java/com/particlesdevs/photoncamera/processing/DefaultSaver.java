package com.particlesdevs.photoncamera.processing;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.processor.HdrxProcessor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public class DefaultSaver extends SaverImplementation {
    private static final String TAG = "DefaultSaver";
    final HdrxProcessor hdrxProcessor;
    ArrayList<ImageFrame> ownedFrames;
    Path ownedDngFile, ownedImageFile;
    java.util.List<com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector.ExpoPair> ownedPairs;
    /** P27: the merge route the shot was captured for (null: read the setting when processing starts). */
    Boolean ownedHybridRoute;

    public DefaultSaver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
        this.hdrxProcessor = new HdrxProcessor(processingEventsListener);
    }

    public void runRaw(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
        super.runRaw(imageFormat, characteristics, captureResult,captureRequest, burstShakiness, cameraRotation, exposures);
        if (ownedFrames != null) {
            // Queued shot: frames and exposure pairs were detached from the
            // shared buffers on the camera thread when the burst completed.
            ArrayList<ImageFrame> frames = ownedFrames;
            ownedFrames = null;
            hdrxProcessor.ownedPairs = ownedPairs;
            hdrxProcessor.ownedHybridRoute = ownedHybridRoute;
            ownedHybridRoute = null;
            hdrxProcessor.configure(PhotonCamera.getSettings().alignAlgorithm,
                    PhotonCamera.getSettings().rawSaver, PhotonCamera.getSettings().selectedMode);
            hdrxProcessor.start(ownedDngFile, ownedImageFile,
                    ParseExif.parse(captureResult, captureRequest), burstShakiness, frames, exposures,
                    imageFormat, cameraRotation, characteristics, captureResult, captureRequest,
                    processingCallback);
            return;
        }
        //Wait for one frame at least.
        Log.d(TAG, "Acquiring:" + IMAGE_BUFFER.size());
        while (bufferLock || IMAGE_BUFFER.isEmpty()){}
        Log.d(TAG, "Acquired:" + IMAGE_BUFFER.size());
        bufferLock = true;
        Log.d(TAG,"Size:"+IMAGE_BUFFER.size());
        /*if (PhotonCamera.getSettings().frameCount == 1) {
            Path dngFile = ImagePath.newDNGFilePath();
            Log.d(TAG, "Size:" + IMAGE_BUFFER.size());
            boolean imageSaved = ImageSaver.Util.saveSingleRaw(dngFile, IMAGE_BUFFER.get(0),
                    characteristics, captureResult, cameraRotation);
            processingEventsListener.notifyImageSavedStatus(imageSaved, dngFile);
            processingEventsListener.onProcessingFinished("Saved Unprocessed RAW");
            IMAGE_BUFFER.clear();
            bufferLock = false;
            return;
        }*/
        Path dngFile = ImagePath.newDNGFilePath();
        Path imageFile = ImagePath.newImageFilePath();
        //Remove broken images
            /*for(int i =0; i<IMAGE_BUFFER.size();i++){
                try{
                    IMAGE_BUFFER.get(i).getFormat();
                } catch (IllegalStateException e){
                    IMAGE_BUFFER.remove(i);
                    i--;
                    Log.d(TAG,"IMGBufferSize:"+IMAGE_BUFFER.size());
                    e.printStackTrace();
                }
            }*/
        hdrxProcessor.configure(
                PhotonCamera.getSettings().alignAlgorithm,
                PhotonCamera.getSettings().rawSaver,
                PhotonCamera.getSettings().selectedMode
        );
        ArrayList<ImageFrame> slicedBuffer = new ArrayList<>();
        ArrayList<ImageFrame> imagebuffer = new ArrayList<>();
        for(int i =0; i<frameCount;i++){
            slicedBuffer.add(IMAGE_BUFFER.get(i));
        }
        for(int i = frameCount; i<IMAGE_BUFFER.size();i++){
            imagebuffer.add(IMAGE_BUFFER.get(i));
        }
        IMAGE_BUFFER.clear();
        IMAGE_BUFFER = imagebuffer;
        bufferLock = false;
        for(int i =0; i<slicedBuffer.size();i++){
            if (slicedBuffer.get(i) == null) {
                slicedBuffer.remove(i);
                i--;
                Log.d(TAG, "IMGBufferSize:" + slicedBuffer.size());
            }
        }

        Log.d(TAG,"moving images");
        //Log.d(TAG,"moved images:"+slicedBuffer.size());
        hdrxProcessor.start(
                dngFile,
                imageFile,
                ParseExif.parse(captureResult, captureRequest),
                burstShakiness,
                slicedBuffer,
                exposures,
                imageFormat,
                cameraRotation,
                characteristics,
                captureResult,
                captureRequest,
                processingCallback
        );
        slicedBuffer.clear();
    }


}
