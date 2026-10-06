package com.particlesdevs.photoncamera.ui.camera.views;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;

/**
 * The preview's frame (P25): 26dp corners masked in the screen background (BG) without cropping or resizing the camera
 * buffer, a 1dp LINE outline and thin corner marks. The preview stays full-bleed (owner's answer 4).
 */
public class ViewfinderFrame extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mask=new Path();
    private final Path marks=new Path();
    private final RectF frame=new RectF();
    private final RectF arc=new RectF();
    public ViewfinderFrame(Context context,AttributeSet attrs){super(context,attrs);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        float d=getResources().getDisplayMetrics().density, r=26*d;
        frame.set(0,0,getWidth(),getHeight());
        mask.reset();mask.setFillType(Path.FillType.EVEN_ODD);mask.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);mask.addRoundRect(frame,r,r,Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);paint.setColor(ShadeStyle.BG);canvas.drawPath(mask,paint);
        // 1dp outline on the edge of the rounded preview.
        float half=d/2f;
        frame.set(half,half,getWidth()-half,getHeight()-half);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d);paint.setColor(ShadeStyle.LINE);
        canvas.drawRoundRect(frame,r-half,r-half,paint);
        // Corner marks: 26dp L-shapes with 10dp rounded corners, 16dp inside the frame, MUTED at 45 %.
        float inset=16*d, size=26*d, round=10*d, w=getWidth(), h=getHeight();
        if(w<2*(inset+size)||h<2*(inset+size))return;
        marks.reset();
        corner(inset,inset,size,round,1,1);
        corner(w-inset,inset,size,round,-1,1);
        corner(inset,h-inset,size,round,1,-1);
        corner(w-inset,h-inset,size,round,-1,-1);
        paint.setStrokeWidth(2*d);paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor((ShadeStyle.MUTED&0x00FFFFFF)|0x73000000);
        canvas.drawPath(marks,paint);
    }
    /** One L-shaped mark with its corner at (x, y), arms going sx / sy. */
    private void corner(float x,float y,float size,float round,int sx,int sy){
        marks.moveTo(x,y+sy*size);
        marks.lineTo(x,y+sy*round);
        arc.set(Math.min(x,x+sx*2*round),Math.min(y,y+sy*2*round),Math.max(x,x+sx*2*round),Math.max(y,y+sy*2*round));
        float start, sweep;
        if(sx>0&&sy>0){start=180;sweep=90;}
        else if(sx<0&&sy>0){start=0;sweep=-90;}
        else if(sx>0&&sy<0){start=180;sweep=-90;}
        else {start=0;sweep=90;}
        marks.arcTo(arc,start,sweep,false);
        marks.lineTo(x+sx*size,y);
    }
}
