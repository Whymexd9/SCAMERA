package com.particlesdevs.photoncamera.control;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

public class Vibration {
    private final Vibrator vibrator;
    private final VibrationEffect tick;
    private final VibrationEffect click;
    private VibrationEffect getTick(){
        VibrationEffect output;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            output = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        } else output = VibrationEffect.createOneShot(9,255);
        return output;
    }
    private VibrationEffect getClick(){
        VibrationEffect output;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            output = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        } else output = VibrationEffect.createOneShot(13,255);
        return output;
    }
    private final VibrationEffect soft;
    private final Context context;

    /** P245: a soft, light pulse's length (ms) and amplitude (1..255) where the vibrator controls its amplitude. */
    // P298 (owner, 2026-10-20: «у уровня горизонта всё ещё не работает вибро отклик»): 18 ms at 70 was not felt on the X200 Ultra
    static final int SOFT_MS = 30, SOFT_AMPLITUDE = 120;

    /**
     * P245 (owner, 2026-10-18: «Для уровня горизонта когда он стоит ровно добавь мягкую легкую вибрацию»): the pulse - short and
     * at a low amplitude where the vibrator has amplitude control, the system's light tick otherwise (a View's CLOCK_TICK, used
     * before, is silent on the OPPO / vivo phones).
     */
    static VibrationEffect softEffect(boolean amplitudeControl) {
        if (amplitudeControl) return VibrationEffect.createOneShot(SOFT_MS, SOFT_AMPLITUDE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        return VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE);
    }

    public Vibration(Context context){
        this.context = context.getApplicationContext();
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        tick = getTick();
        click = getClick();
        soft = softEffect(vibrator != null && vibrator.hasAmplitudeControl());
    }

    /**
     * The soft pulse (P245). P298: its switch is the setting that asks for it (the horizon tile), not the system's touch
     * feedback - vivo ships «touch vibration» off, and a USAGE_TOUCH pulse follows that intensity too; so no touch usage.
     */
    public void Soft(){
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            vibrator.vibrate(soft);
        } catch (RuntimeException e) {
            com.particlesdevs.photoncamera.util.Log.w("Vibration", "soft pulse: " + e);
        }
    }
    public void Tick(){
        vibrator.vibrate(tick);
    }
    public void Click(){
        vibrator.vibrate(click);
    }
}
