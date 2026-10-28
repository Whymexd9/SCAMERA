package com.particlesdevs.photoncamera.processing;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * processing-dng-partial: a DNG whose write failed half way (storage full, a DngCreator RuntimeException, an OutOfMemoryError at
 * 200 MP) stayed in the photo folder, truncated or empty. ImageSaver.Util.writeDng deletes the file unless it was written to its end.
 */
public class ImageSaverDngFailureTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void partialDngDeletedAfterARuntimeException() throws IOException {
        final Path dng = tmp.getRoot().toPath().resolve("IMG_1.dng");
        assertFalse(ImageSaver.Util.writeDng(dng, out -> {
            out.write(new byte[4096]);
            throw new RuntimeException("Failed to write DNG data to output stream");
        }, "DNG"));
        assertFalse(Files.exists(dng));
    }

    @Test public void partialDngDeletedAfterAnIoError() throws IOException {
        final Path dng = tmp.getRoot().toPath().resolve("IMG_2.dng");
        assertFalse(ImageSaver.Util.writeDng(dng, out -> {
            out.write(new byte[16]);
            throw new IOException("ENOSPC");
        }, "DNG"));
        assertFalse(Files.exists(dng));
    }

    @Test public void partialDngDeletedAfterAnOutOfMemoryError() throws IOException {
        final Path dng = tmp.getRoot().toPath().resolve("IMG_3.dng");
        try {
            ImageSaver.Util.writeDng(dng, out -> {
                out.write(new byte[16]);
                throw new OutOfMemoryError("200 MP DNG");
            }, "DNG");
            fail("the Error goes up to the caller (ScamProcessor's catch), as before");
        } catch (OutOfMemoryError expected) {
            // the shot's own catch reports it
        }
        assertFalse(Files.exists(dng));
    }

    @Test public void aCompleteDngStays() throws IOException {
        final Path dng = tmp.getRoot().toPath().resolve("IMG_4.dng");
        final byte[] bytes = "II*\0 a whole file".getBytes(StandardCharsets.US_ASCII);
        assertTrue(ImageSaver.Util.writeDng(dng, out -> out.write(bytes), "DNG"));
        assertArrayEquals(bytes, Files.readAllBytes(dng));
    }

    @Test public void aMissingFolderIsAFailedDngNotACrash() {
        final Path dng = tmp.getRoot().toPath().resolve("missing").resolve("IMG_5.dng");
        assertFalse(ImageSaver.Util.writeDng(dng, out -> out.write(1), "DNG"));
        assertFalse(Files.exists(dng));
        assertFalse(ImageSaver.Util.writeDng(null, out -> out.write(1), "DNG"));
    }
}
