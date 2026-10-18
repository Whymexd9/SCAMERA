package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.Dcg14Mode;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding;
import com.particlesdevs.photoncamera.settings.FormatChoice;
import com.particlesdevs.photoncamera.settings.MotionMode;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SIMPLE_UI §1 The camera's top bar, as the concept draws it (392dp): torch, timer and Motion as 44dp icon buttons; while on,
 * torch and Motion become a card pill with the accent icon and a label («Фонарик», «Движение»), the timer a card circle with
 * its seconds badge (owner: «кружки при выборе должны быть круглые как на концепте»); one pill card [format | photo size |
 * DCG]; the gear. Every size is the concept's dp times {@link UiScale} (once per view tree), packed text sp times k with the
 * capped font scale. When the bar does not fit at the current width and font scale it steps down as the concept's
 * tight / tighter / tightest classes do: (1) labels off, the active buttons 40dp circles, pill padding 8; (2) gaps 2, row
 * padding 6, 36dp circles, pill padding 6, «RAW+HEIC» without spaces; (3) gaps 0, row padding 4, 33dp circles, pill padding 5
 * and 12sp. The gear always stays inside.
 */
public final class TopBar {
    private TopBar() {}

    static final int LEVELS = 4;
    /** DCG 14 bit on this module (Dcg14Mode.available of its characteristics). */
    private static volatile boolean dcgHere;

    /** The module's capabilities that change the bar: DCG and the photo sizes (CameraFragment, on every characteristics update). */
    public static void bindCamera(@Nullable CameraCharacteristics characteristics) {
        PhotoSize.bind(characteristics);
        dcgHere = Dcg14Mode.device() && characteristics != null && Dcg14Mode.available(characteristics);
    }

    /** DCG on this module without characteristics (tests). */
    static void dcgAvailable(boolean here) {
        dcgHere = here;
    }

    public static boolean dcgAvailable() {
        return dcgHere;
    }

