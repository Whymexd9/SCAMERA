package com.particlesdevs.photoncamera.ui.camera.views;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
/** Masks the SurfaceView corners without cropping/resizing the camera buffer. */
public class ViewfinderFrame extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mask=new Path();
    private final RectF frame=new RectF();
    public ViewfinderFrame(Context context,AttributeSet attrs){super(context,attrs);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        float d=getResources().getDisplayMetrics().density;
        frame.set(0,0,getWidth(),getHeight());
        mask.reset();mask.setFillType(Path.FillType.EVEN_ODD);mask.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);mask.addRoundRect(frame,24*d,24*d,Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xFF000000);canvas.drawPath(mask,paint);
    }
}
