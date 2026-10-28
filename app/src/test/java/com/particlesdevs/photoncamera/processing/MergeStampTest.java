package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** «Отметка ES / L на фото» (owner, 2026-10-28): the codes from the owner's logs of that day. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MergeStampTest {
    private static final String PLAN = "hybrid plan: N=2.980ms*ISO72 clip=0.0367 requests=[EXTRA_SHORT 0.372ms*ISO72 (x0.125), "
            + "EXTRA_SHORT 0.372ms*ISO72 (x0.125), EXTRA_SHORT 0.372ms*ISO72 (x0.125), LONG 2.980ms*ISO144 (x2.000), "
            + "LONG 2.980ms*ISO144 (x2.000), LONG 2.980ms*ISO144 (x2.000)]";

    /** The worker's lines of the owner's 10:11:42 shot (glimmer), cut to what the stamp reads. */
    private static final String APPLIED = "HYBRID SHASTA sharpness frame=20 score=1 base=1 pixels=9 (97.1 % of base)\n"
            + "HYBRID SHASTA sharpness frame=21 score=1 base=1 pixels=9 (96.0 % of base)\n"
            + "HYBRID SHASTA sharpness frame=22 score=1 base=1 pixels=9 (95.5 % of base)\n"
            + "HYBRID BENTO frame=23 invalid=3088 hole=119 largestHole=368 usClipped=0.001788\n"
            + "HYBRID BENTO: applied (success) motion=1.405631% of 205815 cells clipped=0.051641 usClippedRatio=0.001788\n"
            + "HYBRID STAGES ms: align=653.8 localAlign=73.3 mask=77.9 merge=1565.0 total=2452.7 merged=26 droppedBracketed=0 bento=1\n";

    @Test
    public void bothFramesUsed() {
        assertArrayEquals(new String[]{"ES ✓3", "L ✓3"}, MergeStamp.lines(PLAN, 3, 3, 3, 3, APPLIED));
    }

    @Test
    public void theMergeRefused() {
        final String report = "HYBRID SHASTA sharpness frame=20 score=1 base=1 pixels=9 (97.1 % of base)\n"
                + "HYBRID SHASTA sharpness frame=21 score=1 base=1 pixels=9 (28.4 % of base)\n"
                + "HYBRID SHASTA sharpness frame=22 score=1 base=1 pixels=9 (31.0 % of base)\n"
                + "HYBRID BENTO: not applied (motion: 31.250000 % of the mask > 25.000000 %) motion=31.25% of 9 cells\n"
                + "HYBRID STAGES ms: align=1 merged=24 droppedBracketed=2 bento=0\n";
        assertEquals("ES ✗ mv31%", MergeStamp.esLine(PLAN, 3, 3, report));
        assertEquals("L ✓1/3 sh28%", MergeStamp.longLine(PLAN, 3, 3, report));
        assertEquals("ES ✗ nclip", MergeStamp.esLine(PLAN, 3, 3, "HYBRID BENTO: not applied (not enough clipping) motion=0%"));
        assertEquals("ES ✗ hole", MergeStamp.esLine(PLAN, 3, 3, "HYBRID BENTO: not applied (large inpainting hole) motion=0%"));
        assertEquals("ES ✗ usclip", MergeStamp.esLine(PLAN, 3, 3, "HYBRID BENTO: not applied (high clipping ratio on ultrashort) m"));
        assertEquals("ES ✗ tune", MergeStamp.esLine(PLAN, 3, 3, "HYBRID BENTO: disabled by tuning"));
        assertEquals("ES ✗ align", MergeStamp.esLine(PLAN, 3, 3, "HYBRID STAGES ms: merged=23 droppedBracketed=0"));
        assertEquals("L ✗ tet", MergeStamp.longLine(PLAN, 3, 3, "HYBRID SHASTA: TET ratio 40.0 above the limit; all bracketed "
                + "frames dropped\nHYBRID STAGES ms: merged=23 droppedBracketed=3"));
        assertEquals("L ✗ mv", MergeStamp.longLine(PLAN, 1, 1, "HYBRID SHASTA motion frame=20 moved=9 of 10 blocks (90.0 % > 25.0 %: "
                + "dropped)\nHYBRID STAGES ms: merged=23 droppedBracketed=1"));
        assertEquals("L ✗ align", MergeStamp.longLine(PLAN, 3, 3, "HYBRID STAGES ms: merged=23 droppedBracketed=3"));
    }

    @Test
    public void thePlanAskedForNone() {
        final String noClip = "hybrid plan: N=30.000ms*ISO4541 clip=0.0004 requests=[LONG 30.000ms*ISO9082 (x2.000)] no clipping (0.0004)";
        assertEquals("ES ✗ nc 0.04%", MergeStamp.esLine(noClip, 0, 0, APPLIED));
        assertEquals("L ✗ r9.0", MergeStamp.longLine("hybrid plan: ... requests=[] brackets skipped (ratio 9.0)", 0, 0, APPLIED));
        assertEquals("L ✗ dcg", MergeStamp.longLine("hybrid plan: ... DCG: no shasta", 0, 0, APPLIED));
        assertEquals("ES ✗ off", MergeStamp.esLine("hybrid plan: ... bento off shasta off", 0, 0, APPLIED));
        assertEquals("L ✗ off", MergeStamp.longLine("hybrid plan: ... bento off shasta off", 0, 0, APPLIED));
        assertEquals("ES ✗ ring", MergeStamp.esLine("hybrid plan: N=1ms*ISO100 ring frames only (Motion mode)", 0, 0, APPLIED));
        assertEquals("ES ✗ floor", MergeStamp.esLine("hybrid plan: ... us skipped (sensor floor)", 0, 0, APPLIED));
        // asked for: none came, or they came back at the N exposure
        assertEquals("ES ✗ lost", MergeStamp.esLine(PLAN, 0, 0, APPLIED));
        assertEquals("L ✗ hal", MergeStamp.longLine(PLAN, 3, 0, APPLIED));
        // no merge for this photo
        assertArrayEquals(new String[]{"ES ✗ nomrg", "L ✗ nomrg"}, MergeStamp.lines(PLAN, -1, -1, -1, -1, null));
    }

    @Test
    public void drawnIntoTheTopRightCorner() {
        final Bitmap photo = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888);
        photo.eraseColor(0xFF808080);
        final Bitmap out = MergeStamp.draw(photo, "ES ✓3", "L ✗ sh28%");
        assertSame("drawn in place", photo, out);
        assertNotEquals("the plate in the top right corner", 0xFF808080, out.getPixel(386, 14));
        assertEquals("the rest of the photo untouched", 0xFF808080, out.getPixel(5, 290));
        assertEquals(0xFF808080, out.getPixel(5, 5));
    }
}
