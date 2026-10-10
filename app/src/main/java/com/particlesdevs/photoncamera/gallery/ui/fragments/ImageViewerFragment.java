package com.particlesdevs.photoncamera.gallery.ui.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.Observable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.viewpager.widget.ViewPager;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.adapters.DepthPageTransformer;
import com.particlesdevs.photoncamera.gallery.adapters.ImageAdapter;
import com.particlesdevs.photoncamera.gallery.compare.SSIVListener;
import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.helper.UltraHdrGalleryUtil;
import com.particlesdevs.photoncamera.gallery.model.ExifDialogModel;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.ui.GalleryFormat;
import com.particlesdevs.photoncamera.gallery.ui.GallerySheets;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.gallery.ui.ViewerChrome;
import com.particlesdevs.photoncamera.gallery.viewmodel.ExifDialogViewModel;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;
import com.particlesdevs.photoncamera.gallery.views.CustomSSIV;
import com.particlesdevs.photoncamera.gallery.views.Histogram;
import com.particlesdevs.photoncamera.processing.ImagePath;
import com.particlesdevs.photoncamera.util.Lang;

import org.apache.commons.io.FileUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Created by Vibhor Srivastava on 02-Dec-2020
 *
 * <p>P59b: the viewer in the card style (GALLERY_TASK.md §2): top row (back, «Сегодня, 11:17 / 24 мм · ISO 100 · 1/250»,
 * the [route | format] card), the «HDR вкл / выкл» pill, the filmstrip and button cards; swipe changes the photo
 * (DepthPageTransformer), double tap zooms to 2.5x and back with a «250 %» pill, pinch as before, a tap hides / shows all
 * controls (a mini-EXIF pill while hidden; they are visible again the next time the viewer opens), a swipe down at 1x
 * closes the viewer. In compare mode (a pane of ImageCompareFragment) only the photo, its label card and the zoom pill.
 * The Ultra HDR, colour-mode, edit, share and delete logic is the one before.
 */
public class ImageViewerFragment extends Fragment implements ImageAdapter.HdrStateListener {
    private static final String TAG = ImageViewerFragment.class.getSimpleName();
    private List<GalleryItem> galleryItems = new ArrayList<>(0);
    private Set<String> rawBases = new java.util.HashSet<>();
    private ExifDialogViewModel exifDialogViewModel;
    private ViewPager viewPager;
    private ImageAdapter adapter;
    private NavController navController;
    private FrameLayout root;
    private ViewerChrome chrome;
    private TextView zoomPill, miniExif, paneLabel, paneZoom;
    private LinearLayout overlays;
    private boolean chromeVisible = true, paneFullExif;
    private String mode;
    private int seek_position = 0;
    private int lastHdrPosition = -1;
    private Histogram histogram;
    private Observable.OnPropertyChangedCallback histogramCallback;
    private SSIVListener ssivListener = new SSIVListener() {
        @Override
        public void onScaleChanged(float newScale, int origin) {
            updateScaleText();
        }

        @Override
        public void onCenterChanged(PointF newCenter, int origin) {
        }

        @Override
        public void onTouched(int id) {
        }
    };
    private int indexToDelete = -1;
    private GalleryViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle bundle = getArguments();
        if (bundle != null) {
            mode = bundle.getString(Constants.MODE_KEY);
            seek_position = bundle.getInt(Constants.IMAGE_POSITION_KEY, 0);
        }
        Context c = requireContext();
        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        exifDialogViewModel = new ViewModelProvider(this).get(ExifDialogViewModel.class);
        navController = NavHostFragment.findNavController(this);
        root = new FrameLayout(c);
        root.setBackgroundColor(0xFF000000);
        viewPager = new ViewPager(c);
        viewPager.setId(View.generateViewId());
        root.addView(viewPager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (isCompareMode()) buildPaneOverlay(c);
        else buildChrome(c);
        viewModel.getCurrentFolderImages().observe(getViewLifecycleOwner(), this::initImageAdapter);
        return root;
    }

