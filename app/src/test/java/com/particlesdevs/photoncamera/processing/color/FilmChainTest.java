package com.particlesdevs.photoncamera.processing.color;

import android.app.Application;
import android.content.Context;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P369: FilmChain's two tables - the display chain of the ISP viewfinder and the settings' preview, built from the settings its key
 * stands for (viewfinder-filmchain-cache-key: a key asked for after the settings moved kept the newer settings' table), and the
 * photo's scene table (FilmChain.scene), keyed by what it depends on, in its own cache.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FilmChainTest {
    /** One of our looks as the chosen LUT (FilmLooks). */
    static final String WARM = FilmLooks.PREFIX + "warm400";
    private Context context;
    private MockedStatic<ScameraApp> app;
    private SettingsManager manager;

    @BeforeClass
    public static void assets() {
        FilmSpectralTest.assets();
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        app.when(() -> ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        app.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PreferenceKeys.initialise(manager);
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, "");
        stock("kodak_portra_400");
        FilmChain.forget();   // the caches are the process's: another test's table is not this one's
    }

    @After
    public void tearDown() {
        app.close();
    }

    private void stock(String id) {
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmSim.STOCK_KEY, id);
    }

    static FilmSim.Settings set(String stock, String print, String format, float grain, float halation, float softness, float ev,
                                float printEv, float tone, float m, float y, float strength) {
        return new FilmSim.Settings(FilmSim.stock(stock), FilmSim.print(print, FilmSim.stock(stock)), FilmSim.format(format), grain,
                halation, softness, ev, printEv, tone, m, y, strength);
    }

    @Test
    public void aChainIsBuiltFromItsKeyNotFromTheSettingsNow() {
        final FilmChain.State a = FilmChain.current();
        assertTrue(a.key, a.key.startsWith(FilmChain.PREFIX + "kodak_portra_400|"));
        // the settings move on before the viewfinder's loader builds the older key
        stock("fujifilm_velvia_100");
        final FilmLut lut = FilmChain.lut(context, a.key);
        assertNotNull(lut);
        assertEquals("the table of the key asked for", "Kodak Portra 400", lut.title);
        stock("kodak_portra_400");
        assertSame("cached under its key", lut, FilmChain.lut(context, a.key));
        // and the table is the one the key's settings make
        final FilmSim.Settings now = FilmSim.current();
        final FilmLut want = FilmChain.compose(FilmSim.lut(now, FilmLooks.SIZE), now.strength, null, 0f);
        assertArrayEquals(want.rgb, lut.rgb, 0f);
    }

    @Test
    public void theSnapshotsChainIsTheSettingsChain() {
        final FilmSim.Settings sim = FilmSim.current();
        assertNotNull(sim);
        assertEquals(FilmChain.current().key, FilmChain.current(sim, "", 1f).key);
        assertEquals(FilmChain.current().strength, FilmChain.current(sim, "", 1f).strength, 0f);
        final FilmChain.State none = FilmChain.current(null, WARM, .6f);
        assertEquals("without a film the chosen LUT, as before", WARM, none.key);
        assertEquals(.6f, none.strength, 0f);
        assertEquals(sim.lutKey(), FilmSim.Settings.forLut(sim.lutKey(), sim.strength).lutKey());
        final FilmSim.Settings odd = set("kodak_vision3_500t", "kodak_2393", "135", 1, 1, 1, .3f, -.25f, -.37f, 12, -7, .57f);
        final FilmSim.Settings back = FilmSim.Settings.forLut(odd.lutKey(), odd.strength);
        assertEquals(odd.lutKey(), back.lutKey());
        assertEquals(odd.tone, back.tone, 0f);
        assertEquals(.57f, back.strength, 0f);
        assertEquals("a slide has no print in its key", "-", FilmSim.Settings.forLut(
                set("fujifilm_velvia_100", "", "135", 1, 1, 1, 0, 0, 0, 0, 0, 1).lutKey(), 1).lutKey().split("\\|")[1]);
        assertNull(FilmSim.Settings.forLut("no_such_stock|-|0.00|0.00|0.00|0.00|0.00", 1));
        assertNull(FilmSim.Settings.forLut("kodak_portra_400|x", 1));
    }

    @Test
    public void theSceneKeyFollowsWhatTheTableDependsOn() {
        final FilmSim.Settings base = set("kodak_portra_400", "kodak_portra_endura", "135", 1, 1, 1, 0, 0, 0, 0, 0, 1);
        final String k = FilmChain.sceneKey(base, "", 1f);
        // the table's inputs: stock, print, film / print exposure, contrast, the enlarger's filters, the chosen LUT and its strength
        final FilmSim.Settings[] moved = {set("kodak_portra_160", "kodak_portra_endura", "135", 1, 1, 1, 0, 0, 0, 0, 0, 1),
                set("kodak_portra_400", "kodak_endura_premier", "135", 1, 1, 1, 0, 0, 0, 0, 0, 1),
                set("kodak_portra_400", "kodak_portra_endura", "135", 1, 1, 1, .5f, 0, 0, 0, 0, 1),
                set("kodak_portra_400", "kodak_portra_endura", "135", 1, 1, 1, 0, .25f, 0, 0, 0, 1),
                set("kodak_portra_400", "kodak_portra_endura", "135", 1, 1, 1, 0, 0, .3f, 0, 0, 1),
                set("kodak_portra_400", "kodak_portra_endura", "135", 1, 1, 1, 0, 0, 0, 5, 0, 1),
                set("kodak_portra_400", "kodak_portra_endura", "135", 1, 1, 1, 0, 0, 0, 0, -5, 1)};
        for (FilmSim.Settings s : moved) assertNotEquals(s.lutKey(), k, FilmChain.sceneKey(s, "", 1f));
        assertNotEquals(k, FilmChain.sceneKey(base, WARM, 1f));
        assertNotEquals(FilmChain.sceneKey(base, WARM, 1f), FilmChain.sceneKey(base, WARM, .5f));
        assertEquals("no LUT: its strength is nothing", k, FilmChain.sceneKey(base, "", .3f));
        // not the effects, nor the strength (the shader's mix)
        final FilmSim.Settings[] same = {set("kodak_portra_400", "kodak_portra_endura", "off", 1, 1, 1, 0, 0, 0, 0, 0, 1),
                set("kodak_portra_400", "kodak_portra_endura", "67", 2, 0, 3, 0, 0, 0, 0, 0, 1),
                set("kodak_portra_400", "kodak_portra_endura", "135", 1, 1, 1, 0, 0, 0, 0, 0, .4f)};
        for (FilmSim.Settings s : same) assertEquals(k, FilmChain.sceneKey(s, "", 1f));
        assertNotEquals("the display chain's keys are another family", FilmChain.current(base, "", 1f).key, k);
    }

    /**
     * P380: the DIR couplers' amount is the table key's 8th field - the settings' value round-trips through the chain's key and the
     * scene key, the chain's table is built at it (not at the default), the film's diffusion (an effect) leaves both keys.
     */
    @Test
    public void theCouplersAmountRoundTripsThroughTheKeys() {
        final String g = SettingsManager.SCOPE_GLOBAL;
        manager.set(g, FilmSim.DIR_KEY, "35");
        manager.set(g, FilmSim.DIFFUSION_KEY, LensFilters.PEARLESCENT);
        manager.set(g, FilmSim.DIFFUSION_STRENGTH_KEY, "200");
        final FilmSim.Settings sim = FilmSim.current();
        assertEquals(.35f, sim.couplers, 1e-6f);
        assertEquals(LensFilters.PEARLESCENT, sim.diffusion);
        assertEquals(2f, sim.diffusionGrade, 0f);
        final FilmChain.State st = FilmChain.current();
        assertTrue(st.key, st.key.startsWith(FilmChain.PREFIX + sim.lutKey() + "|1.00|"));
        assertEquals(11, st.key.substring(FilmChain.PREFIX.length()).split("\\|", -1).length);
        final FilmSim.Settings back = FilmSim.Settings.forLut(sim.lutKey(), sim.strength);
        assertEquals(sim.lutKey(), back.lutKey());
        assertEquals(.35f, back.couplers, 1e-6f);
        // the chain's table is the one of its couplers' amount
        final FilmLut lut = FilmChain.lut(context, st.key);
        assertNotNull(lut);
        final FilmLut want = FilmChain.compose(FilmSim.lut(sim, FilmLooks.SIZE), sim.strength, null, 0f);
        assertArrayEquals(want.rgb, lut.rgb, 0f);
        final FilmSim.Settings full = new FilmSim.Settings(sim.stock, sim.print, sim.format, sim.grain, sim.halation, sim.softness, sim.exposure,
                sim.printEv, sim.tone, sim.printM, sim.printY, sim.strength, sim.diffusion, sim.diffusionGrade, 1f);
        final float[] other = FilmChain.compose(FilmSim.lut(full, FilmLooks.SIZE), 1f, null, 0f).rgb;
        double diff = 0;
        for (int i = 0; i < other.length; i++) diff = Math.max(diff, Math.abs(other[i] - lut.rgb[i]));
        assertTrue("35 % is not 100 %: " + diff, diff > .02);
        // the scene key: the couplers in, the diffusion out
        assertNotEquals(FilmChain.sceneKey(full, "", 1f), FilmChain.sceneKey(sim, "", 1f));
        final FilmSim.Settings plain = new FilmSim.Settings(sim.stock, sim.print, sim.format, sim.grain, sim.halation, sim.softness, sim.exposure,
                sim.printEv, sim.tone, sim.printM, sim.printY, sim.strength, "", .5f, sim.couplers);
        assertEquals(FilmChain.sceneKey(plain, "", 1f), FilmChain.sceneKey(sim, "", 1f));
        assertEquals(FilmChain.current(plain, "", 1f).key, st.key);
        // the defaults: the key of before P380 with "|1.00" after it (today's table)
        manager.set(g, FilmSim.DIR_KEY, "100");
        assertTrue(FilmSim.current().lutKey().endsWith("|0.00|0.00|1.00"));
    }

    @Test
    public void theSceneTableHasItsOwnCache() {
        final FilmSim.Settings sim = FilmSim.current();
        final FilmChain.Scene s1 = FilmChain.scene(context, sim, "", 1f);
        assertNotNull(s1);
        assertEquals(FilmChain.sceneKey(sim, "", 1f), s1.key);
        assertEquals(sim.strength, s1.strength, 0f);
        final FilmLut chain = FilmChain.lut(context, FilmChain.current().key);
        assertNotNull(chain);
        assertNotSame(chain, s1.lut);
        assertSame("the display chain does not push the scene table out", s1.lut, FilmChain.scene(context, sim, "", 1f).lut);
        assertSame("nor the scene table the chain", chain, FilmChain.lut(context, FilmChain.current().key));
        // the strength is the shader's: the same table at another strength
        final FilmSim.Settings half = new FilmSim.Settings(sim.stock, sim.print, sim.format, sim.grain, sim.halation, sim.softness,
                sim.exposure, sim.printEv, sim.tone, sim.printM, sim.printY, .5f);
        final FilmChain.Scene s2 = FilmChain.scene(context, half, "", 1f);
        assertSame(s1.lut, s2.lut);
        assertEquals(.5f, s2.strength, 0f);
        // the film's print, then the chosen LUT on it (film strength 1 in the table)
        final FilmChain.Scene warm = FilmChain.scene(context, sim, WARM, 1f);
        final FilmLut look = FilmLut.chosen(context, WARM);
        assertNotNull(warm);
        assertNotNull(look);
        final int mid = (20 * 33 + 20) * 33 + 20;   // the node at mid grey (FilmSim.lutScene's grid)
        final float[] print = {s1.lut.rgb[mid * 3], s1.lut.rgb[mid * 3 + 1], s1.lut.rgb[mid * 3 + 2]};
        final float[] want = FilmChain.sample(look, print);
        for (int c = 0; c < 3; c++) assertEquals(want[c], warm.lut.rgb[mid * 3 + c], 1e-5f);
    }
}
