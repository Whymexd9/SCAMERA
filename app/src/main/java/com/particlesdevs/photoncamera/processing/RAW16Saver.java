package com.particlesdevs.photoncamera.processing;

import android.media.Image;
import android.os.AsyncTask;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;

public class RAW16Saver extends DefaultSaver{
    private static final String TAG = "RAW16Saver";
    public RAW16Saver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void addImage(Image image) {
        Log.d(TAG, "start buffer size:" + IMAGE_BUFFER.size());
        image.getFormat();
        IMAGE_BUFFER.add(getFrame(image));
        image.close();
        bufferLock = false;
    }
}
