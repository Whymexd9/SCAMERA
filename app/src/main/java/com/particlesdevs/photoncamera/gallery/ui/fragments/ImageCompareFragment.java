package com.particlesdevs.photoncamera.gallery.ui.fragments;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.navigation.fragment.NavHostFragment;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.compare.SSIVListener;
import com.particlesdevs.photoncamera.gallery.compare.ScaleAndPan;
import com.particlesdevs.photoncamera.gallery.ui.GallerySheets;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.util.Lang;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.Observable;

import static com.particlesdevs.photoncamera.gallery.helper.Constants.*;

/**
 * Created by Vibhor Srivastava on 09-Jan-2021
 *
 * <p>P59b: compare in the card style (GALLERY_TASK.md §5): header «Сравнение» with back and swap, two panes (stacked;
 * side by side in landscape) each with its label card (route + format icons, time, ISO; a tap shows the full EXIF) and zoom
 * pill, the «Зум и сдвиг вместе» switch card (both panes follow each other) and the share card button (the screenshot of
 * both panes, as before). Back returns to where compare was opened from.
 */
public class ImageCompareFragment extends Fragment {
    private static boolean toSync = true;
    private final SSIVListenerImpl ssivListener = new SSIVListenerImpl();
    private ImageViewerFragment fragment1 = new ImageViewerFragment();
    private ImageViewerFragment fragment2 = new ImageViewerFragment();
    private int pos1, pos2;
    private File imagesDir;
    private Context mContext;
    private View root, header, bar, panes;
    private int pane1Id, pane2Id;
    private final Handler shareHandler = new Handler(Looper.getMainLooper(), msg -> {
        if (msg.obj instanceof Uri) shareUri((Uri) msg.obj);
        hideButtons(false);
        return true;
    });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        this.mContext = getContext();
        this.imagesDir = new File(mContext.getCacheDir(), "images");
        this.imagesDir.mkdirs();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context c = requireContext();
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(0xFF000000);
        View back = GalleryUi.squareButton(c, R.drawable.ic_gallery_back, Lang.t("Назад", "Back"), v -> NavHostFragment.findNavController(this).navigateUp());
        View swap = GalleryUi.squareButton(c, R.drawable.ic_gallery_swap, Lang.t("Поменять местами", "Swap"), v -> swap());
        header = GalleryUi.header(c, Lang.t("Сравнение", "Compare"), back, swap);
        header.setBackgroundColor(GalleryUi.BG);
        column.addView(header);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        FrameLayout a = new FrameLayout(c), b = new FrameLayout(c);
        pane1Id = View.generateViewId();
        pane2Id = View.generateViewId();
        a.setId(pane1Id);
        b.setId(pane2Id);
        LinearLayout.LayoutParams ap = landscape ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        LinearLayout.LayoutParams bp = landscape ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        if (landscape) bp.leftMargin = GalleryUi.dp(c, 4);
        else bp.topMargin = GalleryUi.dp(c, 4);
        p.addView(a, ap);
        p.addView(b, bp);
        panes = p;
        column.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout cbar = new LinearLayout(c);
        cbar.setOrientation(LinearLayout.HORIZONTAL);
        cbar.setGravity(Gravity.CENTER_VERTICAL);
        cbar.setBackgroundColor(GalleryUi.BG);
        cbar.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 8), GalleryUi.dp(c, 12), GalleryUi.dp(c, 12));
        cbar.addView(syncCard(c), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View share = GalleryUi.squareButton(c, R.drawable.ic_gallery_share, Lang.t("Поделиться сравнением", "Share the comparison"), v -> onShareClick());
        share.setBackground(GalleryUi.pressable(GalleryUi.card(c, GalleryUi.CARD, 18)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 48), GalleryUi.dp(c, 48));
        sp.leftMargin = GalleryUi.dp(c, 8);
        cbar.addView(share, sp);
        bar = cbar;
        column.addView(cbar);
        root = column;
        return column;
    }

    /** «Зум и сдвиг вместе»: a switch card (replaces the old sync toggle). */
    private View syncCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(GalleryUi.dp(c, 48));
        card.setBackground(GalleryUi.pressable(GalleryUi.card(c, GalleryUi.CARD, 18)));
        card.setPadding(GalleryUi.dp(c, 14), GalleryUi.dp(c, 10), GalleryUi.dp(c, 14), GalleryUi.dp(c, 10));
        card.addView(GalleryUi.icon(c, R.drawable.ic_gallery_link, GalleryUi.accent(c), 22));
        TextView t = GalleryUi.fitText(c, Lang.t("Зум и сдвиг вместе", "Zoom and pan together"), 14, GalleryUi.TEXT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = tp.rightMargin = GalleryUi.dp(c, 10);
        card.addView(t, tp);
        View sw = GallerySheets.toggleView(c, toSync);
        card.addView(sw);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(Lang.t("Зум и сдвиг вместе", "Zoom and pan together"));
        card.setStateDescription(toSync ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
        card.setOnClickListener(v -> {
            toSync = !toSync;
            GallerySheets.setToggle(sw, toSync);
            card.setStateDescription(toSync ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
        });
        return card;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle b = getArguments();
        if (b != null) {
            toSync = true;
            pos1 = b.getInt(IMAGE1_KEY);
            pos2 = b.getInt(IMAGE2_KEY);
            attach(pos1, pos2);
        }
    }

    private void attach(int first, int second) {
        FragmentTransaction trans = getChildFragmentManager().beginTransaction();
        if (fragment1.isAdded()) trans.remove(fragment1);
        if (fragment2.isAdded()) trans.remove(fragment2);
        fragment1 = pane(first);
        fragment2 = pane(second);
        trans.add(pane1Id, fragment1, "image_container1");
        trans.add(pane2Id, fragment2, "image_container2");
        trans.commitAllowingStateLoss();
    }

    private ImageViewerFragment pane(int position) {
        ImageViewerFragment f = new ImageViewerFragment();
        Bundle b = new Bundle();
        b.putString(MODE_KEY, COMPARE);
        b.putInt(IMAGE_POSITION_KEY, position);
        f.setArguments(b);
        f.setSsivListener(ssivListener);
        return f;
    }

    /** Swaps the two photos between the panes. */
    private void swap() {
        int t = pos1;
        pos1 = pos2;
        pos2 = t;
        attach(pos1, pos2);
    }

    private void onShareClick() {
        if (root == null || panes == null) return;
        hideButtons(true);
        final View shotView = panes;
        HandlerThread bmpThread = new HandlerThread("ScreenshotThread", Process.THREAD_PRIORITY_BACKGROUND);
        bmpThread.start();
        new Handler(bmpThread.getLooper()).post(() -> {
            Bitmap shot = screenShot(shotView);
            Uri uri = shot != null ? saveBitmap(shot) : null;
            if (shot != null) shot.recycle();
            shareHandler.obtainMessage(0, uri).sendToTarget();
        });
        bmpThread.quitSafely();
    }

    private void shareUri(Uri uri) {
        if (!isAdded()) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(URLConnection.guessContentTypeFromName(uri.toString()));
        intent.setClipData(ClipData.newUri(mContext.getContentResolver(), "", uri));
        startActivity(Intent.createChooser(intent, null));
    }

    private void hideButtons(boolean toHide) {
        if (header != null) header.setVisibility(toHide ? View.INVISIBLE : View.VISIBLE);
        if (bar != null) bar.setVisibility(toHide ? View.INVISIBLE : View.VISIBLE);
    }

    private Uri saveBitmap(Bitmap bitmap) {
        Uri uri = null;
        try {
            File file = new File(imagesDir, "compare_screenshot.jpg");
            OutputStream stream = Files.newOutputStream(file.toPath());
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
            stream.flush();
            stream.close();
            uri = FileProvider.getUriForFile(mContext, mContext.getPackageName() + ".provider", file);
        } catch (IOException | NullPointerException | IllegalArgumentException e) {
            e.printStackTrace();
        }
        if (uri == null) {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(mContext, Lang.t("Не удалось", "Failed"), Toast.LENGTH_SHORT).show());
        }
        return uri;
    }

    private Bitmap screenShot(View view) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    @Override
    public void onDestroyView() {
        root = header = bar = panes = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getParentFragmentManager().beginTransaction().remove(fragment1).remove(fragment2).commitAllowingStateLoss();
    }

    private class SSIVListenerImpl extends SSIVListener {
        private final ScaleAndPan scaleAndPan = new ScaleAndPan();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private int idTouched = 0;

        SSIVListenerImpl() {
            scaleAndPan.addObserver(this::update);
        }

        @Override
        public void onScaleChanged(float newScale, int origin) {
            scaleAndPan.setOrigin(origin);
            scaleAndPan.setScale(newScale);
        }

        @Override
        public void onCenterChanged(PointF newCenter, int origin) {
            scaleAndPan.setOrigin(origin);
            scaleAndPan.setCenter(newCenter);
        }

        @Override
        public void onTouched(int id) {
            idTouched = id;
        }

        public void update(Observable o, Object arg) {
            // Posted: either pane (or this fragment) may be destroyed when it runs; both helpers tolerate that.
            mainHandler.post(() -> {
                if (toSync) copyZoomPan(fragment1.getCurrentSSIV(), fragment2.getCurrentSSIV(), (ScaleAndPan) o);
                fragment1.updateScaleText();
                fragment2.updateScaleText();
            });
        }

        private void copyZoomPan(SubsamplingScaleImageView v1, SubsamplingScaleImageView v2, ScaleAndPan scaleAndPan) {
            if (v1 == null || v2 == null) return;
            if (v1.getId() == idTouched) v2.setScaleAndCenter(scaleAndPan.getScale(), scaleAndPan.getCenter());
            if (v2.getId() == idTouched) v1.setScaleAndCenter(scaleAndPan.getScale(), scaleAndPan.getCenter());
        }
    }
}
