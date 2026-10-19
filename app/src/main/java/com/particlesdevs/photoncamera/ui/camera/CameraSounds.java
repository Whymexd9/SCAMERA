package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

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
 * <li>P247 (owner, 2026-10-18: «Проверить отключение и включение звука затвора и таймера»): the X200 Ultra's CTS package cannot
 *     decode MP3 (it crashes natively), so it had no shutter sound at all and a synthesized beep for the timer, whatever the
 *     switches said. There the same two sounds play from 16-bit PCM ({@code sound_shutter_pcm.raw} / {@code sound_timer_pcm.raw},
 *     mono 24 kHz, decoded from the MP3s) through a static {@link AudioTrack} - no extractor, no decoder - under the same
 *     switches.</li>
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
    private static volatile boolean sShutterLogged;
    /** The PCM sounds where MP3 cannot be decoded ({@link #synthesized}). */
    private AudioTrack pcmShutter, pcmTimer;
    static final int PCM_RATE = 24000;

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

    static boolean synthesizedFor(boolean pd2454, String packageName) {
        return pd2454 && "android.camera.cts".equals(packageName);
    }

    private boolean synthesized() {
        return synthesizedFor(com.particlesdevs.photoncamera.capture.VivoQcomModes.device(), context.getPackageName());
    }

    /** 16-bit little-endian mono PCM as samples. */
    static short[] samples(byte[] pcm) {
        short[] out = new short[pcm.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        return out;
    }

    /** A static AudioTrack holding raw resource {@code id} (16-bit mono PCM at PCM_RATE), or null. */
    private AudioTrack pcmTrack(int id) {
        try (InputStream in = context.getResources().openRawResource(id)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            short[] pcm = samples(bytes.toByteArray());
            if (pcm.length == 0) return null;
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(PCM_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            track.write(pcm, 0, pcm.length);
            return track;
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "PCM sound " + id + ": " + e);
            return null;
        }
    }

    /** A static track from its start (stopped first when it still plays). */
    private static void restart(AudioTrack t) {
        try {
            if (t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) t.stop();
            t.reloadStaticData();
            t.play();
        } catch (IllegalStateException e) {
            Log.w(TAG, "PCM sound: " + e.getMessage());
        }
    }

    public synchronized void load() {
        // PD2454's CTS media path crashes natively in MP3 extraction, including an explicit AOSP decoder: PCM there (P247)
        if (synthesized()) {
            if (pcmShutter == null) pcmShutter = pcmTrack(R.raw.sound_shutter_pcm);
            if (pcmTimer == null) pcmTimer = pcmTrack(R.raw.sound_timer_pcm);
            return;
        }
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
        if (pcmShutter != null) { pcmShutter.release(); pcmShutter = null; }
        if (pcmTimer != null) { pcmTimer.release(); pcmTimer = null; }
        if (pool != null) { pool.release(); pool = null; shutterLoaded = false; }
        if (timer != null) { timer.release(); timer = null; }
    }

    private boolean normalRinger() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return am == null || am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    /**
     * P278 (owner, 2026-10-19, Xiaomi 12X: «звук съемки не выключается»; its log: «switch off, the phone requires it true»): a ROM
     * can say the shutter sound is required everywhere (ro.camera.sound.forced of a global build). The rule stands only where a law
     * asks for it - Japan and South Korea, by the SIM, the network or the locale; elsewhere «Звук затвора» decides.
     */
    static boolean lawRequires(String... countries) {
        for (String c : countries) {
            if (c == null || c.trim().isEmpty()) continue;
            final String code = c.trim().toUpperCase(java.util.Locale.ROOT);
            return code.equals("JP") || code.equals("KR");
        }
        return false;
    }

    private String[] countries() {
        String sim = null, network = null;
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) { sim = tm.getSimCountryIso(); network = tm.getNetworkCountryIso(); }
        } catch (RuntimeException e) {
            Log.d(TAG, "country: " + e.getMessage());
        }
        return new String[]{sim, network, java.util.Locale.getDefault().getCountry()};
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
        if (f) {
            final String[] c = countries();
            final boolean law = lawRequires(c);
            Log.i(TAG, "shutter sound: the phone requires it; country sim=" + c[0] + " network=" + c[1] + " locale=" + c[2]
                    + (law ? " - required by law there, kept" : " - not required there, the switch decides (P278)"));
            f = law;
        }
        forced = f;
        return f;
    }

    /** One shutter sound for the shot that starts now. */
    public void shutter() {
        final boolean on = PreferenceKeys.isCameraSoundsOn(), region = regionForces(), ringer = normalRinger();
        // owner, 2026-10-19 (Xiaomi 12X: «не выключается звук съёмки»): why it sounds, once per process (the switch, the
        // phone's own must-play rule, the ringer)
        if (!sShutterLogged) {
            sShutterLogged = true;
            Log.i(TAG, "shutter sound: switch " + (on ? "on" : "off") + ", the phone requires it " + region + ", normal ringer " + ringer
                    + " -> " + (shutterAudible(on, region, ringer) ? "played" : "silent"));
        }
        if (!shutterAudible(on, region, ringer)) return;
        SoundPool p;
        synchronized (this) {
            // P188 (owner, 2026-10-17: «убери пик после нажатия на кнопку спуска затвора»): the X200 Ultra's CTS package had a
            // synthesized beep here (its MP3 path crashes natively); P247: the real shutter sound there, from PCM (no beep, and
            // nothing blocking: a static track only starts)
            if (synthesized()) {
                if (pcmShutter != null) restart(pcmShutter);
                return;
            }
            p = pool;
        }
        if (p != null && shutterLoaded) p.play(shutterId, 1f, 1f, 1, 0, 1f);
    }

    /** The countdown sound, from its start. */
    public synchronized void timerStart() {
        if (synthesized()) {
            if (pcmTimer != null && timerAudible(PreferenceKeys.isTimerSoundOn(), normalRinger())) restart(pcmTimer);
            return;
        }
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
        if (pcmTimer != null) {
            try {
                if (pcmTimer.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) pcmTimer.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "PCM timer: " + e.getMessage());
            }
        }
        if (timer == null) return;
        try {
            if (timer.isPlaying()) timer.pause();
            timer.seekTo(0);
        } catch (IllegalStateException e) {
            Log.w(TAG, "timer sound: " + e.getMessage());
        }
    }
}
