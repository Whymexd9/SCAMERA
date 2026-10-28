package com.particlesdevs.photoncamera.ui.settings;

import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.fragment.app.Fragment;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * Shared native layout for the approved module-settings mockup (the settings copy, the accent, the DCP and the lab pages).
 * P384 (owner, 2026-10-28: «Сделай это меню красивым»): the module pages that used it are rows of the main page now (ModuleRows);
 * what stays here looks like the preference pages - a caption as their group titles, a row's glyph on its 36dp tile with the
 * chevron drawable, the note's info glyph.
 */
public abstract class ModuleConceptFragment extends Fragment {
    protected static final int BG=SettingsStyle.BG,CARD=SettingsStyle.CARD,TEXT=SettingsStyle.TEXT,MUTED=SettingsStyle.MUTED,LINE=SettingsStyle.LINE;
    protected LinearLayout root,body;
    protected FrameLayout footer;
    protected TextView heading,subtitle;
    protected int accent;
    protected abstract void render();
    protected int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public View onCreateView(LayoutInflater i,ViewGroup p,Bundle b){
        accent=AccentPalette.color(requireContext());
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(12),dp(16),dp(16));
        SettingsStyle.Header header=SettingsStyle.header(requireContext(),this::back,null); // P6b: the header shared with the preference pages
        heading=header.heading;subtitle=header.subtitle;root.addView(header.view,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(requireContext());scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body=column();body.setPadding(0,dp(4),0,dp(18));scroll.addView(body);
        footer=new FrameLayout(requireContext());footer.setPadding(0,dp(12),0,0);root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        render();return root;
    }
    @Override public void onResume(){super.onResume();View toolbar=requireActivity().findViewById(R.id.settings_toolbar);if(toolbar!=null)toolbar.setVisibility(View.GONE);if(root!=null){accent=AccentPalette.color(requireContext());render();}}
    @Override public void onDestroyView(){root=null;super.onDestroyView();}
    protected void back(){requireActivity().getOnBackPressedDispatcher().onBackPressed();}
    protected void page(String title,String sub){heading.setText(title);subtitle.setText(sub);subtitle.setVisibility(sub==null||sub.isEmpty()?View.GONE:View.VISIBLE);body.removeAllViews();footer.removeAllViews();}
    protected LinearLayout column(){LinearLayout v=new LinearLayout(requireContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    protected LinearLayout row(){LinearLayout v=new LinearLayout(requireContext());v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    protected LinearLayout.LayoutParams space(int w,int h,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);lp.topMargin=dp(top);return lp;}
    protected TextView text(String value,float size,int color){TextView t=new TextView(requireContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");t.setIncludeFontPadding(false);return t;}
    protected GradientDrawable shape(int color,int stroke,int radius){return SettingsStyle.shape(requireContext(),color,stroke,radius);}
    protected LinearLayout card(){LinearLayout c=column();c.setBackground(shape(CARD,LINE,12));c.setPadding(dp(2),dp(2),dp(2),dp(2));body.addView(c,space(-1,-2,10));return c;}
    /** P384: as the preference pages' group titles (preference_category_layout): 11.5sp medium, upper case, the accent. */
    protected void caption(String title){
        TextView t=text(title,11.5f,accent);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setAllCaps(true);t.setLetterSpacing(.09f);
        t.setPadding(dp(6),0,dp(6),0);body.addView(t,space(-1,-2,22));
    }
    /** P384: the info glyph of the preference notes (it was the «ⓘ» character). */
    protected void note(String value){
        LinearLayout n=row();n.setGravity(Gravity.TOP);ImageView icon=new ImageView(requireContext());icon.setImageResource(R.drawable.settings_ic_info);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(18),dp(18));ip.rightMargin=dp(10);ip.topMargin=dp(1);n.addView(icon,ip);
        TextView label=text(value,12.5f,MUTED);label.setLineSpacing(dp(3),1);n.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        n.setPadding(dp(6),0,dp(6),0);body.addView(n,space(-1,-2,16));
    }
    protected TextView button(String title,boolean filled,Runnable action){TextView t=text(title,14,filled?0xFF15131D:accent);t.setGravity(Gravity.CENTER);t.setPadding(dp(8),dp(12),dp(8),dp(12));t.setMinHeight(dp(44));t.setBackground(shape(filled?accent:BG,filled?0:0xFF626974,10));t.setOnClickListener(v->action.run());t.setFocusable(true);t.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");}});return t;}
    protected void actions(Runnable all,Runnable none){LinearLayout r=row();TextView a=button(Lang.t(getContext(),"Выбрать всё","Select all"),false,all),b=button(Lang.t(getContext(),"Отменить выбор","Clear selection"),false,none);a.setTag("select_all");b.setTag("clear_selection");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(10);r.addView(a,lp);r.addView(b,new LinearLayout.LayoutParams(0,-2,1));body.addView(r,space(-1,-2,12));}
    protected void primary(String title,boolean enabled,Runnable action){TextView t=button(title,true,action);t.setTypeface(null,1);t.setMinHeight(dp(52));t.setEnabled(enabled);t.setAlpha(enabled?1:.4f);t.setTag("primary_action");footer.addView(t,new FrameLayout.LayoutParams(-1,-2));}
    protected void divider(LinearLayout parent){View v=new View(requireContext());v.setBackgroundColor(LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.leftMargin=dp(48);parent.addView(v,lp);}
    protected ImageView icon(String name){
        ImageView v=new ImageView(requireContext());int resource;
        switch(name){ // P6b: the concept icon set shared with the preference pages
            case "▣":resource=R.drawable.settings_ic_camera;break;
            case "☷":resource=R.drawable.settings_ic_list;break;
            case "◇":resource=R.drawable.settings_ic_tag;break;
            case "▢":case "⧉":resource=R.drawable.settings_ic_copy;break;
            case "back":resource=R.drawable.settings_ic_back;break;
            case "◉":resource=R.drawable.settings_ic_palette;break;
            case "⌕":resource=R.drawable.settings_ic_zoom;break;
            case "⚙︎":resource=R.drawable.settings_ic_chip;break;
            case "●":resource=R.drawable.settings_ic_eye;break;
            case "○":resource=R.drawable.settings_ic_view;break;
            case "✕":resource=R.drawable.settings_ic_reset;break;
            case "≡":resource=R.drawable.settings_ic_sliders;break; // P189: the module group
            case "▦":resource=R.drawable.settings_ic_mosaic;break; // P189: a FULLRES profile
            default:resource=R.drawable.settings_ic_gear;
        }
        v.setImageResource(resource);v.clearColorFilter(); // P136: multi-tone glyphs, drawn as they are
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    /** P384: a glyph on the preference rows' 36dp tile (preference_card's settings_icon_tile), 22dp. */
    protected FrameLayout tile(String name){
        FrameLayout tile=new FrameLayout(requireContext());tile.setBackgroundResource(R.drawable.settings_icon_tile);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(22),dp(22));lp.gravity=Gravity.CENTER;tile.addView(icon(name),lp);return tile;
    }
    /** P384: the chevron of the preference pages (settings_widget_chevron), not a «›» character. */
    protected ImageView chevron(){
        ImageView v=new ImageView(requireContext());v.setImageResource(R.drawable.settings_ic_chevron);v.setColorFilter(MUTED,PorterDuff.Mode.SRC_IN);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    protected void navigation(String icon,String title,String summary,Runnable action){
        LinearLayout c=card(),r=row();r.setPadding(dp(12),dp(12),dp(14),dp(12));r.setMinimumHeight(dp(58));
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(dp(36),dp(36));tp.rightMargin=dp(12);r.addView(tile(icon),tp);
        LinearLayout labels=column();TextView head=text(title,14.5f,TEXT);com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);labels.addView(head);
        if(summary!=null){TextView sub=text(summary,12.5f,SettingsStyle.SUMMARY);sub.setLineSpacing(dp(1),1);labels.addView(sub,space(-1,-2,3));}
        r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(16),dp(20));cp.leftMargin=dp(8);r.addView(chevron(),cp);
        r.setOnClickListener(v->action.run());r.setTag(title);c.addView(r);
    }
    protected View mark(int state,String label,Runnable action){View v=new View(requireContext()){
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override protected void onDraw(Canvas canvas){float cx=getWidth()/2f,cy=getHeight()/2f,h=dp(10);RectF rect=new RectF(cx-h,cy-h,cx+h,cy+h);paint.setStyle(state==0?Paint.Style.STROKE:Paint.Style.FILL);paint.setStrokeWidth(dp(1.5f));paint.setColor(state==0?MUTED:accent);canvas.drawRoundRect(rect,dp(4),dp(4),paint);
            if(state!=0){paint.setStyle(Paint.Style.STROKE);paint.setColor(0xFF17141F);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);if(state==1)canvas.drawLine(cx-dp(4),cy,cx+dp(4),cy,paint);else{canvas.drawLine(cx-dp(5),cy,cx-dp(1),cy+dp(4),paint);canvas.drawLine(cx-dp(1),cy+dp(4),cx+dp(6),cy-dp(5),paint);}}}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.CheckBox");info.setCheckable(true);info.setChecked(state>0);}
    };v.setContentDescription(label+(state==1?Lang.t(getContext(),", выбрано частично",", partly selected"):""));v.setFocusable(true);v.setOnClickListener(w->action.run());return v;}
    protected void open(Fragment fragment){getParentFragmentManager().beginTransaction().replace(R.id.settings_container,fragment).addToBackStack(null).commit();}
}
