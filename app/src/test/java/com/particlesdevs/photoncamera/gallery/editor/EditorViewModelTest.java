package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.net.Uri;
import android.os.Looper;

import com.particlesdevs.photoncamera.gallery.dng.DngFixtures;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The editor's decoded source is closed exactly once however the editor is left: idle, while the photo still decodes, or while
 * «Сохранить копию» runs (the DNG's full-size cache copy edit*.dng stayed until the process died), and when every decode route
 * fails.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class EditorViewModelTest {
    private static int cacheCopies(Application app) {
        File[] l = app.getCacheDir().listFiles((d, n) -> n.startsWith("edit") && n.endsWith(".dng"));
        return l == null ? 0 : l.length;
    }

    private static Uri write(Application app, String name, byte[] data) throws IOException {
        File f = new File(app.getFilesDir(), name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
        return Uri.fromFile(f);
    }

    private static void join(String threadName) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Thread found = null;
            for (Thread t : Thread.getAllStackTraces().keySet()) if (threadName.equals(t.getName())) found = t;
            if (found == null) {
                Thread.sleep(10); // not started yet, or already done
                continue;
            }
            found.join(30000);
            return;
        }
    }

    private static EditorViewModel loaded(Application app, Uri u) throws InterruptedException {
        EditorViewModel vm = new EditorViewModel(app);
        vm.load(u, "shot.dng", "image/x-adobe-dng", null);
        join("EditorDecode");
        return vm;
    }

    @Test
    public void idleEditorClosesTheSource() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        EditorViewModel vm = loaded(app, write(app, "idle.dng", DngFixtures.smallBayer()));
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("the DNG decoded", vm.source.getValue());
        assertEquals("the DNG route made its cache copy", 1, cacheCopies(app));
        vm.onCleared();
        assertEquals(0, cacheCopies(app));
    }

    @Test
    public void editorLeftWhileDecodingClosesTheSource() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        // the decode finished but the main thread has not delivered the source yet: the user pressed back during the spinner
        EditorViewModel vm = loaded(app, write(app, "decoding.dng", DngFixtures.smallBayer()));
        vm.onCleared();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the cleared editor's cache copy is deleted", 0, cacheCopies(app));
    }

    @Test
    public void secondLoadBeforeDeliveryOpensOnce() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Uri u = write(app, "twice.dng", DngFixtures.smallBayer());
        // the decode finished, its source not delivered yet: a rotation's recreated editor calls load again
        EditorViewModel vm = loaded(app, u);
        vm.load(u, "shot.dng", "image/x-adobe-dng", null);
        join("EditorDecode");
        assertEquals("one decode, one cache copy", 1, cacheCopies(app));
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(vm.source.getValue());
        vm.onCleared();
        assertEquals("no cache copy left behind", 0, cacheCopies(app));
    }

    @Test
    public void editorLeftWhileExportingClosesTheSourceAfterTheExport() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        EditorViewModel vm = loaded(app, write(app, "export.dng", DngFixtures.smallBayer()));
        shadowOf(Looper.getMainLooper()).idle();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        vm.export(new EditState(), ready);
        assertTrue(vm.exporting());
        vm.onCleared(); // the gallery finished during «Сохранить копию»
        assertEquals("the running export still reads the source", 1, cacheCopies(app));
        ready.complete(null);
        join("EditorExport");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the export that outlived the editor closed the source", 0, cacheCopies(app));
    }

    @Test
    public void failedOpenDeletesTheCacheCopy() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        // a TIFF header and garbage: DngRaw, LibRaw (not in a host build) and Glide all reject it
        byte[] junk = new byte[4096];
        junk[0] = 'I';
        junk[1] = 'I';
        junk[2] = 42;
        for (int i = 8; i < junk.length; i++) junk[i] = (byte) (i * 31);
        Uri u = write(app, "broken.dng", junk);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<EditSource> opened = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                opened.set(EditSource.open(app, u, "broken.dng", "image/x-adobe-dng"));
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "EditSourceTest");
        t.start();
        for (int i = 0; i < 3000 && t.isAlive(); i++) {
            shadowOf(Looper.getMainLooper()).idle(); // Glide may post to the main thread
            t.join(10);
        }
        assertTrue("the open finished", !t.isAlive());
        if (opened.get() != null) {
            opened.get().close();
            fail("a garbage DNG opened");
        }
        assertTrue("open failed with " + thrown.get(), thrown.get() instanceof IOException);
        assertNull(opened.get());
        assertEquals("no cache copy left behind", 0, cacheCopies(app));
    }
}
