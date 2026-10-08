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
        toggle.setFocusable(true);toggle.setContentDescription(getContext().getString(R.string.manual_panel_open));
        toggle.setOnClickListener(v -> setExpanded(!expanded,true));
    }
    public boolean isExpanded(){return expanded;}

    /** Whether the shade allows the panel on screen (the shade is HIDDEN). */
    private boolean shadeShown = true;
    /** Duration of the fade and slide when the shade leaves or returns to HIDDEN. */
    static final int SHADE_FADE_MS = 200;
    /** How far the panel slides down while it fades out. */
    static final float SHADE_SLIDE_DP = 16;

    public boolean isShadeShown(){return shadeShown;}

    /**
     * Shows the panel while the shade is HIDDEN and hides it at the other levels: a short fade and slide (alpha, 16dp down),
     * INVISIBLE at the end so it takes no touches. Nothing else changes, so it comes back open or closed with the same
     * parameter selected.
     */
    public void setShadeShown(boolean shown, boolean animate){
        boolean changed = shown != shadeShown;
        shadeShown = shown;
        animate().cancel();
        float slide = dp(SHADE_SLIDE_DP);
        if(!animate){
            setAlpha(shown?1f:0f);setTranslationY(shown?0f:slide);setVisibility(shown?VISIBLE:INVISIBLE);
            return;
        }
        if(!changed && (shown ? getVisibility()==VISIBLE && getAlpha()==1f : getVisibility()!=VISIBLE)) return;
        if(shown){
            setVisibility(VISIBLE);
            animate().alpha(1f).translationY(0f).setDuration(SHADE_FADE_MS).start();
        } else {
            animate().alpha(0f).translationY(slide).setDuration(SHADE_FADE_MS)
                    .withEndAction(() -> { if(!shadeShown) setVisibility(INVISIBLE); }).start();
        }
    }
    public void setExpanded(boolean value, boolean animate){
        expanded=value;
        if(animator!=null)animator.cancel();
        if(!value){restoreScale=scale.getVisibility()==VISIBLE;scale.setVisibility(GONE);}
        else if(restoreScale)scale.setVisibility(VISIBLE);
        tabs.setVisibility(VISIBLE);tabs.setImportantForAccessibility(value?IMPORTANT_FOR_ACCESSIBILITY_AUTO:IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        toggle.setContentDescription(getContext().getString(value?R.string.manual_panel_close:R.string.manual_panel_open));toggle.invalidate();
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
        // The first animation frame has p=0. Later frames must restore visibility.
        tabs.setVisibility(expanded || p>0 ? VISIBLE : INVISIBLE);
        if(p>=1)tabs.setClipBounds(null);
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
