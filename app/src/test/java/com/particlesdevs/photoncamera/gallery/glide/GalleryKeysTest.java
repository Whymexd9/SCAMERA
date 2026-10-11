package com.particlesdevs.photoncamera.gallery.glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.net.Uri;

import com.particlesdevs.photoncamera.gallery.files.ImageFile;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/** P81: a picture decoded while the camera still wrote the file must not stay cached under the finished file's key. */
public class GalleryKeysTest {
    @Test
    public void keyFollowsTheFileOnDisk() throws Exception {
        File f = File.createTempFile("IMG_", ".webp");
        try {
            Files.write(f.toPath(), new byte[100]);
            f.setLastModified(1_000_000L);
            ImageFile item = new ImageFile(1, (Uri) null, f.getName(), 5_000L, 100, f.getAbsolutePath());
            Object partial = GalleryKeys.of(item);
            assertEquals(partial, GalleryKeys.of(item));
            Files.write(f.toPath(), new byte[5000]);
            f.setLastModified(1_000_000L);
            assertNotEquals(partial, GalleryKeys.of(item)); // same MediaStore row (DATE_ADDED, SIZE), longer file
            assertNotEquals(GalleryKeys.of(item), GalleryKeys.of(item, "hist"));
        } finally {
            f.delete();
        }
    }

    @Test
    public void withoutAPathTheMediaStoreValuesKeyIt() {
        ImageFile a = new ImageFile(1, (Uri) null, "IMG_1.avif", 5_000L, 100, "content://media/1");
        ImageFile b = new ImageFile(1, (Uri) null, "IMG_1.avif", 5_000L, 200, "content://media/1");
        assertNotEquals(GalleryKeys.of(a), GalleryKeys.of(b));
        assertEquals(GalleryKeys.key("", "IMG_1.avif", 5_000L, 100), "IMG_1.avif|5000|100");
    }
}
