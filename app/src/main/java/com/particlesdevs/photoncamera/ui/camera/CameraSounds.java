package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

/**
 * Shutter and self-timer sounds (P16).
 *
 * <ul>
 * <li>Shutter: {@code res/raw/sound_shutter.mp3} (0.18 s), one per shot at the press, through a {@link SoundPool}
 *     (low latency). It replaces the per-frame ring and the end sound of the burst.</li>
 * <li>Timer: {@code res/raw/sound_timer.mp3}, one 10.9 s countdown (silence after). It starts with the countdown and is cut at
 *     its end or when it is cancelled; the shutter sound follows at the shot.</li>
 * <li>Switches «Звук затвора» ({@code pref_camera_sounds_key}) and «Звук таймера» ({@code pref_timer_sound_key}), each on its
 *     own. Both play only in the normal ringer mode, except where the region requires the shutter sound
 *     ({@code mustPlayShutterSound}): there the shutter always sounds.</li>
 * </ul>
 */
public final class CameraSounds {
    private static final String TAG = "CameraSounds";
    private final Context context;
    private SoundPool pool;
    private int shutterId;
    private volatile boolean shutterLoaded;
    private MediaPlayer timer;
    private Boolean forced;

    public CameraSounds(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Shutter: the region's rule wins; otherwise the switch, in the normal ringer mode only. */
    static boolean shutterAudible(boolean switchOn, boolean regionForces, boolean normalRinger) {
        return regionForces || (switchOn && normalRinger);
    }

    /** Timer: the switch, in the normal ringer mode only. */
    static boolean timerAudible(boolean switchOn, boolean normalRinger) {
        return switchOn && normalRinger;
    }

    public synchronized void load() {
        if (pool == null) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
            pool.setOnLoadCompleteListener((p, id, status) -> { if (id == shutterId && status == 0) shutterLoaded = true; });
            shutterId = pool.load(context, R.raw.sound_shutter, 1);
        }
        if (timer == null) {
            try {
                timer = MediaPlayer.create(context, R.raw.sound_timer);
            } catch (RuntimeException e) {
                Log.w(TAG, "timer sound: " + e.getMessage());
            }
        }
    }

    public synchronized void release() {
        if (pool != null) { pool.release(); pool = null; shutterLoaded = false; }
        if (timer != null) { timer.release(); timer = null; }
    }

    private boolean normalRinger() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return am == null || am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    @SuppressWarnings("deprecation")
    private boolean regionForces() {
        if (forced != null) return forced;
        boolean f = false;
        try {
            if (Build.VERSION.SDK_INT >= 33) f = android.media.MediaActionSound.mustPlayShutterSound();
            else {
                android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
                android.hardware.Camera.getCameraInfo(0, info);
                f = !info.canDisableShutterSound;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "shutter sound rule: " + e.getMessage());
        }
        forced = f;
        return f;
    }

    /** One shutter sound for the shot that starts now. */
    public void shutter() {
        if (!shutterAudible(PreferenceKeys.isCameraSoundsOn(), regionForces(), normalRinger())) return;
        SoundPool p;
        synchronized (this) { p = pool; }
        if (p != null && shutterLoaded) p.play(shutterId, 1f, 1f, 1, 0, 1f);
    }

    /** The countdown sound, from its start. */
    public synchronized void timerStart() {
        if (timer == null || !timerAudible(PreferenceKeys.isTimerSoundOn(), normalRinger())) return;
        try {
            timer.seekTo(0);
            timer.start();
        } catch (IllegalStateException e) {
            Log.w(TAG, "timer sound: " + e.getMessage());
        }
    }

    /** Cut the countdown sound (timer finished or cancelled). */
    public synchronized void timerStop() {
        if (timer == null) return;
        try {
            if (timer.isPlaying()) timer.pause();
            timer.seekTo(0);
        } catch (IllegalStateException e) {
            Log.w(TAG, "timer sound: " + e.getMessage());
        }
    }
}
