package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.ui.settings.SettingsMotion;

/**
 * P384 (owner, 2026-10-28: «Переместить эту группу настроек на главный экран, переделай это меню по-своему чтобы им было очень
 * легко и просто пользоваться»): one module on the settings' main page - a card header (the lens's zoom in a badge, the module's
 * name and state, a chevron) whose rows (name, zoom, the viewfinder button, its copies, its own photo settings) show under it
 * while it is open. A group, not a category: a folded card keeps its rows in the tree (search, page reset and availability see
 * them) but the list shows only its header ({@link #isOnSameScreenAsChildren}, consulted by PreferenceGroupAdapter's flattening
 * in androidx.preference 1.2.1 - ModuleRowsTest.sectionsUseTheDiff guards it). Built and kept in sync by ModuleRows.
 */
public class ModuleSection extends PreferenceGroup {
    private final String slot;
    private boolean expanded, lit, dim;
    private CharSequence badge = "";
    private Runnable toggle;

    public ModuleSection(Context context, String slot) {
        super(context, null);
        this.slot = slot;
        setPersistent(false);
        setIconSpaceReserved(false);
        setLayoutResource(R.layout.preference_module_card);
    }

    public String slot() { return slot; }

    public boolean isExpanded() { return expanded; }

    /** Open or fold the card: the header binds again (chevron, badge), then its rows come in or go (the list's diff). */
    public void setExpanded(boolean open) {
        if (expanded == open) return;
        expanded = open;
        notifyChanged();
        notifyHierarchyChanged();
    }

    /** The badge's text (the zoom, «1.3×»), lit in the accent (the camera's module), dimmed (a hidden button). */
    public void setBadge(CharSequence text, boolean lit, boolean dim) {
        if (text.toString().contentEquals(badge) && this.lit == lit && this.dim == dim) return;
        badge = text;
        this.lit = lit;
        this.dim = dim;
        notifyChanged();
    }

    public CharSequence badge() { return badge; }

    /** The accessibility expand / collapse actions run this (the row's click goes through the preference's own listener). */
    public void setToggle(Runnable toggle) { this.toggle = toggle; }

    @Override protected boolean isOnSameScreenAsChildren() {
        return expanded;
    }

    @Override public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final Context c = getContext();
        final View row = holder.itemView;
        final TextView badgeView = (TextView) holder.findViewById(R.id.settings_module_badge);
        if (badgeView != null) {
            badgeView.setText(badge);
            badgeView.setTextSize(badge.length() > 4 ? 11f : 12.5f);
            badgeView.setAlpha(dim ? .45f : 1f);
            paintBadge(c, badgeView, expanded || lit);
        }
        SettingsMotion.turn(holder.findViewById(R.id.settings_module_chevron), expanded ? 90f : 0f);
        ViewCompat.setStateDescription(row, c.getString(expanded ? R.string.module_card_expanded : R.string.module_card_collapsed));
        ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
                info.addAction(expanded ? AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
                        : AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND);
            }

            @Override public boolean performAccessibilityAction(@NonNull View host, int action, Bundle args) {
                if ((action == AccessibilityNodeInfoCompat.ACTION_EXPAND && !expanded)
                        || (action == AccessibilityNodeInfoCompat.ACTION_COLLAPSE && expanded)) {
                    if (toggle != null) toggle.run();
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    /**
     * The badge: an accent ring (1.5 dp) on a light accent fill when the card is open or the camera uses the module, the line's
     * ring on the tile colour otherwise; a change cross-fades (the row is bound again in the same view).
     */
    private static void paintBadge(Context c, TextView badge, boolean lit) {
        final int accent = AccentPalette.color(c);
        final int fillOn = UiTokens.tint(accent, .14f), fillOff = 0xFF232A2E, ringOff = UiTokens.LINE;
        final Object was = badge.getTag(R.id.settings_bound_preference);
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        badge.setBackground(d);
        badge.setTag(R.id.settings_bound_preference, lit);
        final float on = UiTokens.dp(c, 1.5f), off = Math.max(1, UiTokens.dp(c, 1));
        final ValueAnimator running = badge.getTag(R.id.settings_rise) instanceof ValueAnimator ? (ValueAnimator) badge.getTag(R.id.settings_rise) : null;
        if (running != null) running.cancel();
        final boolean animate = was instanceof Boolean && (Boolean) was != lit && SettingsMotion.enabled(badge);
        if (!animate) {
            d.setColor(lit ? fillOn : fillOff);
            d.setStroke(Math.round(lit ? on : off), lit ? accent : ringOff);
            badge.setTextColor(lit ? accent : UiTokens.TEXT);
            return;
        }
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(240);
        a.addUpdateListener(v -> {
            final float k = lit ? v.getAnimatedFraction() : 1f - v.getAnimatedFraction();
            d.setColor(SettingsMotion.mix(fillOff, fillOn, k));
            d.setStroke(Math.round(off + (on - off) * k), SettingsMotion.mix(ringOff, accent, k));
            badge.setTextColor(SettingsMotion.mix(UiTokens.TEXT, accent, k));
        });
        badge.setTag(R.id.settings_rise, a);
        a.start();
    }
}
