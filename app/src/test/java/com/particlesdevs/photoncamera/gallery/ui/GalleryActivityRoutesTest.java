package com.particlesdevs.photoncamera.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ContentUris;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;

import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** GalleryActivity's routes: VIEW / REVIEW opens the photo asked for; leaving keeps an editor with unsaved work. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryActivityRoutesTest {
    private static List<GalleryItem> photos(int n) {
        List<GalleryItem> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 100 + i);
            l.add(new GalleryItem(new ImageFile(100 + i, u, "IMG_" + i + ".jpg", 1000L - i, 5000 + i, "/storage/emulated/0/DCIM/Camera/IMG_" + i + ".jpg")));
        }
        return l;
    }

    private static ImageFile external(Uri u, String name, long size) {
        return new ImageFile(-1, u, name, 0, size, u.toString());
    }

    @Test
    public void viewIntentFindsThePhotoAskedFor() {
        List<GalleryItem> l = photos(10);
        Uri seventh = l.get(7).getFile().getFileUri();
        assertEquals(7, GalleryActivity.positionOf(l, seventh, external(seventh, "Unknown", 0)));
        Uri path = Uri.parse("file:///storage/emulated/0/DCIM/Camera/IMG_3.jpg");
        assertEquals("by its path", 3, GalleryActivity.positionOf(l, path, external(path, "IMG_3.jpg", 0)));
        Uri other = Uri.parse("content://com.example.files/doc/IMG_5.jpg");
        assertEquals("by its name and size", 5, GalleryActivity.positionOf(l, other, external(other, "IMG_5.jpg", 5005)));
        Uri unknown = Uri.parse("content://com.example.files/doc/x.jpg");
        assertEquals(-1, GalleryActivity.positionOf(l, unknown, external(unknown, "x.jpg", 1)));
    }

    @Test
    public void theViewerIsSentToThePhotoOnce() {
        // the start position bundle given to setGraph never reached the viewer: the viewer's pending selection carries it
        GalleryViewModel vm = new GalleryViewModel(RuntimeEnvironment.getApplication());
        List<GalleryItem> l = photos(10);
        Uri seventh = l.get(7).getFile().getFileUri();
        vm.getCurrentFolderImages().setValue(l);
        vm.selectNext(l.get(GalleryActivity.positionOf(l, seventh, external(seventh, "Unknown", 0))).getFile().getFileUri());
        assertEquals(seventh, vm.takePendingSelect());
        assertNull("taken once", vm.takePendingSelect());
    }

    @Test
    public void intentRoutes() {
        Uri u = Uri.parse("content://media/external/images/media/7");
        assertTrue(GalleryActivity.isViewIntent(new Intent(Intent.ACTION_VIEW, u)));
        assertTrue(GalleryActivity.isViewIntent(new Intent("com.android.camera.action.REVIEW", u)));
        assertFalse("no data: the normal gallery", GalleryActivity.isViewIntent(new Intent(Intent.ACTION_VIEW)));
        assertTrue(GalleryActivity.isEditIntent(new Intent(Intent.ACTION_EDIT, u)));
        assertFalse(GalleryActivity.isEditIntent(new Intent(Intent.ACTION_EDIT)));
        assertFalse(GalleryActivity.isEditIntent(new Intent(Intent.ACTION_MAIN)));
    }

    @Test
    public void leavingKeepsAnEditorWithUnsavedWork() {
        // P299: the gallery opened from the camera closes when the user leaves it ...
        assertTrue(GalleryActivity.finishOnLeave(false, false, false, false));
        // ... but not under unsaved edits or a running «Сохранить копию» (they were dropped without a word)
        assertFalse(GalleryActivity.finishOnLeave(false, false, false, true));
        // another app's gallery, the gallery's own child screens and a finishing gallery as before
        assertFalse(GalleryActivity.finishOnLeave(true, false, false, false));
        assertFalse(GalleryActivity.finishOnLeave(false, true, false, false));
        assertFalse(GalleryActivity.finishOnLeave(false, false, true, false));
    }
}
