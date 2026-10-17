package com.particlesdevs.photoncamera.gallery.ui.fragments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.particlesdevs.photoncamera.gallery.ui.PhotoInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P196: the compare pane's label: EXIF line, resolution · phone line, file name; full info on both panes by default. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ComparePaneLabelTest {
    @Test
    public void threeLinesWithTheRealResolutionAndThePhone() {
        String exif = ImageViewerFragment.joinDot("11:56", "ISO 50", "1/852", "f/2,27", "22,5 мм", "JPEG");
        String file = ImageViewerFragment.fileLine("8192×6144 · 50 Мп", PhotoInfo.device("vivo", "vivo X200 Ultra"));
        assertEquals("8192×6144 · 50 Мп · vivo X200 Ultra", file);
        assertEquals("11:56 · ISO 50 · 1/852 · f/2,27 · 22,5 мм · JPEG\n8192×6144 · 50 Мп · vivo X200 Ultra\nIMG_1.jpg",
                ImageViewerFragment.paneText(exif, file, "IMG_1.jpg").toString());
    }

    @Test
    public void missingPartsLeaveNoEmptyLine() {
        // no EXIF phone (a file from another app without Make / Model): the resolution alone
        assertEquals("4096×3072 · 12,6 Мп", ImageViewerFragment.fileLine("4096×3072 · 12,6 Мп", null));
        assertEquals("", ImageViewerFragment.fileLine(null, null));
        assertEquals("11:55 · ISO 50\nIMG_2.jpg", ImageViewerFragment.paneText("11:55 · ISO 50", "", "IMG_2.jpg").toString());
        assertEquals("11:55 · ISO 50\nIMG_2.jpg", ImageViewerFragment.paneText("11:55 · ISO 50", "IMG_2.jpg").toString());
        assertEquals("8192×6144 · 50 Мп", ImageViewerFragment.paneText("", "8192×6144 · 50 Мп", null).toString());
    }

    @Test
    public void bothPanesStartWithTheFullInfo() {
        // one flag for both panes, on by default (it was per pane and off: the second pane showed «11:55 · ISO 50» only)
        assertTrue(ImageViewerFragment.paneFullExif);
    }
}
