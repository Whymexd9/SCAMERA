package com.particlesdevs.photoncamera.gallery.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.signature.ObjectKey;
import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.particlesdevs.photoncamera.gallery.compare.SSIVListener;
import com.particlesdevs.photoncamera.gallery.helper.UltraHdrGalleryUtil;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.views.CustomSSIV;
import com.particlesdevs.photoncamera.processing.PhotoFormat;

import org.apache.commons.io.FileUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class ImageAdapter extends PagerAdapter {
    private static final int BASE_ID = View.generateViewId();
    private static final ExecutorService HDR_EXECUTOR = Executors.newSingleThreadExecutor();
    private final List<GalleryItem> galleryItemList;
    private final boolean[] hdrRequested;
    private final boolean[] hdrActive;
    private final boolean[] hdrAvailable;
    private final Bitmap[] hdrBitmaps;
    private final Target<Bitmap>[] hdrTargets;
    private ImageViewClickListener imageViewClickListener;
    private SSIVListener ssivListener;
    private SubsamplingScaleImageView.OnImageEventListener imageEventListener;
    private HdrStateListener hdrStateListener;
    /** Set once the host view is gone: late HDR callbacks must not touch the pages any more. */
    private boolean released;


    public ImageAdapter(List<GalleryItem> galleryItemList) {
        this.galleryItemList = galleryItemList;
        int size = galleryItemList.size();
        this.hdrRequested = new boolean[size];
        this.hdrActive = new boolean[size];
        this.hdrAvailable = new boolean[size];
        this.hdrBitmaps = new Bitmap[size];
        this.hdrTargets = new Target[size];
    }

    public void setSsivListener(SSIVListener ssivListener) {
        this.ssivListener = ssivListener;
    }

    public void setImageEventListener(SubsamplingScaleImageView.OnImageEventListener imageEventListener) {
        this.imageEventListener = imageEventListener;
    }

    public void setHdrStateListener(HdrStateListener hdrStateListener) {
        this.hdrStateListener = hdrStateListener;
    }

    @Override
    public int getCount() {
        return galleryItemList.size();
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return view == object;
    }


    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        GalleryItem galleryItem = galleryItemList.get(position);
        String fileExt = FileUtils.getExtension(galleryItem.getFile().getDisplayName());

        CustomSSIV scaleImageView = new CustomSSIV(container.getContext());
        scaleImageView.setId(getSsivId(position));
        if (imageViewClickListener != null) {
            scaleImageView.setOnClickListener(v -> imageViewClickListener.onImageViewClicked(v));
        }
        if (ssivListener != null) {
            scaleImageView.setOnStateChangedListener(ssivListener);
            scaleImageView.setTouchCallBack(ssivListener);
        }
        String fileName = galleryItem.getFile().getDisplayName();
        if (PhotoFormat.isModernPhoto(fileName)) {
            // HEIC / WebP / AVIF: the tiled decoder (BitmapRegionDecoder) reads them on the versions that decode them (HEIC
            // from Android 9, AVIF from 12; below it the page stays empty); if it still fails, the page shows a bitmap
            // decoded by Glide.
            scaleImageView.setOnImageEventListener(new BitmapFallback(imageEventListener, scaleImageView, galleryItem));
            if (PhotoFormat.decodable(fileName, Build.VERSION.SDK_INT)) {
                scaleImageView.setImage(ImageSource.uri(galleryItem.getFile().getFileUri()));
            }
        } else if (!fileExt.equalsIgnoreCase("dng")) {
            scaleImageView.setOnImageEventListener(imageEventListener);
            scaleImageView.setImage(ImageSource.uri(galleryItem.getFile().getFileUri()));
        } else { //For DNG Files, load as a bitmap
            scaleImageView.setOnImageEventListener(imageEventListener);
            Glide.with(container.getContext())
                    .asBitmap()
                    .load(galleryItem.getFile().getFileUri())
                    .apply(RequestOptions.signatureOf(new ObjectKey(galleryItem.getFile().getDisplayName() + galleryItem.getFile().getLastModified())))
                    .into(new CustomViewTarget<SubsamplingScaleImageView, Bitmap>(scaleImageView) {
                        @Override
                        public void onResourceReady(@NonNull Bitmap bitmap, Transition<? super Bitmap> transition) {
                            scaleImageView.setImage(ImageSource.cachedBitmap(bitmap));
                        }

                        @Override
                        protected void onResourceCleared(@Nullable Drawable placeholder) {

                        }

                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {

                        }
                    });
        }
        container.addView(scaleImageView);
        return scaleImageView;
    }

    /**
     * Passes every image event of a HEIC / WebP / AVIF page on to the host's listener; when the tiled decode fails, the
     * page falls back to one bitmap decoded by Glide (which reads HEIC on Android 9+ and AVIF on 12+ through the platform
     * decoder).
     */
    private final class BitmapFallback implements SubsamplingScaleImageView.OnImageEventListener {
        private final SubsamplingScaleImageView.OnImageEventListener host;
        private final SubsamplingScaleImageView page;
        private final GalleryItem item;
        private boolean fellBack;

        BitmapFallback(SubsamplingScaleImageView.OnImageEventListener host, SubsamplingScaleImageView page, GalleryItem item) {
            this.host = host;
            this.page = page;
            this.item = item;
        }

        @Override public void onReady() { if (host != null) host.onReady(); }
        @Override public void onImageLoaded() { if (host != null) host.onImageLoaded(); }
        @Override public void onPreviewLoadError(Exception e) { if (host != null) host.onPreviewLoadError(e); }
        @Override public void onTileLoadError(Exception e) { if (host != null) host.onTileLoadError(e); }
        @Override public void onPreviewReleased() { if (host != null) host.onPreviewReleased(); }

        @Override
        public void onImageLoadError(Exception e) {
            if (fellBack || released || !PhotoFormat.decodable(item.getFile().getDisplayName(), Build.VERSION.SDK_INT)) {
                if (host != null) host.onImageLoadError(e);
                return;
            }
            fellBack = true;
            Glide.with(page.getContext().getApplicationContext())
                    .asBitmap()
                    .load(item.getFile().getFileUri())
                    .apply(RequestOptions.signatureOf(new ObjectKey(item.getFile().getDisplayName() + item.getFile().getLastModified())))
                    .into(new CustomViewTarget<SubsamplingScaleImageView, Bitmap>(page) {
                        @Override
                        public void onResourceReady(@NonNull Bitmap bitmap, Transition<? super Bitmap> transition) {
                            if (!released) page.setImage(ImageSource.cachedBitmap(bitmap));
                        }

                        @Override
                        protected void onResourceCleared(@Nullable Drawable placeholder) {
                        }

                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            if (host != null) host.onImageLoadError(e);
                        }
                    });
        }
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        if (object instanceof SubsamplingScaleImageView) {
            // Tile decodes still in flight would otherwise call the host fragment's listeners on a dead page,
            // and the page's tile bitmaps would stay alive until the next GC.
            detachPage((SubsamplingScaleImageView) object);
        }
        releaseHdrResources(container.getContext(), position);
        container.removeView((View) object);
    }

    /**
     * Detaches a page from every host listener and frees its decoder and tile
     * bitmaps right away. Asynchronous tile loads that finish later see a
     * recycled view and do not report onReady / scale changes any more.
     */
    public static void detachPage(SubsamplingScaleImageView page) {
        page.setOnImageEventListener(null);
        page.setOnStateChangedListener(null);
        page.setOnClickListener(null);
        if (page instanceof CustomSSIV) {
            ((CustomSSIV) page).setTouchCallBack(null);
        }
        page.recycle();
    }

    /**
     * Releases every page's Ultra HDR bitmap (full resolution: ~200 MB at 50 MP)
     * when the host view is destroyed; pending header scans and decodes are
     * ignored from then on.
     */
    public void releaseAllHdr(@Nullable Context context) {
        released = true;
        for (int position = 0; position < hdrTargets.length; position++) {
            releaseHdrResources(context, position);
        }
    }

    public void setImageViewClickListener(ImageViewClickListener imageViewClickListener) {
        this.imageViewClickListener = imageViewClickListener;
    }

    public int getSsivId(int position) {
        return BASE_ID + position;
    }

    /**
     * Whether the page at {@code position} is currently displaying an
     * Ultra HDR bitmap (gainmap attached) rather than the SDR tiled source.
     */
    public boolean isHdrActive(int position) {
        return inBounds(position) && hdrActive[position];
    }

    /**
     * Whether the page is known to contain an Ultra HDR gainmap. This remains true
     * after the user disables HDR viewing, so the gallery can show an HDR-off icon
     * and allow Ultra HDR rendering to be enabled again.
     */
    public boolean isHdrAvailable(int position) {
        return inBounds(position) && hdrAvailable[position];
    }

    /**
     * Loads the Ultra HDR rendition for the page at {@code position} if the
     * file contains a gain map and the device can display it. Cheap header
     * scan first, then a full-resolution Glide decode which preserves the
     * gainmap. No-op for SDR files, DNGs, non-HDR devices or pages already
     * in HDR mode.
     */
    public void loadHdrForPosition(CustomSSIV scaleImageView, int position) {
        if (scaleImageView == null || !inBounds(position)) {
            return;
        }
        if (hdrRequested[position] || hdrActive[position]) {
            return;
        }
        Context context = scaleImageView.getContext();
        if (!UltraHdrGalleryUtil.isDeviceHdrCapable(context)) {
            return;
        }
        GalleryItem galleryItem = galleryItemList.get(position);
        // Ultra HDR is a JPEG container (the camera writes it only into JPEG files): DNG, HEIC, WebP and AVIF pages skip the scan.
        String ext = FileUtils.getExtension(galleryItem.getFile().getDisplayName());
        if (!ext.equalsIgnoreCase("jpg") && !ext.equalsIgnoreCase("jpeg")) {
            return;
        }
        hdrRequested[position] = true;
        if (hdrAvailable[position]) {
            decodeHdrBitmap(scaleImageView, position);
            return;
        }
        HDR_EXECUTOR.execute(() -> {
            boolean candidate = UltraHdrGalleryUtil.isUltraHdrJpeg(context,
                    galleryItem.getFile().getFileUri());
            scaleImageView.post(() -> {
                if (released || !hdrRequested[position] || !inBounds(position)) {
                    return;
                }
                hdrRequested[position] = false;
                if (!candidate) {
                    hdrAvailable[position] = false;
                    if (hdrStateListener != null) {
                        hdrStateListener.onHdrAvailabilityChanged(position, false);
                    }
                    return;
                }
                hdrAvailable[position] = true;
                if (hdrStateListener != null) {
                    hdrStateListener.onHdrAvailabilityChanged(position, true);
                }
                decodeHdrBitmap(scaleImageView, position);
            });
        });
    }

    /**
     * Releases the Ultra HDR bitmap of a page, reverting it to the SDR
     * tiled source. Keeps at most one full-resolution HDR bitmap alive.
     */
    public void releaseHdrForPosition(CustomSSIV scaleImageView, int position) {
        if (scaleImageView == null || !inBounds(position)) {
            return;
        }
        boolean wasActive = hdrActive[position];
        releaseHdrResources(scaleImageView.getContext(), position);
        if (wasActive) {
            scaleImageView.setImage(ImageSource.uri(galleryItemList.get(position).getFile().getFileUri()));
            if (hdrStateListener != null) {
                hdrStateListener.onHdrStateChanged(position, false);
            }
        }
    }

    private void releaseHdrResources(Context context, int position) {
        if (!inBounds(position)) {
            return;
        }
        hdrRequested[position] = false;
        hdrActive[position] = false;
        hdrBitmaps[position] = null;
        Target<Bitmap> target = hdrTargets[position];
        hdrTargets[position] = null;
        if (target != null && context != null) {
            // The application RequestManager clears targets of any manager, and unlike an activity one it does not
            // throw while the activity is being destroyed (onDestroyView during finish()).
            Glide.with(context.getApplicationContext()).clear(target);
        }
    }

    private void decodeHdrBitmap(CustomSSIV scaleImageView, int position) {
        GalleryItem galleryItem = galleryItemList.get(position);
        CustomTarget<Bitmap> target = new CustomTarget<Bitmap>(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) {
            @Override
            public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                if (released || !hdrRequested[position] || !inBounds(position)) {
                    return;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        || !bitmap.hasGainmap()) {
                    hdrRequested[position] = false;
                    hdrAvailable[position] = false;
                    if (hdrStateListener != null) {
                        hdrStateListener.onHdrAvailabilityChanged(position, false);
                    }
                    return;
                }
                hdrActive[position] = true;
                hdrBitmaps[position] = bitmap;
                scaleImageView.setImage(ImageSource.cachedBitmap(bitmap));
                if (hdrStateListener != null) {
                    hdrStateListener.onHdrStateChanged(position, true);
                }
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                hdrRequested[position] = false;
                hdrAvailable[position] = false;
                if (hdrStateListener != null) {
                    hdrStateListener.onHdrAvailabilityChanged(position, false);
                }
            }
        };
        hdrTargets[position] = target;
        hdrRequested[position] = true;
        Glide.with(scaleImageView.getContext())
                .asBitmap()
                .load(galleryItem.getFile().getFileUri())
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .skipMemoryCache(true))
                .into(target);
    }

    private boolean inBounds(int position) {
        return position >= 0 && position < galleryItemList.size();
    }

    public interface ImageViewClickListener {
        void onImageViewClicked(View v);
    }

    /**
     * Notifies the host fragment when a page's HDR state changes so it can
     * toggle the window color mode.
     */
    public interface HdrStateListener {
        void onHdrStateChanged(int position, boolean isHdr);
        void onHdrAvailabilityChanged(int position, boolean isUltraHdr);
    }
}
