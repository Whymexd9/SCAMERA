package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.List;

/**
 * A user-defined {@link VendorTagUtils.TunableKey} of a module: the key name, «Сессия» / «Запрос», its value and whether the camera
 * takes it (the circle: green taken, red refused, grey not tried yet), and a switch that turns the key on or off without deleting
 * it (P122, owner 2026-10-13: «дай возможность включать и выключать реквесты если у меня вписано несколько штук»). A tap on the
 * card edits the key.
 */
public class TunableKeyPreference extends Preference {
    private static final int COLOR_UNKNOWN = 0xFF9E9E9E;
    private static final int COLOR_SUPPORTED = 0xFF4CAF50;
    private static final int COLOR_UNSUPPORTED = 0xFFF44336;

    private final String sensorId;
    private final int index;
    private final Runnable onChanged;
    private boolean enabled = true;

    public TunableKeyPreference(Context context, String sensorId, int index, Runnable onChanged) {
        super(context);
        this.sensorId = sensorId;
        this.index = index;
        this.onChanged = onChanged;
        setLayoutResource(R.layout.preference_card);
        setWidgetLayoutResource(R.layout.settings_widget_key_switch);
        setIconSpaceReserved(true);
        bindData();
        setOnPreferenceClickListener(preference -> {
            TunableKeyDialog.show(getContext(), sensorId, index, onChanged);
            return true;
        });
    }

    /**
     * Set the displayed data once, before the preference is attached. Must not be done in
     * {@link #onBindViewHolder} because those setters trigger {@code notifyChanged()} which
     * crashes while the RecyclerView is computing a layout.
     */
    private void bindData() {
        List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(getContext(), sensorId);
        if (index < 0 || index >= keys.size()) return;
        VendorTagUtils.TunableKey key = keys.get(index);
        enabled = key.enabled;
        setTitle(key.name);
        setSummary(summary(getContext(), key));
        setIcon(createCircleDrawable(getContext(), key.enabled ? getStatusColor(key) : COLOR_UNKNOWN));
    }

    /** «Сессия · Integer 20 · принят камерой» / «Выкл. · Запрос · …». */
    static String summary(Context context, VendorTagUtils.TunableKey key) {
        String kind = VendorTagUtils.TunableKey.TYPE_SESSION.equals(key.type) ? Lang.t(context, "Сессия", "Session")
                : Lang.t(context, "Запрос", "Request");
        String status = !key.tested ? Lang.t(context, "ещё не проверен", "not tried yet")
                : key.supported ? Lang.t(context, "принят камерой", "taken by the camera") : Lang.t(context, "камера не знает", "unknown to the camera");
        return (key.enabled ? "" : Lang.t(context, "Выкл. · ", "Off · ")) + kind + " · " + key.valueType + " " + key.value + " · " + status;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View widget = holder.findViewById(R.id.switchWidget);
        if (widget instanceof CompoundButton) {
            CompoundButton sw = (CompoundButton) widget;
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(enabled);
            sw.setContentDescription(getTitle());
            sw.setOnCheckedChangeListener((button, on) -> {
                List<VendorTagUtils.TunableKey> keys = TunableKeyManager.loadKeys(getContext(), sensorId);
                if (index < 0 || index >= keys.size()) return;
                keys.get(index).enabled = on;
                TunableKeyManager.saveKeys(getContext(), sensorId, keys);
                enabled = on;
                // the list is rebuilt after the click has finished (not while the RecyclerView binds)
                if (onChanged != null) button.post(onChanged);
            });
        }
        holder.itemView.setAlpha(enabled ? 1f : 0.6f);
    }

    private static int getStatusColor(VendorTagUtils.TunableKey key) {
        if (!key.tested) return COLOR_UNKNOWN;
        return key.supported ? COLOR_SUPPORTED : COLOR_UNSUPPORTED;
    }

    static GradientDrawable createCircleDrawable(Context context, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        int size = (int) (24 * context.getResources().getDisplayMetrics().density);
        drawable.setSize(size, size);
        return drawable;
    }
}
