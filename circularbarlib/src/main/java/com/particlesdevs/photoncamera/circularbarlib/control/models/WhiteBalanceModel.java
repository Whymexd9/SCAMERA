package com.particlesdevs.photoncamera.circularbarlib.control.models;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;
import com.particlesdevs.photoncamera.circularbarlib.R;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.*;

public final class WhiteBalanceModel extends ManualModel<Integer> {
    public WhiteBalanceModel(Context context,CameraCharacteristics camera,ManualParamModel params,ValueChangedEvent callback,Vibrator vibrator){
        super(context,camera,new Range<>(2000,10000),params,callback,vibrator);
    }
    @Override protected void fillKnobInfoList(){
        currentInfo=getNewAutoItem(0,null);getKnobInfoList().add(currentInfo);
        if(ManualWhiteBalance.isSupported(cameraCharacteristics)) for(int kelvin=2000;kelvin<=10000;kelvin+=100){
            ShadowTextDrawable label=new ShadowTextDrawable();label.setText(kelvin+"K");label.setTextAppearance(context,R.style.ManualModeKnobText);
            getKnobInfoList().add(new KnobItemInfo(label,kelvin+"K",getKnobInfoList().size(),kelvin));
        }
        knobInfo=new KnobInfo(0,160,0,getKnobInfoList().size()-1,10);
    }
    @Override public void onSelectedKnobItemChanged(KnobItemInfo item){currentInfo=item;manualParamModel.setWhiteBalanceKelvin((int)item.value);}
    @Override public void onRotationStateChanged(KnobView view,KnobView.RotationState state){}
}
