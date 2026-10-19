package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.api.VendorTagUtils;

import org.junit.Test;

/** P122: the vivo X300 Ultra's sensor-mode modules (the owner's table of working modes; session keys only). */
public class VivoQcomIszTest {
    @Test public void bothX300UltraVariantsOnly() {
        assertTrue(VivoQcomIsz.x300Ultra("vivo", "PD2547", "V2547DA", null));
        assertTrue(VivoQcomIsz.x300Ultra("vivo", "PD2547", "V2547A", ""));
        assertTrue(VivoQcomIsz.x300Ultra("vivo", "V2562", "V2562", null));
        assertTrue("another region, by its market name", VivoQcomIsz.x300Ultra("vivo", "PD2599", "V2599", "vivo X300 Ultra"));
        assertFalse(VivoQcomIsz.x300Ultra("vivo", "PD2599", "V2599", "vivo X300 Pro"));
        assertFalse("the X200 Ultra is VivoQcomModes'", VivoQcomIsz.x300Ultra("vivo", "PD2454", "V2454A", "vivo X200 Ultra"));
        assertFalse(VivoQcomIsz.x300Ultra("OPPO", "PD2547", "V2547DA", null));
        assertFalse(VivoQcomIsz.x300Ultra(null, null, null, null));
    }

    @Test public void cameraRolesFromTheVivoCameraType() {
        assertEquals("main", VivoQcomIsz.role("Master"));
        assertEquals("wide", VivoQcomIsz.role("Wide"));
        assertEquals("tele", VivoQcomIsz.role("Tele3P5x"));
        assertNull("the extender profile", VivoQcomIsz.role("Tele3P5xExternal"));
        assertNull(VivoQcomIsz.role("MultiSpectrum"));
        assertNull(VivoQcomIsz.role(null));
    }

    @Test public void dcgOnAllThreeCameras() {
        assertEquals(17, VivoQcomIsz.dcgMode("main"));
        assertEquals(11, VivoQcomIsz.dcgMode("wide"));
        assertEquals(20, VivoQcomIsz.dcgMode("tele"));
        assertEquals(0, VivoQcomIsz.dcgMode(null));
        assertEquals(Dcg14Mode.DCG, Dcg14Mode.next(Dcg14Mode.OFF));
        assertEquals("on / off, no LOFIC step any more", Dcg14Mode.OFF, Dcg14Mode.next(Dcg14Mode.DCG));
    }

    @Test public void modulesCarryTheModeAsASessionKey() {
        VendorTagUtils.TunableKey k = VivoQcomIsz.sessionKey(VivoQcomIsz.TELE_4X);
        assertEquals(VendorTagUtils.TunableKey.TYPE_SESSION, k.type);
        assertEquals("vivo.control.forceSensorMode", k.name);
        assertEquals("7", k.value);
        assertEquals("7.4×", VivoQcomIsz.label(3.7f * 2));
        assertEquals("15×", VivoQcomIsz.label(3.7f * 4));
        assertEquals("2×", VivoQcomIsz.label(2f));
        assertEquals("1.2×", VivoQcomIsz.label(0.6f * 2));
    }

    /** P274: the session's forceSensorMode goes on every request (the camera service drops a session key a request lacks). */
    @Test public void sessionModeIsRepeatedOnEveryRequest() {
        android.hardware.camera2.CaptureRequest.Builder request = org.mockito.Mockito.mock(android.hardware.camera2.CaptureRequest.Builder.class);
        VivoQcomIsz.sessionStart();
        VivoQcomIsz.mirrorRequest(request);
        org.mockito.Mockito.verifyNoInteractions(request);
        VivoQcomIsz.remember(VivoQcomIsz.TELE_2X);
        VivoQcomIsz.mirrorRequest(request);
        org.mockito.Mockito.verify(request).set(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(VivoQcomIsz.TELE_2X));
        VivoQcomIsz.sessionStart();
        android.hardware.camera2.CaptureRequest.Builder next = org.mockito.Mockito.mock(android.hardware.camera2.CaptureRequest.Builder.class);
        VivoQcomIsz.mirrorRequest(next);
        org.mockito.Mockito.verifyNoInteractions(next);
        VivoQcomIsz.mirrorRequest(null);
    }

