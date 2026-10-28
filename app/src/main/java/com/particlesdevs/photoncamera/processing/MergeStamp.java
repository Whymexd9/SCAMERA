package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Log;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Отметка ES / L на фото» (owner, 2026-10-28: «при её активации в углу сверху справа на фото писало использовался ли
 * сверхкороткий и длинный кадры на итоговом, если не сработал должна быть написана причина. Причина должна быть очень коротко
 * написана чтобы поняли только мы с тобой»): two lines in the photo's top right corner, «ES ✓3» / «ES ✗ code» for the
 * ultrashort frames (Bento) and «L ✓3» / «L ✓1/3 code» / «L ✗ code» for the long ones (Shasta). From the shot's plan (its
 * description: what was not asked for and why), the frames the burst handed to the merge, and the worker's report.
 * <p>
 * Codes. Not asked for: off (switched off), nc 0.04% (the ring frame clipped too little for ES), floor (the sensor cannot go
 * short enough), dcg (a DCG shot takes no L), r9.0 / r1.1 (L's ratio above the limit / below 1.2), ring (Motion mode or a mode
 * without a series: ring frames only). Asked for: lost (none came), hal (they came back at the N exposure). In the merge, ES:
 * nclip (too little clipping), hole (a large inpainting hole), usclip (the ES frame itself clipped), mv12% (motion in the mask),
 * nshort (not the shortest), tune (off in the tuning), align (not aligned); L: sh28% (the sharpest share of the base below the
 * gate), tet (the exposure ratio above the limit), mv (motion), align. nomrg: no merge for this photo; ?: the report says
 * nothing.
 */
public final class MergeStamp {
    /** The switch (Система → Диагностика); a debug key: one for every lens (ModuleProfiles.isLocal). */
    public static final String KEY = "pref_debug_merge_stamp";

    private MergeStamp() {}

