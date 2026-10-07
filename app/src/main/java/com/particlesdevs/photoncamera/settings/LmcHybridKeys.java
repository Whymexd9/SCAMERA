package com.particlesdevs.photoncamera.settings;

/**
 * Key names of the LMC hybrid's own settings section (lmc_hybrid_screen) without any Android or settings state, so the
 * settings migration can use them while SettingsManager is being built (before PreferenceKeys can initialise).
 * Readers use PreferenceKeys.hybridValue / hybridSwitch / hybridString / hybridList.
 */
public final class LmcHybridKeys {
    private LmcHybridKeys() {}
    /** Prefix of every LMC hybrid preference: pref_lmc_hybrid_&lt;key&gt;. */
    public static final String PREFIX = "pref_lmc_hybrid_";
    /** The merge route of the shot: {@link #ROUTE_HYBRID} (default on every phone) or {@link #ROUTE_SCAM_HDR}. */
    public static final String ROUTE = "pref_merge_route";
    public static final String ROUTE_HYBRID = "hybrid", ROUTE_SCAM_HDR = "scamhdr";
    /** Former master switch of the hybrid (until the route selector, October 2026); read by the migration only. */
    public static final String ENABLED = "pref_lmc_hybrid_enabled";
    /** Former switches of SCAM HDR (autonomous HDR + SCAM HDR RAW); read by the migration only. */
    public static final String LEGACY_HDR = "pref_vivo_hdr_enabled", LEGACY_NICE = "pref_vivo_nice_enabled";
    /** Keys of the hybrid while it was a SCAM HDR engine (until 3 October 2026). */
    public static final String LEGACY_PREFIX = "pref_vivo_nice_hybrid_";

    /**
     * The hybrid's copy of a SCAM HDR key: pref_vivo_nice_hybrid_&lt;k&gt;, pref_vivo_nice_&lt;k&gt;, pref_nice_&lt;k&gt; -&gt;
     * pref_lmc_hybrid_&lt;k&gt;; pref_agx_nice_&lt;k&gt; -&gt; pref_lmc_hybrid_agx_&lt;k&gt;; pref_vivo_hdr_&lt;k&gt; -&gt;
     * pref_lmc_hybrid_hdr_&lt;k&gt;; any other key is returned unchanged.
     */
    public static String copyKey(String key) {
        if (key.startsWith(LEGACY_PREFIX)) return PREFIX + key.substring(LEGACY_PREFIX.length());
        if (key.startsWith("pref_vivo_nice_")) return PREFIX + key.substring("pref_vivo_nice_".length());
        if (key.startsWith("pref_agx_nice_")) return PREFIX + "agx_" + key.substring("pref_agx_nice_".length());
        if (key.startsWith("pref_nice_")) return PREFIX + key.substring("pref_nice_".length());
        if (key.startsWith("pref_vivo_hdr_")) return PREFIX + "hdr_" + key.substring("pref_vivo_hdr_".length());
        return key;
    }

    /**
     * The SoC the vivo NICE network was built for (Snapdragon 8 Elite SM8750, Hexagon v79). Variants report a suffix
     * (e.g. SM8750-AC on the Galaxy edition), so the model is matched by prefix.
     */
    public static boolean vivoNetSoc() {
        String model = android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : null;
        return model != null && model.toUpperCase(java.util.Locale.ROOT).startsWith("SM8750");
    }
}
