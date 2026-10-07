package com.particlesdevs.photoncamera.ui.camera;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import com.particlesdevs.photoncamera.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.app.base.BaseActivity;
import com.particlesdevs.photoncamera.settings.MigrationManager;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.anggrayudi.storage.contract.RequestStorageAccessContract;
import com.anggrayudi.storage.contract.RequestStorageAccessResult;
import com.anggrayudi.storage.file.StorageType;
import com.particlesdevs.photoncamera.util.FileManager;
import com.particlesdevs.photoncamera.util.SimpleStorageHelper;
import com.particlesdevs.photoncamera.util.log.FragmentLifeCycleMonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.os.Build.VERSION.SDK_INT;


public class CameraActivity extends BaseActivity {

    private static final String STATE_PERMISSION_PLAN = "permission_plan";
    private static final String STATE_PERMISSION_PENDING = "permission_pending";
    private static final String STATE_AWAITING_SETTINGS = "permission_awaiting_settings";

    /** Startup permissions, asked like ArkCam does: one system request, no dialog of ours in front (P31). */
    private PermissionPlan permissionPlan;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<RequestStorageAccessContract.Options> storageAccessLauncher;
    /** A runtime request or the DCIM picker is on screen; its result callback continues the flow. */
    private boolean permissionStepPending;
    /** The user was sent to the app settings; onResume checks the permissions again. */
    private boolean awaitingSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("CameraActivity", "Called onCreate()");
        // Hide system UI immediately to prevent flickering (like gallery view)
        hideSystemUI();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_camera);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, MigrationManager.readAgain);
        PreferenceKeys.setDefaults(this);
        PhotonCamera.getSettings().loadCache();

        permissionPlan = PermissionPlan.restore(SDK_INT,
                savedInstanceState == null ? null : savedInstanceState.getIntArray(STATE_PERMISSION_PLAN));
        boolean stepPending = savedInstanceState != null && savedInstanceState.getBoolean(STATE_PERMISSION_PENDING);
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_AWAITING_SETTINGS)) {
            // Recreated while the user was in the app settings: this start is the return from there.
            permissionPlan.restart();
        }

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    permissionStepPending = false;
                    Log.d("CameraActivity", "Permission result " + result);
                    permissionPlan.onResult(!result.isEmpty());
                    advancePermissions();
                });
        storageAccessLauncher = registerForActivityResult(
                new RequestStorageAccessContract(this, StorageType.EXTERNAL, SimpleStorageHelper.DCIM_BASE_PATH),
                result -> {
                    permissionStepPending = false;
                    if (result instanceof RequestStorageAccessResult.RootPathPermissionGranted) {
                        Log.d("CameraActivity", "Storage access granted (SimpleStorage)");
                        SimpleStorageHelper.updateFileManagerPaths(CameraActivity.this);
                        permissionPlan.onDcimGranted();
                    } else if (result instanceof RequestStorageAccessResult.CanceledByUser) {
                        Log.e("CameraActivity", "Storage access cancelled");
                        permissionPlan.onDcimCancelled();
                    } else {
                        Log.e("CameraActivity", "Storage access denied or wrong folder: " + result);
                    }
                    advancePermissions();
                });

        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentLifeCycleMonitor(), true);

        // After a recreation with a request on screen its result arrives through the launcher callback.
        if (!stepPending) advancePermissions();
        else permissionStepPending = true;
    }

    /** Takes the next step of the startup permission flow; the camera starts once nothing is missing. */
    private void advancePermissions() {
        String[] missing = permissionPlan.missing(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        boolean hasDcimAccess = !permissionPlan.needsDcimAccess() || SimpleStorageHelper.hasStorageAccess(this);
        PermissionPlan.Step step = permissionPlan.next(missing, this::shouldShowRequestPermissionRationale, hasDcimAccess);
        Log.d("CameraActivity", "Permission step " + step + " missing=" + Arrays.toString(missing));
        switch (step) {
            case REQUEST:
                // One batch of everything missing; ColorOS shows it as one combined system sheet.
                permissionPlan.onRequest();
                permissionStepPending = true;
                permissionLauncher.launch(missing);
                break;
            case SETTINGS:
                showSettingsRedirectDialog(missing);
                break;
            case PICK_DCIM:
                launchDcimPicker();
                break;
            case DCIM_FAILED:
                showDcimFailedDialog();
                break;
            case START:
                tryLoad();
                break;
        }
    }

    private void launchDcimPicker() {
        Toast.makeText(this, permissionPlan.dcimPicks() == 0 ? R.string.perm_dcim_hint : R.string.perm_dcim_wrong_folder,
                Toast.LENGTH_LONG).show();
        permissionPlan.onDcimPick();
        try {
            permissionStepPending = true;
            storageAccessLauncher.launch(new RequestStorageAccessContract.Options(SimpleStorageHelper.createDcimInitialPath(this)));
        } catch (RuntimeException e) {
            permissionStepPending = false;
            Log.e("CameraActivity", "Cannot open the DCIM folder picker: " + e);
            permissionPlan.onDcimCancelled();
            showDcimFailedDialog();
        }
    }

    private void showSettingsRedirectDialog(String[] missing) {
        List<PermissionPlan.Group> groups = permissionPlan.groups(missing);
        String title;
        String message;
        if (PermissionPlan.mediaOnly(groups)) {
            title = getString(R.string.perm_rationale_media_title);
            message = getString(R.string.perm_rationale_media_settings);
        } else {
            List<String> names = new ArrayList<>();
            for (PermissionPlan.Group group : groups) names.add(getString(groupName(group)));
            title = getString(R.string.perm_settings_title);
            message = getString(R.string.perm_settings_message, String.join(", ", names));
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.perm_open_settings, (dialog, which) -> openAppSettings())
                .setNegativeButton(R.string.cancel, (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private static int groupName(PermissionPlan.Group group) {
        switch (group) {
            case CAMERA:
                return R.string.perm_group_camera;
            case MICROPHONE:
                return R.string.perm_group_microphone;
            case PHOTOS:
                return R.string.perm_group_photos;
            case FILES:
                return R.string.perm_group_files;
            default:
                return R.string.perm_group_storage;
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            awaitingSettings = true;
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            awaitingSettings = false;
            Log.e("CameraActivity", "Cannot open the app settings: " + e);
            advancePermissions();
        }
    }

    private void showDcimFailedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_rationale_dcim_title)
                .setMessage(R.string.perm_dcim_failed)
                .setPositiveButton(R.string.perm_retry, (dialog, which) -> {
                    permissionPlan.restart();
                    advancePermissions();
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntArray(STATE_PERMISSION_PLAN, permissionPlan.save());
        outState.putBoolean(STATE_PERMISSION_PENDING, permissionStepPending);
        outState.putBoolean(STATE_AWAITING_SETTINGS, awaitingSettings);
    }

    private void tryLoad() {
        if (SDK_INT >= Build.VERSION_CODES.R) {
            SimpleStorageHelper.updateFileManagerPaths(this);
        }
        FileManager.CreateFolders();
        Log.setLogFolder(getApplicationContext());
        // Reads the "Full debug" switch and, when on, opens Download/SCAMERA/SCAMERA-debug.log
        // and writes the build/device/camera header for this session.
        com.particlesdevs.photoncamera.util.ScameraDebugLog.init(getApplicationContext());
        PhotonCamera photonCamera = PhotonCamera.getInstance(this);
        if (photonCamera != null) {
            photonCamera.getSupportedDevice().loadCheck();
        }
        if (getSupportFragmentManager().findFragmentById(R.id.container) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, CameraFragment.newInstance())
                    .commit();
        }
    }

    @Override
    public void onBackPressed() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.container);
        if (!(fragment instanceof BackPressedListener) || !((BackPressedListener) fragment).onBackPressed())
            super.onBackPressed();
    }

    private String appliedThemeSignature;
    private String themeSignature() {
        com.particlesdevs.photoncamera.settings.SettingsManager sm=PhotonCamera.getSettingsManagerStatic();
        return sm.getString("default_scope",PreferenceKeys.Key.KEY_THEME)+"/"+
                sm.getString("default_scope",PreferenceKeys.Key.KEY_THEME_ACCENT)+"/"+
                sm.getBoolean("default_scope",PreferenceKeys.Key.KEY_SHOW_GRADIENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String signature=themeSignature();
        if(appliedThemeSignature!=null && !appliedThemeSignature.equals(signature)) {
            appliedThemeSignature=signature;
            recreate();
            return;
        }
        appliedThemeSignature=signature;
        // Apply hideSystemUI in onResume to prevent flickering when returning to the camera
        hideSystemUI();
        // Ensure portrait orientation is enforced every time activity resumes
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (awaitingSettings) {
            // Back from the app settings: check again and go on, no restart of the app needed.
            awaitingSettings = false;
            permissionPlan.restart();
            advancePermissions();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == KeyEvent.ACTION_DOWN) {
                    View view = findViewById(R.id.shutter_button);
                    // Null while the startup permission flow runs (no camera fragment yet).
                    if (view != null && view.isClickable())
                        view.performClick();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        // Set the content to appear under the system bars so that the
                        // content doesn't resize when the system bars hide and show.
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        // Hide the nav bar and status bar
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

}

