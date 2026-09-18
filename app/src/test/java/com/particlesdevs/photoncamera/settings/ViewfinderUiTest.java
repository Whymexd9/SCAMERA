package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance;
import com.particlesdevs.photoncamera.ui.camera.views.ModeTabsView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w400dp-h880dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ViewfinderUiTest {
    private Context context;
    private MockedStatic<PhotonCamera> camera;
    @Before public void setup(){
        context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Photon_SettingsActivity);
        SettingsManager manager=new SettingsManager(context);
        camera=mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(()->PhotonCamera.getStringStatic(anyInt())).thenAnswer(i->context.getString(i.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        PreferenceKeys.initialise(manager);
    }
    @After public void teardown(){camera.close();}
    @Test public void compactControlsRenderAndResetWithoutChangingTabLabels() throws Exception {
        // Render the production XML controls. The scene is a neutral placeholder;
        // no camera or neural processing is simulated by this screenshot.
        LinearLayout screen=new LinearLayout(context);screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xFF101316);screen.setPadding(12,12,12,12);
        View top=LayoutInflater.from(context).inflate(R.layout.layout_main_topbar,screen,false);
        com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding tb=androidx.databinding.DataBindingUtil.getBinding(top);
        tb.setTimerVisible(true);tb.setFlashVisible(true);tb.executePendingBindings();
        screen.addView(top,new LinearLayout.LayoutParams(-1,56));
        FrameLayout preview=new FrameLayout(context);preview.setBackgroundColor(0xFF45525B);
        screen.addView(preview,new LinearLayout.LayoutParams(-1,500));
        View manual=LayoutInflater.from(context).inflate(R.layout.manual_palette,preview,false);
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);mp.setMargins(16,0,16,52);
        preview.addView(manual,mp);
        View container=manual.findViewById(R.id.knobViewContainer);container.setVisibility(View.VISIBLE);
        LinearScaleView scale=manual.findViewById(R.id.linearScaleView);scale.setVisibility(View.VISIBLE);
        List<KnobItemInfo> items=new ArrayList<>();
        items.add(new KnobItemInfo(null,"Auto",0,0));
        for(int k=2000;k<=10000;k+=100)items.add(new KnobItemInfo(null,k+"K",items.size(),k));
        scale.setTemperatureMode(true);scale.setItems(items,17);
        scale.setSelectedItem(items.get(17));assertEquals("3600K",scale.getSelected().text);
        ((TextView)manual.findViewById(R.id.wb_option_tv)).setSelected(true);
        String[] labels={"EV","Tv","ISO","ББ","AF"};int[] ids={R.id.ev_option_tv,R.id.exposure_option_tv,R.id.iso_option_tv,R.id.wb_option_tv,R.id.focus_option_tv};
        for(int i=0;i<ids.length;i++)assertEquals(labels[i],((TextView)manual.findViewById(ids[i])).getText().toString());
        LinearLayout lenses=new LinearLayout(context);lenses.setPadding(3,3,3,3);lenses.setBackgroundResource(R.drawable.glass_pill);
        for(String label:new String[]{"2.4×","1×","0.7×","0.4×"}){
            TextView t=new TextView(context);t.setText(label);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER);t.setTextSize(13);t.setBackgroundResource(R.drawable.manual_tab_background);t.setSelected(label.equals("1×"));lenses.addView(t,new LinearLayout.LayoutParams(0,34,1));
        }
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(264,40,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.bottomMargin=6;preview.addView(lenses,lp);
        View bottom=LayoutInflater.from(context).inflate(R.layout.layout_main_bottombar,screen,false);screen.addView(bottom,new LinearLayout.LayoutParams(-1,172));
        ModeTabsView modes=bottom.findViewById(R.id.mode_picker_view);modes.setValues(new String[]{"Фото","Ночь"});modes.setSelectedItem(0);
        bottom.findViewById(R.id.processing_progress_bar).setVisibility(View.INVISIBLE);
        int exact=View.MeasureSpec.EXACTLY;screen.measure(View.MeasureSpec.makeMeasureSpec(400,exact),View.MeasureSpec.makeMeasureSpec(760,exact));screen.layout(0,0,400,760);
        assertTrue(manual.getHeight()<=120);assertEquals(40,manual.findViewById(R.id.buttons_container).getHeight());
        assertEquals(72,bottom.findViewById(R.id.shutter_button).getWidth());
        assertEquals(R.id.galery_button_container,((View)bottom.findViewById(R.id.processing_progress_bar).getParent()).getId());
        Bitmap image=Bitmap.createBitmap(400,760,Bitmap.Config.ARGB_8888);screen.draw(new Canvas(image));
        java.io.File dir=new java.io.File("build/reports/viewfinder");dir.mkdirs();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"concept-controls.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
        scale.setListener(new LinearScaleView.OnValueChangedListener(){
            public void onValueChanged(KnobItemInfo item,boolean user){}
            public void onDragStateChanged(boolean dragging){}
            public void onAutoRequested(){scale.setSelectedItem(items.get(0));}
        });
        scale.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,20,30,0));
        scale.onTouchEvent(MotionEvent.obtain(0,1,MotionEvent.ACTION_MOVE,150,30,0));
        scale.onTouchEvent(MotionEvent.obtain(0,2,MotionEvent.ACTION_UP,150,30,0));
        assertEquals(0,scale.getSelected().value,0);assertFalse(scale.isDragging());
        int[] calls={0};modes.setOnItemSelectedListener(i->calls[0]++);modes.getChildAt(1).performClick();
        assertEquals(1,modes.getSelectedItem());assertEquals(1,calls[0]);modes.setEnabled(false);modes.getChildAt(0).performClick();assertEquals(1,calls[0]);
    }
    @Test public void whiteBalanceUsesFiniteSensorGainsAndSupportsAuto(){
        android.hardware.camera2.CameraCharacteristics c=mock(android.hardware.camera2.CameraCharacteristics.class);
        android.hardware.camera2.params.ColorSpaceTransform identity=new android.hardware.camera2.params.ColorSpaceTransform(new int[]{1,1,0,1,0,1,0,1,1,1,0,1,0,1,0,1,1,1});
        when(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(identity);
        when(c.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{0,1});
        when(c.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{2});
        assertTrue(ManualWhiteBalance.isSupported(c));
        for(int k=2000;k<=10000;k+=100){
            double[] xyz=ManualWhiteBalance.xyz(k);for(double v:xyz)assertTrue(Double.isFinite(v)&&v>0);
            android.hardware.camera2.params.RggbChannelVector g=ManualWhiteBalance.gains(c,k);
            assertTrue(g.getRed()>=1 && g.getRed()<=8);assertTrue(g.getBlue()>=1 && g.getBlue()<=8);
        }
        com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel m=new com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel();
        m.reset();assertFalse(m.isManualMode());m.setWhiteBalanceKelvin(3600);assertTrue(m.isManualMode());m.reset();assertEquals(0,m.getWhiteBalanceKelvin());
    }
}
