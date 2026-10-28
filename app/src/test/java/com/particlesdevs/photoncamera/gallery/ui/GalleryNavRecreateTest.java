package com.particlesdevs.photoncamera.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentFactory;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.particlesdevs.photoncamera.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * GalleryActivity's start navigation on the real activity_gallery layout (FragmentContainerView + app:navGraph), the gallery's
 * fragments replaced by a dummy: a recreation (rotation, a theme change, process death) keeps the restored back stack - the
 * editor and its unsaved edits stayed only when nothing set the graph again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryNavRecreateTest {
    static final String EDIT_KEY = "test_edit";

    public static class Dummy extends Fragment {
    }

    /** onCreate as GalleryActivity's: the layout, then {@link GalleryActivity#startNavigation} for this intent. */
    public static class Host extends FragmentActivity {
        @Override
        protected void onCreate(Bundle saved) {
            getSupportFragmentManager().setFragmentFactory(new FragmentFactory() {
                @NonNull
                @Override
                public Fragment instantiate(@NonNull ClassLoader cl, @NonNull String className) {
                    if (className.startsWith("com.particlesdevs.photoncamera.gallery")) return new Dummy();
                    return super.instantiate(cl, className);
                }
            });
            super.onCreate(saved);
            setContentView(R.layout.activity_gallery);
            Bundle edit = new Bundle();
            edit.putString(EDIT_KEY, "editor " + System.nanoTime());
            GalleryActivity.startNavigation(nav(this), getIntent(), saved == null, edit);
        }
    }

    public static NavController nav(FragmentActivity a) {
        return ((NavHostFragment) a.getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host)).getNavController();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void launcherRouteKeepsTheEditorOnRecreate() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class, launcher).setup()) {
            NavController nav = nav(c.get());
            assertEquals("P347: the launcher icon starts on the library", R.id.imageLibraryFragment, nav.getCurrentDestination().getId());
            nav.navigate(R.id.action_imageLibraryFragment_to_imageViewerFragment);
            nav.navigate(R.id.action_imageViewerFragment_to_photoEditorFragment);
            idle();
            String editor = nav.getCurrentBackStackEntry().getId();
            c.recreate();
            idle();
            NavController after = nav(c.get());
            assertEquals("the re-inflated XML graph starts on the library again", R.id.imageLibraryFragment,
                    after.getGraph().getStartDestinationId());
            assertEquals(R.id.photoEditorFragment, after.getCurrentDestination().getId());
            assertEquals("the same editor entry (its ViewModel, edits and undo history)", editor, after.getCurrentBackStackEntry().getId());
            assertEquals(R.id.imageViewerFragment, after.getPreviousBackStackEntry().getDestination().getId());
            assertTrue(after.popBackStack());
            assertEquals(R.id.imageLibraryFragment, after.getPreviousBackStackEntry().getDestination().getId());
            assertTrue(after.popBackStack());
            idle();
            assertEquals(R.id.imageLibraryFragment, after.getCurrentDestination().getId());
            // ImageLibraryFragment's back: navigateUp, else finish; the viewer as the start relaunched the gallery on the newest photo
            assertFalse("the root library leaves, it does not relaunch the gallery", after.navigateUp());
            assertNull(shadowOf(c.get()).getNextStartedActivity());
        }
    }

    @Test
    public void editRouteKeepsTheEditorOnRecreate() {
        Intent edit = new Intent(Intent.ACTION_EDIT).setDataAndType(Uri.parse("content://media/external/images/media/5"), "image/jpeg");
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class, edit).setup()) {
            NavController nav = nav(c.get());
            assertEquals("P351: ACTION_EDIT starts on the editor", R.id.photoEditorFragment, nav.getCurrentDestination().getId());
            String entry = nav.getCurrentBackStackEntry().getId();
            String args = nav.getCurrentBackStackEntry().getArguments().getString(EDIT_KEY);
            assertNotNull(args);
            c.recreate();
            idle();
            NavController after = nav(c.get());
            assertEquals(R.id.photoEditorFragment, after.getGraph().getStartDestinationId());
            assertEquals(R.id.photoEditorFragment, after.getCurrentDestination().getId());
            assertEquals("not a fresh editor with a new ViewModel", entry, after.getCurrentBackStackEntry().getId());
            assertEquals(args, after.getCurrentBackStackEntry().getArguments().getString(EDIT_KEY));
            assertFalse("the editor at the root returns to the other app, not to a relaunched viewer", after.navigateUp());
            assertNull(shadowOf(c.get()).getNextStartedActivity());
        }
    }

    @Test
    public void cameraRouteKeepsItsPlaceOnRecreate() {
        Intent camera = new Intent().putExtra("CameraFragment", true);
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class, camera).setup()) {
            NavController nav = nav(c.get());
            assertEquals("the camera's thumbnail opens the viewer", R.id.imageViewerFragment, nav.getCurrentDestination().getId());
            nav.navigate(R.id.action_imageViewFragment_to_imageLibraryFragment);
            idle();
            c.recreate();
            idle();
            NavController after = nav(c.get());
            assertEquals(R.id.imageLibraryFragment, after.getCurrentDestination().getId());
            assertEquals(R.id.imageViewerFragment, after.getPreviousBackStackEntry().getDestination().getId());
        }
    }

    @Test
    public void viewIntentOpensTheViewer() {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/images/media/7"), "image/jpeg");
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class, view).setup()) {
            assertEquals(R.id.imageViewerFragment, nav(c.get()).getCurrentDestination().getId());
        }
    }
}
