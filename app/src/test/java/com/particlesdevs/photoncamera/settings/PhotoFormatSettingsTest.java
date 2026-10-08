package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.view.ContextThemeWrapper;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.util.FileManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** «Формат фото» in the settings tree, its accessors and availability, the shade / top-bar labels and the gallery listing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class PhotoFormatSettingsTest {
    private static final String[] NEW_KEYS = {PhotoFormat.KEY, PhotoFormat.KEY_HEIC_QUALITY, PhotoFormat.KEY_WEBP_QUALITY,
            PhotoFormat.KEY_WEBP_LOSSLESS, PhotoFormat.KEY_ALSO_JPEG};
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<PhotonCamera> camera;

    @Before
    public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> PhotonCamera.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        com.particlesdevs.photoncamera.api.Settings settings = mock(com.particlesdevs.photoncamera.api.Settings.class);
        camera.when(PhotonCamera::getSettings).thenReturn(settings);
        PhotonCamera app = mock(PhotonCamera.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
    }

    @After
    public void tearDown() {
        camera.close();
        FileManager.tempImageFiles = null;
    }

    private PreferenceScreen inflate() {
        PreferenceManager pm = new PreferenceManager(context);
        pm.setSharedPreferencesName("photo_format_test");
        return pm.inflateFromResource(context, R.xml.preferences, null);
    }

    @Test
    public void rowsSitNextToJpegQualityInConfig() {
        PreferenceScreen config = inflate().findPreference("output_settings_screen");
        assertNotNull(config);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < config.getPreferenceCount(); i++) order.add(config.getPreference(i).getKey());
        int jpeg = order.indexOf("pref_jpeg_quality");
        // the five AVIF rows follow the WebP ones (AvifSettingsTest)
        assertEquals(Arrays.asList(PhotoFormat.KEY, "pref_jpeg_quality", PhotoFormat.KEY_HEIC_QUALITY, PhotoFormat.KEY_WEBP_QUALITY,
                PhotoFormat.KEY_WEBP_LOSSLESS, PhotoFormat.KEY_AVIF_QUALITY, PhotoFormat.KEY_AVIF_LOSSLESS, PhotoFormat.KEY_AVIF_DEPTH,
                PhotoFormat.KEY_AVIF_CHROMA, PhotoFormat.KEY_AVIF_SPEED, PhotoFormat.KEY_ALSO_JPEG, "pref_ultrahdr_key"),
                order.subList(jpeg - 1, jpeg + 11));
        ListPreference format = config.findPreference(PhotoFormat.KEY);
        assertArrayEquals(new CharSequence[]{"jpeg", "heic", "webp", "avif"}, format.getEntryValues());
        assertEquals(4, format.getEntries().length);
        for (CharSequence v : format.getEntryValues()) assertEquals(v.toString(), PhotoFormat.parse(v).value);
    }

    @Test
    public void everyNewStringInEnglishAndRussian() {
        int[] ids = {R.string.prefs_photo_format_title, R.string.prefs_photo_format_summary, R.string.prefs_heic_quality_title,
                R.string.prefs_heic_quality_summary, R.string.prefs_webp_quality_title, R.string.prefs_webp_quality_summary,
                R.string.prefs_webp_lossless_title, R.string.prefs_webp_lossless_summary, R.string.prefs_photo_also_jpeg_title,
                R.string.prefs_photo_also_jpeg_summary, R.string.topbar_group, R.string.topbar_format};
        Context en = localized(Locale.ENGLISH), ru = localized(new Locale("ru"));
        for (int id : ids) {
            String e = en.getString(id), r = ru.getString(id);
            assertFalse(e.isEmpty());
            assertNotEquals(context.getResources().getResourceEntryName(id), e, r);
            assertFalse(e, e.matches(".*[\\u0400-\\u04FF].*"));
            assertTrue(r, r.matches(".*[\\u0400-\\u04FF].*"));
        }
        assertEquals("Формат фото", ru.getString(R.string.prefs_photo_format_title));
        assertEquals("Также сохранять JPEG", ru.getString(R.string.prefs_photo_also_jpeg_title));
        assertEquals(4, ru.getResources().getStringArray(R.array.photo_format_entries).length);
    }

    @Test
    public void accessorsDefaultsAndUltraHdr() {
        assertEquals(PhotoFormat.JPEG, PreferenceKeys.getPhotoFormat());
        assertEquals(90, PreferenceKeys.getHeicQuality());
        assertEquals(90, PreferenceKeys.getWebpQuality());
        assertFalse(PreferenceKeys.isWebpLossless());
        assertFalse(PreferenceKeys.isAlsoSaveJpeg());
        prefs.edit().putBoolean("pref_ultrahdr_key", true).commit();
        assertTrue(PreferenceKeys.isUltraHdrActive());
        prefs.edit().putString(PhotoFormat.KEY, "heic").putString(PhotoFormat.KEY_HEIC_QUALITY, "250").commit();
        assertEquals(PhotoFormat.HEIC, PreferenceKeys.getPhotoFormat());
        assertEquals(100, PreferenceKeys.getHeicQuality());
        assertTrue(PreferenceKeys.isUltraHdrOn());
        assertFalse("Ultra HDR needs a JPEG in the shot", PreferenceKeys.isUltraHdrActive());
        prefs.edit().putBoolean(PhotoFormat.KEY_ALSO_JPEG, true).commit();
        assertTrue(PreferenceKeys.isUltraHdrActive());
        prefs.edit().putString(PhotoFormat.KEY, "webp").putBoolean(PhotoFormat.KEY_ALSO_JPEG, false).putBoolean("pref_ultrahdr_key", true).commit();
        assertFalse(PreferenceKeys.isUltraHdrActive());
        assertEquals(PhotoFormat.WEBP, PreferenceKeys.getChosenPhotoFormat());
    }

    @Test
    public void availabilityHidesOtherFormatsAndExplainsUltraHdr() {
        Map<String, Object> values = new HashMap<>();
        SettingsAvailability jpeg = new SettingsAvailability(values);
        for (String key : new String[]{PhotoFormat.KEY_HEIC_QUALITY, PhotoFormat.KEY_WEBP_QUALITY, PhotoFormat.KEY_WEBP_LOSSLESS, PhotoFormat.KEY_ALSO_JPEG})
            assertTrue(key, jpeg.hidden(key));
        assertNull(jpeg.reason("pref_ultrahdr_key"));
        values.put(PhotoFormat.KEY, "heic");
        SettingsAvailability heic = new SettingsAvailability(values);
        assertFalse(heic.hidden(PhotoFormat.KEY_HEIC_QUALITY));
        assertFalse(heic.hidden(PhotoFormat.KEY_ALSO_JPEG));
        assertTrue(heic.hidden(PhotoFormat.KEY_WEBP_QUALITY));
        assertNotNull(heic.reason("pref_ultrahdr_key"));
        assertNotNull(heic.reason("pref_jpeg_quality"));
        values.put(PhotoFormat.KEY_ALSO_JPEG, true);
        assertNull(new SettingsAvailability(values).reason("pref_ultrahdr_key"));
        values.put(PhotoFormat.KEY, "webp");
        values.put(PhotoFormat.KEY_WEBP_LOSSLESS, true);
        SettingsAvailability webp = new SettingsAvailability(values);
        assertFalse(webp.hidden(PhotoFormat.KEY_WEBP_QUALITY));
        assertNotNull(webp.reason(PhotoFormat.KEY_WEBP_QUALITY));
        assertNull(webp.reason(PhotoFormat.KEY_WEBP_LOSSLESS));
        // the tree has every row the rules name
        PreferenceScreen tree = inflate();
        for (String key : NEW_KEYS) assertNotNull(key, tree.findPreference(key));
    }

    @Test
    public void shadeAndTopBarNameTheCodec() {
        ShadeCatalog.setFlashAvailable(true);
        ShadeCatalog catalog = new ShadeCatalog(context, prefs);
        ShadeCatalog.Entry format = catalog.entry(ShadeCatalog.FORMAT);
        PreferenceKeys.setSaveRaw(0);
        assertEquals("JPEG", catalog.valueText(format, true));
        prefs.edit().putString(PhotoFormat.KEY, "heic").commit();
        assertEquals("HEIC", catalog.valueText(format, true));
        PreferenceKeys.setSaveRaw(1);
        assertEquals("R+H", catalog.valueText(format, true));
        assertEquals("RAW + HEIC", catalog.valueText(format, false));
        prefs.edit().putString(PhotoFormat.KEY, "webp").commit();
        assertEquals("R+W", catalog.valueText(format, true));
        // one format choice with seven options (MANUAL_TASK.md §4 and the owner's request)
        assertArrayEquals(new CharSequence[]{"JPEG", "HEIC", "WebP", "RAW", "RAW + JPEG", "RAW + HEIC", "RAW + WebP"},
                ShadeCatalog.labels(format));
        PreferenceKeys.setSaveRaw(2);
        assertEquals("RAW", catalog.valueText(format, true));
        assertEquals("webp", prefs.getString(PhotoFormat.KEY, null)); // RAW only keeps the stored codec
        // the codec has no tile of its own any more: the FORMAT choice covers it
        assertNull(catalog.entry(ShadeCatalog.PHOTO_FORMAT));
        Set<String> group = new HashSet<>();
        for (ShadeCatalog.Group g : ShadeCatalog.GROUPS) if (g.keys.contains(ShadeCatalog.FORMAT)) group.addAll(g.keys);
        assertFalse(group.contains(ShadeCatalog.PHOTO_FORMAT));
        // other entries keep their own labels
        ShadeCatalog.Entry route = catalog.entry(ShadeCatalog.ROUTE);
        assertSame(route.labels, ShadeCatalog.labels(route));
    }

    @Test
    public void galleryListsHeicAndWebp() throws Exception {
        File camera = tmp.newFolder("Camera"), raw = tmp.newFolder("Raw");
        File dcimOld = FileManager.sDCIM_CAMERA, rawOld = FileManager.sPHOTON_RAW_DIR;
        try {
            FileManager.sDCIM_CAMERA = camera;
            FileManager.sPHOTON_RAW_DIR = raw;
            FileManager.tempImageFiles = null;
            for (String name : new String[]{"a.jpg", "b.heic", "c.webp", "d.HEIF", "e.png", "f.txt"})
                Files.write(new File(camera, name).toPath(), new byte[]{1, 2, 3});
            Files.write(new File(raw, "g.dng").toPath(), new byte[]{1});
            new File(camera, "empty.webp").createNewFile();
            Set<String> names = new HashSet<>();
            for (File f : FileManager.getAllImageFiles()) names.add(f.getName());
            assertEquals(new HashSet<>(Arrays.asList("a.jpg", "b.heic", "c.webp", "d.HEIF", "g.dng")), names);
        } finally {
            FileManager.sDCIM_CAMERA = dcimOld;
            FileManager.sPHOTON_RAW_DIR = rawOld;
        }
        assertEquals("HEIC", tag("IMG_1.heic"));
        assertEquals("WEBP", tag("IMG_1.webp"));
        assertEquals("", tag("IMG_1.jpg"));
        assertEquals("RAW", tag("IMG_1.dng"));
    }

    private static String tag(String name) {
        return new GalleryItem(new ImageFile(1, Uri.parse("content://media/1"), name, 0, 1, "/x/" + name)).getMediaTypeTag();
    }

    private Context localized(Locale locale) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }
}
