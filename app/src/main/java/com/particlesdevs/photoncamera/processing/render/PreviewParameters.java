package com.particlesdevs.photoncamera.processing.render;

import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.app.ScameraApp;

public class PreviewParameters {
    public NoiseModeler noiseModeler;
    private int analogIso;
    public byte cfaPattern;
    public void FillParameters(CaptureResult result, CameraCharacteristics characteristics) {
        Integer analogue = characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        if(analogue != null){
            analogIso = analogue;
        } else analogIso = 100;
        Object ptr = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (ptr != null) cfaPattern = (byte) (int) ptr;
        if (ScameraApp.getSettings().cfaPattern >= 0) {
            cfaPattern = (byte) ScameraApp.getSettings().cfaPattern;
        }
    }
    public void FillDynamicParameters(CaptureResult result) {
        noiseModeler = new NoiseModeler( result.get(CaptureResult.SENSOR_NOISE_PROFILE),analogIso,result.get(CaptureResult.SENSOR_SENSITIVITY),cfaPattern);

    }
}
