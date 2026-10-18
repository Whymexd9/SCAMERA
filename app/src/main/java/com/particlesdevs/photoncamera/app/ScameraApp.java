package com.particlesdevs.photoncamera.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.os.HandlerCompat;

import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.control.Gravity;
import com.particlesdevs.photoncamera.control.Gyro;
import com.particlesdevs.photoncamera.control.Vibration;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.processing.render.PreviewParameters;
import com.particlesdevs.photoncamera.settings.MigrationManager;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.SplashActivity;
import com.particlesdevs.photoncamera.util.AssetLoader;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.util.ScameraCrashReporter;
import com.particlesdevs.photoncamera.util.SimpleStorageHelper;
import com.particlesdevs.photoncamera.util.ObjectLoader;
import com.particlesdevs.photoncamera.util.log.ActivityLifecycleMonitor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScameraApp extends Application {
    public static final boolean DEBUG = false;
    private static ScameraApp sApp;
    //    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
//    private final ExecutorService executorService = Executors.newWorkStealingPool();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler mainThreadHandler = HandlerCompat.createAsync(Looper.getMainLooper());
    private Settings mSettings;
    private Gravity mGravity;
    private Gyro mGyro;
    private Vibration mVibration;
    //private Parameters mParameters;
    private PreviewParameters mPreviewParameters;
    private CaptureController mCaptureController;
    private SettingsManager mSettingsManager;
    private AssetLoader mAssetLoader;
    private ObjectLoader objectLoader;
    private AudioManager audioManager;

    @Nullable
    public static ScameraApp getInstance(Context context) {
        if (context instanceof Activity) {
            Application application = ((Activity) context).getApplication();
            if (application instanceof ScameraApp) {
                return (ScameraApp) application;
            }
        }
        return null;
    }

    @Nullable
    public static Context getAppContext() {
        return sApp;
    }

    public static AudioManager getAudioManager() {
        return sApp.audioManager;
    }

    public static Handler getMainHandler() {
        return sApp.mainThreadHandler;
    }

    public static Settings getSettings() {
        return sApp.mSettings;
    }

    public static Gravity getGravity() {
        return sApp.mGravity;
    }

    public static Gyro getGyro() {
        return sApp.mGyro;
    }

    public static Vibration getVibration() {
        return sApp == null ? null : sApp.mVibration;
    }

    /*public static Parameters getParameters() {
        return sApp.mParameters;
    }*/

    public static PreviewParameters getPreviewParameters() {
        return sApp.mPreviewParameters;
    }

    public static CaptureController getCaptureController() {
        return sApp.mCaptureController;
    }

    public static void setCaptureController(CaptureController captureController) {
        sApp.mCaptureController = captureController;
    }

    public static AssetLoader getAssetLoader() {
        return sApp.mAssetLoader;
    }

    /** P156: a restart is on its way (an imported config or a reset): nothing may move the module profiles meanwhile. */
    public static volatile boolean restarting;

    public static void restartWithDelay(Context context, long delayMs) {
        restarting = true;
        getMainHandler().postDelayed(() -> restartApp(context), delayMs);
    }

    public static void restartApp(Context context) {
        Intent intent = new Intent(context, SplashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        context.startActivity(intent);
        System.exit(0);
    }

    public static void showToast(String msg) {
        getMainHandler().post(() -> Toast.makeText(sApp, msg, Toast.LENGTH_LONG).show());
    }

    public static void showToast(@StringRes int stringRes) {
        getMainHandler().post(() -> Toast.makeText(sApp, stringRes, Toast.LENGTH_LONG).show());
    }

    public static void showToastFast(@StringRes int stringRes) {
        getMainHandler().post(() -> Toast.makeText(sApp, stringRes, Toast.LENGTH_SHORT).show());
    }

    public static Resources getResourcesStatic() {
        return sApp.getResources();
    }

    public static String getStringStatic(@StringRes int stringRes) {
        return sApp.getResources().getString(stringRes);
    }

    public static Drawable getDrawableStatic(int resID) {
        return ContextCompat.getDrawable(sApp, resID);
    }

    public static PackageInfo getPackageInfo() throws PackageManager.NameNotFoundException {
        return sApp.getPackageManager().getPackageInfo(sApp.getPackageName(), 0);
    }

    public static String getVersion() {
        String version = "";
        try {
            PackageInfo pInfo = ScameraApp.getPackageInfo();
            version = pInfo.versionName + '(' + pInfo.versionCode + ')';
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return version;
    }
    public static String getLibsDirectory(){
        return sApp.getApplicationInfo().nativeLibraryDir;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public SettingsManager getSettingsManager() {
        return mSettingsManager;
    }
    
    public static SettingsManager getSettingsManagerStatic() {
        return sApp != null ? sApp.mSettingsManager : null;
    }

    @Override
    public void onCreate() {
        // First thing of all: a crash during initModules() leaves no logcat for the user,
        // so make sure it always lands in a file we can ask for. P214b: in the :scam process too - the reporter only
        // sets the default handler and, on a crash, appends to Download/SCAMERA/SCAMERA-crash.log (no camera, no GPU,
        // no settings), and its report names the process.
        ScameraCrashReporter.install(this);
        // The firmware test has its own process. Do not open cameras, initialize
        // GPU pipelines or start background modules inside that process.
        if (android.os.Build.VERSION.SDK_INT >= 28 &&
                android.app.Application.getProcessName().endsWith(":scam")) {
            super.onCreate();
            return;
        }
        registerActivityLifecycleCallbacks(new ActivityLifecycleMonitor());
        sApp = this;
        // P214b: the build in logcat at every process start (the daily file gets it when the camera screen attaches it)
        android.util.Log.i("ScameraBuild", com.particlesdevs.photoncamera.util.BuildIdentity.line());
        Log.d("ScameraApp", "Initializing modules");
        initModules();
        super.onCreate();
        // Why the previous process ended (a native crash or a kill leaves no line of ours): into the log once
        com.particlesdevs.photoncamera.util.ExitReasons.logPrevious(this);
    }
    private void initModules() {

        SimpleStorageHelper.init(this);

        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mGravity = new Gravity(sensorManager);

        mGyro = new Gyro(sensorManager);

        mVibration = new Vibration(this);

        mSettingsManager = new SettingsManager(this);

        MigrationManager.migrate(mSettingsManager);

        PreferenceKeys.initialise(mSettingsManager);


        mSettings = new Settings();

        //mParameters = new Parameters();
        mPreviewParameters = new PreviewParameters();
        mAssetLoader = new AssetLoader(this);
        
        // Initialize gallery icon visibility based on preference
        applyGalleryIconVisibility();
        //test();
    }
    
    /**
     * Applies the gallery icon visibility setting based on the user preference.
     * This should be called on app startup to ensure the launcher icon state matches the saved preference.
     */
    private void applyGalleryIconVisibility() {
        try {
            com.particlesdevs.photoncamera.gallery.GalleryLauncherIcon.apply(this,
                    mSettingsManager.getBoolean(SettingsManager.SCOPE_GLOBAL, PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON));
        } catch (Exception e) {
            Log.e("ScameraApp", "Error applying gallery icon visibility: " + e.getMessage());
        }
    }
    //  a MemoryInfo object for the device's current memory status.
    /*public ActivityManager.MemoryInfo AvailableMemory() {
        ActivityManager activityManager = (ActivityManager) mCameraActivity.SystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo;
    }*/

    @Override
    public void onTerminate() {
        super.onTerminate();
        executorService.shutdownNow();
        mCaptureController = null;
        sApp = null;
    }
}