    private void buildChrome(Context c) {
        chrome = new ViewerChrome(c, new ViewerChrome.Actions() {
            @Override public void back() { onGalleryButtonClick(); }
            @Override public void share() { onShareButtonClick(); }
            @Override public void edit() { onEditButtonClick(); }
            @Override public void compare() { onQuickCompare(); }
            @Override public void info() { onExifButtonClick(); }
            @Override public void delete() { onDeleteButtonClick(); }
            @Override public void hdr() { onHdrToggleClicked(); }
            @Override public void openPosition(int position) { if (viewPager != null) viewPager.setCurrentItem(position, true); }
        });
        root.addView(chrome.top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(chrome.bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        overlays = new LinearLayout(c);
        overlays.setOrientation(LinearLayout.VERTICAL);
        overlays.setGravity(Gravity.CENTER_HORIZONTAL);
        zoomPill = GalleryUi.pill(c, "", true);
        zoomPill.setVisibility(View.GONE);
        miniExif = GalleryUi.pill(c, "", false);
        miniExif.setVisibility(View.GONE);
        overlays.addView(zoomPill);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = GalleryUi.dp(c, 6);
        overlays.addView(miniExif, mp);
        overlays.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(overlays, op);
        chrome.bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeOverlays());
    }

    private void buildPaneOverlay(Context c) {
        paneLabel = GalleryUi.text(c, "", 12, GalleryUi.TEXT);
        paneLabel.setBackground(GalleryUi.round(c, GalleryUi.PILL_BG, GalleryUi.LINE, 12));
        paneLabel.setPadding(GalleryUi.dp(c, 9), GalleryUi.dp(c, 5), GalleryUi.dp(c, 9), GalleryUi.dp(c, 5));
        paneLabel.setCompoundDrawablePadding(GalleryUi.dp(c, 6));
        paneLabel.setMaxLines(2);
        paneLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = lp.topMargin = lp.rightMargin = GalleryUi.dp(c, 10);
        root.addView(paneLabel, lp);
        paneZoom = GalleryUi.pill(c, "", true);
        paneZoom.setVisibility(View.GONE);
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        zp.rightMargin = zp.bottomMargin = GalleryUi.dp(c, 10);
        root.addView(paneZoom, zp);
    }

    private void placeOverlays() {
        if (overlays == null || chrome == null) return;
        FrameLayout.LayoutParams op = (FrameLayout.LayoutParams) overlays.getLayoutParams();
        op.bottomMargin = chromeVisible ? chrome.bottom.getHeight() + GalleryUi.dp(root.getContext(), 10) : GalleryUi.dp(root.getContext(), 16);
        overlays.setLayoutParams(op);
    }

    /**
     * SubsamplingScaleImageView decodes tiles on background threads and reports them (onReady / onScaleChanged) on the main
     * thread after this view may be gone: the pages are detached from every listener and recycled here, and every callback
     * below checks for a destroyed view. A fragment kept on the back stack (compare, settings) must also not keep the pages'
     * tile bitmaps or a full-resolution Ultra HDR bitmap alive.
     */
    @Override
    public void onDestroyView() {
        if (viewPager != null) {
            viewPager.clearOnPageChangeListeners();
            for (int i = 0; i < viewPager.getChildCount(); i++) {
                View child = viewPager.getChildAt(i);
                if (child instanceof SubsamplingScaleImageView) ImageAdapter.detachPage((SubsamplingScaleImageView) child);
            }
        }
        if (adapter != null) {
            adapter.setImageEventListener(null);
            adapter.setSsivListener(null);
            adapter.setImageViewClickListener(null);
            adapter.setHdrStateListener(null);
            adapter.releaseAllHdr(getContext());
        }
        if (histogramCallback != null) exifDialogViewModel.getExifDataModel().removeOnPropertyChangedCallback(histogramCallback);
        if (histogram != null) histogram.setHistogramLoadingListener(null);
        histogramCallback = null;
        histogram = null;
        adapter = null;
        viewPager = null;
        chrome = null;
        root = null;
        lastHdrPosition = -1;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getParentFragmentManager().beginTransaction().remove(ImageViewerFragment.this).commitAllowingStateLoss();
    }

    private void initImageAdapter(List<GalleryItem> galleryItems) {
        if (galleryItems == null) return;
        this.galleryItems = galleryItems;
        rawBases = GalleryFormat.rawBaseNames(galleryItems);
        // View destroyed (late edit/delete result): the LiveData observer rebuilds the adapter with the view.
        if (viewPager == null) return;
        if (galleryItems.isEmpty() && !isCompareMode()) { // nothing to show: the grid says so (its empty message, its folders)
            viewPager.post(this::onGalleryButtonClick);
            return;
        }
        adapter = new ImageAdapter(this.galleryItems);
        adapter.setImageViewClickListener(this::onImageViewClicked);
        adapter.setHdrStateListener(this);
        if (ssivListener != null) adapter.setSsivListener(ssivListener);
        adapter.setImageEventListener(new SubsamplingScaleImageView.DefaultOnImageEventListener() {
            @Override
            public void onReady() {
                CustomSSIV page = getCurrentSSIV();
                if (page != null) {
                    page.setDoubleTapZoomScale(page.getMinScale() * 2.5f);
                    page.setSwipeDownListener(isCompareMode() ? null : ImageViewerFragment.this::onGalleryButtonClick);
                }
                updateScaleText();
            }
        });
        viewPager.setAdapter(adapter);
        if (chrome != null) chrome.filmstrip.setItems(galleryItems);
        int position = Math.max(0, Math.min(seek_position, galleryItems.size() - 1));
        viewPager.setCurrentItem(position);
        final ViewPager pager = viewPager;
        pager.post(() -> {
            if (viewPager != pager) return;
            onPageHdrSelected(position);
            onPageShown(position);
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewPager.setPageTransformer(true, new DepthPageTransformer());
        viewPager.setOffscreenPageLimit(3);
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (root == null) return;
                seek_position = position;
                onPageShown(position);
                onPageHdrSelected(position);
            }
        });
    }

