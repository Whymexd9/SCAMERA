package com.particlesdevs.photoncamera.settings;

/**
 * Single source of truth for all classes that carry {@code @Tunable} annotations.
 * Add or remove entries here; {@link TunableSettingsManager} and the settings UI
 * both derive their class lists from this array.
 */
public final class TunableRegistry {

    private TunableRegistry() {}

    public static final Class<?>[] TUNABLE_CLASSES = {
        com.particlesdevs.photoncamera.processing.render.Parameters.class,
    };
}
