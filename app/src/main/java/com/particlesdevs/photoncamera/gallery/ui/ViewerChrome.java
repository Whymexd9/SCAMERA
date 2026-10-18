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

import com.particlesdevs.photoncamera.gallery.glide.GalleryKeys;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * SIMPLE_UI §5 (the concept's viewer): over a dark gradient at the top the back disc and two lines - when («Сегодня,
 * 11:17») and with what («24 мм · 12 Мп · JPEG»); at the bottom one bar of five labelled buttons «Поделиться · Изменить ·
 * Сравнить · Сведения · Ещё», where «Ещё» opens a small menu with «HDR: вкл. / выкл.» (Ultra HDR photos) and «Удалить».
 * The filmstrip of P59b stays built for the swipe position but is not shown (the concept has none). Built here so the
 * fragment and the 360dp layout test share them.
 */
public final class ViewerChrome {
    /** The concept's .vbar: the card colour at 92 %. */
    static final int BAR_FILL = 0xEB1A1F22;
    public final LinearLayout top, bottom;
    public final View back;
    public final TextView metaTitle, metaSub;
    public final TextView hdrPill;
    public final RecyclerView strip;
    public final Filmstrip filmstrip;
    public final LinearLayout buttons;
    public final View share, edit, compare, info, more;
    /** «Ещё»'s menu: its HDR row (Ultra HDR photos only) and «Удалить». */
    public final LinearLayout menu;
    public final TextView menuHdr, delete;
    @Nullable private android.widget.PopupWindow popup;

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
        top.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 14), GalleryUi.dp(c, 12), GalleryUi.dp(c, 24));
        top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000}));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        back = disc(c, R.drawable.ui_ic_back, Lang.t("Назад к сетке", "Back to the grid"), v -> a.back());
        row.addView(back, new LinearLayout.LayoutParams(GalleryUi.dp(c, 44), GalleryUi.dp(c, 44)));
        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        metaTitle = GalleryUi.fitText(c, "", 15, GalleryUi.TEXT);
        metaTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        metaSub = GalleryUi.fitText(c, "", 12.5f, GalleryUi.MUTED);
        meta.addView(metaTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = GalleryUi.dp(c, 3);
        meta.addView(metaSub, sp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mp.leftMargin = GalleryUi.dp(c, 10);
        row.addView(meta, mp);
        top.addView(row);
        // the P59b pill is kept for the fragment's state; the switch lives in «Ещё» now
        hdrPill = GalleryUi.text(c, "", 13, accent);
        hdrPill.setVisibility(View.GONE);

        bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(GalleryUi.dp(c, 6), 0, GalleryUi.dp(c, 6), GalleryUi.dp(c, 16));
        strip = new RecyclerView(c);
        strip.setLayoutManager(new LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false));
        filmstrip = new Filmstrip(a::openPosition);
        strip.setAdapter(filmstrip);
        strip.setVisibility(View.GONE);
        bottom.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setBackground(GalleryUi.round(c, BAR_FILL, GalleryUi.LINE, 22));
        buttons.setPadding(GalleryUi.dp(c, 3), GalleryUi.dp(c, 5), GalleryUi.dp(c, 3), GalleryUi.dp(c, 5));
        share = barButton(c, R.drawable.ui_ic_share, Lang.t("Поделиться", "Share"), v -> a.share());
        edit = barButton(c, R.drawable.ui_ic_edit, Lang.t("Изменить", "Edit"), v -> a.edit());
        compare = barButton(c, R.drawable.ui_ic_compare, Lang.t("Сравнить", "Compare"), v -> a.compare());
        compare.setContentDescription(Lang.t("Сравнить с соседним", "Compare with the next photo"));
        info = barButton(c, R.drawable.ui_ic_info, Lang.t("Сведения", "Details"), v -> a.info());
        more = barButton(c, R.drawable.ui_ic_more, Lang.t("Ещё", "More"), v -> toggleMenu());
        for (View b : new View[]{share, edit, compare, info, more})
            buttons.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bottom.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // «Ещё»: the concept's .menu card
        menu = new LinearLayout(c);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(GalleryUi.round(c, GalleryUi.CARD, GalleryUi.LINE, 18));
        int p6 = GalleryUi.dp(c, 6);
        menu.setPadding(p6, p6, p6, p6);
        menu.setMinimumWidth(GalleryUi.dp(c, 210));
        menu.setTag("viewer_menu");
        menuHdr = menuRow(c, R.drawable.ui_ic_hdr, "", GalleryUi.TEXT, v -> { dismissMenu(); a.hdr(); });
        menuHdr.setTag("viewer_menu_hdr");
        menuHdr.setVisibility(View.GONE);
        delete = menuRow(c, R.drawable.ui_ic_trash, Lang.t("Удалить", "Delete"), GalleryUi.WARN, v -> { dismissMenu(); a.delete(); });
        delete.setTag("viewer_menu_delete");
        menu.addView(menuHdr);
        menu.addView(delete);
    }

    /** The concept's .back: a 44dp card disc with the line icon in the text colour. */
    private static View disc(Context c, int icon, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(GalleryUi.CARD);
        b.setBackground(GalleryUi.pressable(d));
        b.setContentDescription(description);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        ImageView i = GalleryUi.icon(c, icon, GalleryUi.TEXT, 22);
        b.addView(i, new FrameLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22), Gravity.CENTER));
        return b;
    }

    /** A button of the concept's .vbar: the line icon over a 10sp label, both in the text colour. */
    private static View barButton(Context c, int icon, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 8));
        b.setBackground(GalleryUi.pressable(GalleryUi.round(c, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, 16)));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setContentDescription(label);
        b.addView(GalleryUi.icon(c, icon, GalleryUi.TEXT, 22));
        TextView t = GalleryUi.fitText(c, label, 10, GalleryUi.TEXT);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = GalleryUi.dp(c, 3);
        b.addView(t, lp);
        return b;
    }

    private static TextView menuRow(Context c, int icon, String label, int color, View.OnClickListener click) {
        TextView t = GalleryUi.text(c, label, 14, color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(GalleryUi.dp(c, 44));
        t.setPadding(GalleryUi.dp(c, 12), 0, GalleryUi.dp(c, 12), 0);
        t.setCompoundDrawablePadding(GalleryUi.dp(c, 10));
        android.graphics.drawable.Drawable d = c.getDrawable(icon);
        if (d != null) {
            d = d.mutate();
            d.setTint(color);
            int s = GalleryUi.dp(c, 20);
            d.setBounds(0, 0, s, s);
        }
        t.setCompoundDrawables(d, null, null, null);
        t.setBackground(GalleryUi.pressable(GalleryUi.round(c, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, 12)));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(click);
        return t;
    }

    /** «Ещё»: the menu above the bar's right end, or closed again. */
    public void toggleMenu() {
        if (popup != null && popup.isShowing()) {
            dismissMenu();
            return;
        }
        Context c = more.getContext();
        if (menu.getParent() instanceof ViewGroup) ((ViewGroup) menu.getParent()).removeView(menu);
        popup = new android.widget.PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(GalleryUi.dp(c, 12));
        popup.setOutsideTouchable(true);
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.showAsDropDown(more, more.getWidth() - menu.getMeasuredWidth() - GalleryUi.dp(c, 8),
                -(more.getHeight() + menu.getMeasuredHeight() + GalleryUi.dp(c, 24)));
    }

    public boolean menuShown() {
        return popup != null && popup.isShowing();
    }

    public void dismissMenu() {
        if (popup != null) popup.dismiss();
        popup = null;
    }

    /** The two lines at the top; {@code route} and {@code format} stay for the callers (the format is in {@code sub}). */
    public void bindMeta(String title, String sub, @Nullable String route, GalleryFormat format, boolean ultraHdr) {
        metaTitle.setText(title);
        metaSub.setText(sub);
    }

    /** «HDR: вкл. / выкл.» in «Ещё» for an Ultra HDR photo; hidden otherwise. */
    public void bindHdr(boolean ultraHdr, boolean on) {
        menuHdr.setVisibility(ultraHdr ? View.VISIBLE : View.GONE);
        menuHdr.setText(on ? Lang.t("HDR: вкл.", "HDR: on") : Lang.t("HDR: выкл.", "HDR: off"));
        menuHdr.setStateDescription(on ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
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
            if (item.getFile().getFileUri() != null && GalleryKeys.waitForFile(i, item.getFile(), () -> {
                if (holder.getAbsoluteAdapterPosition() == position) notifyItemChanged(position);
            })) Glide.with(i).clear(i); // P89: after the camera has finished writing the file
            else if (item.getFile().getFileUri() != null)
                Glide.with(i).asBitmap().load(item.getFile().getFileUri())
                        .apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .signature(GalleryKeys.of(item.getFile()))
                                .override(132, 132).centerCrop())
                        .into(i);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
