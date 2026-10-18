package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SIMPLE_UI §4 «Прозрачность шторки» (the concept's sheetA page): a preview - a scene with the quick sheet over its lower
 * 62 % (title and eight tile shapes) at the chosen transparency - then one card of radio rows 0 / 20 / 30 / 40 / 50 / 60 %
 * (40 % the default) and the note that text and icons stay opaque. Stored as the percentage (ShadeStyle.TRANSPARENCY_KEY).
 */
public class ShadeTransparencyPreference extends Preference {
    static final int[] VALUES = {0, 20, 30, 40, 50, 60};
    @Nullable private Preview preview;
    @Nullable private LinearLayout card;

    public ShadeTransparencyPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(true);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public ShadeTransparencyPreference(Context context) {
        this(context, null);
    }

    @Override
    protected Object onGetDefaultValue(android.content.res.TypedArray a, int index) {
        return a.getString(index);
    }

    public int value() {
        try {
            return Math.max(0, Math.min(80, Math.round(Float.parseFloat(getPersistedString(String.valueOf(ShadeStyle.TRANSPARENCY_DEFAULT))))));
        } catch (RuntimeException e) {
            return ShadeStyle.TRANSPARENCY_DEFAULT;
        }
    }

    public void choose(int percent) {
        persistString(String.valueOf(percent));
        if (preview != null) preview.setAlpha01(1f - percent / 100f);
        fill();
        notifyDependencyChange(false);
    }

    /** «40 % · по умолчанию» and the like, for the row of «Приложение». */
    public static String label(Context c, int percent) {
        switch (percent) {
            case 0: return Lang.t(c, "0 % · непрозрачная", "0 % · opaque");
            case 40: return Lang.t(c, "40 % · по умолчанию", "40 % · default");
            default: return percent + " %";
        }
    }

    @Nullable
    private static String hint(Context c, int percent) {
        switch (percent) {
            case 0: return Lang.t(c, "Кадр под шторкой не виден", "The frame under the panel is hidden");
            case 40: return Lang.t(c, "Кадр просвечивает, текст читается", "The frame shows through, text stays readable");
            case 60: return Lang.t(c, "Для светлых сцен может читаться хуже", "In bright scenes it may read worse");
            default: return null;
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        preview = new Preview(c);
        preview.setAlpha01(1f - value() / 100f);
        page.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTokens.dp(c, 150)));
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 18));
        card.setTag("shade_transparency_options");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = UiTokens.dp(c, 14);
        page.addView(card, cp);
        fill();
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = UiTokens.dp(c, 14);
        page.addView(note(c), np);
        host.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void fill() {
        LinearLayout box = card;
        if (box == null) return;
        Context c = getContext();
        int accent = SettingsStyle.accent(c), now = value();
        box.removeAllViews();
        for (int i = 0; i < VALUES.length; i++) {
            if (i > 0) {
                View line = new View(c);
                line.setBackgroundColor(UiTokens.LINE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiTokens.dp(c, .5f)));
                lp.setMarginStart(UiTokens.dp(c, 52));
                box.addView(line, lp);
            }
            int v = VALUES[i];
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(UiTokens.dp(c, 52));
            row.setPadding(UiTokens.dp(c, 14), UiTokens.dp(c, 10), UiTokens.dp(c, 14), UiTokens.dp(c, 10));
            row.setBackgroundResource(R.drawable.settings_row);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(UiTokens.dp(c, 24), UiTokens.dp(c, 24));
            rp.setMarginEnd(UiTokens.dp(c, 14));
            row.addView(SettingsStyle.radio(c, v == now, accent), rp);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = SettingsStyle.text(c, label(c, v), 14.5f, UiTokens.TEXT);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            texts.addView(t);
            String h = hint(c, v);
            if (h != null) {
                TextView s = SettingsStyle.text(c, h, 12.5f, UiTokens.SUMMARY);
                s.setPadding(0, UiTokens.dp(c, 2), 0, 0);
                texts.addView(s);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setTag("shade_transparency_" + v);
            row.setSelected(v == now);
            row.setContentDescription(label(c, v));
            row.setOnClickListener(x -> choose(v));
            box.addView(row);
        }
    }

    private static View note(Context c) {
        return LutChangesPreference.noteView(c, Lang.t(c, "Чем прозрачнее, тем лучше виден кадр под шторкой. Текст и значки всегда остаются непрозрачными.",
                "The more transparent, the more of the frame shows under the panel. Text and icons always stay opaque."));
    }

    /** The concept's .prev: the scene, the sheet over its lower 62 % with «Быстрые настройки» and eight tile shapes. */
    static final class Preview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        @Nullable private Bitmap scene;
        private float alpha = .6f;

        Preview(Context c) {
            super(c);
            setTag("shade_transparency_preview");
        }

        void setAlpha01(float a) {
            alpha = a;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            if (scene == null || scene.getWidth() != w) scene = LutSamples.scene(w, h, 0, 1.6f);
            float r = UiTokens.dp(c, 18);
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(scene, 0, 0, paint);
            float top = h * .38f;
            paint.setColor(UiTokens.alpha(ShadeStyle.SHEET_FILL, alpha));
            canvas.drawRoundRect(new RectF(0, top, w, h + r), r, r, paint);
            float pad = UiTokens.dp(c, 10);
            paint.setColor(UiTokens.TEXT);
            paint.setTextSize(UiTokens.dp(c, 12));
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            float ty = top + pad - paint.ascent();
            canvas.drawText(Lang.t(c, "Быстрые настройки", "Quick settings"), pad, ty, paint);
            float gap = UiTokens.dp(c, 5), tw = (w - 2 * pad - 3 * gap) / 4f, th = UiTokens.dp(c, 28), y0 = ty + paint.descent() + UiTokens.dp(c, 4);
            float tr = UiTokens.dp(c, 9), lw = Math.max(1, UiTokens.dp(c, 1));
            for (int k = 0; k < 8; k++) {
                float x = pad + (k % 4) * (tw + gap), y = y0 + (k / 4) * (th + gap);
                RectF t = new RectF(x, y, x + tw, y + th);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(UiTokens.alpha(0xFF1A1F22, alpha));
                canvas.drawRoundRect(t, tr, tr, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(lw);
                paint.setColor(UiTokens.LINE);
                canvas.drawRoundRect(t, tr, tr, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(lw);
            paint.setColor(UiTokens.LINE);
            rect.inset(lw / 2, lw / 2);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
