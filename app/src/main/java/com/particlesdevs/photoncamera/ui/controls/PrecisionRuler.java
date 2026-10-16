package com.particlesdevs.photoncamera.ui.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.*;
import android.widget.OverScroller;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * Relative ruler: changing the step never changes or quantizes the current value. P172 (owner, 2026-10-16: «Сделай так чтобы
 * при выборе шага с таким шагом параметр и менялся»): a drag or a fling moves the value by whole steps - one tick (16dp) one
 * step (53 with «Шаг 10»: 63, 73, ...); the part of a tick not yet passed only slides the scale, which settles on the tick
 * when the finger lifts and the fling ends.
 */
public final class PrecisionRuler extends View {
    public interface Listener {void changed(double value);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OverScroller scroller;
    private final double min,max;
    private double value,step;
    private float lastX;
    /** P172: the drag's pixels short of a whole tick (they slide the scale, not the value). */
    private float pending;
    private boolean dragging;
    private int lastScroll;
    private long lastTick;
    private VelocityTracker velocity;
    private Listener listener;
    public PrecisionRuler(Context c,double min,double max,double value,double step){super(c);this.min=min;this.max=max;this.value=value;this.step=step;scroller=new OverScroller(c);setFocusable(true);setContentDescription(Lang.t(c,"Точная шкала","Fine scale"));}
    public void setListener(Listener l){listener=l;}
    /** The centre needle's colour (the camera accent by default; the settings' editor gives its own, P137). */
    public void setAccent(int color){accent=color;invalidate();}
    private Integer accent;
    public double getValue(){return value;}
    public void setStep(double s){scroller.forceFinished(true);pending=0;if(Double.isFinite(s)&&s>0)step=s;invalidate();}
    public void setValue(double v){scroller.forceFinished(true);pending=0;change(v);invalidate();}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private void change(double v){if(!Double.isFinite(v))return;double next=Math.max(min,Math.min(max,v));if(next==value)return;value=next;if(listener!=null)listener.changed(value);invalidate();}
    private void move(float pixels){
        float spacing=dp(16);
        pending+=pixels;
        int ticks=(int)(pending/spacing);   // whole ticks passed, towards zero
        if(ticks!=0){
            pending-=ticks*spacing;
            double before=value;
            change(value+ticks*step);
            if(value==before)pending=0;   // at the end of the range: the scale does not slide on
            long now=android.os.SystemClock.uptimeMillis();
            if(value!=before&&now-lastTick>30){performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);lastTick=now;}
        }
        if((value<=min&&pending<0)||(value>=max&&pending>0))pending=0;
        invalidate();
    }
    @Override protected void onDraw(Canvas c){super.onDraw(c);float center=getWidth()/2f,spacing=dp(16),y=getHeight()*.52f;double pos=value/step+pending/spacing,base=Math.floor(pos);int count=(int)(center/spacing)+2;
        paint.setStrokeWidth(dp(1));paint.setTextSize(dp(10));paint.setTextAlign(Paint.Align.CENTER);
        for(int i=-count;i<=count;i++){double tick=base+i,v=tick*step;if(v<min-1e-8||v>max+1e-8)continue;float x=center+(float)(tick-pos)*spacing;boolean major=((long)tick)%5==0;paint.setColor(0x99FFFFFF);c.drawLine(x,y,x,y+dp(major?18:9),paint);if(major)c.drawText(format(v),x,y-dp(8),paint);}
        paint.setColor(accent!=null?accent:AccentPalette.camera(getContext()));paint.setStrokeWidth(dp(2));c.drawLine(center,y-dp(3),center,y+dp(24),paint);
    }
    public static String format(double v){return java.math.BigDecimal.valueOf(v).setScale(3,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    @Override public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:scroller.forceFinished(true);dragging=true;pending=0;if(velocity!=null)velocity.recycle();velocity=VelocityTracker.obtain();velocity.addMovement(e);lastX=e.getX();getParent().requestDisallowInterceptTouchEvent(true);return true;
        case MotionEvent.ACTION_MOVE:if(velocity==null)return false;velocity.addMovement(e);move(lastX-e.getX());lastX=e.getX();return true;
        case MotionEvent.ACTION_UP:dragging=false;if(velocity==null)return false;velocity.addMovement(e);velocity.computeCurrentVelocity(1000,ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity());int speed=(int)-velocity.getXVelocity();velocity.recycle();velocity=null;lastScroll=0;scroller.fling(0,0,speed,0,-200000,200000,0,0);postInvalidateOnAnimation();performClick();return true;
        case MotionEvent.ACTION_CANCEL:dragging=false;pending=0;invalidate();if(velocity!=null){velocity.recycle();velocity=null;}scroller.forceFinished(true);return true;
        default:return true;}}
    @Override public void computeScroll(){
        if(scroller.computeScrollOffset()){int x=scroller.getCurrX();move(x-lastScroll);lastScroll=x;if((value<=min||value>=max)&&pending==0)scroller.forceFinished(true);postInvalidateOnAnimation();}
        else if(!dragging&&pending!=0){pending=0;invalidate();}   // P172: settle on the tick
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){scroller.forceFinished(true);if(velocity!=null){velocity.recycle();velocity=null;}super.onDetachedFromWindow();}
    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(1,(float)min,(float)max,(float)value));info.addAction(4096);info.addAction(8192);}
    @Override public boolean performAccessibilityAction(int action,android.os.Bundle args){if(action==4096||action==8192){change(value+(action==4096?step:-step));return true;}return super.performAccessibilityAction(action,args);}
}
