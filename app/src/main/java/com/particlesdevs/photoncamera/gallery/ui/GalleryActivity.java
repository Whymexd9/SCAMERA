package com.particlesdevs.photoncamera.gallery.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.base.BaseActivity;
import com.particlesdevs.photoncamera.databinding.ActivityGalleryBinding;
import com.particlesdevs.photoncamera.gallery.editor.PhotoEditorFragment;
import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.ui.fragments.ImageLibraryFragment;
import com.particlesdevs.photoncamera.gallery.ui.fragments.ImageViewerFragment;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.ArrayList;
import java.util.List;

public class GalleryActivity extends BaseActivity {
    private ActivityGalleryBinding activityGalleryBinding;
    private GalleryViewModel viewModel;

    private boolean externalUsage = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hide system UI immediately to prevent flickering
        hideSystemUI();
        
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            Object fragment = getIntent().getExtras().get("CameraFragment");
            externalUsage = fragment == null;
        } else {
            externalUsage = true;
        }

        getDelegate().setLocalNightMode(PreferenceKeys.getThemeValue());
        activityGalleryBinding = DataBindingUtil.setContentView(this, R.layout.activity_gallery);
        viewModel = new ViewModelProvider(this).get(GalleryViewModel.class);
        
        // A recreation (rotation, a theme change, process death): the NavHostFragment restored the back stack and each entry's
        // arguments, so nothing navigates again (setGraph with another start popped it: the editor and its unsaved edits were lost,
        // the launcher route went back to the library). The lists are read again only for a new GalleryViewModel (the first start,
        // or after process death); a surviving one keeps the folder the viewer shows.
        boolean fresh = savedInstanceState == null;
        List<GalleryItem> shown = viewModel.getCurrentFolderImages().getValue();
        boolean refill = fresh || shown == null || shown.isEmpty();
        Bundle editArgs = null;
        // Check if this is a VIEW or REVIEW intent from another app (like HedgeCam2)
        if (isViewIntent(intent)) {
            if (refill) handleExternalViewIntent(intent.getData(), fresh);
        } else if (isEditIntent(intent)) {
            externalUsage = true;
            if (refill) editArgs = editorFor(intent.getData(), intent.getType());
        } else if (refill) {
            // Normal gallery mode - show all media
            viewModel.fetchAllMedia();
            viewModel.setCurrentFolderImages(viewModel.getAllSelectedImageFolder().getValue());
        }
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host);
        if (host != null) startNavigation(host.getNavController(), intent, fresh, editArgs);
    }

    static boolean isViewIntent(Intent intent) {
        String action = intent.getAction();
        return (Intent.ACTION_VIEW.equals(action) || Constants.ACTION_REVIEW.equals(action)) && intent.getData() != null;
    }

    static boolean isEditIntent(Intent intent) {
        return !isViewIntent(intent) && Intent.ACTION_EDIT.equals(intent.getAction()) && intent.getData() != null;
    }

    /**
     * The start destination of a new gallery. P351: ACTION_EDIT from another app starts on the editor ({@code editArgs}). P347
     * (owner, 2026-10-26: «когда открывал галерею через иконку на рабочем столе открывалось не последнее фото, а открывался наш
     * список по папкам как обычно»): the gallery's own launcher icon starts on the library (the folders and the photo grid); the
     * camera's thumbnail and VIEW / REVIEW still open the viewer (the graph's start destination). Back from a photo opened there
     * returns to the library. When {@code fresh} is false (a recreation) the restored back stack stays and only the start
     * destination is put back into the graph the NavHostFragment inflated again.
     */
    static void startNavigation(NavController nav, Intent intent, boolean fresh, @Nullable Bundle editArgs) {
        int start;
        if (isEditIntent(intent)) start = R.id.photoEditorFragment;
        else if (!isViewIntent(intent) && Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_LAUNCHER))
            start = R.id.imageLibraryFragment;
        else return;
        if (!fresh) {
            // the XML graph again (start: the viewer) under the restored back stack: navigateUp at the root library (or editor)
            // relaunched the gallery on the viewer at the newest photo instead of leaving
            nav.getGraph().setStartDestination(start);
            return;
        }
        androidx.navigation.NavGraph graph = nav.getNavInflater().inflate(R.navigation.gallery_nav_graph);
        graph.setStartDestination(start);
        nav.setGraph(graph, start == R.id.photoEditorFragment ? editArgs : null);
    }

    /** The position of another app's photo in the gallery's list: by URI, absolute path, or display name and size; -1 for none. */
    static int positionOf(List<GalleryItem> items, Uri uri, ImageFile external) {
        String externalPath = uri.getPath();
        String externalDisplayName = external.getDisplayName();
        for (int i = 0; i < items.size(); i++) {
            GalleryItem item = items.get(i);
            if (item.getFile() == null) continue;
            // Try to match by URI (most reliable for content:// URIs)
            if (uri.equals(item.getFile().getFileUri())) return i;
            // Try to match by absolute path (for file:// URIs)
            if (externalPath != null && item.getFile().getAbsolutePath() != null && externalPath.equals(item.getFile().getAbsolutePath()))
                return i;
            // Try to match by display name and size as last resort
            if (externalDisplayName != null && externalDisplayName.equals(item.getFile().getDisplayName())
                    && external.getSize() == item.getFile().getSize()) return i;
        }
        return -1;
    }

    /**
     * Handle VIEW intent from external apps (like HedgeCam2)
     * Loads all gallery images but starts viewing at the selected image ({@code select}: a new gallery; after process death the
     * viewer's saved position applies)
     */
    private void handleExternalViewIntent(Uri uri, boolean select) {
        try {
            // Load all media from the gallery first
            viewModel.fetchAllMedia();
            
            // Create an ImageFile from the external URI
            ImageFile externalImageFile = createImageFileFromUri(uri);
            
            // Get all images from the gallery
            GalleryItem allImagesFolder = viewModel.getAllSelectedImageFolder().getValue();
            
            int initialPosition = 0;
            
            if (allImagesFolder != null && !allImagesFolder.getFiles().isEmpty()) {
                List<GalleryItem> allImages = allImagesFolder.getFiles();

                // Try to find the external image in the existing gallery
                // Match by URI, absolute path, or display name
                int foundPosition = positionOf(allImages, uri, externalImageFile);

                if (foundPosition >= 0) {
                    // Image found in gallery, use its position
                    initialPosition = foundPosition;
                } else {
                    // Image not in gallery, add it at the beginning
                    GalleryItem externalGalleryItem = new GalleryItem(externalImageFile);
                    allImages.add(0, externalGalleryItem);
                    initialPosition = 0;
                }
                
                // Set the current folder images
                viewModel.setCurrentFolderImages(allImagesFolder);
            } else {
                // No gallery images found, create a single-item list with just the external image
                GalleryItem externalGalleryItem = new GalleryItem(externalImageFile);
                List<GalleryItem> singleItemList = new ArrayList<>();
                singleItemList.add(externalGalleryItem);
                viewModel.getCurrentFolderImages().setValue(singleItemList);
            }
            
            // The viewer (the graph's start, already created without arguments by the NavHostFragment) moves to the photo when it
            // binds the list. A start position passed by setGraph with the same graph was dropped: the newest photo opened instead.
            List<GalleryItem> shown = viewModel.getCurrentFolderImages().getValue();
            if (select && shown != null && initialPosition < shown.size())
                viewModel.selectNext(shown.get(initialPosition).getFile().getFileUri());
        } catch (Exception e) {
            e.printStackTrace();
            // Fallback to normal gallery mode if something goes wrong
            viewModel.fetchAllMedia();
            viewModel.setCurrentFolderImages(viewModel.getAllSelectedImageFolder().getValue());
        }
    }
    
    /**
     * P351: another app asked this gallery to edit a photo (ACTION_EDIT): the editor alone (the graph starts there,
     * {@link #startNavigation}), the copy's URI returned with RESULT_OK; back without saving returns RESULT_CANCELED. A file
     * outside MediaStore's DCIM / Pictures gets its copy in Pictures/SCAMERA. Returns the editor's arguments.
     */
    private Bundle editorFor(Uri uri, @Nullable String type) {
        ImageFile file = createImageFileFromUri(uri);
        List<GalleryItem> single = new ArrayList<>();
        single.add(new GalleryItem(file));
        viewModel.getCurrentFolderImages().setValue(single);
        // P367: the zoom test hook's extras (scam_edit_zoom / _cx / _cy) pass through; nothing when absent
        return com.particlesdevs.photoncamera.gallery.editor.ExternalEdit.withZoomHook(
                com.particlesdevs.photoncamera.gallery.editor.ExternalEdit.args(uri, file.getDisplayName(), null, type, true), getIntent());
    }

    /**
     * Creates an ImageFile object from a URI
     * Extracts file information like name, size, etc. from the URI
     */
    private ImageFile createImageFileFromUri(Uri uri) {
        String displayName = "Unknown";
        long size = 0;
        long lastModified = System.currentTimeMillis();
        
        // Try to get file information from the ContentResolver
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                
                if (nameIndex != -1) {
                    displayName = cursor.getString(nameIndex);
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // P352: a file:// URI (or a provider without a name) is named by its path, so its extension (a RAW) is known
        if ("Unknown".equals(displayName) && uri.getLastPathSegment() != null) {
            String seg = uri.getLastPathSegment();
            displayName = seg.substring(seg.lastIndexOf('/') + 1);
        }
        // Create and return the ImageFile
        // Use a negative ID to indicate this is an external file
        return new ImageFile(-1, uri, displayName, lastModified, size, uri.toString());
    }

    /*public void onBackArrowClicked(View view) {
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host);
        NavController navController = navHostFragment.getNavController();
        navController.navigateUp();
    }*/

    @Override
    protected void onResume() {
        super.onResume();
        // Apply hideSystemUI in onResume to prevent flickering when returning to the gallery
        hideSystemUI();
        childLaunch = false;
    }

    /** P299: this gallery started one of its own screens (share, edit, a viewer) - leaving for it keeps the gallery. */
    private boolean childLaunch;

    @Override
    public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        childLaunch = true;
        super.startActivityForResult(intent, requestCode, options);
    }

    /**
     * P345 (owner, 2026-10-26, X200 Ultra screen recording): the system's delete request (MediaStore.createDeleteRequest) comes in
     * through an IntentSender, not startActivityForResult, so the P299 rule below took its dialog for the user leaving and closed the
     * gallery under it: the camera behind the dialog, the result lost (the viewer gone, no «Удалено», the camera's thumbnail still the
     * deleted photo) and the camera session restarted twice. It is the gallery's own screen as well.
     */
    @Override
    public void startIntentSenderForResult(IntentSender intent, int requestCode, @Nullable Intent fillInIntent, int flagsMask,
                                           int flagsValues, int extraFlags, @Nullable Bundle options) throws IntentSender.SendIntentException {
        childLaunch = true;
        super.startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues, extraFlags, options);
    }

    /**
     * P299 (owner, 2026-10-20: «если в камере зайти в галерею и свернуть, а потом нажать на иконку камеры, она откроется на
     * галерее»): the gallery opened from the camera shares its task, and the launcher icon brings the task back with the gallery
     * on top. Leaving it for the home screen (or another app) closes it, so the icon returns to the camera.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (finishOnLeave(externalUsage, childLaunch, isFinishing(), editorBusy())) finish();
    }

    /**
     * P299, except while the editor holds unsaved edits or a running «Сохранить копию»: finishing then dropped them without a
     * word (the editor asks before discarding edits on back); the camera's icon returns to the editor instead.
     */
    static boolean finishOnLeave(boolean externalUsage, boolean childLaunch, boolean finishing, boolean editorBusy) {
        return !externalUsage && !childLaunch && !finishing && !editorBusy;
    }

    private boolean editorBusy() {
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host);
        Fragment top = host != null ? host.getChildFragmentManager().getPrimaryNavigationFragment() : null;
        return top instanceof PhotoEditorFragment && ((PhotoEditorFragment) top).hasUnsavedWork();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        activityGalleryBinding = null;
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float displayAspectRatio = (float) Math.max(dm.heightPixels, dm.widthPixels) / Math.min(dm.heightPixels, dm.widthPixels);
            if (displayAspectRatio <= (16f / 9) || dm.densityDpi > 440) {
                hideSystemUI();
            }
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GalleryFileOperations.REQUEST_PERM_DELETE) {
            NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host);
            Fragment fragment = null;
            if (navHostFragment != null) {
                fragment = navHostFragment.getChildFragmentManager().getFragments().get(0);
            }
            if (fragment instanceof ImageViewerFragment) {
                ((ImageViewerFragment) fragment).handleImagesDeletedCallback(resultCode == Activity.RESULT_OK);
            } else if (fragment instanceof ImageLibraryFragment) {
                ((ImageLibraryFragment) fragment).handleImagesDeletedCallback(resultCode == Activity.RESULT_OK);
            }
        }
    }
}
