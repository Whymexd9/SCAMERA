package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.PhotoFormat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * The one format choice (MANUAL_TASK.md §4 and the owner's request): seven options, each with its own drawable; every
 * option of the format setting maps to a drawable and every format value the app can store has an icon; the choice maps
 * onto the save mode and the codec without anything else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FormatChoiceTest {
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<PhotonCamera> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> PhotonCamera.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        camera.when(PhotonCamera::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PhotonCamera app = mock(PhotonCamera.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
    }

    @After
    public void tearDown() {
        camera.close();
    }

    @Test
    public void everyOptionHasItsOwnDrawable() {
        assertEquals(Arrays.asList("JPEG", "HEIC", "WebP", "RAW", "RAW + JPEG", "RAW + HEIC", "RAW + WebP"),
                Arrays.stream(FormatChoice.values()).map(FormatChoice::longLabel).collect(java.util.stream.Collectors.toList()));
        Set<Integer> icons = new HashSet<>();
        for (FormatChoice c : FormatChoice.values()) {
            Drawable d = context.getDrawable(c.icon);
            assertNotNull(c.name(), d);
            assertEquals(c.name(), 24, Math.round(d.getIntrinsicWidth() / context.getResources().getDisplayMetrics().density));
            icons.add(c.icon);
        }
        assertEquals("one icon per option", FormatChoice.values().length, icons.size());
    }

    @Test
    public void everyFormatValueTheAppStoresHasAnIcon() {
        // Every save mode the app stores (0 photo, 1 RAW + photo, 2 RAW) with every codec maps to an option with an icon.
        for (int saveMode = 0; saveMode <= 2; saveMode++)
            for (PhotoFormat codec : PhotoFormat.values()) {
                FormatChoice c = FormatChoice.of(saveMode, codec);
                assertNotNull(context.getDrawable(c.icon));
                assertEquals(saveMode, c.saveMode);
                if (saveMode != 2) assertEquals(codec, c.codec);
            }
        // Every option of the format setting (the shade's FORMAT entry, which the top bar and «Формат фото» share) maps to
        // a drawable; the entry offers exactly the seven options on Android 9+.
        ShadeCatalog.Entry format = new ShadeCatalog(context, prefs).entry(ShadeCatalog.FORMAT);
        assertEquals(FormatChoice.values().length, format.values.length);
        assertEquals(format.values.length, format.valueIcons.length);
        for (int i = 0; i < format.values.length; i++) {
            FormatChoice c = FormatChoice.values()[Integer.parseInt(format.values[i].toString())];
            assertEquals(c.icon, format.valueIcons[i]);
            assertNotNull(context.getDrawable(format.valueIcons[i]));
            assertEquals(c.longLabel(), format.labels[i].toString());
        }
    }

    @Test
    public void heicIsNotOfferedBelowAndroid9() {
        List<FormatChoice> old = FormatChoice.offered(PhotoFormat.HEIC_MIN_SDK - 1);
        assertEquals(Arrays.asList(FormatChoice.JPEG, FormatChoice.WEBP, FormatChoice.RAW, FormatChoice.RAW_JPEG, FormatChoice.RAW_WEBP), old);
        assertEquals(Arrays.asList(FormatChoice.values()), FormatChoice.offered(PhotoFormat.HEIC_MIN_SDK));
    }

    @Test
    public void theChoiceMapsOntoSaveModeAndCodec() {
        FormatChoice.store(FormatChoice.RAW_HEIC);
        assertEquals(1, PreferenceKeys.isSaveRaw());
        assertEquals(PhotoFormat.HEIC, PreferenceKeys.getChosenPhotoFormat());
        assertEquals(FormatChoice.RAW_HEIC, FormatChoice.current());
        // RAW only keeps whatever codec is stored
        FormatChoice.store(FormatChoice.RAW);
        assertEquals(2, PreferenceKeys.isSaveRaw());
        assertEquals("heic", prefs.getString(PhotoFormat.KEY, null));
        assertEquals(FormatChoice.RAW, FormatChoice.current());
        FormatChoice.store(FormatChoice.WEBP);
        assertEquals(0, PreferenceKeys.isSaveRaw());
        assertEquals(PhotoFormat.WEBP, PreferenceKeys.getChosenPhotoFormat());
        FormatChoice.store(FormatChoice.RAW_JPEG);
        assertEquals(1, PreferenceKeys.isSaveRaw());
        assertEquals(PhotoFormat.JPEG, PreferenceKeys.getChosenPhotoFormat());
        assertEquals("R+J", FormatChoice.current().shortLabel());
    }
}
