package com.particlesdevs.photoncamera.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** P59b: the gallery's card-style pieces on a 360dp phone in Russian (GALLERY_TASK.md §6). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryUiTest {
    private MockedStatic<PhotonCamera> camera;
    private ActivityController<AppCompatActivity> controller;
    private Context context;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity activity = controller.get();
        activity.setTheme(R.style.Theme_Photon_SettingsActivity);
        controller.setup();
        context = activity;
        SettingsManager manager = new SettingsManager(context);
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> PhotonCamera.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        camera.when(PhotonCamera::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        LibraryAdapter.loadPictures = false;
    }

    @After
    public void tearDown() {
        LibraryAdapter.loadPictures = true;
        camera.close();
        controller.destroy();
    }

    private int dp(float v) {
        return GalleryUi.dp(context, v);
    }

    @Test
    public void selectionCountUsesRussianPlurals() {
        assertEquals("1 снимок", GalleryUi.shots(1));
        assertEquals("2 снимка", GalleryUi.shots(2));
        assertEquals("5 снимков", GalleryUi.shots(5));
        assertEquals("11 снимков", GalleryUi.shots(11));
        assertEquals("21 снимок", GalleryUi.shots(21));
        assertEquals("Удалить 3 снимка?", GallerySheets.deleteTitle(3));
        assertEquals("Удалить снимок?", GallerySheets.deleteTitle(1));
    }

    @Test
    public void compareIsEnabledOnlyWithExactlyTwo() {
        SelectionBar bar = new SelectionBar(context, () -> {}, () -> {}, () -> {}, () -> {});
        for (int n : new int[]{1, 2, 3, 5}) {
            bar.bind(n);
            assertEquals("compare at " + n, n == 2, bar.compare.isEnabled());
            assertTrue(bar.share.isEnabled());
            assertTrue(bar.delete.isEnabled());
            assertEquals(GalleryUi.shots(n), bar.count.getText().toString());
        }
    }

    private static GalleryItem item(long id, String name, Uri uri) {
        return new GalleryItem(new ImageFile(id, uri, name, 1_790_000_000_000L - id * 1000, 1000, "/storage/emulated/0/DCIM/Camera/" + name));
    }

    private static List<Integer> badges(RecyclerView.ViewHolder h) {
        LibraryAdapter.CellHolder cell = (LibraryAdapter.CellHolder) h;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < cell.badges.getChildCount(); ++i) out.add((Integer) cell.badges.getChildAt(i).getTag());
        return out;
    }

    @Test
    public void gridCellShowsFormatAndUltraHdrBadges() {
        Uri uhdr = Uri.parse("content://media/external/images/media/4");
        LibraryAdapter.knownUltraHdr(uhdr, true);
        List<GalleryItem> items = Arrays.asList(item(1, "IMG_1.jpg", null), item(2, "IMG_1.dng", null),
                item(3, "IMG_3.heic", null), item(4, "IMG_4.jpg", uhdr));
        LibraryAdapter adapter = new LibraryAdapter(new LibraryAdapter.Host() {
            @Override public boolean selecting() { return false; }
            @Override public boolean isSelected(GalleryItem item) { return false; }
            @Override public void onPhotoClicked(int itemIndex, View view) {}
        });
        adapter.setItems(new ArrayList<>(items));
        RecyclerView parent = new RecyclerView(context);
        int[] expected = {R.drawable.ic_shade_rawjpeg, R.drawable.ic_shade_raw, R.drawable.ic_shade_heic, R.drawable.ic_shade_jpeg};
        for (int i = 0; i < items.size(); ++i) {
            int pos = adapter.positionOf(i);
            RecyclerView.ViewHolder h = adapter.onCreateViewHolder(parent, LibraryAdapter.PHOTO);
            adapter.onBindViewHolder(h, pos);
            List<Integer> b = badges(h);
            assertEquals(items.get(i).getFile().getDisplayName(), (Integer) expected[i], b.get(0));
            assertEquals("Ultra HDR badge of " + items.get(i).getFile().getDisplayName(), i == 3, b.contains(R.drawable.ic_gallery_uhdr));
            assertTrue(h.itemView.getContentDescription().toString().length() > 0);
        }
        // the day header carries the count
        RecyclerView.ViewHolder header = adapter.onCreateViewHolder(parent, LibraryAdapter.HEADER);
        adapter.onBindViewHolder(header, 0);
        assertTrue(header.itemView.getContentDescription().toString().contains("фото"));
    }

    @Test
    public void detailsHideTheMergeRowWithoutRouteInfo() {
        GallerySheets.Details d = details();
        d.route = null;
        LinearLayout sheet = GallerySheets.details(context, d, null);
        assertNull(sheet.findViewWithTag("route"));
        assertNotNull(sheet.findViewWithTag("format"));
        d.route = GalleryFormat.routeOf("parameters:\n Version=1\n Route=Hybrid");
        assertEquals("Hybrid", d.route);
        assertNotNull(GallerySheets.details(context, d, null).findViewWithTag("route"));
        assertNull(GalleryFormat.routeOf("parameters:\n Version=1"));
    }

    private static GallerySheets.Details details() {
        GallerySheets.Details d = new GallerySheets.Details();
        d.fileName = "IMG_20261008_111700_0123456789.jpg";
        d.when = "Сегодня, 11:17";
        d.folder = "Camera";
        d.iso = "ISO 12800";
        d.shutter = "1/1000";
        d.fnum = "f/1,85";
        d.focal = "6,7 мм";
        d.route = "SCAM HDR";
        d.format = "RAW + JPEG";
        d.ultraHdr = true;
        d.lens = "230 мм экв.";
        d.device = "Xiaomi 25010PN30G";
        d.width = 8160;
        d.height = 6144;
        d.bytes = 34_567_890;
        return d;
    }

    /** Lays the view out at {@code widthDp} and checks that no text is cut, ellipsized or wider than its view. */
    private void assertNoOverflow(View view, int widthDp) {
        int w = dp(widthDp);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, w, view.getMeasuredHeight());
        check(view, w);
    }

    private void check(View v, int rootWidth) {
        int[] at = new int[2];
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Layout l = t.getLayout();
            CharSequence text = t.getText();
            if (l != null && text.length() > 0) {
                int avail = t.getWidth() - t.getTotalPaddingLeft() - t.getTotalPaddingRight();
                for (int i = 0; i < l.getLineCount(); ++i) {
                    assertTrue("\"" + text + "\" line " + i + " " + l.getLineWidth(i) + " px > " + avail, l.getLineWidth(i) <= avail + 1);
                    assertEquals("\"" + text + "\" ellipsized", 0, l.getEllipsisCount(i));
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); ++i) {
                View c = g.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                assertTrue(c.getClass().getSimpleName() + " right " + c.getRight() + " > " + g.getWidth(), c.getRight() <= g.getWidth() + 1);
                assertTrue(c.getClass().getSimpleName() + " left " + c.getLeft(), c.getLeft() >= -1);
                check(c, rootWidth);
            }
        }
    }

    @Test
    public void layoutsFitAt360dp() {
        SelectionBar bar = new SelectionBar(context, () -> {}, () -> {}, () -> {}, () -> {});
        bar.bind(11);
        assertNoOverflow(bar.view, 360 - 24);
        ViewerChrome chrome = new ViewerChrome(context, new ViewerChrome.Actions() {
            @Override public void back() {}
            @Override public void share() {}
            @Override public void edit() {}
            @Override public void compare() {}
            @Override public void info() {}
            @Override public void delete() {}
            @Override public void hdr() {}
            @Override public void openPosition(int position) {}
        });
        chrome.bindMeta("6 октября 2025, 23:59", "230 мм · ISO 12800 · 1/1000", "SCAM HDR", GalleryFormat.RAW_JPEG, true);
        chrome.bindHdr(true, false);
        FrameLayout host = new FrameLayout(context);
        host.addView(chrome.top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        assertNoOverflow(host, 360);
        assertNoOverflow(chrome.buttons, 360 - 24);
        assertNoOverflow(GallerySheets.details(context, details(), null), 360);
        assertNoOverflow(GallerySheets.delete(context, 21, 123_456_789L, () -> {}, () -> {}), 360);
    }

    @Test
    public void dayLabelsInRussian() {
        long now = 1_790_000_000_000L; // 2026-09-21
        assertEquals("Сегодня", GalleryUi.dayLabel(now, now));
        assertEquals("Вчера", GalleryUi.dayLabel(now - 24L * 3600 * 1000, now));
        assertFalse(GalleryUi.dayLabel(now - 40L * 24 * 3600 * 1000, now).isEmpty());
        assertEquals("5 фото", GalleryUi.dayCount(5));
    }

    /** Renders the screens into build/reports/gallery for review (no assertions beyond drawing). */
    @Test
    public void renderForReview() throws Exception {
        int w = dp(360);
        // library: header, chips, day sections, cells (painted placeholders), selection bar
        FrameLayout lib = new FrameLayout(context);
        lib.setBackgroundColor(GalleryUi.BG);
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        lib.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(GalleryUi.header(context, "Галерея", GalleryUi.squareButton(context, R.drawable.ic_gallery_camera, "К камере", v -> {}),
                GalleryUi.squareButton(context, R.drawable.ic_gallery_folders, "Папки", v -> {})));
        LinearLayout chips = new LinearLayout(context);
        chips.setPadding(dp(16), dp(4), dp(16), dp(10));
        chips.addView(GalleryUi.chip(context, "Все", 128, true));
        TextView c2 = GalleryUi.chip(context, "Camera", 96, false);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = dp(8);
        chips.addView(c2, cl);
        col.addView(chips);
        final List<GalleryItem> items = new ArrayList<>();
        long now = System.currentTimeMillis();
        String[] names = {"A.jpg", "A.dng", "B.heic", "C.webp", "D.jpg", "E.avif", "F.jpg"};
        for (int i = 0; i < names.length; ++i)
            items.add(new GalleryItem(new ImageFile(i + 1, null, names[i], now - (i < 4 ? 0 : 26L * 3600 * 1000), 1000, "/x/" + names[i])));
        final boolean[] sel = {true};
        LibraryAdapter adapter = new LibraryAdapter(new LibraryAdapter.Host() {
            @Override public boolean selecting() { return sel[0]; }
            @Override public boolean isSelected(GalleryItem item) { return item == items.get(1) || item == items.get(2); }
            @Override public void onPhotoClicked(int itemIndex, View view) {}
        });
        RecyclerView grid = new RecyclerView(context);
        androidx.recyclerview.widget.GridLayoutManager glm = new androidx.recyclerview.widget.GridLayoutManager(context, 3);
        glm.setSpanSizeLookup(adapter.spans(3));
        grid.setLayoutManager(glm);
        grid.setPadding(dp(9), 0, dp(9), 0);
        grid.setAdapter(adapter);
        adapter.setItems(items);
        col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        SelectionBar bar = new SelectionBar(context, () -> {}, () -> {}, () -> {}, () -> {});
        bar.bind(2);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM);
        bp.leftMargin = bp.rightMargin = dp(12);
        bp.bottomMargin = dp(14);
        lib.addView(bar.view, bp);
        render(lib, w, dp(640), "library", grid);
        // viewer chrome over a grey "photo"
        FrameLayout viewer = new FrameLayout(context);
        viewer.setBackgroundColor(0xFF000000);
        View photo = new View(context);
        photo.setBackgroundColor(0xFF4A5A6A);
        viewer.addView(photo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270), android.view.Gravity.CENTER));
        ViewerChrome chrome = new ViewerChrome(context, new ViewerChrome.Actions() {
            @Override public void back() {}
            @Override public void share() {}
            @Override public void edit() {}
            @Override public void compare() {}
            @Override public void info() {}
            @Override public void delete() {}
            @Override public void hdr() {}
            @Override public void openPosition(int position) {}
        });
        chrome.bindMeta("Сегодня, 11:17", "24 мм · ISO 100 · 1/250", "Hybrid", GalleryFormat.RAW_JPEG, true);
        chrome.bindHdr(true, true);
        chrome.filmstrip.setItems(items);
        viewer.addView(chrome.top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP));
        viewer.addView(chrome.bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM));
        chrome.filmstrip.setCurrent(2);
        render(viewer, w, dp(640), "viewer", chrome.strip);
        render(GallerySheets.details(context, details(), null), w, 0, "details", null);
        render(GallerySheets.delete(context, 3, 9_400_000L, () -> {}, () -> {}), w, 0, "delete", null);
        List<GallerySheets.Folder> folders = Arrays.asList(new GallerySheets.Folder("1", "Camera", "DCIM/Camera", true),
                new GallerySheets.Folder("2", "Screenshots", "Pictures/Screenshots", false));
        render(GallerySheets.folders(context, true, folders, (f, on) -> {}), w, 0, "folders", null);
    }

    private void render(View v, int w, int h, String name, RecyclerView paintCells) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                h > 0 ? View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY) : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, w, v.getMeasuredHeight());
        if (paintCells != null) {
            int[] colours = {0xFF6B8E9B, 0xFF9B6B5E, 0xFF5E7B6B, 0xFF8B7B5E, 0xFF6B5E8B, 0xFF5E8B8B, 0xFF8B5E6B};
            for (int i = 0; i < paintCells.getChildCount(); ++i) {
                View child = paintCells.getChildAt(i);
                RecyclerView.ViewHolder hold = paintCells.getChildViewHolder(child);
                android.widget.ImageView img = hold instanceof LibraryAdapter.CellHolder ? ((LibraryAdapter.CellHolder) hold).image
                        : child instanceof FrameLayout && ((FrameLayout) child).getChildAt(0) instanceof android.widget.ImageView
                        ? (android.widget.ImageView) ((FrameLayout) child).getChildAt(0) : null;
                if (img != null) img.setImageDrawable(new android.graphics.drawable.ColorDrawable(colours[i % colours.length]));
            }
        }
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, Math.max(1, v.getMeasuredHeight()), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(GalleryUi.BG);
        v.draw(canvas);
        java.io.File dir = new java.io.File("build/reports/gallery");
        dir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name + ".png"))) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
