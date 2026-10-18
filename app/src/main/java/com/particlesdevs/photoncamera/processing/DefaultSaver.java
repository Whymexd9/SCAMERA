package com.particlesdevs.photoncamera.processing;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.processor.ScamProcessor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public class DefaultSaver extends SaverImplementation {
    private static final String TAG = "DefaultSaver";
    final ScamProcessor scamProcessor;
    ArrayList<ImageFrame> ownedFrames;
    Path ownedDngFile, ownedImageFile;
    java.util.List<com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector.ExpoPair> ownedPairs;
    /** P27: the merge route the shot was captured for (null: read the setting when processing starts). */
    Boolean ownedHybridRoute;
    Boolean ownedIszAspect4x3;
    /** P190: the FULLRES series of the queued shot (ImageSaver.fullResSeries / fullResRequested). */
    int ownedFullResSeries, ownedFullResRequested;
    /** P212f (W2 = M3): the hand-set white balance's gains at the press of the queued shot (null: automatic). */
    android.hardware.camera2.params.RggbChannelVector ownedManualWb;

    public DefaultSaver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
        this.scamProcessor = new ScamProcessor(processingEventsListener);
    }

    public void runRaw(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
        super.runRaw(imageFormat, characteristics, captureResult,captureRequest, burstShakiness, cameraRotation, exposures);
        scamProcessor.ownedIszAspect4x3 = ownedIszAspect4x3 != null ? ownedIszAspect4x3 : iszAspect4x3;
        ownedIszAspect4x3 = null;
        if (ownedFrames != null) {
            // Queued shot: frames and exposure pairs were detached from the
            // shared buffers on the camera thread when the burst completed.
            ArrayList<ImageFrame> frames = ownedFrames;
            ownedFrames = null;
            scamProcessor.ownedPairs = ownedPairs;
            scamProcessor.ownedHybridRoute = ownedHybridRoute;
            ownedHybridRoute = null;
            scamProcessor.ownedFullResSeries = ownedFullResSeries;
            scamProcessor.ownedFullResRequested = ownedFullResRequested;
            ownedFullResSeries = ownedFullResRequested = 0;
            scamProcessor.ownedManualWb = ownedManualWb;
            ownedManualWb = null;
            scamProcessor.configure(ScameraApp.getSettings().alignAlgorithm,
                    ScameraApp.getSettings().rawSaver, ScameraApp.getSettings().selectedMode);
            scamProcessor.start(ownedDngFile, ownedImageFile,
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
        /*if (ScameraApp.getSettings().frameCount == 1) {
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
        final String name = ImagePath.newBaseName(); // one name for both (P119)
        Path dngFile = ImagePath.dngFile(name);
        Path imageFile = ImagePath.imageFile(name);
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
        scamProcessor.configure(
                ScameraApp.getSettings().alignAlgorithm,
                ScameraApp.getSettings().rawSaver,
                ScameraApp.getSettings().selectedMode
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
        scamProcessor.start(
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
