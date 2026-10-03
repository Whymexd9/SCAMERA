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
    /** Master switch of the LMC hybrid; independent of SCAM HDR. */
    public static final String ENABLED = "pref_lmc_hybrid_enabled";
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

    /** The SoC the vivo NICE network was built for (Snapdragon 8 Elite SM8750, Hexagon v79). */
    public static boolean vivoNetSoc() {
        return "SM8750".equals(android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MODEL : "");
    }

    /**
     * Fresh-install state of the hybrid's switch: on wherever the NICE network does not run and the hybrid's worker (an
     * arm64-v8a executable with GLES 3.1 compute) can; SCAM HDR stays the default on SM8750.
     */
    public static boolean defaultOn() {
        if (vivoNetSoc()) return false;
        String[] abis = android.os.Build.SUPPORTED_64_BIT_ABIS;
        if (abis != null) for (String abi : abis) if ("arm64-v8a".equals(abi)) return true;
        return false;
    }
}
