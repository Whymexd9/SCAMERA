package com.particlesdevs.photoncamera.ui.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.*;
import android.widget.OverScroller;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;

/** Relative ruler: changing the step never changes or quantizes the current value. */
public final class PrecisionRuler extends View {
    public interface Listener {void changed(double value);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OverScroller scroller;
    private final double min,max;
    private double value,step;
    private float lastX;
    private int lastScroll;
    private long lastTick;
    private VelocityTracker velocity;
    private Listener listener;
    public PrecisionRuler(Context c,double min,double max,double value,double step){super(c);this.min=min;this.max=max;this.value=value;this.step=step;scroller=new OverScroller(c);setFocusable(true);setContentDescription("Точная шкала");}
    public void setListener(Listener l){listener=l;}
    public double getValue(){return value;}
    public void setStep(double s){scroller.forceFinished(true);if(Double.isFinite(s)&&s>0)step=s;invalidate();}
    public void setValue(double v){scroller.forceFinished(true);change(v);}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private void change(double v){if(!Double.isFinite(v))return;double next=Math.max(min,Math.min(max,v));if(next==value)return;value=next;if(listener!=null)listener.changed(value);invalidate();}
    private void move(float pixels){double ticks=pixels/dp(16);double next=value+ticks*step;change(next);long now=android.os.SystemClock.uptimeMillis();if(Math.abs(pixels)>dp(2)&&now-lastTick>45){performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);lastTick=now;}}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float center=getWidth()/2f,spacing=dp(16),y=getHeight()*.52f;double pos=value/step,base=Math.floor(pos);int count=(int)(center/spacing)+2;
        paint.setStrokeWidth(dp(1));paint.setTextSize(dp(10));paint.setTextAlign(Paint.Align.CENTER);
        for(int i=-count;i<=count;i++){double tick=base+i,v=tick*step;if(v<min-1e-8||v>max+1e-8)continue;float x=center+(float)(tick-pos)*spacing;boolean major=((long)tick)%5==0;paint.setColor(0x99FFFFFF);c.drawLine(x,y,x,y+dp(major?18:9),paint);if(major)c.drawText(format(v),x,y-dp(8),paint);}
        paint.setColor(AccentPalette.camera(getContext()));paint.setStrokeWidth(dp(2));c.drawLine(center,y-dp(3),center,y+dp(24),paint);
    }
    public static String format(double v){return java.math.BigDecimal.valueOf(v).setScale(3,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    @Override public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:scroller.forceFinished(true);if(velocity!=null)velocity.recycle();velocity=VelocityTracker.obtain();velocity.addMovement(e);lastX=e.getX();getParent().requestDisallowInterceptTouchEvent(true);return true;
        case MotionEvent.ACTION_MOVE:if(velocity==null)return false;velocity.addMovement(e);move(lastX-e.getX());lastX=e.getX();return true;
        case MotionEvent.ACTION_UP:if(velocity==null)return false;velocity.addMovement(e);velocity.computeCurrentVelocity(1000,ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity());int speed=(int)-velocity.getXVelocity();velocity.recycle();velocity=null;lastScroll=0;scroller.fling(0,0,speed,0,-200000,200000,0,0);postInvalidateOnAnimation();performClick();return true;
        case MotionEvent.ACTION_CANCEL:if(velocity!=null){velocity.recycle();velocity=null;}scroller.forceFinished(true);return true;
        default:return true;}}
    @Override public void computeScroll(){if(scroller.computeScrollOffset()){int x=scroller.getCurrX();double before=value;move(x-lastScroll);lastScroll=x;if(value==before)scroller.forceFinished(true);postInvalidateOnAnimation();}}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){scroller.forceFinished(true);if(velocity!=null){velocity.recycle();velocity=null;}super.onDetachedFromWindow();}
    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(1,(float)min,(float)max,(float)value));info.addAction(4096);info.addAction(8192);}
    @Override public boolean performAccessibilityAction(int action,android.os.Bundle args){if(action==4096||action==8192){change(value+(action==4096?step:-step));return true;}return super.performAccessibilityAction(action,args);}
}
