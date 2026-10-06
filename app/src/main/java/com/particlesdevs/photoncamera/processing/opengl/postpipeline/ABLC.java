package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.annotation.SuppressLint;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.scripts.ABL;

import java.util.Calendar;
import java.util.Locale;

public class ABLC extends Node {
    private static final String TAG = "ABLC";

    boolean enable = true;

    int histSize = 256;

    double noiseEV = 0.0;

    double minExposureMpy = 8.0;

    double maxEV = 10.0;

    
    public ABLC() {
        super("", "ABLC");
    }

    @Override
    public void Compile() {
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void Run() {
        if(!enable){
            WorkingTexture = super.previousNode.WorkingTexture;
            return;
        }
        ABL abl = new ABL(basePipeline.glint.glProcessing, histSize);

        // Use bruteforce method to find optimal black levels that minimize color shifting
        //float[] blackLevels = bruteforceOptimalBlackLevels(hist);
        double noise = Math.sqrt(basePipeline.noiseS + basePipeline.noiseO);
        noise *= Math.pow(2.0, noiseEV);
        Log.d(TAG, "Noise value:" + noise);
        float[] blackLevels = abl.Compute(
                minExposureMpy,
                maxEV,
                noise,
                previousNode.WorkingTexture
        );

        Log.d(TAG, String.format("Bruteforce Black Levels - R: %.4f, G: %.4f, B: %.4f", 
               blackLevels[0], blackLevels[1], blackLevels[2]));

        // Apply black level correction
        glProg.useAssetProgram("ABLC/levelcorrection");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setVar("blackLevel", blackLevels);
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