    /** Whether the stamp is on (the lab's renders read it off: ScamLabRun.basePrefs). */
    public static boolean on() {
        try {
            final Object v = com.particlesdevs.photoncamera.settings.PreferenceValue.get(
                    com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences(), KEY);
            return v instanceof Boolean ? (Boolean) v : "1".equals(String.valueOf(v)) || "true".equals(String.valueOf(v));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Both lines of a shot: its plan's description, the burst's ES / L frames captured and sent (-1: no merge), the report. */
    public static String[] lines(@Nullable String plan, int esCaptured, int esSent, int longCaptured, int longSent, @Nullable String report) {
        return new String[]{esLine(plan, esCaptured, esSent, report), longLine(plan, longCaptured, longSent, report)};
    }

    /** The ES line: {@code plan} the plan's description (null: none), {@code captured} / {@code sent} the burst's ES frames. */
    static String esLine(@Nullable String plan, int captured, int sent, @Nullable String report) {
        if (sent < 0 || report == null) return "ES ✗ nomrg";
        if (sent == 0) return "ES ✗ " + (captured > 0 ? "hal" : notAsked(plan, true));
        if (report.contains("HYBRID BENTO: applied")) return "ES ✓" + sent;
        if (report.contains("HYBRID BENTO: disabled")) return "ES ✗ tune";
        final int at = report.indexOf("HYBRID BENTO: not applied (");
        if (at < 0) return "ES ✗ align";
        final String why = report.substring(at + "HYBRID BENTO: not applied (".length());
        if (why.startsWith("not enough clipping")) return "ES ✗ nclip";
        if (why.startsWith("large inpainting hole")) return "ES ✗ hole";
        if (why.startsWith("high clipping ratio")) return "ES ✗ usclip";
        if (why.startsWith("ultrashort frame is not the shortest")) return "ES ✗ nshort";
        if (why.startsWith("motion: ")) return "ES ✗ mv" + Math.round(number(why, "motion: ", 0)) + "%";
        return "ES ✗ ?";
    }

    /** The L line, as {@link #esLine}. */
    static String longLine(@Nullable String plan, int captured, int sent, @Nullable String report) {
        if (sent < 0 || report == null) return "L ✗ nomrg";
        if (sent == 0) return "L ✗ " + (captured > 0 ? "hal" : notAsked(plan, false));
        final int stages = report.indexOf("HYBRID STAGES ms:");
        if (stages < 0) return "L ✗ ?";
        final int dropped = Math.max(0, Math.min(sent, (int) Math.round(number(report.substring(stages), "droppedBracketed=", 0))));
        if (dropped == 0) return "L ✓" + sent;
        final String why;
        if (report.contains("HYBRID SHASTA: TET ratio")) why = "tet";
        else if (Pattern.compile("HYBRID SHASTA motion[^\\n]*: dropped\\)").matcher(report).find()) why = "mv";
        else {
            // the aligned frames have a sharpness line each; the lowest share of the base is what the gate refused
            final Matcher m = Pattern.compile("HYBRID SHASTA sharpness frame=\\d+[^\\n]*\\(([0-9.]+) % of base\\)").matcher(report);
            int lines = 0;
            double lowest = Double.MAX_VALUE;
            while (m.find()) {
                lines++;
                lowest = Math.min(lowest, Double.parseDouble(m.group(1)));
            }
            why = lines < sent ? "align" : String.format(Locale.ROOT, "sh%d%%", Math.round(lowest));
        }
        return dropped >= sent ? "L ✗ " + why : "L ✓" + (sent - dropped) + "/" + sent + " " + why;
    }

    /** Why the plan asked for no ES ({@code ultrashort}) or L frames: the reasons HybridPlan writes into its description. */
    static String notAsked(@Nullable String plan, boolean ultrashort) {
        if (plan == null) return "?";
        if (plan.contains("ring frames only")) return "ring";
        if (ultrashort) {
            if (plan.contains(" bento off")) return "off";
            if (plan.contains(" us skipped (sensor floor)")) return "floor";
            if (plan.contains(" no clipping (")) return String.format(Locale.ROOT, "nc %.2f%%", 100 * number(plan, " no clipping (", 0));
            return plan.contains("EXTRA_SHORT") ? "lost" : "?";
        }
        if (plan.contains(" shasta off")) return "off";
        if (plan.contains(" DCG: no shasta")) return "dcg";
        if (plan.contains(" brackets skipped (ratio ")) return String.format(Locale.ROOT, "r%.1f", number(plan, " brackets skipped (ratio ", 0));
        return plan.contains("LONG") ? "lost" : "?";
    }

    /** The number right after {@code key} in {@code text}, {@code fallback} without one. */
    static double number(String text, String key, double fallback) {
        final int at = text.indexOf(key);
        if (at < 0) return fallback;
        final Matcher m = Pattern.compile("-?[0-9]+(\\.[0-9]+)?").matcher(text);
        if (!m.find(at + key.length()) || m.start() != at + key.length()) return fallback;
        try {
            return Double.parseDouble(m.group());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The two lines drawn into the top right corner of {@code img} (the upright photo): white monospace on a dark plate, about
     * 3.5 % of the short side high. A bitmap that cannot be drawn into is copied; null or nothing drawable: {@code img} as is.
     */
    public static Bitmap draw(Bitmap img, String... lines) {
        if (img == null || lines == null || lines.length == 0) return img;
        try {
            final Bitmap out = img.isMutable() ? img : img.copy(img.getConfig(), true);
            if (out == null) return img;
            final Canvas canvas = new Canvas(out);
            final float size = Math.max(16f, Math.min(out.getWidth(), out.getHeight()) * .035f);
            final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            text.setTextSize(size);
            text.setColor(0xFFFFFFFF);
            float width = 0;
            for (String l : lines) width = Math.max(width, text.measureText(l));
            final float pad = size * .45f, line = size * 1.25f, margin = size * .6f;
            final RectF plate = new RectF(out.getWidth() - margin - width - 2 * pad, margin,
                    out.getWidth() - margin, margin + 2 * pad + line * lines.length - (line - size));
            final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
            back.setColor(0xA0000000);
            canvas.drawRoundRect(plate, pad, pad, back);
            for (int i = 0; i < lines.length; i++)
                canvas.drawText(lines[i], plate.right - pad - text.measureText(lines[i]), plate.top + pad + size * .82f + i * line, text);
            return out;
        } catch (RuntimeException e) {
            Log.w("MergeStamp", "not drawn: " + e);
            return img;
        }
    }
}
