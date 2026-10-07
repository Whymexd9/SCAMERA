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
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void compactControlsRenderAndResetWithoutChangingTabLabels() throws Exception {
        compactControlsRenderAndResetWithoutChangingTabLabels(new String[]{"Экспокоррекция","Выдержка","ISO","Баланс белого","Фокус"},"");
    }
    /** The same on an English system: the tab labels (content descriptions) are English. */
    @Test public void compactControlsRenderAndResetWithoutChangingTabLabelsInEnglish() throws Exception {
        compactControlsRenderAndResetWithoutChangingTabLabels(new String[]{"Exposure compensation","Shutter","ISO","White balance","Focus"},"-en");
    }
    private void compactControlsRenderAndResetWithoutChangingTabLabels(String[] labels,String png) throws Exception {
        // Render the production XML controls. The scene is a neutral placeholder;
        // no camera or neural processing is simulated by this screenshot.
        LinearLayout screen=new LinearLayout(context);screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xFF101316);screen.setPadding(12,12,12,12);
        View top=LayoutInflater.from(context).inflate(R.layout.layout_main_topbar,screen,false);
        com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding tb=com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding.bind(top);
        tb.executePendingBindings();
        // P25 top bar: the route and format badges, filled as the camera screen fills them.
        com.particlesdevs.photoncamera.ui.camera.CameraUIViewImpl.bindBadges(tb);
        screen.addView(top,new LinearLayout.LayoutParams(-1,64));
        // The 3:4 preview of a 400dp-wide phone; the bottom bar gets what an 880dp-tall 20:9 screen leaves it.
        FrameLayout preview=new FrameLayout(context);preview.setBackgroundColor(0xFF45525B);
        screen.addView(preview,new LinearLayout.LayoutParams(-1,533));
        View manual=LayoutInflater.from(context).inflate(R.layout.manual_palette,preview,false);
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);mp.setMargins(16,0,16,52);
        preview.addView(manual,mp);
        var panel=(com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel)manual;
        assertFalse(panel.isExpanded());assertEquals(View.INVISIBLE,manual.findViewById(R.id.buttons_container).getVisibility());
        panel.setExpanded(true,false);
        View container=manual.findViewById(R.id.knobViewContainer);container.setVisibility(View.VISIBLE);
        LinearScaleView scale=manual.findViewById(R.id.linearScaleView);scale.setVisibility(View.VISIBLE);
        List<KnobItemInfo> items=new ArrayList<>();
        items.add(new KnobItemInfo(null,"Auto",0,0));
        for(int k=2000;k<=10000;k+=100)items.add(new KnobItemInfo(null,k+"K",items.size(),k));
        scale.setTemperatureMode(true);scale.setItems(items,17);
        scale.setSelectedItem(items.get(17));assertEquals("3600K",scale.getSelected().text);
        ((TextView)manual.findViewById(R.id.wb_option_tv)).setSelected(true);
        int[] ids={R.id.ev_option_tv,R.id.exposure_option_tv,R.id.iso_option_tv,R.id.wb_option_tv,R.id.focus_option_tv};
        for(int i=0;i<ids.length;i++)assertEquals(labels[i],manual.findViewById(ids[i]).getContentDescription().toString());
        // P25: the lens strip lives in the bottom bar now (above the shutter row, under the zoom ruler's place).
        View bottom=LayoutInflater.from(context).inflate(R.layout.layout_main_bottombar,screen,false);screen.addView(bottom,new LinearLayout.LayoutParams(-1,259));
        var lenses=(com.particlesdevs.photoncamera.ui.camera.views.AuxButtonsLayout)bottom.findViewById(R.id.aux_buttons_container);
        var lensModel=new com.particlesdevs.photoncamera.ui.camera.model.AuxButtonsModel();
        java.util.List<com.particlesdevs.photoncamera.ui.camera.data.CameraLensData> cameraData=new ArrayList<>();
        float[] zoom={2.4f,1f,.4f};for(int i=0;i<zoom.length;i++){var lens=new com.particlesdevs.photoncamera.ui.camera.data.CameraLensData(""+i);lens.setZoomFactor(zoom[i]);cameraData.add(lens);}
        lensModel.setBackCameras(cameraData);lensModel.setFrontCameras(new ArrayList<>());lenses.setAuxButtonsModel(lensModel);lenses.setActiveId("1");
        bottom.findViewById(R.id.processing_progress_bar).setVisibility(View.INVISIBLE);
        int exact=View.MeasureSpec.EXACTLY;screen.measure(View.MeasureSpec.makeMeasureSpec(400,exact),View.MeasureSpec.makeMeasureSpec(880,exact));screen.layout(0,0,400,880);
        assertTrue(manual.getHeight()<=152);assertEquals(48,manual.findViewById(R.id.buttons_container).getHeight());
        // Top bar: the settings gear is a 44dp square card at the end, the badges stay clear of it.
        View gear=top.findViewById(R.id.settings_button),badges=top.findViewById(R.id.topbar_badges);
        assertEquals(44,gear.getWidth());assertEquals(gear.getWidth(),gear.getHeight());
        assertTrue("badges must not run under the gear",badges.getRight()<=gear.getLeft());
        TextView route=top.findViewById(R.id.route_badge),format=top.findViewById(R.id.format_badge);
        assertTrue(route.getText().toString().endsWith("Hybrid"));assertEquals("JPEG",format.getText().toString());
        assertNotNull(route.getCompoundDrawables()[0]);assertNotNull(format.getCompoundDrawables()[0]);
        assertTrue("badge icon has its space",route.getCompoundPaddingLeft()>route.getPaddingLeft());
        for(TextView badge:new TextView[]{route,format})assertTrue("badge text must fit",badge.getLayout().getEllipsisCount(0)==0&&badge.getLayout().getLineCount()==1);
        assertEquals(72,bottom.findViewById(R.id.shutter_button).getWidth());
        assertEquals(R.id.galery_button_container,((View)bottom.findViewById(R.id.processing_progress_bar).getParent()).getId());
        // Shutter row: gallery card | shutter exactly in the centre | front / back switch, none overlapping.
        View shutterBox=bottom.findViewById(R.id.shutter_button_container),gallery=bottom.findViewById(R.id.galery_button_container),
                flipBox=bottom.findViewById(R.id.camera_switch_container),row=(View)shutterBox.getParent();
        assertEquals(row.getWidth()/2f,(shutterBox.getLeft()+shutterBox.getRight())/2f,1f);
        assertTrue(gallery.getRight()<=shutterBox.getLeft());assertTrue(flipBox.getLeft()>=shutterBox.getRight());
        assertEquals(52,gallery.getWidth());assertEquals(52,flipBox.getWidth());
        // The strip: a centred pill above the shutter row, the ruler's place above it, all inside the bottom bar.
        View slot=bottom.findViewById(R.id.zoom_ruler_slot),rowRoot=bottom.findViewById(R.id.bottom_buttons);
        assertEquals(bottom.getWidth()/2f,(lenses.getLeft()+lenses.getRight())/2f,1f);
        assertTrue(lenses.getBottom()<=rowRoot.getTop());assertTrue(slot.getBottom()<=lenses.getTop());assertTrue(slot.getTop()>=0);
        for(int i=0;i<lenses.getChildCount();i++){TextView t=(TextView)lenses.getChildAt(i);assertFalse(t.getText().toString().isEmpty());if(png.isEmpty())assertFalse("decimal comma in Russian",t.getText().toString().contains("."));else assertFalse("decimal point in English",t.getText().toString().matches(".*\\d,\\d.*"));assertTrue("Lens label must stay within its button",t.getLayout().getWidth()<=t.getWidth());}
        Bitmap image=Bitmap.createBitmap(400,880,Bitmap.Config.ARGB_8888);screen.draw(new Canvas(image));
        java.io.File dir=new java.io.File("build/reports/viewfinder");dir.mkdirs();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"concept-controls"+png+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
        // P32: with the phone in landscape only the labels turn; the buttons (and the selected pill) keep their portrait size.
        int[] widths=new int[lenses.getChildCount()];for(int i=0;i<widths.length;i++)widths[i]=lenses.getChildAt(i).getWidth();
        for(int orientation:new int[]{90,180,-90,0}){
            lenses.rotateLabels(orientation,0);screen.measure(View.MeasureSpec.makeMeasureSpec(400,exact),View.MeasureSpec.makeMeasureSpec(880,exact));screen.layout(0,0,400,880);
            for(int i=0;i<lenses.getChildCount();i++){var lens=(com.particlesdevs.photoncamera.ui.camera.views.LensButton)lenses.getChildAt(i);
                assertEquals(0f,lens.getRotation(),0f);assertEquals((orientation+360)%360,(lens.getLabelRotation()+360)%360,0f);assertEquals(widths[i],lens.getWidth());assertEquals(40,lens.getHeight());}
            if(orientation==90){screen.draw(new Canvas(image));try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"concept-controls-landscape"+png+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}}
        }
        scale.setListener(new LinearScaleView.OnValueChangedListener(){
            public void onValueChanged(KnobItemInfo item,boolean user){}
            public void onDragStateChanged(boolean dragging){}
            public void onAutoRequested(){scale.setSelectedItem(items.get(0));}
        });
        scale.onTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,20,30,0));
        scale.onTouchEvent(MotionEvent.obtain(0,1,MotionEvent.ACTION_MOVE,150,30,0));
        scale.onTouchEvent(MotionEvent.obtain(0,2,MotionEvent.ACTION_UP,150,30,0));
        assertEquals(3600,scale.getSelected().value,0);assertFalse(scale.isDragging());
        scale.onTouchEvent(MotionEvent.obtain(0,3,MotionEvent.ACTION_DOWN,20,30,0));
        scale.onTouchEvent(MotionEvent.obtain(0,4,MotionEvent.ACTION_UP,20,30,0));
        assertEquals(0,scale.getSelected().value,0);
        panel.setExpanded(false,false);assertEquals(View.GONE,container.getVisibility());assertEquals(0,scale.getSelected().value,0);
        panel.setExpanded(true,false);assertEquals(View.VISIBLE,container.getVisibility());
        // The front / back switch took the place of «Фото | Ночь»: a tap reaches it once, a disabled switch ignores taps.
        try(var controller=Robolectric.buildActivity(android.app.Activity.class)){
            controller.setup();
            View bar=LayoutInflater.from(context).inflate(R.layout.layout_main_bottombar,null,false);
            controller.get().setContentView(bar);
            bar.measure(View.MeasureSpec.makeMeasureSpec(400,exact),View.MeasureSpec.makeMeasureSpec(259,exact));bar.layout(0,0,400,259);
            View flip=bar.findViewById(R.id.flip_camera_button);int[] calls={0};flip.setOnClickListener(v->calls[0]++);
            assertTrue(flip.isClickable());assertFalse(flip.getContentDescription().toString().isEmpty());
            float fx=flip.getWidth()/2f,fy=flip.getHeight()/2f;
            flip.dispatchTouchEvent(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,fx,fy,0));flip.dispatchTouchEvent(MotionEvent.obtain(0,10,MotionEvent.ACTION_UP,fx,fy,0));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,calls[0]);
            flip.setEnabled(false);
            flip.dispatchTouchEvent(MotionEvent.obtain(0,20,MotionEvent.ACTION_DOWN,fx,fy,0));flip.dispatchTouchEvent(MotionEvent.obtain(0,30,MotionEvent.ACTION_UP,fx,fy,0));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,calls[0]);
        }
    }
    @Test public void animatedManualControlsRemainVisibleAndReceiveTouchesAfterReversal(){
        try(var controller=Robolectric.buildActivity(android.app.Activity.class)){
            controller.setup();var activity=controller.get();
            var panel=(com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel)LayoutInflater.from(context).inflate(R.layout.manual_palette,null,false);
            activity.setContentView(panel);panel.measure(View.MeasureSpec.makeMeasureSpec(356,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(160,View.MeasureSpec.AT_MOST));panel.layout(0,0,356,panel.getMeasuredHeight());
            View tabs=panel.findViewById(R.id.buttons_container);int[] clicks={0};
            int[] ids={R.id.ev_option_tv,R.id.exposure_option_tv,R.id.iso_option_tv,R.id.wb_option_tv,R.id.focus_option_tv};
            for(int id:ids)panel.findViewById(id).setOnClickListener(v->clicks[0]++);
            panel.setExpanded(true,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertEquals(View.VISIBLE,tabs.getVisibility());assertEquals(1f,tabs.getAlpha(),.001f);assertNull(tabs.getClipBounds());
            for(int id:ids){View tab=panel.findViewById(id);int[] at=new int[2];tab.getLocationInWindow(at);int[] base=new int[2];panel.getLocationInWindow(base);float x=at[0]-base[0]+tab.getWidth()/2f,y=at[1]-base[1]+tab.getHeight()/2f;long now=android.os.SystemClock.uptimeMillis();
                assertTrue("Touch down must reach a manual control",panel.dispatchTouchEvent(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0)));panel.dispatchTouchEvent(MotionEvent.obtain(now,now+10,MotionEvent.ACTION_UP,x,y,0));Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
            assertEquals(5,clicks[0]);
            panel.setExpanded(false,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(80));
            panel.setExpanded(true,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertEquals(View.VISIBLE,tabs.getVisibility());assertEquals(1f,tabs.getAlpha(),.001f);
            panel.setExpanded(false,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));assertEquals(View.INVISIBLE,tabs.getVisibility());
        }
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
