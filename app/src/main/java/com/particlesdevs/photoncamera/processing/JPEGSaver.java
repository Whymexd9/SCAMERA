package com.particlesdevs.photoncamera.processing;

import android.media.Image;

import com.particlesdevs.photoncamera.app.ScameraApp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public class JPEGSaver extends DefaultSaver {
    private static final String TAG = "JPEGSaver";
    public JPEGSaver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void addImage(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        try {
            IMAGE_BUFFER.add(getFrame(image));
            byte[] bytes = new byte[buffer.remaining()];
            if (IMAGE_BUFFER.size() == ScameraApp.getCaptureController().mMeasuredFrameCnt && ScameraApp.getSettings().frameCount != 1) {
                Path jpgPath = PhotoFormat.JPEG.fileFor(ImagePath.newImageFilePath()); // the camera's own JPEG bytes: always .jpg
                buffer.duplicate().get(bytes);
                Files.write(jpgPath, bytes);

//                scamProcessor.start(dngFile, jpgFile, IMAGE_BUFFER, mImage.getFormat(),
//                        CaptureController.mCameraCharacteristics, CaptureController.mCaptureResult,
//                        () -> clearImageReader(mReader));

                IMAGE_BUFFER.clear();
            }
            if (ScameraApp.getSettings().frameCount == 1) {
                Path jpgPath = PhotoFormat.JPEG.fileFor(ImagePath.newImageFilePath()); // the camera's own JPEG bytes: always .jpg
                IMAGE_BUFFER.clear();
                buffer.get(bytes);
                Files.write(jpgPath, bytes);
                image.close();
                processingEventsListener.onProcessingFinished("JPEG: Single Frame, Not Processed!");
                processingEventsListener.notifyImageSavedStatus(true, jpgPath);
            }
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
        }
    }
}
