package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.processing.avif.AvifEncoder;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ManagedSwitchPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference;
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

/**
 * AVIF in the format choice (viewfinder chooser, top-bar badge, shade FORMAT tile, «Формат фото»), its five settings rows
 * next to the HEIC / WebP ones (shown only for AVIF; «Без потерь» explains the rows it overrides), their strings in both
 * languages and the gallery listing of .avif files.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class AvifSettingsTest {
    private static final String[] AVIF_ROWS = {PhotoFormat.KEY_AVIF_QUALITY, PhotoFormat.KEY_AVIF_LOSSLESS, PhotoFormat.KEY_AVIF_DEPTH,
            PhotoFormat.KEY_AVIF_CHROMA, PhotoFormat.KEY_AVIF_SPEED};
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<PhotonCamera> camera;

    @Before
    public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
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
        // The phone's state: the native encoder loaded (this JVM has no native library).
        AvifEncoder.setAvailableForTesting(true);
    }

    @After
    public void tearDown() {
        AvifEncoder.setAvailableForTesting(null);
        camera.close();
        FileManager.tempImageFiles = null;
    }

    @Test
    public void offeredFromAndroid12WithTheEncoder() {
        List<FormatChoice> all = Arrays.asList(FormatChoice.JPEG, FormatChoice.HEIC, FormatChoice.WEBP, FormatChoice.AVIF, FormatChoice.RAW,
                FormatChoice.RAW_JPEG, FormatChoice.RAW_HEIC, FormatChoice.RAW_WEBP, FormatChoice.RAW_AVIF);
        assertEquals(all, FormatChoice.offered(PhotoFormat.AVIF_MIN_SDK, true));
        assertEquals(all, FormatChoice.offered(35));
        List<FormatChoice> withoutAvif = new ArrayList<>(all);
        withoutAvif.removeAll(Arrays.asList(FormatChoice.AVIF, FormatChoice.RAW_AVIF));
        assertEquals("Android 11: no AVIF", withoutAvif, FormatChoice.offered(PhotoFormat.AVIF_MIN_SDK - 1, true));
        assertEquals("no encoder library: no AVIF", withoutAvif, FormatChoice.offered(35, false));
        AvifEncoder.setAvailableForTesting(false);
        assertEquals(withoutAvif, FormatChoice.offered(35));
        assertFalse(FormatChoice.offered(PhotoFormat.HEIC_MIN_SDK - 1, true).contains(FormatChoice.RAW_AVIF));
    }

    @Test
    public void olderOptionsKeepTheirOrdinals() {
        // The shade's list values and the chooser's pick are ordinals: the seven older options keep theirs, AVIF is appended.
        String[] order = {"JPEG", "HEIC", "WEBP", "RAW", "RAW_JPEG", "RAW_HEIC", "RAW_WEBP", "AVIF", "RAW_AVIF"};
        assertEquals(order.length, FormatChoice.values().length);
        for (int i = 0; i < order.length; i++) assertEquals(order[i], i, FormatChoice.valueOf(order[i]).ordinal());
    }

    @Test
    public void choiceRoundTripLabelsAndIcons() {
        FormatChoice.store(FormatChoice.AVIF);
        assertEquals(0, PreferenceKeys.isSaveRaw());
        assertEquals("avif", prefs.getString(PhotoFormat.KEY, null));
        assertEquals(FormatChoice.AVIF, FormatChoice.current());
        FormatChoice.store(FormatChoice.RAW_AVIF);
        assertEquals(1, PreferenceKeys.isSaveRaw());
        assertEquals(PhotoFormat.AVIF, PreferenceKeys.getChosenPhotoFormat());
        assertEquals(FormatChoice.RAW_AVIF, FormatChoice.current());
        FormatChoice.store(FormatChoice.RAW);
        assertEquals("avif", prefs.getString(PhotoFormat.KEY, null)); // RAW only keeps the stored codec
        assertEquals(FormatChoice.RAW, FormatChoice.current());
        assertEquals(FormatChoice.AVIF, FormatChoice.of(0, PhotoFormat.AVIF));
        assertEquals(FormatChoice.RAW_AVIF, FormatChoice.of(1, PhotoFormat.AVIF));
        assertEquals(FormatChoice.RAW, FormatChoice.of(2, PhotoFormat.AVIF));
        assertEquals(PhotoFormat.AVIF, FormatChoice.RAW.codecToStore(PhotoFormat.AVIF));
        assertEquals("AVIF", FormatChoice.AVIF.shortLabel());
        assertEquals("AVIF", FormatChoice.AVIF.longLabel());
        assertEquals("R+A", FormatChoice.RAW_AVIF.shortLabel());
        assertEquals("RAW + AVIF", FormatChoice.RAW_AVIF.longLabel());
        // own 24dp icons, different from every other option's
        assertEquals(R.drawable.ic_shade_avif, FormatChoice.AVIF.icon);
        assertEquals(R.drawable.ic_shade_rawavif, FormatChoice.RAW_AVIF.icon);
        Set<Integer> others = new HashSet<>();
        for (FormatChoice c : FormatChoice.values()) if (c.codec != PhotoFormat.AVIF) others.add(c.icon);
        for (FormatChoice c : new FormatChoice[]{FormatChoice.AVIF, FormatChoice.RAW_AVIF}) {
            Drawable d = context.getDrawable(c.icon);
            assertNotNull(c.name(), d);
            assertEquals(c.name(), 24, Math.round(d.getIntrinsicWidth() / context.getResources().getDisplayMetrics().density));
            assertEquals(c.name(), 24, Math.round(d.getIntrinsicHeight() / context.getResources().getDisplayMetrics().density));
            assertFalse(c.name(), others.contains(c.icon));
        }
        // Without the encoder (or below Android 12) a stored AVIF reads as JPEG and stays stored.
        FormatChoice.store(FormatChoice.RAW_AVIF);
        AvifEncoder.setAvailableForTesting(false);
        assertEquals(FormatChoice.RAW_JPEG, FormatChoice.current());
        assertEquals("avif", prefs.getString(PhotoFormat.KEY, null));
    }

    @Test
    public void shadeTileChooserAndTopBarShowAvif() {
        ShadeCatalog.setFlashAvailable(true);
        ShadeCatalog catalog = new ShadeCatalog(context, prefs);
        ShadeCatalog.Entry format = catalog.entry(ShadeCatalog.FORMAT);
        assertArrayEquals(new CharSequence[]{"JPEG", "HEIC", "WebP", "AVIF", "RAW", "RAW + JPEG", "RAW + HEIC", "RAW + WebP", "RAW + AVIF"},
                ShadeCatalog.labels(format));
        // list values are the ordinals, icons the options' own
        assertArrayEquals(new CharSequence[]{"0", "1", "2", "7", "3", "4", "5", "6", "8"}, format.values);
        assertEquals(R.drawable.ic_shade_avif, format.valueIcons[3]);
        assertEquals(R.drawable.ic_shade_rawavif, format.valueIcons[8]);
        PreferenceKeys.setSaveRaw(0);
        prefs.edit().putString(PhotoFormat.KEY, "avif").commit();
        assertEquals("AVIF", catalog.valueText(format, true));
        assertEquals(R.drawable.ic_shade_avif, format.valueIcons[catalog.index(format)]);
        PreferenceKeys.setSaveRaw(1);
        assertEquals("R+A", catalog.valueText(format, true));
        assertEquals("RAW + AVIF", catalog.valueText(format, false));
        // the shade's search finds the format tile by the codec's name
        assertTrue(catalog.search("avif").contains(format));
        // the top bar's format badge: icon, name and tag of the stored option
        View top = LayoutInflater.from(context).inflate(R.layout.layout_main_topbar, null, false);
        com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding tb = com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding.bind(top);
        com.particlesdevs.photoncamera.ui.camera.CameraUIViewImpl.bindBadges(tb);
        assertEquals("RAW_AVIF", tb.formatBadge.getTag());
        assertEquals(context.getString(R.string.topbar_format, "RAW + AVIF"), tb.formatBadge.getContentDescription().toString());
        PreferenceKeys.setSaveRaw(0);
        com.particlesdevs.photoncamera.ui.camera.CameraUIViewImpl.bindBadges(tb);
        assertEquals("AVIF", tb.formatBadge.getTag());
    }

    @Test
    public void rowsFollowWebpInConfigWithTheirDefaults() {
        PreferenceScreen config = inflate().findPreference("output_settings_screen");
        assertNotNull(config);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < config.getPreferenceCount(); i++) order.add(config.getPreference(i).getKey());
        int at = order.indexOf(PhotoFormat.KEY_WEBP_LOSSLESS);
        assertEquals(Arrays.asList(AVIF_ROWS), order.subList(at + 1, at + 1 + AVIF_ROWS.length));
        assertEquals(PhotoFormat.KEY_ALSO_JPEG, order.get(at + 1 + AVIF_ROWS.length));
        assertTrue(config.findPreference(PhotoFormat.KEY_AVIF_QUALITY) instanceof UniversalSeekBarPreference);
        assertTrue(config.findPreference(PhotoFormat.KEY_AVIF_LOSSLESS) instanceof ManagedSwitchPreference);
        ListPreference depth = config.findPreference(PhotoFormat.KEY_AVIF_DEPTH);
        ListPreference chroma = config.findPreference(PhotoFormat.KEY_AVIF_CHROMA);
        ListPreference speed = config.findPreference(PhotoFormat.KEY_AVIF_SPEED);
        assertArrayEquals(new CharSequence[]{"8", "10", "12"}, depth.getEntryValues());
        assertArrayEquals(new CharSequence[]{"444", "420"}, chroma.getEntryValues());
        assertArrayEquals(new CharSequence[]{"5", "6", "8", "9"}, speed.getEntryValues());
        // the XML defaults are the accessors' defaults
        assertEquals(String.valueOf(PhotoFormat.AVIF_DEFAULT_DEPTH), depth.getValue());
        assertEquals("444", chroma.getValue());
        assertEquals(String.valueOf(PhotoFormat.AVIF_DEFAULT_SPEED), speed.getValue());
        assertEquals(90, PreferenceKeys.getAvifQuality());
        assertEquals(PhotoFormat.AVIF_DEFAULT_DEPTH, PreferenceKeys.getAvifDepth());
        assertEquals(PhotoFormat.AVIF_DEFAULT_SPEED, PreferenceKeys.getAvifSpeed());
        assertTrue(PreferenceKeys.isAvifYuv444());
        // every speed step stays inside libavif's range
        for (CharSequence v : speed.getEntryValues()) assertEquals(Integer.parseInt(v.toString()),
                (int) SettingsNumericRules.value(PhotoFormat.KEY_AVIF_SPEED, v, -1));
    }

    @Test
    public void rowsOnlyForAvifAndLosslessExplainsTheRest() {
        Map<String, Object> values = new HashMap<>();
        for (String format : new String[]{"jpeg", "heic", "webp", "jxl"}) {
            values.put(PhotoFormat.KEY, format);
            for (String key : AVIF_ROWS) assertTrue(format + " " + key, new SettingsAvailability(values).hidden(key));
        }
        values.put(PhotoFormat.KEY, "avif");
        SettingsAvailability avif = new SettingsAvailability(values);
        for (String key : AVIF_ROWS) {
            assertFalse(key, avif.hidden(key));
            assertNull(key, avif.reason(key));
        }
        assertTrue(avif.hidden(PhotoFormat.KEY_WEBP_QUALITY));
        assertTrue(avif.hidden(PhotoFormat.KEY_HEIC_QUALITY));
        assertFalse(avif.hidden(PhotoFormat.KEY_ALSO_JPEG));
        assertNotNull("Ultra HDR needs «Также сохранять JPEG»", avif.reason("pref_ultrahdr_key"));
        values.put(PhotoFormat.KEY_AVIF_LOSSLESS, true);
        SettingsAvailability lossless = new SettingsAvailability(values);
        for (String key : new String[]{PhotoFormat.KEY_AVIF_QUALITY, PhotoFormat.KEY_AVIF_DEPTH, PhotoFormat.KEY_AVIF_CHROMA})
            assertNotNull(key, lossless.reason(key));
        assertNull(lossless.reason(PhotoFormat.KEY_AVIF_SPEED));
        assertNull(lossless.reason(PhotoFormat.KEY_AVIF_LOSSLESS));
        values.put(PhotoFormat.KEY_ALSO_JPEG, true);
        assertNull(new SettingsAvailability(values).reason("pref_ultrahdr_key"));
        // the tree has every row the rules name
        PreferenceScreen tree = inflate();
        for (String key : AVIF_ROWS) assertNotNull(key, tree.findPreference(key));
    }

    @Test
    public void everyAvifStringInEnglishAndRussian() {
        int[] ids = {R.string.prefs_avif_quality_title, R.string.prefs_avif_quality_summary, R.string.prefs_avif_lossless_title,
                R.string.prefs_avif_lossless_summary, R.string.prefs_avif_depth_title, R.string.prefs_avif_depth_summary,
                R.string.prefs_avif_chroma_title, R.string.prefs_avif_chroma_summary, R.string.prefs_avif_speed_title,
                R.string.prefs_avif_speed_summary};
        Context en = localized(Locale.ENGLISH), ru = localized(new Locale("ru"));
        for (int id : ids) {
            String e = en.getString(id), r = ru.getString(id);
            assertFalse(e.isEmpty());
            assertFalse(e, e.matches(".*[\\u0400-\\u04FF].*"));
            assertTrue(r, r.matches(".*[\\u0400-\\u04FF].*"));
        }
        assertEquals("Качество AVIF", ru.getString(R.string.prefs_avif_quality_title));
        assertEquals("Без потерь", ru.getString(R.string.prefs_avif_lossless_title));
        assertEquals("Глубина цвета", ru.getString(R.string.prefs_avif_depth_title));
        assertEquals("Цветовая субдискретизация", ru.getString(R.string.prefs_avif_chroma_title));
        assertEquals("Скорость кодирования", ru.getString(R.string.prefs_avif_speed_title));
        assertTrue(en.getString(R.string.prefs_avif_lossless_summary).contains("photo's"));
        assertTrue(ru.getString(R.string.prefs_photo_format_summary).contains("AVIF"));
        assertTrue(en.getString(R.string.prefs_photo_format_summary).contains("AVIF"));
        int[][] arrays = {{R.array.avif_depth_entries, 3}, {R.array.avif_chroma_entries, 2}, {R.array.avif_speed_entries, 4}};
        for (int[] a : arrays) {
            String[] e = en.getResources().getStringArray(a[0]), r = ru.getResources().getStringArray(a[0]);
            assertEquals(a[1], e.length);
            assertEquals(a[1], r.length);
        }
        assertEquals("Баланс", ru.getResources().getStringArray(R.array.avif_speed_entries)[1]);
        assertEquals("Balanced", en.getResources().getStringArray(R.array.avif_speed_entries)[1]);
        assertEquals("10 бит", ru.getResources().getStringArray(R.array.avif_depth_entries)[1]);
    }

    @Test
    public void galleryListsAndTagsAvif() throws Exception {
        File dir = tmp.newFolder("Camera"), raw = tmp.newFolder("Raw");
        File dcimOld = FileManager.sDCIM_CAMERA, rawOld = FileManager.sPHOTON_RAW_DIR;
        try {
            FileManager.sDCIM_CAMERA = dir;
            FileManager.sPHOTON_RAW_DIR = raw;
            FileManager.tempImageFiles = null;
            for (String name : new String[]{"a.jpg", "b.avif", "c.AVIF", "d.txt"})
                Files.write(new File(dir, name).toPath(), new byte[]{1, 2, 3});
            new File(dir, "empty.avif").createNewFile();
            Set<String> names = new HashSet<>();
            for (File f : FileManager.getAllImageFiles()) names.add(f.getName());
            assertEquals(new HashSet<>(Arrays.asList("a.jpg", "b.avif", "c.AVIF")), names);
        } finally {
            FileManager.sDCIM_CAMERA = dcimOld;
            FileManager.sPHOTON_RAW_DIR = rawOld;
        }
        assertEquals("AVIF", new GalleryItem(new ImageFile(1, Uri.parse("content://media/1"), "IMG_1.avif", 0, 1, "/x/IMG_1.avif"))
                .getMediaTypeTag());
        // share / edit / media scanner get the AVIF MIME type
        assertEquals("image/avif", PhotoFormat.mimeForName("IMG_1.avif"));
    }

    private PreferenceScreen inflate() {
        PreferenceManager pm = new PreferenceManager(context);
        pm.setSharedPreferencesName("avif_settings_test");
        return pm.inflateFromResource(context, R.xml.preferences, null);
    }

    private Context localized(Locale locale) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }
}
