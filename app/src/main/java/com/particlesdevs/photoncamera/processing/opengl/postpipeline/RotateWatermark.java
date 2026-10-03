package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.opengl.GLImage;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.FileManager;

import java.io.File;
import java.io.IOException;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_REPEAT;

public class RotateWatermark extends Node {
    private static final int LOGO_WIDTH = 200, HEIGHT = 200;

    /**
     * The signature drawn from the settings: the camera-ring logo of the stock watermark (optional)
     * and up to two lines of text (default "SHOT ON" / "SCAMERA"). Null when nothing is left to draw.
     */
    static Bitmap buildBitmap() {
        String line1 = PreferenceKeys.getWatermarkLine1().trim().toUpperCase(java.util.Locale.ROOT);
        String line2 = PreferenceKeys.getWatermarkLine2().trim().toUpperCase(java.util.Locale.ROOT);
        boolean logo = PreferenceKeys.isWatermarkLogoOn();
        if (!logo && line1.isEmpty() && line2.isEmpty()) return null;
        Bitmap logoBitmap = null;
        if (logo) {
            try (java.io.InputStream in = PhotonCamera.getAssetLoader().getInputStream("watermark/photoncamera_watermark.png")) {
                Bitmap full = BitmapFactory.decodeStream(in);
                if (full != null) logoBitmap = Bitmap.createBitmap(full, 0, 0, Math.min(LOGO_WIDTH, full.getWidth()), Math.min(HEIGHT, full.getHeight()));
            } catch (Exception e) {
                Log.d("Watermark", "logo unavailable: " + e);
            }
        }
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        text.setTextSize(80f);
        text.setShadowLayer(3f, 2f, 2f, 0x99000000);
        float textWidth = Math.max(text.measureText(line1), text.measureText(line2));
        int left = logoBitmap != null ? LOGO_WIDTH + 10 : 30;
        int width = left + Math.max(0, Math.round(textWidth)) + 40;
        Bitmap out = Bitmap.createBitmap(width, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (logoBitmap != null) canvas.drawBitmap(logoBitmap, 0f, 0f, null);
        if (!line1.isEmpty() && !line2.isEmpty()) {
            canvas.drawText(line1, left, 88f, text);
            canvas.drawText(line2, left, 176f, text);
        } else if (!line1.isEmpty() || !line2.isEmpty()) {
            canvas.drawText(line1.isEmpty() ? line2 : line1, left, 132f, text);
        }
        return out;
    }
    private int rotate;
    private boolean watermarkNeeded;
    private GLImage watermark;
    private GLImage noise;
    public RotateWatermark(int rotation) {
        super("", "Rotate");
        rotate = rotation;
        watermarkNeeded = PreferenceKeys.isShowWatermarkOn();
    }

    @Override
    public void Compile() {}
    @Override
    public void AfterRun() {
        if(watermark != null) watermark.close();
        if(noise != null) noise.close();
    }

    @Override
    public void Run() {

        //else lutbm = BitmapFactory.decodeResource(PhotonCamera.getResourcesStatic(), R.drawable.neutral_lut);
        glProg.setDefine("WATERMARK",watermarkNeeded);

        glProg.useAssetProgram("RotateWatermark/addwatermark_rotate");
        try {
            File waterExternal = new File(FileManager.sPHOTON_TUNING_DIR,"watermark.png");
            Bitmap custom = waterExternal.exists() ? null : buildBitmap();
            if (waterExternal.exists()) watermark = new GLImage(waterExternal);
            else if (custom != null) watermark = new GLImage(custom);
            else watermark = new GLImage(PhotonCamera.getAssetLoader().getInputStream("watermark/photoncamera_watermark.png"));
            noise = new GLImage(PhotonCamera.getAssetLoader().getInputStream("noise.png"));
            glProg.setTexture("Watermark", new GLTexture(watermark,GL_LINEAR,GL_CLAMP_TO_EDGE,0));
            glProg.setTexture("Noise", new GLTexture(noise,GL_LINEAR,GL_REPEAT,0));
        } catch (IOException e) {
            Log.d(Name,"Failed to load watermark or noise texture:" + Log.getStackTraceString(e));
        }

        glProg.setVar("watersizek", 100f / PreferenceKeys.getWatermarkHeightPercent());
        glProg.setVar("watermarkAlpha", PreferenceKeys.getWatermarkOpacity());
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        int rot = -1;
        Log.d(Name,"Rotation:"+rotate);
        switch (rotate){
            case 0:
                //WorkingTexture = new GLTexture(size.x,size.y, previousNode.WorkingTexture.mFormat, null);
                rot = 0;
                break;
            case 90:
                //WorkingTexture = new GLTexture(size.y,size.x, previousNode.WorkingTexture.mFormat, null);
                rot = 3;
                break;
            case 180:
                //WorkingTexture = new GLTexture(size, previousNode.WorkingTexture.mFormat, null);
                rot = 2;
                break;
            case 270:
                //WorkingTexture = new GLTexture(size.y,size.x, previousNode.WorkingTexture.mFormat, null);
                rot = 1;
                break;
        }
        Log.d(Name,"selected rotation:"+rot);
        glProg.setVar("rotate",rot);
        if(basePipeline.mParameters.mirror) {
            glProg.setVar("mirror", 1);
        } else {
            glProg.setVar("mirror", 0);
        }
        // After the hybrid final resize the working image (and cropSize) are already at the final size.
        Point rawSz = ((PostPipeline) basePipeline).finalSize != null ? previousNode.WorkingTexture.mSize : basePipeline.mParameters.rawSize;
        glProg.setVar("cropSize",((PostPipeline)basePipeline).cropSize);
        glProg.setVar("rawSize",rawSz);
        Log.d(Name,"Crop size:"+((PostPipeline)basePipeline).cropSize);
        Log.d(Name,"Raw size:"+basePipeline.mParameters.rawSize);

    }
}
