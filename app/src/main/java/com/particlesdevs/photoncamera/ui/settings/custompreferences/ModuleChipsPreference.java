package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.ui.settings.SettingsMotion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * P384 (owner, 2026-10-28: «А то что на третьем скриншоте сделать как отдельную группу настроек на главной странице разделенную по
 * модулям»): the module chips of «Сенсор по модулям» - one pill per module (its zoom; a dot when the module has sensor values of
 * its own), the chosen one marked by an accent pill that glides from chip to chip; a tap on the chosen chip lets it go again, so
 * the main page stays short until a module is picked. It stores nothing: the choice only shows that module's sensor rows
 * (SensorConfigPreferenceGenerator.ModuleSelection, through ModuleRows).
 */
public class ModuleChipsPreference extends Preference {
    private final List<String> slots = new ArrayList<>();
    private final List<CharSequence> labels = new ArrayList<>();
    private final List<Boolean> dots = new ArrayList<>();
    @Nullable private String selected;
    @Nullable private Consumer<String> onPick;

    public ModuleChipsPreference(Context context) {
        super(context);
        setPersistent(false);
        setSelectable(false);
        setIconSpaceReserved(false);
        setLayoutResource(R.layout.preference_module_chips);
    }

    /** The modules in chip order, the chip's label of each, and which have sensor values of their own (the dot). */
    public void setModules(List<String> modules, Function<String, CharSequence> label, Predicate<String> customised) {
        final List<CharSequence> l = new ArrayList<>();
        final List<Boolean> d = new ArrayList<>();
        for (String slot : modules) {
            l.add(label.apply(slot));
            d.add(customised.test(slot));
        }
        if (slots.equals(modules) && texts(labels).equals(texts(l)) && dots.equals(d)) return;
        slots.clear();
        slots.addAll(modules);
        labels.clear();
        labels.addAll(l);
        dots.clear();
        dots.addAll(d);
        if (selected != null && !slots.contains(selected)) selected = null;
        notifyChanged();
    }

    private static List<String> texts(List<CharSequence> in) {
        final List<String> out = new ArrayList<>();
        for (CharSequence c : in) out.add(String.valueOf(c));
        return out;
    }

    public List<String> modules() { return Collections.unmodifiableList(slots); }

    /** The chosen module (null: none); the chips show it, the pill glides to it when the row is on screen. */
    public void setSelected(@Nullable String slot) {
        if (slot != null && !slots.contains(slot)) slot = null;
        if (Objects.equals(selected, slot)) return;
        selected = slot;
        notifyChanged();
    }

    @Nullable public String selected() { return selected; }

    /** What a tap chooses: a module, or null when the chosen chip is tapped again. */
    public void setOnPick(@Nullable Consumer<String> onPick) { this.onPick = onPick; }

    private void pick(String slot) {
        final String next = slot.equals(selected) ? null : slot;
        setSelected(next);
        if (onPick != null) onPick.accept(next);
    }

    @Override public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final Context c = getContext();
        final LinearLayout chips = (LinearLayout) holder.findViewById(R.id.settings_chips);
        final View pill = holder.findViewById(R.id.settings_chips_pill);
        final TextView hint = (TextView) holder.findViewById(R.id.settings_chips_hint);
        final HorizontalScrollView scroll = (HorizontalScrollView) holder.findViewById(R.id.settings_chips_scroll);
        if (chips == null || pill == null) return;
        final int accent = AccentPalette.color(c);
        final String signature = slots + "|" + texts(labels) + "|" + dots + "|" + accent;
        if (!signature.equals(chips.getTag())) {
            chips.removeAllViews();
            for (int i = 0; i < slots.size(); i++) chips.addView(chip(c, i, accent), chipParams(c, i));
            chips.setTag(signature);
            pill.setTag(null);   // no chip to glide from
        }
        pill.setBackground(UiTokens.shape(c, UiTokens.tint(accent, .20f), accent, 20));
        for (int i = 0; i < chips.getChildCount(); i++) {
            final TextView chip = (TextView) chips.getChildAt(i);
            chip.setContentDescription(c.getString(R.string.module_chip_description, labels.get(i))
                    + (slots.get(i).equals(selected) ? c.getString(R.string.module_chip_selected) : "")
                    + (dots.get(i) ? c.getString(R.string.module_chip_customised) : ""));
            chip.setSelected(slots.get(i).equals(selected));
        }
        if (hint != null) hint.setVisibility(selected == null ? View.VISIBLE : View.GONE);
        final String was = pill.getTag() instanceof String ? (String) pill.getTag() : null;
        pill.setTag(selected == null ? "" : selected);
        final int index = selected == null ? -1 : slots.indexOf(selected);
        if (index < 0) {
            pill.setVisibility(View.INVISIBLE);
            colour(chips, -1, -1, 1f, accent);
            return;
        }
        final View target = chips.getChildAt(index);
        final int from = was == null || was.isEmpty() ? -1 : slots.indexOf(was);
        final Runnable place = () -> {
            final boolean glide = from >= 0 && from != index && pill.getVisibility() == View.VISIBLE;
            pill.setVisibility(View.VISIBLE);
            if (!glide) {
                SettingsMotion.glide(pill, chips.getLeft() + target.getLeft(), target.getWidth(), null);
                pill.animate().cancel();
                colour(chips, -1, index, 1f, accent);
            } else {
                SettingsMotion.glide(pill, chips.getLeft() + target.getLeft(), target.getWidth(),
                        k -> colour(chips, from, index, (float) k, accent));
            }
            if (scroll != null && SettingsMotion.enabled(scroll)) {
                final int left = target.getLeft() - UiTokens.dp(c, 16), right = target.getRight() + UiTokens.dp(c, 16);
                if (left < scroll.getScrollX()) scroll.smoothScrollTo(Math.max(0, left), 0);
                else if (right > scroll.getScrollX() + scroll.getWidth()) scroll.smoothScrollTo(right - scroll.getWidth(), 0);
            }
        };
        if (target.getWidth() > 0) place.run();
        else target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                v.removeOnLayoutChangeListener(this);
                if (index == (selected == null ? -1 : slots.indexOf(selected))) v.post(place);
            }
        });
    }

    /** The chips' text: the chosen one in the accent, the others in the text colour; {@code k} of the way from {@code from} to {@code to}. */
    private static void colour(LinearLayout chips, int from, int to, float k, int accent) {
        for (int i = 0; i < chips.getChildCount(); i++) {
            final float on = i == to ? k : i == from ? 1f - k : 0f;
            ((TextView) chips.getChildAt(i)).setTextColor(SettingsMotion.mix(UiTokens.TEXT, accent, on));
        }
    }

    private TextView chip(Context c, int i, int accent) {
        final TextView t = new TextView(c);
        final String slot = slots.get(i);
        final SpannableStringBuilder text = new SpannableStringBuilder(labels.get(i));
        if (dots.get(i)) {
            final int at = text.length();
            text.append("  •");
            text.setSpan(new ForegroundColorSpan(accent), at, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(UiTokens.TEXT);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setPadding(UiTokens.dp(c, 16), 0, UiTokens.dp(c, 16), 0);
        final GradientDrawable ring = UiTokens.shape(c, 0, UiTokens.LINE, 20);
        t.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1FFFFFFF), ring, null));
        t.setTag("module_chip_" + slot);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> pick(slot));
        SettingsMotion.pressable(t);
        return t;
    }

    private static LinearLayout.LayoutParams chipParams(Context c, int i) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiTokens.dp(c, 40));
        if (i > 0) lp.setMarginStart(UiTokens.dp(c, 8));
        return lp;
    }
}
