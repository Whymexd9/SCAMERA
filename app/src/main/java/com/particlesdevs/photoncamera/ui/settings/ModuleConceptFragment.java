package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.fragment.app.Fragment;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;

/** Shared native layout for the approved module-settings mockup. */
public abstract class ModuleConceptFragment extends Fragment {
    protected static final int BG=0xFF101416,CARD=0xFF1B2023,TEXT=0xFFF4F3F7,MUTED=0xFFB2BAC9,LINE=0xFF30363C;
    protected LinearLayout root,body;
    protected FrameLayout footer;
    protected TextView heading,subtitle;
    protected int accent;
    protected abstract void render();
    protected int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public View onCreateView(LayoutInflater i,ViewGroup p,Bundle b){
        accent=AccentPalette.color(requireContext());
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(12),dp(16),dp(16));
        FrameLayout header=new FrameLayout(requireContext());root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout titles=column();titles.setPadding(dp(42),0,dp(42),dp(16));
        TextView brand=text("SCAMERA",10,MUTED);brand.setLetterSpacing(.16f);brand.setGravity(Gravity.CENTER);titles.addView(brand);
        heading=text("",20,TEXT);heading.setTypeface(null,1);heading.setGravity(Gravity.CENTER);titles.addView(heading,space(-1,-2,8));
        subtitle=text("",13,MUTED);subtitle.setGravity(Gravity.CENTER);titles.addView(subtitle,space(-1,-2,6));header.addView(titles,new FrameLayout.LayoutParams(-1,-2));
        ImageView back=icon("back");back.setPadding(dp(9),dp(9),dp(9),dp(9));back.setContentDescription("Назад");back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);back.setFocusable(true);back.setOnClickListener(v->back());header.addView(back,new FrameLayout.LayoutParams(dp(40),dp(44),Gravity.START|Gravity.TOP));
        ScrollView scroll=new ScrollView(requireContext());scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body=column();body.setPadding(0,dp(4),0,dp(18));scroll.addView(body);
        footer=new FrameLayout(requireContext());footer.setPadding(0,dp(12),0,0);root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        render();return root;
    }
    @Override public void onResume(){super.onResume();View toolbar=requireActivity().findViewById(R.id.settings_toolbar);if(toolbar!=null)toolbar.setVisibility(View.GONE);if(root!=null){accent=AccentPalette.color(requireContext());render();}}
    @Override public void onDestroyView(){View toolbar=requireActivity().findViewById(R.id.settings_toolbar);if(toolbar!=null)toolbar.setVisibility(View.VISIBLE);root=null;super.onDestroyView();}
    protected void back(){requireActivity().getOnBackPressedDispatcher().onBackPressed();}
    protected void page(String title,String sub){heading.setText(title);subtitle.setText(sub);subtitle.setVisibility(sub==null||sub.isEmpty()?View.GONE:View.VISIBLE);body.removeAllViews();footer.removeAllViews();}
    protected LinearLayout column(){LinearLayout v=new LinearLayout(requireContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    protected LinearLayout row(){LinearLayout v=new LinearLayout(requireContext());v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    protected LinearLayout.LayoutParams space(int w,int h,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);lp.topMargin=dp(top);return lp;}
    protected TextView text(String value,float size,int color){TextView t=new TextView(requireContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");t.setIncludeFontPadding(false);return t;}
    protected GradientDrawable shape(int color,int stroke,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    protected LinearLayout card(){LinearLayout c=column();c.setBackground(shape(CARD,LINE,12));c.setPadding(dp(2),dp(2),dp(2),dp(2));body.addView(c,space(-1,-2,10));return c;}
    protected void caption(String title){TextView t=text(title,13,MUTED);body.addView(t,space(-1,-2,18));}
    protected void note(String value){LinearLayout n=row();TextView icon=text("ⓘ",18,MUTED);n.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(36)));TextView label=text(value,12,MUTED);label.setLineSpacing(dp(3),1);n.addView(label,new LinearLayout.LayoutParams(0,-2,1));body.addView(n,space(-1,-2,16));}
    protected TextView button(String title,boolean filled,Runnable action){TextView t=text(title,14,filled?0xFF15131D:accent);t.setGravity(Gravity.CENTER);t.setPadding(dp(8),dp(12),dp(8),dp(12));t.setMinHeight(dp(44));t.setBackground(shape(filled?accent:BG,filled?0:0xFF626974,10));t.setOnClickListener(v->action.run());t.setFocusable(true);t.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");}});return t;}
    protected void actions(Runnable all,Runnable none){LinearLayout r=row();TextView a=button("Выбрать всё",false,all),b=button("Отменить выбор",false,none);a.setTag("select_all");b.setTag("clear_selection");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(10);r.addView(a,lp);r.addView(b,new LinearLayout.LayoutParams(0,-2,1));body.addView(r,space(-1,-2,12));}
    protected void primary(String title,boolean enabled,Runnable action){TextView t=button(title,true,action);t.setTypeface(null,1);t.setMinHeight(dp(52));t.setEnabled(enabled);t.setAlpha(enabled?1:.4f);t.setTag("primary_action");footer.addView(t,new FrameLayout.LayoutParams(-1,-2));}
    protected void divider(LinearLayout parent){View v=new View(requireContext());v.setBackgroundColor(LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.leftMargin=dp(48);parent.addView(v,lp);}
    protected ImageView icon(String name){
        ImageView v=new ImageView(requireContext());int resource;
        switch(name){
            case "▣":resource=R.drawable.module_camera;break;
            case "☷":resource=R.drawable.module_list;break;
            case "◇":resource=R.drawable.module_tag;break;
            case "▢":resource=R.drawable.module_copy;break;
            case "back":resource=R.drawable.ic_baseline_arrow_back_24;break;
            case "◉":resource=R.drawable.settings_concept_color;break;
            default:resource=R.drawable.ic_settings;
        }
        v.setImageResource(resource);v.setColorFilter(accent);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    protected void navigation(String icon,String title,String summary,Runnable action){LinearLayout c=card(),r=row();r.setPadding(dp(12),dp(14),dp(12),dp(14));r.setMinimumHeight(dp(56));ImageView ic=icon(icon);ic.setPadding(0,0,dp(12),0);r.addView(ic,new LinearLayout.LayoutParams(dp(38),dp(28)));LinearLayout labels=column();labels.addView(text(title,15,TEXT));if(summary!=null){TextView sub=text(summary,12,MUTED);sub.setLineSpacing(dp(3),1);labels.addView(sub,space(-1,-2,6));}r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",25,MUTED));r.setOnClickListener(v->action.run());r.setTag(title);c.addView(r);}
    protected View mark(int state,String label,Runnable action){View v=new View(requireContext()){
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override protected void onDraw(Canvas canvas){float cx=getWidth()/2f,cy=getHeight()/2f,h=dp(10);RectF rect=new RectF(cx-h,cy-h,cx+h,cy+h);paint.setStyle(state==0?Paint.Style.STROKE:Paint.Style.FILL);paint.setStrokeWidth(dp(1.5f));paint.setColor(state==0?MUTED:accent);canvas.drawRoundRect(rect,dp(4),dp(4),paint);
            if(state!=0){paint.setStyle(Paint.Style.STROKE);paint.setColor(0xFF17141F);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);if(state==1)canvas.drawLine(cx-dp(4),cy,cx+dp(4),cy,paint);else{canvas.drawLine(cx-dp(5),cy,cx-dp(1),cy+dp(4),paint);canvas.drawLine(cx-dp(1),cy+dp(4),cx+dp(6),cy-dp(5),paint);}}}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.CheckBox");info.setCheckable(true);info.setChecked(state>0);}
    };v.setContentDescription(label+(state==1?", выбрано частично":""));v.setFocusable(true);v.setOnClickListener(w->action.run());return v;}
    protected void open(Fragment fragment){getParentFragmentManager().beginTransaction().replace(R.id.settings_container,fragment).addToBackStack(null).commit();}
}
