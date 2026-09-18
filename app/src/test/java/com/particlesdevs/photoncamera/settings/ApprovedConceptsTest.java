package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import androidx.preference.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.camera.ManualStops;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.particlesdevs.photoncamera.ui.settings.SettingsSearchFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w400dp-h880dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ApprovedConceptsTest {
    @Test public void rangesUseActualSensorIsoAndLongExposureEndpoints() {
        List<Long> iso=ManualStops.iso(72,12800);
        assertEquals(Long.valueOf(72),iso.get(0));assertEquals(Long.valueOf(12800),iso.get(iso.size()-1));
        assertTrue(iso.containsAll(Arrays.asList(100L,125L,160L,200L,800L,6400L,12800L)));
        List<Long> shutter=ManualStops.shutter(43125,33024215041L);
        assertTrue(shutter.contains(32000000000L));assertTrue(shutter.contains(8000000L));
        assertEquals(Long.valueOf(33024215041L),shutter.get(shutter.size()-1));
        assertEquals("32 s",ManualStops.shutterLabel(32000000000L));
        assertEquals("1/125",ManualStops.shutterLabel(8000000));
        for(List<Long> values:Arrays.asList(iso,shutter,ManualStops.shutter(100000,200000),ManualStops.iso(100,100)))
            for(int i=1;i<values.size();i++)assertTrue(values.get(i)>values.get(i-1));
        assertEquals(Arrays.asList(100L),ManualStops.iso(100,100));
        assertTrue(ManualStops.iso(100,800).stream().noneMatch(v->v>800));
    }
    @Test public void photographicRulerDragsUnderFixedMarkerAndHonorsCancel() throws Exception {
        Context c=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Photon_SettingsActivity);
        LinearScaleView v=new LinearScaleView(c);v.setPhotographicMode(true);
        List<KnobItemInfo> items=new ArrayList<>();items.add(new KnobItemInfo(null,"A",0,0));
        for(long iso:ManualStops.iso(72,12800)) {
            KnobItemInfo item=new KnobItemInfo(null,""+iso,items.size(),iso);item.majorTick=ManualStops.majorIso(iso);items.add(item);
        }
        int selected=0;for(int i=0;i<items.size();i++)if(items.get(i).value==800)selected=i;
        v.setItems(items,selected);v.layout(0,0,400,88);
        final int[] auto={0};v.setListener(new LinearScaleView.OnValueChangedListener(){
            public void onValueChanged(KnobItemInfo item,boolean user){}
            public void onDragStateChanged(boolean dragging){}
            public void onAutoRequested(){auto[0]++;}
        });
        v.onTouchEvent(MotionEvent.obtain(0,0,0,200,60,0));
        v.onTouchEvent(MotionEvent.obtain(0,1,2,176,60,0));
        v.onTouchEvent(MotionEvent.obtain(0,2,1,176,60,0));
        assertEquals(1000,v.getSelected().value,0);assertFalse(v.isDragging());
        v.onTouchEvent(MotionEvent.obtain(0,3,0,20,60,0));v.onTouchEvent(MotionEvent.obtain(0,4,3,20,60,0));assertEquals(0,auto[0]);
        v.onTouchEvent(MotionEvent.obtain(0,5,0,20,60,0));v.onTouchEvent(MotionEvent.obtain(0,6,1,20,60,0));assertEquals(1,auto[0]);
        Bitmap b=Bitmap.createBitmap(400,88,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));
        java.io.File dir=new java.io.File("build/reports/viewfinder");dir.mkdirs();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"approved-iso-ruler.png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    @Test public void searchTraversesNestedPagesAndReturnsRealDestination() {
        Context c=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Photon_SettingsActivity);
        PreferenceManager manager=new PreferenceManager(c);PreferenceScreen root=manager.createPreferenceScreen(c);root.setKey("prefscreen");
        PreferenceScreen page=manager.createPreferenceScreen(c);page.setKey("noise");page.setTitle("Шумоподавление");root.addPreference(page);
        Preference p=new Preference(c);p.setKey("luma");p.setTitle("Яркостное шумоподавление");p.setSummary("Сила обработки");page.addPreference(p);
        Preference hidden=new Preference(c);hidden.setKey("hidden");hidden.setTitle("Скрытый");hidden.setVisible(false);page.addPreference(hidden);
        ArrayList<SettingsSearchFragment.Entry> entries=SettingsSearchFragment.index(root);assertEquals(2,entries.size());
        SettingsSearchFragment.Entry hit=entries.get(1);assertEquals("noise",hit.page);assertEquals("luma",hit.key);
        assertTrue(hit.matches("ШУМ ярк"));assertTrue(hit.matches("обработки"));assertFalse(hit.matches("шум неизвестно"));
    }
}
