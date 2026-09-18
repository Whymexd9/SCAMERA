package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.RelativeLayout;
import com.particlesdevs.photoncamera.circularbarlib.R;

/** A single translucent surface grows from the stationary left control. */
public class ExpandingManualPanel extends RelativeLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private View tabs, scale, toggle;
    private ValueAnimator animator;
    private float progress;
    private boolean expanded, restoreScale;
    public ExpandingManualPanel(Context c, AttributeSet a) { super(c,a); setWillNotDraw(false); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        tabs=findViewById(R.id.buttons_container); scale=findViewById(R.id.knobViewContainer);
        tabs.setBackground(null); tabs.setVisibility(INVISIBLE);
        toggle=new View(getContext()) {
            @Override protected void onDraw(Canvas c) {
                paint.setColor(0xffeceaf5); paint.setStrokeWidth(dp(1.7f)); paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                float x=getWidth()/2f,y=getHeight()/2f;
                if(expanded) {c.drawLine(x+dp(3),y-dp(6),x-dp(3),y,paint);c.drawLine(x-dp(3),y,x+dp(3),y+dp(6),paint);}
                else for(int i=-1;i<=1;i++){float cy=y+dp(i*6);float cx=x+dp(i==0?4:-3);c.drawLine(x-dp(9),cy,cx-dp(2),cy,paint);c.drawLine(cx+dp(2),cy,x+dp(9),cy,paint);c.drawCircle(cx,cy,dp(2),paint);}
            }
        };
        LayoutParams lp=new LayoutParams(dp(48),dp(48));lp.addRule(ALIGN_BOTTOM,R.id.buttons_container);addView(toggle,lp);
        toggle.setFocusable(true);toggle.setContentDescription("Открыть ручные настройки");
        toggle.setOnClickListener(v -> setExpanded(!expanded,true));
    }
    public boolean isExpanded(){return expanded;}
    public void setExpanded(boolean value, boolean animate){
        expanded=value;
        if(animator!=null)animator.cancel();
        if(!value){restoreScale=scale.getVisibility()==VISIBLE;scale.setVisibility(GONE);}
        else if(restoreScale)scale.setVisibility(VISIBLE);
        tabs.setVisibility(VISIBLE);tabs.setImportantForAccessibility(value?IMPORTANT_FOR_ACCESSIBILITY_AUTO:IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        toggle.setContentDescription(value?"Свернуть ручные настройки":"Открыть ручные настройки");toggle.invalidate();
        if(!animate){progress=value?1:0;updateFrame();return;}
        animator=ValueAnimator.ofFloat(progress,value?1:0);animator.setDuration(value?330:250);
        animator.setInterpolator(value?new OvershootInterpolator(0.25f):new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a->{progress=(float)a.getAnimatedValue();updateFrame();});animator.start();
    }
    private void updateFrame(){
        float p=Math.max(0,Math.min(1,progress));
        tabs.setAlpha(Math.max(0,(p-.18f)/.82f));
        tabs.setTranslationX(-dp(12)*(1-p));
        tabs.setClipBounds(new Rect(0,0,Math.round(tabs.getWidth()*p),tabs.getHeight()));
        if(p==0)tabs.setVisibility(INVISIBLE);
        invalidate();
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);if(tabs!=null)updateFrame();}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);paint.setStyle(Paint.Style.FILL);paint.setColor(0x8D0E1214);
        float h=dp(48),w=h+(getWidth()-h)*Math.max(0,Math.min(1,progress));
        c.drawRoundRect(0,getHeight()-h,w,getHeight(),h/2,h/2,paint);
    }
    @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
}