    /** The current photo's meta, badges, filmstrip and zoom. */
    private void onPageShown(int position) {
        updateExif();
        updateScaleText();
        if (chrome != null) chrome.centre(position);
        CustomSSIV page = getSsivAt(position);
        if (page != null && page.isReady()) {
            page.setDoubleTapZoomScale(page.getMinScale() * 2.5f);
            page.setSwipeDownListener(isCompareMode() ? null : this::onGalleryButtonClick);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null && viewPager != null) {
            int position = viewPager.getCurrentItem();
            if (adapter.isHdrActive(position)) {
                UltraHdrGalleryUtil.setWindowHdr(getActivity(), true);
                updateHdrToggleUi(adapter.isHdrAvailable(position), true);
            } else {
                if (!isCompareMode()) {
                    applyWindowColour(position, false);
                    probePageColour(position);
                }
                adapter.loadHdrForPosition(getSsivAt(position), position);
                updateHdrToggleUi(adapter.isHdrAvailable(position), false);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        UltraHdrGalleryUtil.setWindowHdr(getActivity(), false);
        if (adapter != null && viewPager != null) {
            int position = viewPager.getCurrentItem();
            updateHdrToggleUi(adapter.isHdrAvailable(position), false);
        }
    }

    /**
     * Loads the Ultra HDR rendition for the newly selected page and releases the previous page's HDR bitmap so at most one
     * full-resolution HDR bitmap stays in memory. The window colour mode is re-synchronised with the visible page.
     */
    private void onPageHdrSelected(int position) {
        if (adapter == null) return;
        if (lastHdrPosition >= 0 && lastHdrPosition != position) adapter.releaseHdrForPosition(getSsivAt(lastHdrPosition), lastHdrPosition);
        lastHdrPosition = position;
        adapter.loadHdrForPosition(getSsivAt(position), position);
        if (!isCompareMode() && getActivity() != null) {
            applyWindowColour(position, adapter.isHdrActive(position));
            probePageColour(position);
        }
        updateHdrToggleUi(adapter.isHdrAvailable(position), adapter.isHdrActive(position));
    }

    /** P46: the window colour mode of the page (HDR for its Ultra HDR rendition, else by the colour of its file). */
    private void applyWindowColour(int position, boolean hdr) {
        if (getActivity() == null || adapter == null) return;
        UltraHdrGalleryUtil.setWindowMode(getActivity(), hdr, adapter.colourOf(position));
    }

    /** P46: learns the colour of the page's file; a wide-gamut / HLG page switches the window when it is still shown. */
    private void probePageColour(int position) {
        if (adapter == null) return;
        adapter.probeColour(position, getContext(), () -> {
            if (viewPager != null && adapter != null && position == viewPager.getCurrentItem() && !isCompareMode()
                    && !adapter.isHdrActive(position)) applyWindowColour(position, false);
        });
    }

    @Override
    public void onHdrStateChanged(int position, boolean isHdr) {
        if (getActivity() != null && viewPager != null && position == viewPager.getCurrentItem()) {
            applyWindowColour(position, isHdr);
            updateHdrToggleUi(adapter != null && adapter.isHdrAvailable(position), isHdr);
        }
    }

    /**
     * The «HDR» pill: on releases the full-resolution HDR bitmap and restores the tiled SDR source and the default window
     * mode; off loads the gainmap-backed rendition again.
     */
    private void onHdrToggleClicked() {
        if (adapter == null || viewPager == null) return;
        int position = viewPager.getCurrentItem();
        if (!adapter.isHdrAvailable(position)) return;
        if (adapter.isHdrActive(position)) {
            adapter.releaseHdrForPosition(getSsivAt(position), position);
            applyWindowColour(position, false);
            updateHdrToggleUi(true, false);
        } else {
            adapter.loadHdrForPosition(getSsivAt(position), position);
        }
    }

    @Override
    public void onHdrAvailabilityChanged(int position, boolean isUltraHdr) {
        if (viewPager != null && position == viewPager.getCurrentItem()) {
            boolean isHdr = adapter != null && adapter.isHdrActive(position);
            updateHdrToggleUi(isUltraHdr, isHdr);
            updateExif();
        }
    }

    private void updateHdrToggleUi(boolean isUltraHdr, boolean isHdr) {
        if (chrome != null) chrome.bindHdr(isUltraHdr, isHdr);
    }

    public void setSsivListener(SSIVListener ssivListener) {
        this.ssivListener = ssivListener;
    }

    public CustomSSIV getCurrentSSIV() {
        if (viewPager == null) return null;
        return getSsivAt(viewPager.getCurrentItem());
    }

    private CustomSSIV getSsivAt(int position) {
        if (adapter == null || viewPager == null) return null;
        // The pages only (Xiaomi 17 Ultra crash, 2026-10-09): findViewById tests the pager itself first, and its generated id
        // can equal a page id (BASE_ID + position), which returned the ViewPager and failed the cast.
        int id = adapter.getSsivId(position);
        for (int i = 0; i < viewPager.getChildCount(); i++) {
            View child = viewPager.getChildAt(i);
            if (child instanceof CustomSSIV && child.getId() == id) return (CustomSSIV) child;
        }
        return null;
    }

    private void onQuickCompare() {
        if (galleryItems.size() >= 2 && viewPager != null) {
            Bundle b = new Bundle(2);
            int image1pos = viewPager.getCurrentItem();
            int image2pos = image1pos + 1;
            if (image1pos == galleryItems.size() - 1) {
                image2pos = image1pos;
                image1pos -= 1;
            }
            b.putInt(Constants.IMAGE1_KEY, image1pos);
            b.putInt(Constants.IMAGE2_KEY, image2pos);
            navController.navigate(R.id.action_imageViewerFragment_to_imageCompareFragment, b);
        } else if (getContext() != null) {
            Toast.makeText(getContext(), Lang.t("Нет снимка для сравнения", "No photo to compare with"), Toast.LENGTH_SHORT).show();
        }
    }

    /** Back: to the grid (the library when the viewer was the start). */
    private void onGalleryButtonClick() {
        if (navController == null) return;
        if (navController.getPreviousBackStackEntry() == null) {
            Bundle none = new Bundle();
            navController.navigate(R.id.action_imageViewFragment_to_imageLibraryFragment, none);
        } else navController.navigateUp();
    }

    private void onEditButtonClick() {
        if (viewPager == null || galleryItems == null || getContext() == null || galleryItems.isEmpty()) return;
        GalleryItem galleryItem = galleryItems.get(viewPager.getCurrentItem());
        String fileName = galleryItem.getFile().getDisplayName();
        Uri uri = galleryItem.getFile().getFileUri();
        Intent editIntent = new Intent(Intent.ACTION_EDIT);
        editIntent.setDataAndType(uri, mediaTypeOf(fileName));
        String outPutFileUri = uri.toString().replace(fileName, ImagePath.generateNewFileName("IMG") + '.' + FileUtils.getExtension(fileName));
        editIntent.putExtra(MediaStore.EXTRA_OUTPUT, outPutFileUri);
        editIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(editIntent, null), Constants.REQUEST_EDIT_IMAGE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Constants.REQUEST_EDIT_IMAGE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Toast.makeText(getContext(), Lang.t("Сохранено: ", "Saved: ") + data.getData().getPath(), Toast.LENGTH_LONG).show();
            viewModel.fetchAllMedia();
            initImageAdapter(viewModel.getCurrentFolderImages().getValue());
            updateExif();
        }
    }

    private void onDeleteButtonClick() {
        if (viewPager == null || galleryItems.isEmpty() || getContext() == null) return;
        int position = viewPager.getCurrentItem();
        GalleryItem item = galleryItems.get(position);
        GallerySheets.showDelete(requireContext(), 1, item.getFile().getSize(), () -> {
            indexToDelete = position;
            GalleryFileOperations.deleteImageFiles(getActivity(), Collections.singletonList((ImageFile) item.getFile()), this::handleImagesDeletedCallback);
        });
    }

    /** MIME type for share / edit: MimeTypeMap lacks HEIC on older Android versions, the photo formats are mapped here. */
    private static String mediaTypeOf(String fileName) {
        String known = com.particlesdevs.photoncamera.processing.PhotoFormat.mimeForName(fileName);
        if (known != null) return known;
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileUtils.getExtension(fileName));
        return guessed != null ? guessed : "image/*";
    }