    /** P275: the SDK lease is an X300 Ultra ISZ module's only (never on another phone, never without a camera). */
    @Test public void sdkLeaseOnlyForX300UltraIszModules() {
        assertFalse(VivoQcomIsz.leaseWanted(null));
        assertFalse("not an X300 Ultra in the unit test", VivoQcomIsz.leaseWanted("5"));
    }

    /** P126 (X300 Ultra 2026-10-14): two «4.8×» and two «9.6×» on the tele, a plain «2×» crop next to the ISZ «2×». */
    @Test public void extraModulesGo() {
        java.util.List<VivoQcomIsz.Mod> mods = java.util.Arrays.asList(
                new VivoQcomIsz.Mod("back0", "4", 0.4f, 0, false),
                new VivoQcomIsz.Mod("back1", "3", 1f, 0, false),
                new VivoQcomIsz.Mod("back2", "5", 2.4f, 0, false),
                new VivoQcomIsz.Mod("back3", "3", 2f, 0, true),   // IszModule's CROPPED_RAW «2×»
                new VivoQcomIsz.Mod("back4", "5", 4.8f, 5, true),
                new VivoQcomIsz.Mod("back5", "5", 4.8f, 5, true),  // the second «4.8×»
                new VivoQcomIsz.Mod("back6", "3", 2f, 10, true),
                new VivoQcomIsz.Mod("back7", "5", 9.6f, 7, true));
        assertEquals(java.util.Arrays.asList("back5", "back3"), VivoQcomIsz.extras(mods, true));
        assertEquals("the plain twin only once", java.util.Collections.singletonList("back5"), VivoQcomIsz.extras(mods, false));
    }

    // ---- P214i: the owner's list (2026-10-18) as the table, lenses by focal length, the frame check and its fallback

    /** The SensorModeTable of cameras 3 (main), 4 (ultra-wide) and 5 (tele) in x300u_camera.txt (Android 17, vendorVcf). */
    static final String MAIN_TABLE = "34 3 16320 12256 10 8160 6128 21 8160 6128 30 8160 6128 30 4080 3064 30 4080 3064 30 4080 3064 30"
            + " 4080 3064 30 4080 3064 30 4080 3064 30 4080 3064 30 2040 1532 30 4080 3064 60 4080 3064 50 4080 2616 30 4080 2616 30"
            + " 4080 3064 30 4080 3064 30 4080 3064 60 4080 3064 60 4080 2616 60 4080 2616 60 4080 2304 120 3456 1944 120 1920 1080 240"
            + " 8160 4608 30 4048 3060 30 4048 3060 30 4048 3060 30 2432 1824 30 4080 3064 30 4080 3064 30 4048 3060 30 4048 3060 30";
    static final String WIDE_TABLE = "24 3 8192 6144 30 4096 3072 30 4096 3072 30 4096 3072 30 8192 6144 30 4096 2632 60 4096 2632 30"
            + " 4096 3072 30 4096 2632 60 4096 3072 30 4096 2632 30 4096 3072 30 3456 1944 120 1920 1080 240 4096 2304 120 2048 1536 30"
            + " 2048 1536 30 4096 3072 30 4096 3072 30 4096 3072 60 8160 4608 30 4096 3072 30 1536 1152 30 4096 3072 60";
    static final String TELE_TABLE = "33 3 16320 12288 7 8160 6144 30 4080 3072 30 4080 3072 30 4080 3072 30 4080 3072 30 4080 3072 30"
            + " 4080 3072 30 4080 3072 60 4080 2616 30 4080 2616 30 3456 1944 120 4080 2296 120 4080 2296 120 8160 6144 30 1920 1080 240"
            + " 4080 2616 60 4080 2616 60 8160 4608 30 4080 3072 60 4080 3072 30 4080 2616 30 4080 2616 60 4080 3072 30 4080 3072 60"
            + " 4080 3072 30 4080 3072 60 8160 6144 16 4080 3072 30 3840 2160 120 2040 1528 120 4080 3072 30 4080 2304 120";

