package com.particlesdevs.photoncamera.processing;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.util.function.BooleanSupplier;

/**
 * P92 (owner, 2026-10-11: «чтобы когда приложение сворачивалось, обработка фото не прерывалась»). Behind the screen an app
 * without a foreground service is cached within seconds and frozen (Android 12+ cached-app freezer: the merge worker, a
 * child process, with it) or killed when memory is short, so a shot pressed before leaving the app stopped half-way. This
 * foreground service (type specialUse, a low-priority "processing" notification, a partial wake lock for the screen-off
 * case) is started at the shutter, while the app is in front (a start from the background is refused on Android 12+), and
 * stops itself once nothing is captured, queued or merged for 3 s ({@link #busy}).
 */
public final class ProcessingKeepAlive extends Service {
    private static final String TAG = "ProcessingKeepAlive";
    private static final String CHANNEL = "scam_processing";
    private static final int NOTIFICATION_ID = 0x5CA4;
    private static final long POLL_MS = 1000, IDLE_MS = 3000;

    /** Whether a shot is being captured, waits in the queue or is merged / saved (set by CaptureController). */
    public static volatile BooleanSupplier busy = () -> false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private long idleSince;

    /** At the shutter (the app is in front): keeps the process running until the shot is saved. */
    public static void hold(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            Intent i = new Intent(app, ProcessingKeepAlive.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i);
            else app.startService(i);
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException and the like: the shot is processed as before
            Log.w(TAG, "keep-alive not started: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground refused: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SCAMERA:processing");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(10 * 60 * 1000L); // at most 10 minutes per start
            }
        }
        idleSince = 0;
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_MS);
        return START_NOT_STICKY;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            boolean working;
            try {
                working = busy.getAsBoolean();
            } catch (RuntimeException e) {
                working = false;
            }
            final long now = android.os.SystemClock.uptimeMillis();
            if (working) idleSince = 0;
            else if (idleSince == 0) idleSince = now;
            if (!working && now - idleSince >= IDLE_MS) {
                stopForeground(true);
                stopSelf();
                return;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Lang.t("Обработка фото", "Photo processing"),
                    NotificationManager.IMPORTANCE_LOW));
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_round_photo_camera_24)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(Lang.t("Обработка фото…", "Processing the photo…"))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true)
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