    private void onShareButtonClick() {
        if (viewPager == null || galleryItems.isEmpty()) return;
        GalleryItem galleryItem = galleryItems.get(viewPager.getCurrentItem());
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_STREAM, galleryItem.getFile().getFileUri());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(mediaTypeOf(galleryItem.getFile().getDisplayName()));
        startActivity(Intent.createChooser(intent, null));
    }

    /** «Сведения»: the details sheet of the current photo (histogram filled when it is ready). */
    private void onExifButtonClick() {
        if (viewPager == null || galleryItems.isEmpty() || getContext() == null) return;
        int position = viewPager.getCurrentItem();
        GalleryItem item = galleryItems.get(position);
        updateExif();
        Context c = requireContext();
        histogram = new Histogram(c, null);
        ExifDialogModel model = exifDialogViewModel.getExifDataModel();
        if (histogramCallback != null) model.removeOnPropertyChangedCallback(histogramCallback);
        final Histogram view = histogram;
        histogramCallback = new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable sender, int propertyId) {
                if (model.getHistogramModel() != null) view.post(() -> view.setHistogramModel(model.getHistogramModel()));
            }
        };
        model.addOnPropertyChangedCallback(histogramCallback);
        exifDialogViewModel.updateHistogramView((ImageFile) item.getFile());
        GallerySheets.show(c, GallerySheets.details(c, details(item, model, adapter != null && adapter.isHdrAvailable(position)), histogram));
    }

    private GallerySheets.Details details(GalleryItem item, ExifDialogModel m, boolean ultraHdr) {
        GallerySheets.Details d = new GallerySheets.Details();
        d.fileName = item.getFile().getDisplayName();
        d.when = GalleryUi.dayTime(item.getFile().getLastModified(), System.currentTimeMillis());
        d.folder = folderOf(item.getFile().getAbsolutePath());
        d.iso = m.getIsoValue();
        d.shutter = m.getShutterValue();
        d.fnum = m.getFnumValue();
        d.focal = m.getFocalValue();
        d.route = GalleryFormat.routeOf(m.getDescription());
        GalleryFormat f = GalleryFormat.of(item, rawBases);
        d.format = f.label.isEmpty() ? GalleryFormat.extension(d.fileName).toUpperCase(Locale.ROOT) : f.label;
        d.formatIcon = f.icon;
        d.ultraHdr = ultraHdr;
        d.lens = m.getFocal35Value() != null ? m.getFocal35Value() + Lang.t(" экв.", " equiv.") : null;
        d.device = m.getMakeModel();
        d.width = m.getWidth();
        d.height = m.getHeight();
        d.bytes = item.getFile().getSize();
        return d;
    }

    static String folderOf(@Nullable String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "";
        String dir = path.substring(0, slash);
        int prev = dir.lastIndexOf('/');
        return prev >= 0 ? dir.substring(prev + 1) : dir;
    }

    private void onImageViewClicked(View view) {
        if (isCompareMode()) {
            paneFullExif = !paneFullExif;
            updateExif();
            return;
        }
        chromeVisible = !chromeVisible;
        if (chrome != null) {
            chrome.top.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
            chrome.bottom.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        }
        if (miniExif != null) miniExif.setVisibility(chromeVisible ? View.GONE : View.VISIBLE);
        placeOverlays();
    }

    /**
     * The zoom pill relative to the fit scale («250 %»), hidden at 1x. Also reached from SubsamplingScaleImageView callbacks
     * and the compare fragment's posted zoom sync, possibly after onDestroyView: then there is nothing to update.
     */
    public void updateScaleText() {
        if (root == null) return;
        SubsamplingScaleImageView view = getCurrentSSIV();
        TextView pill = isCompareMode() ? paneZoom : zoomPill;
        if (pill == null) return;
        if (view == null || !view.isReady() || view.getMinScale() <= 0) {
            pill.setVisibility(View.GONE);
            return;
        }
        float rel = view.getScale() / view.getMinScale();
        pill.setText(String.format(Locale.ROOT, "%d %%", Math.round(rel * 100)));
        pill.setVisibility(rel > 1.02f ? View.VISIBLE : View.GONE);
    }

    public void resetScaleText() {
        if (zoomPill != null) zoomPill.setVisibility(View.GONE);
        if (paneZoom != null) paneZoom.setVisibility(View.GONE);
    }

    private void updateExif() {
        if (viewPager == null || root == null || getContext() == null) return;
        int position = viewPager.getCurrentItem();
        if (galleryItems.isEmpty() || position < 0 || position >= galleryItems.size()) return;
        GalleryItem item = galleryItems.get(position);
        exifDialogViewModel.updateModel(requireContext().getContentResolver(), item.getFile());
        ExifDialogModel m = exifDialogViewModel.getExifDataModel();
        GalleryFormat format = GalleryFormat.of(item, rawBases);
        boolean uhdr = adapter != null && adapter.isHdrAvailable(position);
        String route = GalleryFormat.routeOf(m.getDescription());
        String when = GalleryUi.dayTime(item.getFile().getLastModified(), System.currentTimeMillis());
        String lens = m.getFocal35Value() != null ? m.getFocal35Value() : m.getFocalValue();
        String sub = joinDot(lens, m.getIsoValue(), m.getShutterValue());
        if (chrome != null) chrome.bindMeta(when, sub, route, format, uhdr);
        if (miniExif != null) miniExif.setText(joinDot(m.getShutterValue(), m.getIsoValue(), m.getFnumValue(), m.getFocalValue()));
        if (paneLabel != null) bindPaneLabel(item, m, format, route, when);
    }

    private void bindPaneLabel(GalleryItem item, ExifDialogModel m, GalleryFormat format, @Nullable String route, String when) {
        Context c = paneLabel.getContext();
        String time = when.substring(Math.max(0, when.lastIndexOf(' ') + 1));
        String text = paneFullExif ? joinDot(time, m.getIsoValue(), m.getShutterValue(), m.getFnumValue(), m.getFocalValue(), format.label)
                : joinDot(time, m.getIsoValue());
        // P65: the file name on a second, smaller and muted line (which two shots are compared)
        String name = item.getFile().getDisplayName();
        paneLabel.setText(paneText(text, name));
        android.graphics.drawable.Drawable routeD = route != null ? c.getDrawable(GalleryFormat.routeIcon(route)) : null;
        android.graphics.drawable.Drawable fmt = c.getDrawable(format.icon);
        int s = GalleryUi.dp(c, 15), accent = GalleryUi.accent(c);
        android.graphics.drawable.Drawable icons = combine(c, routeD, fmt, s, accent);
        paneLabel.setCompoundDrawables(icons, null, null, null);
        paneLabel.setContentDescription((route != null ? route + ", " : "") + format.label + ", " + text
                + (name == null || name.isEmpty() ? "" : ", " + name));
    }

    /** The label's text: the EXIF line, then the file name (85 %, muted) when there is one. */
    static CharSequence paneText(String text, @Nullable String name) {
        if (name == null || name.isEmpty()) return text;
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(text);
        if (s.length() > 0) s.append('\n');
        int start = s.length();
        s.append(name);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.ForegroundColorSpan(GalleryUi.MUTED), start, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private static android.graphics.drawable.Drawable combine(Context c, @Nullable android.graphics.drawable.Drawable a,
                                                              @Nullable android.graphics.drawable.Drawable b, int s, int tint) {
        List<android.graphics.drawable.Drawable> list = new ArrayList<>();
        for (android.graphics.drawable.Drawable d : new android.graphics.drawable.Drawable[]{a, b}) {
            if (d == null) continue;
            d = d.mutate();
            d.setTint(tint);
            list.add(d);
        }
        if (list.isEmpty()) return null;
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(list.toArray(new android.graphics.drawable.Drawable[0]));
        int gap = GalleryUi.dp(c, 4);
        for (int i = 0; i < list.size(); ++i) {
            l.setLayerSize(i, s, s);
            l.setLayerInsetLeft(i, i * (s + gap));
        }
        l.setBounds(0, 0, list.size() * s + (list.size() - 1) * gap, s);
        return l;
    }

    static String joinDot(String... parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (s.length() > 0) s.append(" · ");
            s.append(p);
        }
        return s.toString();
    }

    private boolean isCompareMode() {
        return mode != null && mode.equalsIgnoreCase(Constants.COMPARE);
    }

    public void handleImagesDeletedCallback(boolean isDeleted) {
        if (isDeleted && indexToDelete >= 0 && indexToDelete < galleryItems.size()) {
            galleryItems.remove(indexToDelete);
            seek_position = Math.min(indexToDelete, Math.max(0, galleryItems.size() - 1));
            if (!galleryItems.isEmpty()) initImageAdapter(galleryItems);
            if (getContext() != null) Toast.makeText(getContext(), Lang.t("Удалено: ", "Deleted: ") + GalleryUi.shots(1), Toast.LENGTH_SHORT).show();
            indexToDelete = -1;
            if (galleryItems.isEmpty()) {
                viewModel.setUpdatePending(true);
                if (root != null && navController != null) navController.navigateUp();
            } else viewModel.setUpdatePending(true);
        } else if (getContext() != null) {
            Toast.makeText(getContext(), Lang.t("Не удалось удалить", "Deletion failed"), Toast.LENGTH_SHORT).show();
        }
    }
}