    static int[] table(String text) {
        String[] parts = text.trim().split("\\s+");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    private static String modes(java.util.List<VivoQcomIsz.Row> rows) {
        StringBuilder out = new StringBuilder();
        for (VivoQcomIsz.Row r : rows) out.append(out.length() == 0 ? "" : " ").append(r.mode).append('@').append(r.factor).append('x');
        return out.toString();
    }

    @Test public void theModulesAreTheQuadAndTetraRowsOfTheOwnersList() {
        assertEquals("14 mm: 21 = 2x ISZ Quad Bayer", "21@2x", modes(VivoQcomIsz.modules("wide")));
        assertEquals("35 mm: 10 = 2x ISZ Quad Bayer", "10@2x", modes(VivoQcomIsz.modules("main")));
        assertEquals("85 mm: 5 = 2x ISZ Quad, 7 = 4x ISZ Quad-Quad", "5@2x 7@4x", modes(VivoQcomIsz.modules("tele")));
        assertEquals("", modes(VivoQcomIsz.modules(null)));
        // the remosaiced ones are no «without remosaic» module and declare no mosaic
        for (int remosaiced : new int[]{6, 31}) {
            VivoQcomIsz.Row r = VivoQcomIsz.row("tele", remosaiced);
            assertEquals(VivoQcomIsz.Kind.REMOSAICED, r.kind);
            assertFalse(r.module());
            assertEquals(1, r.block());
            assertEquals(0, VivoQcomIsz.declaredBlock(r, false));
            assertFalse(VivoQcomIsz.iszModule("tele", remosaiced));
        }
        // the tele's video Quad rows are a mosaic (an owner-made module merges them natively) but no photo module
        for (int video : new int[]{10, 13, 17}) {
            VivoQcomIsz.Row r = VivoQcomIsz.row("tele", video);
            assertEquals(VivoQcomIsz.Kind.QUAD_2X_VIDEO, r.kind);
            assertFalse(r.module());
            assertEquals(2, VivoQcomIsz.declaredBlock(r, false));
        }
        assertTrue(VivoQcomIsz.iszModule("main", VivoQcomIsz.MAIN_2X));
        assertTrue(VivoQcomIsz.iszModule("wide", VivoQcomIsz.WIDE_2X));
        assertTrue(VivoQcomIsz.iszModule("tele", VivoQcomIsz.TELE_2X));
        assertTrue(VivoQcomIsz.iszModule("tele", VivoQcomIsz.TELE_4X));
        assertFalse("a mode of another lens", VivoQcomIsz.iszModule("main", VivoQcomIsz.TELE_2X));
        assertFalse("the main's mode 10 is not the tele's", VivoQcomIsz.iszModule("tele", VivoQcomIsz.MAIN_2X));
        assertNull("no DCG row in the ISZ list", VivoQcomIsz.row("tele", VivoQcomIsz.TELE_DCG));
        assertNull(VivoQcomIsz.row("main", VivoQcomIsz.MAIN_DCG));
        assertNull(VivoQcomIsz.row("wide", VivoQcomIsz.WIDE_DCG));
    }

    @Test public void theDeclaredBlockDrivesTheNativeMosaicMergeUntilTheFramesProveABayer() {
        assertEquals(2, VivoQcomIsz.declaredBlock(VivoQcomIsz.row("wide", 21), false));
        assertEquals(2, VivoQcomIsz.declaredBlock(VivoQcomIsz.row("main", 10), false));
        assertEquals(2, VivoQcomIsz.declaredBlock(VivoQcomIsz.row("tele", 5), false));
        assertEquals(4, VivoQcomIsz.declaredBlock(VivoQcomIsz.row("tele", 7), false));
        assertEquals("proved a plain Bayer: the stored block 1 merges", 0, VivoQcomIsz.declaredBlock(VivoQcomIsz.row("tele", 5), true));
        assertEquals(0, VivoQcomIsz.declaredBlock(VivoQcomIsz.row("tele", 7), true));
        assertEquals("a mode outside the list", 0, VivoQcomIsz.declaredBlock(VivoQcomIsz.row("main", 5), false));
        assertEquals(0, VivoQcomIsz.declaredBlock((VivoQcomIsz.Row) null, false));
        // off the X300 Ultra (and before a camera's role is known) the old rule stays
        assertEquals(2, VivoQcomIsz.declaredBlock("5", 5));
        assertEquals(4, VivoQcomIsz.declaredBlock("5", 7));
        assertEquals(0, VivoQcomIsz.declaredBlock("3", 3));
        assertEquals(0, VivoQcomIsz.declaredBlock("5", 0));
    }

    /** The 30912 log's lens list: 4.03 / 11.56 / 22.48 mm = 14.516 / 36.429 / 88.550 mm equivalent (36 mm over the width). */
    @Test public void theLensNamesFindTheirCamerasByFocalLength() {
        final float uwWidth = 36f * 4.03f / 14.516362f, mainWidth = 36f * 11.56f / 36.428574f, teleWidth = 36f * 22.48f / 88.550415f;
        assertEquals(14.52f, VivoQcomIsz.equivalent35(4.03f, uwWidth), 0.01f);
        assertEquals("wide", VivoQcomIsz.role(null, 1, new float[]{4.03f}, uwWidth));
        assertEquals("main", VivoQcomIsz.role(null, 1, new float[]{11.56f}, mainWidth));
        assertEquals("tele", VivoQcomIsz.role("", 1, new float[]{22.48f}, teleWidth));
        assertNull("the front camera", VivoQcomIsz.role(null, 0, new float[]{3.64f}, 5.2f));
        assertNull("no focal length", VivoQcomIsz.role(null, 1, new float[0], mainWidth));
        assertNull("no sensor size", VivoQcomIsz.role(null, 1, new float[]{11.56f}, 0f));
        assertEquals("a CameraType wins", "main", VivoQcomIsz.role("Master", 1, new float[]{22.48f}, teleWidth));
        assertNull("a CameraType that is no lens of ours stays none", VivoQcomIsz.role("Tele3P5xExternal", 1, new float[]{22.48f}, teleWidth));
        assertNull("between the lenses", VivoQcomIsz.roleByFocal(24f));
        assertNull(VivoQcomIsz.roleByFocal(60f));
        assertNull(VivoQcomIsz.roleByFocal(200f));
        assertNull(VivoQcomIsz.roleByFocal(Float.NaN));
    }

    @Test public void aModuleIsOfferedOnlyWhereTheCamerasRowHasItsRawSize() {
        final int[] main = table(MAIN_TABLE), wide = table(WIDE_TABLE), tele = table(TELE_TABLE);
        assertEquals(2 + 34 * 3, main.length);
        assertEquals(2 + 24 * 3, wide.length);
        assertEquals(2 + 33 * 3, tele.length);
        assertTrue(VivoQcomIsz.rowFits(main, VivoQcomIsz.MAIN_2X, 4080, 3064));
        assertTrue(VivoQcomIsz.rowFits(wide, VivoQcomIsz.WIDE_2X, 4096, 3072));
        assertTrue(VivoQcomIsz.rowFits(tele, VivoQcomIsz.TELE_2X, 4080, 3072));
        assertTrue(VivoQcomIsz.rowFits(tele, VivoQcomIsz.TELE_4X, 4080, 3072));
        assertEquals("4080x3072@30", VivoQcomIsz.rowText(tele, VivoQcomIsz.TELE_4X));
        assertEquals("16:9 video row", "4080x2616@30", VivoQcomIsz.rowText(tele, 10));
        assertFalse("a row of another size (rows moved)", VivoQcomIsz.rowFits(tele, 10, 4080, 3072));
        assertFalse("the 200 MP row", VivoQcomIsz.rowFits(tele, 0, 4080, 3072));
        assertFalse("beyond the table", VivoQcomIsz.rowFits(wide, 24, 4096, 3072));
        assertEquals("none", VivoQcomIsz.rowText(wide, 30));
        assertTrue("no table: the owner's list holds", VivoQcomIsz.rowFits(null, VivoQcomIsz.TELE_2X, 4080, 3072));
        assertEquals("?", VivoQcomIsz.rowText(null, 5));
        assertFalse("a cut table", VivoQcomIsz.rowFits(new int[]{33, 3, 16320, 12288, 7}, 5, 4080, 3072));
    }

    /** Build 30912's tele (SCAMERA-debug.log (8)): «block 1 (confident; tile votes b1/b2/b4 15020/0/1 of 31266)», mode 5. */
    @Test public void threeClearBayerAnswersProveTheModeDidNotRun() {
        assertTrue(VivoQcomIsz.plainBayer(true, 1, new int[]{15020, 0, 1}));
        assertTrue(VivoQcomIsz.plainBayer(true, 1, new int[]{10751, 0, 5}));
        assertTrue(VivoQcomIsz.plainBayer(true, 1, new int[]{6864, 0, 0}));
        assertFalse("too few tiles (a flat scene)", VivoQcomIsz.plainBayer(true, 1, new int[]{120, 0, 0}));
        assertFalse("mosaic votes above 2 %", VivoQcomIsz.plainBayer(true, 1, new int[]{5000, 120, 0}));
        assertFalse(VivoQcomIsz.plainBayer(false, 1, new int[]{15020, 0, 1}));
        assertFalse(VivoQcomIsz.plainBayer(true, 2, new int[]{0, 15020, 0}));
        assertFalse(VivoQcomIsz.plainBayer(true, 1, null));

        VivoQcomIsz.StreamCheck tele = new VivoQcomIsz.StreamCheck("back4", "5", "7.4×", "tele", 5, 2);
        final VivoQcomIsz.StreamCheck.Verdict none = VivoQcomIsz.StreamCheck.Verdict.NONE;
        assertEquals(none, tele.observe(true, 1, new int[]{15020, 0, 1}));
        assertEquals("an unconfident answer says nothing", none, tele.observe(false, 1, new int[]{30, 0, 0}));
        assertEquals("a weak Bayer answer does not count", none, tele.observe(true, 1, new int[]{150, 0, 0}));
        assertEquals(none, tele.observe(true, 1, new int[]{14000, 0, 0}));
        assertEquals(VivoQcomIsz.StreamCheck.Verdict.PLAIN, tele.observe(true, 1, new int[]{13000, 1, 0}));
        assertEquals("once per session", none, tele.observe(true, 1, new int[]{13000, 0, 0}));

        VivoQcomIsz.StreamCheck tetra = new VivoQcomIsz.StreamCheck("back5", "5", "15×", "tele", 7, 4);
        assertEquals(none, tetra.observe(true, 1, new int[]{9000, 0, 0}));
        assertEquals("the declared Tetra: the mode runs", VivoQcomIsz.StreamCheck.Verdict.MOSAIC, tetra.observe(true, 4, new int[]{0, 0, 9000}));
        assertEquals(none, tetra.observe(true, 1, new int[]{9000, 0, 0}));
        assertEquals(none, tetra.observe(true, 1, new int[]{9000, 0, 0}));

        VivoQcomIsz.StreamCheck quad = new VivoQcomIsz.StreamCheck("back6", "3", "2×", "main", 10, 2);
        assertEquals("a Tetra answer is not the Quad it declares, nor a Bayer", none, quad.observe(true, 4, new int[]{0, 0, 9000}));
        assertEquals(VivoQcomIsz.StreamCheck.Verdict.MOSAIC, quad.observe(true, 2, new int[]{0, 9000, 0}));
    }

    /** Default RAW stream first, then build 30912's CROPPED_RAW one, then the fallback (Bayer merge, the zoom as a crop). */
    @Test public void aBayerOnTheDefaultStreamTriesCroppedRawThenFallsBack() {
        final VivoQcomIsz.StreamCheck.Verdict plain = VivoQcomIsz.StreamCheck.Verdict.PLAIN,
                mosaic = VivoQcomIsz.StreamCheck.Verdict.MOSAIC, none = VivoQcomIsz.StreamCheck.Verdict.NONE;
        final VivoQcomIsz.Trial n = VivoQcomIsz.Trial.NONE, p = VivoQcomIsz.Trial.PLAIN, c = VivoQcomIsz.Trial.CROPPED,
                b = VivoQcomIsz.Trial.BAYER;
        final VivoQcomIsz.Trial d = VivoQcomIsz.Trial.DEFAULT;
        // P275: CROPPED_RAW first (build 30906), the default stream second
        assertEquals("CROPPED_RAW's Bayer: the default stream next", p, VivoQcomIsz.next(n, plain, true, false));
        assertEquals("the default stream's Bayer too: the fallback", b, VivoQcomIsz.next(p, plain, false, false));
        assertEquals("no CROPPED_RAW to try (refused / not listed): straight to the fallback, no restart loop", b, VivoQcomIsz.next(n, plain, false, false));
        assertEquals("the mosaic with CROPPED_RAW: that stream stays", c, VivoQcomIsz.next(n, mosaic, true, false));
        assertEquals("the mosaic on the default stream: that stream stays", d, VivoQcomIsz.next(p, mosaic, false, false));
        assertEquals("a fallback whose frames are the mosaic again", c, VivoQcomIsz.next(b, mosaic, true, false));
        assertEquals(b, VivoQcomIsz.next(c, plain, true, false));
        assertEquals(b, VivoQcomIsz.next(d, plain, false, false));
        assertEquals(b, VivoQcomIsz.next(b, plain, true, false));
        assertEquals("scam_dev fixes the stream: no trial of the other one", b, VivoQcomIsz.next(n, plain, true, true));
        assertEquals(b, VivoQcomIsz.next(n, plain, false, true));
        assertEquals("no verdict, no change", p, VivoQcomIsz.next(p, none, false, false));
    }

    @Test public void aTrialCountsOnItsFirmwareOnly() {
        final String fw = "vivo/PD2547BF_EX_A/PD2547BF:17/17.1.6.36.W20/compiler10171200:user/release-keys";
        for (VivoQcomIsz.Trial t : VivoQcomIsz.Trial.values())
            assertEquals(t, VivoQcomIsz.noted(VivoQcomIsz.noteValue(fw, t), fw));
        assertEquals("an update: tried again", VivoQcomIsz.Trial.NONE,
                VivoQcomIsz.noted(VivoQcomIsz.noteValue(fw, VivoQcomIsz.Trial.BAYER), fw.replace("W20", "W21")));
        assertEquals(VivoQcomIsz.Trial.NONE, VivoQcomIsz.noted(null, fw));
        assertEquals(VivoQcomIsz.Trial.NONE, VivoQcomIsz.noted("", fw));
        assertEquals(VivoQcomIsz.Trial.NONE, VivoQcomIsz.noted(fw + "|SOMETHING", fw));
        assertEquals(VivoQcomIsz.Trial.NONE, VivoQcomIsz.noted("BAYER", fw));
        assertEquals("isz_5_7", VivoQcomIsz.noteKey("5", 7));
        assertEquals("no camera / mode, or not the X300 Ultra", VivoQcomIsz.Trial.NONE, VivoQcomIsz.trial(null, 5));
        assertFalse(VivoQcomIsz.bayerFallback("5", 0));
    }

    @Test public void dcgWithoutACameraTypeOnTheTeleOnly() {
        assertEquals("CameraType: all three as before", VivoQcomIsz.MAIN_DCG, VivoQcomIsz.dcgMode("Master", null));
        assertEquals(VivoQcomIsz.WIDE_DCG, VivoQcomIsz.dcgMode("Wide", "wide"));
        assertEquals(VivoQcomIsz.TELE_DCG, VivoQcomIsz.dcgMode("Tele3P5x", null));
        assertEquals("by focal length: the tele's DCG works", VivoQcomIsz.TELE_DCG, VivoQcomIsz.dcgMode(null, "tele"));
        assertEquals("the 35 mm's 14-bit mode turns highlights pink", 0, VivoQcomIsz.dcgMode(null, "main"));
        assertEquals("the 14 mm's too", 0, VivoQcomIsz.dcgMode("", "wide"));
        assertEquals(0, VivoQcomIsz.dcgMode(null, null));
    }
}
