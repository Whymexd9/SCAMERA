package com.particlesdevs.photoncamera.gallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;

import org.junit.Test;

public class GalleryLauncherIconTest {
    @Test
    public void defaultStateIsTheShownIcon() {
        assertTrue(GalleryLauncherIcon.matches(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, false));
        assertFalse(GalleryLauncherIcon.matches(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, true));
    }

    @Test
    public void hideDisablesTheAlias() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, GalleryLauncherIcon.stateFor(true));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, GalleryLauncherIcon.stateFor(false));
        assertTrue(GalleryLauncherIcon.matches(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, true));
        assertFalse(GalleryLauncherIcon.matches(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, false));
    }
}
