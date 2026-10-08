package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P59b: the library's floating selection bar (replaces the FABs): × to clear, the accent count pill with the plural
 * («3 снимка», replaces number_fab), «Сравнить» (only with exactly 2), «Поделиться», «Удалить» (warning colour).
 */
public final class SelectionBar {
    public final LinearLayout view;
    public final View close, compare, share, delete;
    public final TextView count;

    public SelectionBar(Context c, Runnable onClose, Runnable onCompare, Runnable onShare, Runnable onDelete) {
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackground(GalleryUi.card(c, GalleryUi.CARD, 22));
        int p = GalleryUi.dp(c, 6);
        view.setPadding(p, p, p, p);
        view.setContentDescription(Lang.t("Выбранные снимки", "Selected photos"));
        close = GalleryUi.squareButton(c, R.drawable.ic_gallery_close, Lang.t("Снять выделение", "Clear the selection"), v -> onClose.run());
        close.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 14)));
        view.addView(close, new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 40)));
        count = GalleryUi.fitText(c, "", 14, GalleryUi.accent(c));
        count.setTypeface(Typeface.DEFAULT_BOLD);
        count.setGravity(Gravity.CENTER);
        count.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 8), GalleryUi.dp(c, 12), GalleryUi.dp(c, 8));
        int bg = UiTokens.chipBg(GalleryUi.accent(c));
        count.setBackground(GalleryUi.round(c, bg, bg, 999));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = cp.rightMargin = GalleryUi.dp(c, 4);
        view.addView(count, cp);
        compare = GalleryUi.actionButton(c, R.drawable.ic_gallery_compare, Lang.t("Сравнить", "Compare"), false, v -> onCompare.run());
        share = GalleryUi.actionButton(c, R.drawable.ic_gallery_share, Lang.t("Поделиться", "Share"), false, v -> onShare.run());
        delete = GalleryUi.actionButton(c, R.drawable.ic_gallery_delete, Lang.t("Удалить", "Delete"), true, v -> onDelete.run());
        for (View b : new View[]{compare, share, delete})
            view.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    /** The count and the enabled actions for {@code n} selected photos. */
    public void bind(int n) {
        count.setText(GalleryUi.shots(n));
        count.setContentDescription(Lang.t("Выбрано: ", "Selected: ") + GalleryUi.shots(n));
        GalleryUi.setEnabledLook(compare, n == 2);
        GalleryUi.setEnabledLook(share, n > 0);
        GalleryUi.setEnabledLook(delete, n > 0);
    }
}
