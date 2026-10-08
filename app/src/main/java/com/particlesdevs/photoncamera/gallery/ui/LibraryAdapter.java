package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.helper.UltraHdrGalleryUtil;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P59b: the library grid: day sections («Сегодня» ... «N фото») over a 3-column grid of square cards (14dp radius, 1dp line)
 * with the format badge and an Ultra HDR badge (a sun) bottom-left on a solid dark backing, and while selecting an accent
 * check circle; a selected card shrinks inside an accent border. Selection itself lives in the fragment ({@link Host}).
 */
public final class LibraryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int HEADER = 0, PHOTO = 1;

    /** The fragment: what is selected, what a tap / long press does. */
    public interface Host {
        boolean selecting();

        boolean isSelected(GalleryItem item);

        void onPhotoClicked(int itemIndex, View view);
    }

    /** A row: a day header or a photo (index into the items). */
    static final class Row {
        final int type, item, count;
        final String label;

        Row(int type, int item, String label, int count) {
            this.type = type;
            this.item = item;
            this.label = label;
            this.count = count;
        }
    }

    private static final Map<Uri, Boolean> UHDR = new ConcurrentHashMap<>();
    private static final ExecutorService PROBE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GalleryUhdrProbe");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final Host host;
    private List<GalleryItem> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private Set<String> rawBases = new java.util.HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    long now = System.currentTimeMillis();

    public LibraryAdapter(Host host) {
        this.host = host;
        setHasStableIds(true);
    }

    public void setItems(List<GalleryItem> list) {
        items = list != null ? list : new ArrayList<>();
        rawBases = GalleryFormat.rawBaseNames(items);
        now = System.currentTimeMillis();
        rows.clear();
        rows.addAll(rowsOf(items, now));
        notifyDataSetChanged();
    }

    /** Day sections in list order (the list is newest first). */
    static List<Row> rowsOf(List<GalleryItem> items, long now) {
        List<Row> out = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            long day = dayKey(items.get(i).getFile().getLastModified());
            int j = i;
            while (j < items.size() && dayKey(items.get(j).getFile().getLastModified()) == day) j++;
            out.add(new Row(HEADER, i, GalleryUi.dayLabel(items.get(i).getFile().getLastModified(), now), j - i));
            for (int k = i; k < j; ++k) out.add(new Row(PHOTO, k, null, 0));
            i = j;
        }
        return out;
    }

    static long dayKey(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }

    public List<GalleryItem> items() { return items; }

    /** The item index of an adapter position, -1 for a header. */
    public int itemAt(int position) {
        if (position < 0 || position >= rows.size()) return -1;
        Row r = rows.get(position);
        return r.type == PHOTO ? r.item : -1;
    }

    public int positionOf(int itemIndex) {
        for (int p = 0; p < rows.size(); ++p) if (rows.get(p).type == PHOTO && rows.get(p).item == itemIndex) return p;
        return -1;
    }

    public GridLayoutManager.SpanSizeLookup spans(int columns) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < rows.size() && rows.get(position).type == HEADER ? columns : 1;
            }
        };
    }

    @Override
    public int getItemViewType(int position) { return rows.get(position).type; }

    @Override
    public long getItemId(int position) {
        Row r = rows.get(position);
        return r.type == HEADER ? -1 - position : items.get(r.item).getFile().getId() * 2 + 1 + (long) r.item * 0x100000000L;
    }

    @Override
    public int getItemCount() { return rows.size(); }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        return viewType == HEADER ? new HeaderHolder(c) : new CellHolder(c);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row r = rows.get(position);
        if (holder instanceof HeaderHolder) ((HeaderHolder) holder).bind(r);
        else ((CellHolder) holder).bind(items.get(r.item), r.item);
    }

    /** Refreshes the selection state of the bound cells without reloading their pictures. */
    public void refreshSelection(RecyclerView rv) {
        for (int i = 0; i < rv.getChildCount(); ++i) {
            RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
            if (h instanceof CellHolder) ((CellHolder) h).applySelection();
        }
    }

    // ---------------------------------------------------------------- views

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView label, count;

        HeaderHolder(Context c) {
            super(new LinearLayout(c));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 14), GalleryUi.dp(c, 4), GalleryUi.dp(c, 8));
            label = GalleryUi.text(c, "", 12, GalleryUi.accent(c));
            label.setLetterSpacing(.14f);
            label.setAlpha(.85f);
            label.setAllCaps(true);
            count = GalleryUi.text(c, "", 12, GalleryUi.MUTED);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(count);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void bind(Row r) {
            label.setText(r.label);
            count.setText(GalleryUi.dayCount(r.count));
            itemView.setContentDescription(r.label + ", " + GalleryUi.dayCount(r.count));
        }
    }

    /** A square view (width = height). */
    static final class Square extends FrameLayout {
        Square(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    final class CellHolder extends RecyclerView.ViewHolder {
        final Square cell;
        final ImageView image;
        final LinearLayout badges;
        final FrameLayout check;
        final ImageView checkMark;
        GalleryItem item;
        int index;

        CellHolder(Context c) {
            super(new Square(c));
            cell = (Square) itemView;
            int gap = GalleryUi.dp(c, 3);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(gap, gap, gap, gap); // 6dp between cells
            cell.setLayoutParams(lp);
            cell.setBackground(GalleryUi.card(c, GalleryUi.CARD, 14));
            cell.setClipToOutline(true);
            cell.setClickable(true);
            cell.setFocusable(true);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), GalleryUi.dp(view.getContext(), 14));
                }
            });
            image.setClipToOutline(true);
            cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            badges = new LinearLayout(c);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            badges.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
            bp.leftMargin = bp.bottomMargin = GalleryUi.dp(c, 6);
            cell.addView(badges, bp);
            check = new FrameLayout(c);
            checkMark = GalleryUi.icon(c, R.drawable.ic_gallery_check, GalleryUi.INK, 14);
            check.addView(checkMark, new FrameLayout.LayoutParams(GalleryUi.dp(c, 14), GalleryUi.dp(c, 14), Gravity.CENTER));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 24), GalleryUi.dp(c, 24), Gravity.END | Gravity.TOP);
            cp.topMargin = cp.rightMargin = GalleryUi.dp(c, 6);
            cell.addView(check, cp);
            check.setVisibility(View.GONE);
            cell.setOnClickListener(v -> {
                if (item != null) host.onPhotoClicked(index, v);
            });
        }

        void bind(GalleryItem item, int index) {
            this.item = item;
            this.index = index;
            Context c = cell.getContext();
            GalleryFormat format = GalleryFormat.of(item, rawBases);
            badges.removeAllViews();
            badges.addView(badge(c, format.icon));
            Uri uri = item.getFile().getFileUri();
            Boolean uhdr = uri != null ? UHDR.get(uri) : Boolean.FALSE;
            if (Boolean.TRUE.equals(uhdr)) badges.addView(badge(c, R.drawable.ic_gallery_uhdr));
            else if (uhdr == null && uri != null && loadPictures && isJpeg(item)) probe(c, uri);
            cell.setContentDescription(describe(item, format, Boolean.TRUE.equals(uhdr)));
            if (uri != null && loadPictures) {
                Glide.with(image)
                        .asBitmap()
                        .load(uri)
                        .apply(new RequestOptions()
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .signature(new ObjectKey(item.getFile().getDisplayName() + item.getFile().getLastModified()))
                                .override(240, 240)
                                .centerCrop())
                        .into(image);
            }
            applySelection();
        }

        private void probe(Context c, Uri uri) {
            final Context app = c.getApplicationContext();
            final GalleryItem bound = item;
            PROBE.execute(() -> {
                boolean hdr = UltraHdrGalleryUtil.isUltraHdrJpeg(app, uri);
                UHDR.put(uri, hdr);
                if (hdr) main.post(() -> {
                    if (item == bound) bind(bound, index);
                });
            });
        }

        void applySelection() {
            if (item == null) return;
            Context c = cell.getContext();
            boolean selecting = host.selecting(), on = selecting && host.isSelected(item);
            int accent = GalleryUi.accent(c);
            cell.setBackground(GalleryUi.round(c, GalleryUi.CARD, on ? accent : GalleryUi.LINE, 14));
            float s = on ? .88f : 1f;
            image.setScaleX(s);
            image.setScaleY(s);
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) badges.getLayoutParams();
            bp.leftMargin = bp.bottomMargin = GalleryUi.dp(c, on ? 12 : 6);
            badges.setLayoutParams(bp);
            check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            check.setBackground(GalleryUi.round(c, on ? accent : 0x66000000, on ? accent : 0xFFFFFFFF, 999));
            checkMark.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (selecting) cell.setStateDescription(on ? com.particlesdevs.photoncamera.util.Lang.t("выбрано", "selected")
                    : com.particlesdevs.photoncamera.util.Lang.t("не выбрано", "not selected"));
            else cell.setStateDescription(null);
        }
    }

    /** A badge: the icon (16dp, at least 14dp) on a solid dark rounded backing. */
    static View badge(Context c, int icon) {
        FrameLayout b = new FrameLayout(c);
        b.setBackground(GalleryUi.round(c, GalleryUi.SCRIM, GalleryUi.SCRIM, 9));
        int pad = GalleryUi.dp(c, 4);
        b.setPadding(pad, pad, pad, pad);
        ImageView i = GalleryUi.icon(c, icon, GalleryUi.TEXT, 16);
        b.addView(i, new FrameLayout.LayoutParams(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = GalleryUi.dp(c, 4);
        b.setLayoutParams(lp);
        b.setTag(icon);
        return b;
    }

    String describe(GalleryItem item, GalleryFormat format, boolean uhdr) {
        StringBuilder s = new StringBuilder(GalleryUi.dayTime(item.getFile().getLastModified(), now));
        if (!format.label.isEmpty()) s.append(", ").append(format.label);
        if (uhdr) s.append(", Ultra HDR");
        return s.toString();
    }

    /** Test hook: marks a file as Ultra HDR (or not) without reading it. */
    static void knownUltraHdr(Uri uri, boolean hdr) {
        UHDR.put(uri, hdr);
    }

    static boolean isJpeg(GalleryItem item) {
        String ext = GalleryFormat.extension(item.getFile().getDisplayName());
        return "jpg".equals(ext) || "jpeg".equals(ext);
    }

    /** Test hook: false = no picture loads and no Ultra HDR probes (layout tests). */
    static boolean loadPictures = true;
}
