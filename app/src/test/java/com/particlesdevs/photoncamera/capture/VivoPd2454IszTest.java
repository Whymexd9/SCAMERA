package com.particlesdevs.photoncamera.capture;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;

import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.MosaicBlockStore;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = VivoPd2454IszTest.BuilderState.class)
public class VivoPd2454IszTest {
    @Implements(CaptureRequest.Builder.class)
    public static class BuilderState {
        final Map<String, Object> logical = new HashMap<>();
        int physicalWrites;

        @Implementation protected <T> void set(CaptureRequest.Key<T> key, T value) {
            logical.put(key.getName(), value);
        }

        @Implementation protected <T> T get(CaptureRequest.Key<T> key) {
            return (T) logical.get(key.getName());
        }

        @Implementation protected <T> void setPhysicalCameraKey(CaptureRequest.Key<T> key, T value, String id) {
            physicalWrites++;
        }
    }

    private MockedStatic<ScameraApp> app;
    private MockedStatic<VivoQcomModes> vivo;
    private Context context;
    private SharedPreferences prefs;

    @Before public void setup() {
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public String getPackageName() { return "org.codeaurora.snapcam"; }
        };
        SettingsManager manager = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(call -> context.getString(call.getArgument(0)));
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(ScameraApp::getSettings).thenReturn(new Settings());
        ScameraApp instance = mock(ScameraApp.class);
        when(instance.getSettingsManager()).thenReturn(manager);
        app.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(instance);
        PreferenceKeys.initialise(manager);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().apply();
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        PixelPro10.setFullRes(false);
        vivo = mockStatic(VivoQcomModes.class, CALLS_REAL_METHODS);
        vivo.when(VivoQcomModes::device).thenReturn(true);
        vivo.when(VivoQcomModes::enabled).thenReturn(true);
        for (int i = 0; i < 8; i++) prefs.edit()
                .putString("module_auto_back" + i, "5")
                .putBoolean("module_visible_back" + i, i == 0).apply();
        prefs.edit().putString("module_zoom_back0", "3.7")
                .putBoolean("module_sensorcrop_back0", false).putString("module_active", "back0").apply();
    }

    @After public void cleanup() {
        vivo.close();
        app.close();
    }

    private List<String> shown() {
        List<String> out = new ArrayList<>();
        for (String slot : ModuleRegistry.slots()) if (ModuleRegistry.visible(slot)) out.add(slot);
        return out;
    }

    @Test public void nativeProfilesRequireRealCtsIdentityAndExcludeFullResAndDcg() {
        for (String camera : Arrays.asList("3", "4", "5")) {
            int mode = "5".equals(camera) ? 5 : 3;
            assertEquals(2, VivoPd2454NativeRaw.factor(camera, mode));
            assertTrue(VivoPd2454NativeRaw.eligible(true, "android.camera.cts", camera, mode, false, false));
            assertFalse(VivoPd2454NativeRaw.eligible(false, "android.camera.cts", camera, mode, false, false));
            assertFalse(VivoPd2454NativeRaw.eligible(true, "org.codeaurora.snapcam", camera, mode, false, false));
            assertFalse(VivoPd2454NativeRaw.eligible(true, "android.camera.cts.extra", camera, mode, false, false));
            assertFalse(VivoPd2454NativeRaw.eligible(true, "android.camera.cts", camera, mode, true, false));
            assertFalse(VivoPd2454NativeRaw.eligible(true, "android.camera.cts", camera, mode, false, true));
        }
        assertEquals(4, VivoPd2454NativeRaw.factor("5", 7));
        assertEquals(0, VivoPd2454NativeRaw.factor("3", 7));
        assertEquals(0, VivoPd2454NativeRaw.factor("4", 7));
        assertEquals(0, VivoPd2454NativeRaw.factor("5", 6));
        assertEquals(0, VivoPd2454NativeRaw.factor("5", 17));
        assertEquals(0, VivoPd2454NativeRaw.factor("0", 3));
        assertEquals(0, VivoPd2454NativeRaw.factor(null, 3));
    }

    private Context nativeContext() {
        Context cts = new android.content.ContextWrapper(context) {
            @Override public String getPackageName() { return "android.camera.cts"; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "android.camera.cts_preferences".equals(name) ? prefs : super.getSharedPreferences(name, mode);
            }
        };
        app.when(ScameraApp::getAppContext).thenReturn(cts);
        ScameraApp.getSettings().selectedMode = com.particlesdevs.photoncamera.api.CameraMode.PHOTO;
        return cts;
    }

    @Test public void nativeModulesOfferOnlyMeasuredModesAndRemainDeleted() {
        Context cts = nativeContext();
        prefs.edit().putString("module_auto_back0", "3").putString("module_zoom_back0", "1")
                .putString("module_auto_back1", "4").putString("module_zoom_back1", "0.4")
                .putString("module_auto_back2", "5").putString("module_zoom_back2", "3.7")
                .putBoolean("module_visible_back1", true).putBoolean("module_visible_back2", true)
                .putString("module_auto_back8", "5").putBoolean("module_visible_back8", false).apply();
        VivoPd2454NativeRaw.ensureModules(cts);
        assertEquals(7, shown().size());
        Map<String, Integer> offered = new HashMap<>();
        String deleted = null;
        for (String slot : shown()) if (ModuleRegistry.duplicateOf(slot) != null) {
            int mode = ModuleRegistry.sensorMode(slot);
            String camera = ModuleRegistry.camera(slot);
            offered.put(camera + ":" + mode, VivoPd2454NativeRaw.factor(camera, mode));
            assertTrue(ModuleRegistry.sensorCrop(slot));
            assertEquals(ModuleRegistry.zoom(slot), ModuleRegistry.nativeRatio(slot), .0001f);
            // P181 (owner): «2×», not «2× ISZ»
            assertEquals(VivoQcomIsz.label(ModuleRegistry.zoom(slot)), ModuleRegistry.label(slot));
            deleted = slot;
        }
        assertEquals(offered.toString(), Integer.valueOf(2), offered.get("3:3"));
        assertEquals(Integer.valueOf(2), offered.get("4:3"));
        assertEquals(Integer.valueOf(2), offered.get("5:5"));
        assertEquals(Integer.valueOf(4), offered.get("5:7"));
        assertEquals(4, offered.size());
        // P181: a name «… ISZ» an earlier version gave goes back to the zoom alone; the owner's own name stays
        String plain = ModuleRegistry.label(deleted);
        prefs.edit().putString("module_name_" + deleted, plain + " ISZ").apply();
        VivoPd2454NativeRaw.ensureModules(cts);
        assertEquals(plain, ModuleRegistry.label(deleted));
        prefs.edit().putString("module_name_" + deleted, "Tele ISZ mine").apply();
        VivoPd2454NativeRaw.ensureModules(cts);
        assertEquals("Tele ISZ mine", ModuleRegistry.label(deleted));
        prefs.edit().putString("module_name_" + deleted, plain).apply();
        assertEquals("2×", VivoPd2454NativeRaw.plainName("2× ISZ", "2×"));
        assertEquals("0.8×", VivoPd2454NativeRaw.plainName("0.8×", "0.8×"));
        VivoPd2454Isz.ensureModules(cts);
        assertEquals(7, shown().size());
        assertNotNull(deleted);
        assertTrue(ModuleRegistry.remove(deleted));
        VivoPd2454NativeRaw.ensureModules(cts);
        assertEquals(6, shown().size());
    }

    @Test public void nativeForceIsAnArrayOnEveryBuilderAndInactiveInVideo() throws Exception {
        nativeContext();
        prefs.edit().putString("module_auto_back0", "3").apply();
        save("back0", VivoQcomIsz.sessionKey(3));
        for (int i = 0; i < 3; i++) {
            CaptureRequest.Builder request = builder();
            VivoPd2454NativeRaw.apply(request, "3");
            assertArrayEquals(new int[]{3}, request.get(new CaptureRequest.Key<>(VivoQcomIsz.KEY, int[].class)));
        }
        ScameraApp.getSettings().selectedMode = com.particlesdevs.photoncamera.api.CameraMode.MOTION;
        assertTrue(VivoPd2454NativeRaw.active("3"));
        ScameraApp.getSettings().selectedMode = com.particlesdevs.photoncamera.api.CameraMode.NIGHT;
        assertTrue(VivoPd2454NativeRaw.active("3"));
        ScameraApp.getSettings().selectedMode = com.particlesdevs.photoncamera.api.CameraMode.VIDEO;
        assertFalse(VivoPd2454NativeRaw.active("3"));
        CaptureRequest.Builder request = builder();
        VivoPd2454NativeRaw.apply(request, "3");
        assertNull(request.get(new CaptureRequest.Key<>(VivoQcomIsz.KEY, int[].class)));
    }

    private String offer() {
        VivoPd2454Isz.ensureModules(context);
        for (String slot : shown()) if (!"back0".equals(slot)) return slot;
        fail("no offered tele module");
        return null;
    }

    private void save(String slot, VendorTagUtils.TunableKey... keys) {
        TunableKeyManager.saveKeys(context, slot, Arrays.asList(keys));
    }

    private CaptureRequest.Builder builder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class,
                String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "5", null);
    }

    @Test public void addsOneDeletableFourTimesTeleModuleWithoutChangingOwnerKeys() {
        VendorTagUtils.TunableKey owner = new VendorTagUtils.TunableKey(
                "CaptureRequest", CaptureRequest.JPEG_QUALITY.getName(), Byte.class, (byte) 92);
        save("back0", owner);
        String original = prefs.getString("pref_sensorconfig_back0_tunablekeys", "");
        String slot = offer();
        assertEquals("back0", ModuleRegistry.duplicateOf(slot));
        assertEquals("5", ModuleRegistry.camera(slot));
        assertEquals(14.8f, ModuleRegistry.zoom(slot), .0001f);
        assertEquals(14.8f, ModuleRegistry.nativeRatio(slot), .0001f);
        assertTrue(ModuleRegistry.sensorCrop(slot));
        assertTrue(ModuleRegistry.label(slot).contains("remosaic") || ModuleRegistry.label(slot).contains("ремозаик"));
        assertEquals(original, prefs.getString("pref_sensorconfig_back0_tunablekeys", ""));
        List<VendorTagUtils.TunableKey> copied = TunableKeyManager.loadKeys(context, slot);
        assertEquals(2, copied.size());
        assertEquals(owner.name, copied.get(0).name);
        assertEquals("92", copied.get(0).value);
        assertEquals(VendorTagUtils.TunableKey.TYPE_SESSION, copied.get(1).type);
        assertEquals("Integer", copied.get(1).valueType);
        assertEquals("6", copied.get(1).value);
        assertTrue(copied.get(1).enabled);
        assertEquals(0, MosaicBlockStore.declaredBlock(ModuleRegistry.sensorMode(slot)));
        VivoPd2454Isz.ensureModules(context);
        assertEquals(2, shown().size());
        assertTrue(ModuleRegistry.remove(slot));
        VivoPd2454Isz.ensureModules(context);
        assertEquals(Arrays.asList("back0"), shown());
    }

    @Test public void anExistingManualModeSixIsNotDuplicatedOrRewritten() {
        prefs.edit().putBoolean("module_visible_back1", true).putString("module_name_back1", "Owner tele")
                .putString("module_zoom_back1", "15.25").putString("module_duplicate_back1", "back0").apply();
        save("back1", VivoQcomIsz.sessionKey(6), new VendorTagUtils.TunableKey(
                "CaptureRequest", CaptureRequest.JPEG_ORIENTATION.getName(), Integer.class, 90));
        String before = prefs.getString("pref_sensorconfig_back1_tunablekeys", "");
        VivoPd2454Isz.ensureModules(context);
        assertEquals(2, shown().size());
        assertEquals("Owner tele", ModuleRegistry.label("back1"));
        assertEquals(15.25f, ModuleRegistry.zoom("back1"), 0f);
        assertEquals(before, prefs.getString("pref_sensorconfig_back1_tunablekeys", ""));
    }

    @Test public void aFailedOfferCanRetryButACreatedDeletedModuleDoesNotReturn() {
        for (int i = 1; i < 9; i++) prefs.edit().putBoolean("module_visible_back" + i, true)
                .putString("module_auto_back" + i, "3").apply();
        VivoPd2454Isz.ensureModules(context);
        assertFalse(prefs.getBoolean(VivoPd2454Isz.OFFERED, false));
        prefs.edit().putBoolean("module_visible_back8", false).apply();
        VivoPd2454Isz.ensureModules(context);
        assertTrue(ModuleRegistry.visible("back8"));
        assertTrue(prefs.getBoolean(VivoPd2454Isz.OFFERED, false));
        assertTrue(ModuleRegistry.remove("back8"));
        VivoPd2454Isz.ensureModules(context);
        assertFalse(ModuleRegistry.visible("back8"));
    }

    @Test public void aNinthBackSlotOffersFallbackWithoutOverwritingAnyOfEightOwnerModules() {
        for (int i = 0; i < 8; i++) {
            prefs.edit().putBoolean("module_visible_back" + i, true)
                    .putString("module_name_back" + i, "Owner " + i).apply();
            save("back" + i, new VendorTagUtils.TunableKey(
                    "CaptureRequest", CaptureRequest.JPEG_ORIENTATION.getName(), Integer.class, i * 90));
        }
        Map<String, ?> before = prefs.getAll();
        CameraLensData tele = new CameraLensData("5");
        tele.setZoomFactor(3.7f);
        List<String> slots = ModuleRegistry.initialize("back", Arrays.asList(tele));
        assertEquals(9, slots.size());
        assertEquals("back8", slots.get(8));
        assertFalse(ModuleRegistry.visible("back8"));
        VivoPd2454Isz.ensureModules(context);
        assertEquals(9, shown().size());
        assertEquals("back0", ModuleRegistry.duplicateOf("back8"));
        assertEquals(14.8f, ModuleRegistry.zoom("back8"), .0001f);
        assertEquals(6, ModuleRegistry.sensorMode("back8"));
        for (Map.Entry<String, ?> entry : before.entrySet())
            assertEquals(entry.getKey(), entry.getValue(), prefs.getAll().get(entry.getKey()));
        ModuleRegistry.select("back8");
        assertTrue(VivoPd2454Isz.active("5"));
        assertTrue(ModuleRegistry.remove("back8"));
        VivoPd2454Isz.ensureModules(context);
        assertFalse(ModuleRegistry.visible("back8"));
    }

    @Test public void capacityAndInitializationStayEightForFrontAndOtherDevices() {
        CameraLensData tele = new CameraLensData("5");
        tele.setZoomFactor(3.7f);
        assertEquals(9, ModuleRegistry.capacity("back"));
        assertEquals(8, ModuleRegistry.capacity("front"));
        assertEquals(8, ModuleRegistry.initialize("front", Arrays.asList(tele)).size());
        assertFalse(prefs.contains("module_auto_front8"));
        vivo.when(VivoQcomModes::device).thenReturn(false);
        assertEquals(8, ModuleRegistry.capacity("back"));
        assertEquals(8, ModuleRegistry.initialize("back", Arrays.asList(tele)).size());
        assertFalse(prefs.contains("module_auto_back8"));
    }

    @Test public void onlyPd2454AndAnExistingVisiblePlainTeleSourceAreOffered() {
        vivo.when(VivoQcomModes::device).thenReturn(false);
        VivoPd2454Isz.ensureModules(context);
        assertEquals(1, shown().size());
        assertFalse(prefs.contains(VivoPd2454Isz.OFFERED));
        vivo.when(VivoQcomModes::device).thenReturn(true);
        prefs.edit().putString("module_auto_back0", "3").apply();
        VivoPd2454Isz.ensureModules(context);
        assertEquals(1, shown().size());
        assertFalse(prefs.contains(VivoPd2454Isz.OFFERED));
        prefs.edit().putString("module_auto_back0", "5").putBoolean("module_visible_back0", false).apply();
        VivoPd2454Isz.ensureModules(context);
        assertFalse(prefs.contains(VivoPd2454Isz.OFFERED));
        prefs.edit().putBoolean("module_visible_back0", true).apply();
        save("back0", VivoQcomIsz.sessionKey(7));
        VivoPd2454Isz.ensureModules(context);
        assertEquals(1, shown().size());
        assertFalse(prefs.contains(VivoPd2454Isz.OFFERED));
    }

    @Test public void selectionOfOfferedTeleDisablesOtherFeaturesAndUsesTheFourTimesBase() {
        String slot = offer();
        PixelPro10.setFullRes(true);
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        ModuleRegistry.select(slot);
        assertFalse(PixelPro10.fullResOn());
        assertFalse(Dcg14Mode.on());
        assertTrue(VivoPd2454Isz.active("5"));
        assertFalse(VivoPd2454Isz.active("3"));
        assertEquals(14.8f, ModuleRegistry.nativeRatio(slot), .0001f);
    }

    @Test public void lastNativePassRestoresForceSixAfterProviderRequestDefaults() throws Exception {
        String slot = offer();
        ModuleRegistry.select(slot);
        CaptureRequest.Builder builder = builder();
        BuilderState state = Shadow.extract(builder);
        Map<String, Object> values = state.logical;
        TunableKeyManager.applySessionKeys(builder, "5");
        assertEquals(6, values.get(VivoQcomIsz.KEY));
        vivo.when(() -> VivoQcomModes.apply(builder, CaptureController.mCameraCharacteristics, "5"))
                .thenAnswer(call -> {
                    builder.set(new CaptureRequest.Key<>(VivoQcomIsz.KEY, Integer.class), 0);
                    return "";
                });
        try (MockedStatic<HiddenFeatures> hidden = mockStatic(HiddenFeatures.class)) {
            for (int i = 0; i < 3; i++) {
                VendorTagUtils.builderSessionApply(builder, i > 0, false, "5");
                assertEquals(6, values.get(VivoQcomIsz.KEY));
                values.put(VivoQcomIsz.KEY, 0);
            }
        }
        assertEquals(0, state.physicalWrites);
    }

    @Test public void disabledResultWrongTypeOrOtherForcedModesAreNeverMirrored() throws Exception {
        String slot = offer();
        ModuleRegistry.select(slot);
        CaptureRequest.Builder builder = builder();
        VendorTagUtils.TunableKey disabled = VivoQcomIsz.sessionKey(6);
        disabled.enabled = false;
        for (VendorTagUtils.TunableKey key : Arrays.asList(disabled,
                new VendorTagUtils.TunableKey("CaptureResult", VivoQcomIsz.KEY, Integer.class, 6),
                new VendorTagUtils.TunableKey("CaptureRequest", VivoQcomIsz.KEY, Integer.class, 6),
                new VendorTagUtils.TunableKey(VendorTagUtils.TunableKey.TYPE_SESSION, VivoQcomIsz.KEY, int[].class, new int[]{6}),
                VivoQcomIsz.sessionKey(5), VivoQcomIsz.sessionKey(7))) {
            save(slot, key);
            VivoPd2454Isz.mirrorRequest(builder, "5");
        }
        BuilderState state = Shadow.extract(builder);
        assertTrue(state.logical.isEmpty());
        assertEquals(0, state.physicalWrites);
    }

    @Test public void fullResDcgAndOtherPhonesDoNotReceiveTheFallbackRequestKey() throws Exception {
        ModuleRegistry.select(offer());
        CaptureRequest.Builder builder = builder();
        PixelPro10.setFullRes(true);
        VivoPd2454Isz.mirrorRequest(builder, "5");
        PixelPro10.setFullRes(false);
        Dcg14Mode.setMode(Dcg14Mode.DCG);
        VivoPd2454Isz.mirrorRequest(builder, "5");
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        vivo.when(VivoQcomModes::device).thenReturn(false);
        VivoPd2454Isz.mirrorRequest(builder, "5");
        BuilderState state = Shadow.extract(builder);
        assertTrue(state.logical.isEmpty());
        assertEquals(0, state.physicalWrites);
    }
}
