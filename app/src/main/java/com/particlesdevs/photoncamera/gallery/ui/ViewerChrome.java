package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * P59b: the viewer's controls (GALLERY_TASK.md §2): the top row (back, a two-line meta card, the [route | format] group
 * card), the «HDR вкл / выкл» pill, the filmstrip card (44dp thumbnails, the current one with an accent border, kept
 * centred) and the button card «Поделиться · Изменить · Сравнить · Сведения · Удалить». Built here so the fragment and the
 * 360dp layout test share them.
 */
public final class ViewerChrome {
    public final LinearLayout top, bottom;
    public final View back;
    public final TextView metaTitle, metaSub;
    public final LinearLayout group;
    public final ImageView routeIcon, formatIcon;
    public final View routeSep;
    public final TextView hdrPill;
    public final RecyclerView strip;
    public final Filmstrip filmstrip;
    public final LinearLayout buttons;
    public final View share, edit, compare, info, delete;

    public interface Actions {
        void back();

        void share();

        void edit();

        void compare();

        void info();

        void delete();

        void hdr();

        void openPosition(int position);
    }

    public ViewerChrome(Context c, Actions a) {
        int accent = GalleryUi.accent(c);
        top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 12), GalleryUi.dp(c, 12), 0);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        back = GalleryUi.squareButton(c, R.drawable.ic_gallery_back, Lang.t("Назад к сетке", "Back to the grid"), v -> a.back());
        row.addView(back);
        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setMinimumHeight(GalleryUi.dp(c, 44));
        meta.setBackground(GalleryUi.card(c, GalleryUi.CARD, 16));
        meta.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 4), GalleryUi.dp(c, 12), GalleryUi.dp(c, 4));
        metaTitle = GalleryUi.fitText(c, "", 13, GalleryUi.TEXT);
        metaSub = GalleryUi.fitText(c, "", 11.5f, GalleryUi.MUTED);
        meta.addView(metaTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = GalleryUi.dp(c, 2);
        meta.addView(metaSub, sp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mp.leftMargin = mp.rightMargin = GalleryUi.dp(c, 8);
        row.addView(meta, mp);
        group = new LinearLayout(c);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setBackground(GalleryUi.card(c, GalleryUi.CARD, 16));
        group.setPadding(GalleryUi.dp(c, 2), 0, GalleryUi.dp(c, 2), 0);
        routeIcon = groupIcon(c, R.drawable.topbar_ic_route_hybrid, accent);
        routeSep = new View(c);
        routeSep.setBackgroundColor(GalleryUi.LINE);
        formatIcon = groupIcon(c, R.drawable.ic_shade_jpeg, accent);
        group.addView(routeIcon);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(Math.max(1, GalleryUi.dp(c, 1)), GalleryUi.dp(c, 26));
        group.addView(routeSep, sepLp);
        group.addView(formatIcon);
        group.setContentDescription(Lang.t("Склейка и формат", "Merge and format"));
        row.addView(group, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, GalleryUi.dp(c, 44)));
        top.addView(row);
        hdrPill = GalleryUi.text(c, "", 13, accent);
        hdrPill.setTypeface(Typeface.DEFAULT_BOLD);
        hdrPill.setGravity(Gravity.CENTER_VERTICAL);
        hdrPill.setMinHeight(GalleryUi.dp(c, 40));
        hdrPill.setPadding(GalleryUi.dp(c, 10), GalleryUi.dp(c, 8), GalleryUi.dp(c, 14), GalleryUi.dp(c, 8));
        hdrPill.setCompoundDrawablePadding(GalleryUi.dp(c, 6));
        hdrPill.setClickable(true);
        hdrPill.setFocusable(true);
        hdrPill.setOnClickListener(v -> a.hdr());
        hdrPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.END;
        hp.topMargin = GalleryUi.dp(c, 8);
        top.addView(hdrPill, hp);

        bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(GalleryUi.dp(c, 12), 0, GalleryUi.dp(c, 12), GalleryUi.dp(c, 12));
        strip = new RecyclerView(c);
        strip.setBackground(GalleryUi.card(c, GalleryUi.CARD, 18));
        int p4 = GalleryUi.dp(c, 4);
        strip.setPadding(p4, p4, p4, p4);
        strip.setClipToPadding(false);
        strip.setLayoutManager(new LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false));
        filmstrip = new Filmstrip(a::openPosition);
        strip.setAdapter(filmstrip);
        strip.setContentDescription(Lang.t("Лента снимков", "Photo strip"));
        bottom.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setBackground(GalleryUi.card(c, GalleryUi.CARD, 20));
        buttons.setPadding(p4, p4, p4, p4);
        share = GalleryUi.actionButton(c, R.drawable.ic_gallery_share, Lang.t("Поделиться", "Share"), false, v -> a.share());
        edit = GalleryUi.actionButton(c, R.drawable.ic_gallery_edit, Lang.t("Изменить", "Edit"), false, v -> a.edit());
        compare = GalleryUi.actionButton(c, R.drawable.ic_gallery_compare, Lang.t("Сравнить", "Compare"), false, v -> a.compare());
        compare.setContentDescription(Lang.t("Сравнить с соседним", "Compare with the next photo"));
        info = GalleryUi.actionButton(c, R.drawable.ic_gallery_info, Lang.t("Сведения", "Details"), false, v -> a.info());
        delete = GalleryUi.actionButton(c, R.drawable.ic_gallery_delete, Lang.t("Удалить", "Delete"), true, v -> a.delete());
        for (View b : new View[]{share, edit, compare, info, delete})
            buttons.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = GalleryUi.dp(c, 8);
        bottom.addView(buttons, bp);
    }

    private static ImageView groupIcon(Context c, int res, int accent) {
        ImageView i = GalleryUi.icon(c, res, accent, 24);
        i.setLayoutParams(new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 24)));
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return i;
    }

    /** The meta card, the group card and its accessible names. */
    public void bindMeta(String title, String sub, @Nullable String route, GalleryFormat format, boolean ultraHdr) {
        metaTitle.setText(title);
        metaSub.setText(sub);
        boolean hasRoute = route != null;
        routeIcon.setVisibility(hasRoute ? View.VISIBLE : View.GONE);
        routeSep.setVisibility(hasRoute ? View.VISIBLE : View.GONE);
        if (hasRoute) {
            routeIcon.setImageResource(GalleryFormat.routeIcon(route));
            routeIcon.setContentDescription(Lang.t("Склейка: ", "Merge: ") + route);
        }
        formatIcon.setImageResource(format.icon);
        formatIcon.setContentDescription(Lang.t("Формат: ", "Format: ") + format.label + (ultraHdr ? ", Ultra HDR" : ""));
    }

    /** The «HDR вкл / выкл» pill for an Ultra HDR photo; hidden otherwise. */
    public void bindHdr(boolean ultraHdr, boolean on) {
        Context c = hdrPill.getContext();
        if (!ultraHdr) {
            hdrPill.setVisibility(View.GONE);
            return;
        }
        int accent = GalleryUi.accent(c);
        hdrPill.setVisibility(View.VISIBLE);
        hdrPill.setText(on ? Lang.t("HDR вкл", "HDR on") : Lang.t("HDR выкл", "HDR off"));
        hdrPill.setTextColor(on ? GalleryUi.INK : accent);
        hdrPill.setBackground(GalleryUi.pressable(GalleryUi.round(c, on ? accent : GalleryUi.CARD, accent, 999)));
        android.graphics.drawable.Drawable sun = c.getDrawable(R.drawable.ic_gallery_uhdr);
        if (sun != null) {
            sun = sun.mutate();
            sun.setTint(on ? GalleryUi.INK : accent);
            int s = GalleryUi.dp(c, 18);
            sun.setBounds(0, 0, s, s);
        }
        hdrPill.setCompoundDrawables(sun, null, null, null);
        hdrPill.setContentDescription("Ultra HDR");
        hdrPill.setStateDescription(on ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
    }

    /** Keeps the current thumbnail centred. */
    public void centre(int position) {
        filmstrip.setCurrent(position);
        LinearLayoutManager lm = (LinearLayoutManager) strip.getLayoutManager();
        if (lm == null) return;
        Context c = strip.getContext();
        int item = GalleryUi.dp(c, 50);
        lm.scrollToPositionWithOffset(position, Math.max(0, (strip.getWidth() - item) / 2));
    }

    /** The filmstrip: 44dp rounded thumbnails, 6dp apart, the current one with a 2dp accent border. */
    public static final class Filmstrip extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        public interface Open {
            void open(int position);
        }

        private final Open open;
        private List<GalleryItem> items = new ArrayList<>();
        private int current = -1;

        Filmstrip(Open open) {
            this.open = open;
        }

        public void setItems(List<GalleryItem> list) {
            items = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        void setCurrent(int position) {
            int old = current;
            current = position;
            if (old >= 0 && old < items.size()) notifyItemChanged(old);
            if (position >= 0 && position < items.size()) notifyItemChanged(position);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout f = new FrameLayout(c);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(GalleryUi.dp(c, 44), GalleryUi.dp(c, 44));
            lp.leftMargin = lp.rightMargin = GalleryUi.dp(c, 3);
            f.setLayoutParams(lp);
            ImageView i = new ImageView(c);
            i.setScaleType(ImageView.ScaleType.CENTER_CROP);
            i.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), GalleryUi.dp(view.getContext(), 10));
                }
            });
            i.setClipToOutline(true);
            f.addView(i, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            f.setClickable(true);
            f.setFocusable(true);
            return new RecyclerView.ViewHolder(f) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FrameLayout f = (FrameLayout) holder.itemView;
            Context c = f.getContext();
            ImageView i = (ImageView) f.getChildAt(0);
            GalleryItem item = items.get(position);
            boolean on = position == current;
            f.setForeground(on ? GalleryUi.round(c, 0, GalleryUi.accent(c), 10) : null);
            if (on) ((android.graphics.drawable.GradientDrawable) f.getForeground()).setStroke(GalleryUi.dp(c, 2), GalleryUi.accent(c));
            f.setSelected(on);
            f.setContentDescription(GalleryUi.dayTime(item.getFile().getLastModified(), System.currentTimeMillis()));
            f.setStateDescription(on ? Lang.t("текущий", "current") : null);
            f.setOnClickListener(v -> open.open(holder.getAbsoluteAdapterPosition()));
            if (item.getFile().getFileUri() != null)
                Glide.with(i).asBitmap().load(item.getFile().getFileUri())
                        .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .signature(new ObjectKey(item.getFile().getDisplayName() + item.getFile().getLastModified()))
                                .override(132, 132).centerCrop())
                        .into(i);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
