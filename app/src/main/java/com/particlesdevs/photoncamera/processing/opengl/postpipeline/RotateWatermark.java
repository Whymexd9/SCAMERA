package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.opengl.GLImage;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.io.IOException;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_REPEAT;

public class RotateWatermark extends Node {
    private static final int LOGO_WIDTH = 200, HEIGHT = 200;

    /**
     * The signature drawn from the settings: the SCAMERA camera glyph (optional, drawLogo)
     * and up to two lines of text (default "SHOT ON" / "SCAMERA"). Null when nothing is left to draw.
     */
    static Bitmap buildBitmap() {
        String line1 = PreferenceKeys.getWatermarkLine1().trim().toUpperCase(java.util.Locale.ROOT);
        String line2 = PreferenceKeys.getWatermarkLine2().trim().toUpperCase(java.util.Locale.ROOT);
        boolean logo = PreferenceKeys.isWatermarkLogoOn();
        if (!logo && line1.isEmpty() && line2.isEmpty()) return null;
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        text.setTextSize(80f);
        text.setShadowLayer(3f, 2f, 2f, 0x99000000);
        float textWidth = Math.max(text.measureText(line1), text.measureText(line2));
        int left = logo ? LOGO_WIDTH + 10 : 30;
        int width = left + Math.max(0, Math.round(textWidth)) + 40;
        Bitmap out = Bitmap.createBitmap(width, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (logo) drawLogo(canvas, HEIGHT);
        if (!line1.isEmpty() && !line2.isEmpty()) {
            canvas.drawText(line1, left, 88f, text);
            canvas.drawText(line2, left, 176f, text);
        } else if (!line1.isEmpty() || !line2.isEmpty()) {
            canvas.drawText(line1.isEmpty() ? line2 : line1, left, 132f, text);
        }
        return out;
    }

    /**
     * The SCAMERA glyph (the camera of the app icon: body, viewfinder hump, lens, LED) in a size x size box, white with
     * the shadow of the text; strokes as light as the sans-serif-light lines next to it.
     */
    static void drawLogo(Canvas canvas, float size) {
        float s = size / 200f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setShadowLayer(3f, 2f, 2f, 0x99000000);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(9f * s);
        p.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawRoundRect(new RectF(28 * s, 58 * s, 172 * s, 158 * s), 22 * s, 22 * s, p);
        Path hump = new Path();
        hump.moveTo(58 * s, 58 * s);
        hump.lineTo(66 * s, 40 * s);
        hump.lineTo(98 * s, 40 * s);
        hump.lineTo(106 * s, 58 * s);
        canvas.drawPath(hump, p);
        canvas.drawCircle(100 * s, 108 * s, 30 * s, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(100 * s, 108 * s, 11 * s, p);
        canvas.drawCircle(146 * s, 82 * s, 6.5f * s, p);
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

        final boolean legacy = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy();
        // Nothing to draw (no logo, both lines empty): no watermark
        final Bitmap signature = watermarkNeeded || legacy ? buildBitmap() : null;
        if (signature == null) watermarkNeeded = false;
        glProg.setDefine("WATERMARK",watermarkNeeded);

        glProg.useAssetProgram("RotateWatermark/addwatermark_rotate");
        // W1.11: with WATERMARK 0 the shader never reads the watermark, and the noise it samples is not used (the dither
        // line is commented out): no font rendering, logo or noise.png decode and no two uploads for nothing.
        if (watermarkNeeded || legacy) try {
            watermark = new GLImage(signature != null ? signature : Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
            noise = new GLImage(ScameraApp.getAssetLoader().getInputStream("noise.png"));
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