    /** Values, states and descriptions from the settings; then the fit. */
    public static void bind(LayoutMainTopbarBinding tb) {
        View root = tb.getRoot();
        Context c = root.getContext();
        if (root.getTag(R.id.topbar_row) == null) {
            root.setTag(R.id.topbar_row, Boolean.TRUE);
            scaleTree(root, UiScale.k(c), true);
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) v.post(() -> fit(tb));
            });
        }
        int accent = ShadeStyle.accent(c);
        // torch (pref_ae_mode: 0 torch, 1 off)
        boolean torch = PreferenceKeys.getAeMode() == 0;
        String torchName = Lang.t(c, torch ? "Фонарик: вкл." : "Фонарик: выкл.", torch ? "Torch: on" : "Torch: off");
        button(tb.torchButton, tb.torchIcon, tb.torchLabel, torch, accent, torchName);
        // timer (0, 3 or 10 s)
        int seconds = timerSeconds(c);
        boolean timer = seconds > 0;
        tb.timerBadge.setText(Lang.t(c, seconds + "с", seconds + "s"));
        tb.timerBadge.setVisibility(timer ? View.VISIBLE : View.GONE);
        tb.timerBadge.setTextColor(ShadeStyle.INK);
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(accent);
        badge.setCornerRadius(UiScale.dpF(c, 8));
        badge.setStroke(Math.max(1, UiScale.dp(c, 2)), 0xFF000000);   // the dark ring round the badge
        tb.timerBadge.setBackground(badge);
        String timerName = timer ? Lang.t(c, "Таймер: " + seconds + " с", "Timer: " + seconds + " s") : Lang.t(c, "Таймер: выкл.", "Timer: off");
        button(tb.timerButton, tb.timerIcon, null, timer, accent, timerName);
        // Motion
        boolean motion = MotionMode.on();
        button(tb.motionModeButton, tb.motionIcon, tb.motionLabel, motion, accent,
                c.getString(motion ? R.string.motion_mode_on : R.string.motion_mode_off));
        // the pill card: format | size | DCG
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xBF1A1F22);
        card.setStroke(Math.max(1, UiScale.dp(c, 1)), ShadeStyle.LINE);
        card.setCornerRadius(UiScale.dpF(c, 20));
        tb.topbarBadges.setBackground(card);
        FormatChoice format = FormatChoice.current();
        tb.formatBadge.setTag(format.name());
        tb.formatBadge.setTag(R.id.format_badge, format.longLabel());
        tb.formatBadge.setText(format.longLabel());
        tb.formatBadge.setTextColor(ShadeStyle.TEXT);
        tb.formatBadge.setContentDescription(c.getString(R.string.topbar_format, format.longLabel()));
        int size = PhotoSize.current();
        tb.sizeBadge.setText(PhotoSize.label(size));
        tb.sizeBadge.setTextColor(size != PhotoSize.NORMAL ? accent : ShadeStyle.TEXT);
        tb.sizeBadge.setContentDescription(Lang.t(c, "Размер фото: " + PhotoSize.label(size) + ". Нажатие — следующий размер, удержание — все размеры",
                "Photo size: " + PhotoSize.label(size) + ". Tap: next size, hold: all sizes"));
        boolean dcg = dcgHere, dcgOn = dcg && Dcg14Mode.mode() != Dcg14Mode.OFF;
        tb.dcgBadge.setVisibility(dcg ? View.VISIBLE : View.GONE);
        tb.dcgDivider.setVisibility(dcg ? View.VISIBLE : View.GONE);
        tb.dcgBadge.setTextColor(dcgOn ? accent : ShadeStyle.TEXT);
        tb.dcgBadge.setSelected(dcgOn);
        tb.dcgBadge.setContentDescription(c.getString(dcgOn ? R.string.dcg14_on : R.string.dcg14_off));
        ViewCompat.setStateDescription(tb.dcgBadge, Lang.t(c, dcgOn ? "вкл." : "выкл.", dcgOn ? "on" : "off"));
        tb.settingsButton.setImageTintList(ColorStateList.valueOf(ShadeStyle.TEXT));
        for (TextView t : new TextView[]{tb.formatBadge, tb.sizeBadge, tb.dcgBadge}) t.setBackground(ShadeStyle.ripple(c, 20));
        fit(tb);
    }

    /**
     * The «Размер фото» sheet (a long press on the size button): Обычный · 12 Мп, Подробный · 50 Мп, Максимум · 200 Мп, each with
     * what it gives; a size this module lacks says why and does nothing.
     */
    public static void openSizeSheet(CameraFragment fragment) {
        Context c = fragment.getContext();
        if (c == null) return;
        CameraSheets.size(c, PhotoSize.current(), PhotoSize.fullAvailable(), PhotoSize.maxAvailable(), size -> {
            fragment.showSnackBar(PhotoSize.set(fragment, size));
            fragment.refreshTopBar();
        }, text -> fragment.showSnackBar(text.toString()));
    }

    /** An icon button: off, the bare icon; on, a card pill (or circle) with the accent icon and, where it has one, the label. */
    private static void button(View box, ImageView icon, @Nullable TextView label, boolean on, int accent, String name) {
        Context c = box.getContext();
        box.setActivated(on);
        box.setContentDescription(name);
        ViewCompat.setTooltipText(box, name);
        icon.setImageTintList(ColorStateList.valueOf(on ? accent : ShadeStyle.TEXT));
        if (label != null) label.setTextColor(accent);
        if (on) {
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(ShadeStyle.CARD);
            pill.setCornerRadius(UiScale.dpF(c, 999));
            box.setBackground(pill);
        } else {
            box.setBackground(ShadeStyle.ripple(c, 22));
        }
    }

    /** The self-timer in seconds (0: off). */
    static int timerSeconds(Context c) {
        int[] values = c.getResources().getIntArray(R.array.countdowntimer_entryvalues);
        int i = PreferenceKeys.getCountdownTimerIndex();
        return i >= 0 && i < values.length ? values[i] : 0;
    }

    /** The first level 0..3 at which the bar fits its width (3 when none does: then it is as small as it gets). */
    static void fit(LayoutMainTopbarBinding tb) {
        View root = tb.getRoot();
        int width = root.getWidth();
        if (width <= 0) return;
        for (int level = 0; level < LEVELS; level++) {
            apply(tb, level);
            if (need(tb) <= width || level == LEVELS - 1) {
                root.setTag(R.id.topbar_spacer, level);
                return;
            }
        }
    }

    /** The bar's level after the last fit (tests). */
    static int level(LayoutMainTopbarBinding tb) {
        Object l = tb.getRoot().getTag(R.id.topbar_spacer);
        return l instanceof Integer ? (Integer) l : 0;
    }

    /** The width the items need at their current sizes (the spacer takes no width). */
    private static int need(LayoutMainTopbarBinding tb) {
        ViewGroup row = (ViewGroup) tb.getRoot();
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int sum = row.getPaddingLeft() + row.getPaddingRight();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v.getVisibility() == View.GONE || v == tb.topbarSpacer) continue;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int ws = lp.width >= 0 ? View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY) : unspecified;
            v.measure(ws, View.MeasureSpec.makeMeasureSpec(row.getHeight() > 0 ? row.getHeight() : 1000, View.MeasureSpec.AT_MOST));
            sum += v.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        }
        return sum;
    }

    private static void apply(LayoutMainTopbarBinding tb, int level) {
        Context c = tb.getRoot().getContext();
        // the concept's .topbar / .tight / .tighter / .tightest
        float pad = level >= 3 ? 5 : level >= 2 ? 6 : level >= 1 ? 8 : 10, gap = level >= 3 ? 0 : level >= 2 ? 2 : 4;
        int height = UiScale.dp(c, 44), circle = UiScale.dp(c, level >= 3 ? 33 : level >= 2 ? 36 : level >= 1 ? 40 : 44);
        for (View b : new View[]{tb.torchButton, tb.timerButton, tb.motionModeButton}) {
            TextView label = b == tb.torchButton ? tb.torchLabel : b == tb.motionModeButton ? tb.motionLabel : null;
            boolean pill = label != null && b.isActivated() && level < 1;
            if (label != null) {
                label.setVisibility(pill ? View.VISIBLE : View.GONE);
                UiScale.textFixed(label, 13);
            }
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            // a pill with its label, or a circle (round while on; off it has no background, only its width counts)
            lp.width = pill ? ViewGroup.LayoutParams.WRAP_CONTENT : circle;
            lp.height = pill ? height : circle;
            b.setLayoutParams(lp);
            if (b != tb.timerButton) {
                int p = pill ? UiScale.dp(c, 10) : 0;
                b.setPaddingRelative(p, 0, p, 0);
            }
        }
        for (View v : new View[]{tb.timerButton, tb.motionModeButton, tb.topbarBadges, tb.settingsButton}) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.setMarginStart(UiScale.dp(c, gap));
            v.setLayoutParams(lp);
        }
        int rowPad = UiScale.dp(c, level >= 3 ? 4 : level >= 2 ? 6 : 10);
        tb.getRoot().setPaddingRelative(rowPad, 0, rowPad, 0);
        String format = String.valueOf(tb.formatBadge.getTag(R.id.format_badge));
        tb.formatBadge.setText(level >= 2 ? format.replace(" + ", "+") : format);
        for (TextView t : new TextView[]{tb.formatBadge, tb.sizeBadge, tb.dcgBadge}) {
            int p = UiScale.dp(c, pad);
            t.setPaddingRelative(p, 0, p, 0);
            UiScale.textFixed(t, level >= 3 ? 12 : 13);
        }
        UiScale.textFixed(tb.timerBadge, 10);
    }

    /**
     * The layout's dp sizes, margins and paddings times {@code k} (the bar's own height too: it is the concept's 64dp), once;
     * text sizes are set by {@link #apply}.
     */
    private static void scaleTree(View v, float k, boolean root) {
        if (Math.abs(k - 1f) < 1e-3f) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            if (lp.width > 0) lp.width = Math.round(lp.width * k);
            if (lp.height > 0) lp.height = Math.round(lp.height * k);
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                int start = m.getMarginStart(), end = m.getMarginEnd();
                m.topMargin = Math.round(m.topMargin * k);
                m.bottomMargin = Math.round(m.bottomMargin * k);
                m.setMarginStart(Math.round(start * k));
                m.setMarginEnd(Math.round(end * k));
            }
            v.setLayoutParams(lp);
        }
        v.setPaddingRelative(Math.round(v.getPaddingStart() * k), Math.round(v.getPaddingTop() * k),
                Math.round(v.getPaddingEnd() * k), Math.round(v.getPaddingBottom() * k));
        v.setMinimumWidth(Math.round(v.getMinimumWidth() * k));
        v.setMinimumHeight(Math.round(v.getMinimumHeight() * k));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scaleTree(g.getChildAt(i), k, false);
        }
    }
}
