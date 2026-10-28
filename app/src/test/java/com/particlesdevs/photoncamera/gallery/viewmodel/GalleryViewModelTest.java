package com.particlesdevs.photoncamera.gallery.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The gallery's lists after a delete, a folders change or «Сохранить копию» (MediaStore replaced by fixed folders). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryViewModelTest {
    private MockedStatic<GalleryFileOperations> files;
    private ArrayList<GalleryFileOperations.ImagesFolder> folders = new ArrayList<>();
    private GalleryViewModel vm;

    private static ImageFile photo(long id, String name) {
        Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        return new ImageFile(id, u, name, id, 1000, "/storage/emulated/0/DCIM/x/" + name);
    }

    private static GalleryFileOperations.ImagesFolder folder(String name, ImageFile... images) {
        GalleryFileOperations.ImagesFolder f = mock(GalleryFileOperations.ImagesFolder.class);
        when(f.getFolderName()).thenReturn(name);
        when(f.getAllImageFiles()).thenReturn(new ArrayList<>(Arrays.asList(images)));
        when(f.getTopImage()).thenReturn(images[0]);
        return f;
    }

    /** What MediaStore holds from now on (the selected folders). */
    private void store(GalleryFileOperations.ImagesFolder... selected) {
        folders = new ArrayList<>(Arrays.asList(selected));
    }

    @Before
    public void setUp() {
        files = mockStatic(GalleryFileOperations.class);
        files.when(() -> GalleryFileOperations._fetchSelectedFolders(any(ContentResolver.class))).thenAnswer(inv -> folders);
        files.when(GalleryFileOperations::extractAllSelectedImages).thenAnswer(inv -> {
            ArrayList<ImageFile> all = new ArrayList<>();
            for (GalleryFileOperations.ImagesFolder f : folders) all.addAll(f.getAllImageFiles());
            return all;
        });
        vm = new GalleryViewModel(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        files.close();
    }

    @Test
    public void emptySelectionClearsAll() {
        store(folder("Camera", photo(1, "a.jpg"), photo(2, "b.jpg")));
        vm.fetchAllMedia();
        assertEquals(2, vm.getAllSelectedImageFolder().getValue().getFiles().size());
        store(); // every photo deleted
        vm.fetchAllMedia();
        assertTrue("no stale «ALL» with the deleted photos", vm.getAllSelectedImageFolder().getValue().getFiles().isEmpty());
        assertEquals("ALL", vm.getAllSelectedImageFolder().getValue().getDisplayName());
        assertNull("no chips", vm.refreshKeeping("ALL"));
        assertTrue("the grid and the viewer are empty", vm.getCurrentFolderImages().getValue().isEmpty());
    }

    @Test
    public void refreshKeepsTheChipFolder() {
        store(folder("Camera", photo(1, "a.jpg"), photo(3, "c.jpg")), folder("Raw", photo(2, "b.dng")));
        vm.fetchAllMedia();
        // a delete in the «Camera» chip: Camera minus the photo, in the grid and in the viewer alike
        store(folder("Camera", photo(3, "c.jpg")), folder("Raw", photo(2, "b.dng")));
        assertEquals("Camera", vm.refreshKeeping("Camera"));
        assertEquals("Camera", vm.getCurrentFolderName());
        List<GalleryItem> shown = vm.getCurrentFolderImages().getValue();
        assertEquals(1, shown.size());
        assertEquals("c.jpg", shown.get(0).getFile().getDisplayName());
        GalleryItem camera = null;
        for (GalleryItem f : vm.getSelectedDisplayFolders().getValue()) if ("Camera".equals(f.getDisplayName())) camera = f;
        assertSame("the chip's own list", camera.getFiles(), shown);
    }

    @Test
    public void refreshFallsBackToAllWhenTheFolderIsGone() {
        store(folder("Camera", photo(1, "a.jpg")), folder("Raw", photo(2, "b.dng")));
        vm.fetchAllMedia();
        store(folder("Raw", photo(2, "b.dng")));
        assertEquals("the first chip", "ALL", vm.refreshKeeping("Camera"));
        assertSame(vm.getAllSelectedImageFolder().getValue().getFiles(), vm.getCurrentFolderImages().getValue());
        assertEquals("ALL", vm.refreshKeeping(null));
    }

    @Test
    public void savedCopyIsNotListedTwice() {
        store(folder("Camera", photo(42, "IMG_edit.jpg"), photo(1, "IMG.jpg")));
        vm.fetchAllMedia();
        vm.setCurrentFolderImages(vm.getAllSelectedImageFolder().getValue());
        Uri listed = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 42);
        vm.showNewFile(new ImageFile(42, listed, "IMG_edit.jpg", 0, 0, null));
        assertEquals(2, vm.getCurrentFolderImages().getValue().size());
        assertEquals(listed, vm.takePendingSelect());
        // the volume-specific URI of the same row (what the insert into VOLUME_EXTERNAL_PRIMARY returns)
        Uri primary = Uri.parse("content://media/external_primary/images/media/42");
        vm.showNewFile(new ImageFile(42, primary, "IMG_edit.jpg", 0, 0, null));
        assertEquals("the copy once", 2, vm.getCurrentFolderImages().getValue().size());
        assertEquals("the viewer selects the listed row", listed, vm.takePendingSelect());
        assertNull(vm.takePendingSelect());
    }
}
