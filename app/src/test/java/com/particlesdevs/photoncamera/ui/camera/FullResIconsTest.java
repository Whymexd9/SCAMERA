package com.particlesdevs.photoncamera.ui.camera;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import com.particlesdevs.photoncamera.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P179: the telephoto's two FULLRES buttons - the 200 MP icon is the 50 MP frame with twice as fine a grid, so it reads as
 * the higher resolution: more of its plot is pixel edges. Renders build/reports/viewfinder/fullres-tele-icons.png.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FullResIconsTest {
    @Test
    public void the200IconHasTheFinerGrid() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        int size = 192;
        Bitmap fifty = draw(c, R.drawable.manual_ic_fullres, size), twoHundred = draw(c, R.drawable.manual_ic_fullres_200, size);
        int edges50 = edges(fifty), edges200 = edges(twoHundred);
        assertTrue("finer grid: " + edges50 + " vs " + edges200, edges200 > edges50 * 3 / 2);
        Bitmap sheet = Bitmap.createBitmap(size * 2 + 48, size + 32, Bitmap.Config.ARGB_8888);
        sheet.eraseColor(0xFF1B1F23);
        Canvas canvas = new Canvas(sheet);
        canvas.drawBitmap(fifty, 16, 16, null);
        canvas.drawBitmap(twoHundred, size + 32, 16, null);
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "fullres-tele-icons.png"))) {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static Bitmap draw(Context c, int id, int size) {
        Drawable d = c.getDrawable(id);
        assertNotNull(d);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.setTint(0xFFB4C5FF);
        d.draw(new Canvas(b));
        return b;
    }

    /** Lit / dark changes along the rows of the grid area: the finer the grid, the more. */
    private static int edges(Bitmap b) {
        int n = 0, w = b.getWidth();
        for (int y = w * 8 / 24; y < w * 16 / 24; y++) {
            boolean last = false;
            for (int x = w * 8 / 24; x < w * 16 / 24; x++) {
                boolean lit = (b.getPixel(x, y) >>> 24) > 128;
                if (lit != last) n++;
                last = lit;
            }
        }
        return n;
    }
}
