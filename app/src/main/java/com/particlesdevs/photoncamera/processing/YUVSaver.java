package com.particlesdevs.photoncamera.processing;

import android.media.Image;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.app.ScameraApp;

public class YUVSaver extends DefaultSaver{
    private static final String TAG = "YUVSaver";
    public YUVSaver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void addImage(Image image) {
        Log.d(TAG, "start buffersize:" + IMAGE_BUFFER.size());
        IMAGE_BUFFER.add(getFrame(image));
        if (IMAGE_BUFFER.size() == ScameraApp.getCaptureController().mMeasuredFrameCnt && ScameraApp.getSettings().frameCount != 1) {

//            scamProcessor.start(dngFile, jpgFile, IMAGE_BUFFER, mImage.getFormat(),
//                        CaptureController.mCameraCharacteristics, CaptureController.mCaptureResult,
//                        () -> clearImageReader(mReader));

            IMAGE_BUFFER.clear();
        }
        if (ScameraApp.getSettings().frameCount == 1) {
            IMAGE_BUFFER.clear();
            processingEventsListener.onProcessingFinished("YUV: Single Frame, Not Processed!");

        }
    }
}
