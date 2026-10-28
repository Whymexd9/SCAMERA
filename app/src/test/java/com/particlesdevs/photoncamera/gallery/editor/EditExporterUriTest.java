package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** «Сохранить копию»: the copy's URI in the gallery lists' form (the volume-specific one listed the copy twice). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class EditExporterUriTest {
    @Test
    public void copyUriMatchesTheGalleryForm() {
        Uri inserted = Uri.parse("content://media/external_primary/images/media/42");
        Uri listed = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 42);
        assertEquals("content://media/external/images/media/42", listed.toString());
        assertEquals(listed, EditExporter.galleryUri(inserted));
        assertEquals(listed, EditExporter.galleryUri(listed));
    }

    @Test
    public void aUriWithoutARowIdStaysAsItIs() {
        Uri file = Uri.parse("file:///storage/emulated/0/Pictures/SCAMERA/IMG_1_edit.jpg");
        assertEquals(file, EditExporter.galleryUri(file));
    }
}
