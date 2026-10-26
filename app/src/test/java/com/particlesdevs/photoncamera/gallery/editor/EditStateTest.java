package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.particlesdevs.photoncamera.gallery.editor.EditState.Param;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P351: the editor's state as JSON (undo / redo, the saved instance state, copied settings) and its history. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class EditStateTest {

    static EditState sample() {
        EditState s = new EditState();
        s.set(Param.EXPOSURE, 1.37f);
        s.set(Param.CONTRAST, -25);
        s.set(Param.TEMPERATURE, 40);
        s.set(Param.hue(3), 12);
        s.set(Param.lum(7), -60);
        s.set(Param.SHARPEN_RADIUS, 2.3f);
        s.set(Param.GRADE_SH_HUE, 215);
        s.set(Param.GRADE_SH_SAT, 30);
        s.set(Param.MONO, 1);
        s.set(Param.bw(2), 45);
        s.set(Param.CURVE_DARKS, -20);
        s.curves[EditState.CURVE_MASTER] = new float[]{0, 0.05f, 0.5f, 0.6f, 1, 0.95f};
        s.curves[EditState.CURVE_BLUE] = new float[]{0, 0, 1, 0.9f};
        s.quarterTurns = 3;
        s.flipH = true;
        s.straighten = -7.5f;
        s.cropL = 0.1f;
        s.cropT = 0.2f;
        s.cropR = 0.85f;
        s.cropB = 0.9f;
        s.aspect = 4f / 5f;
        return s;
    }

    @Test
    public void jsonRoundTrip() {
        EditState s = sample();
        EditState back = EditState.fromJson(s.toJson());
        assertNotNull(back);
        assertEquals(s, back);
        assertEquals(s.hashCode(), back.hashCode());
        assertEquals(s.toJson(), back.toJson());
        assertArrayEquals(s.curves[0], back.curves[0], 0f);
        assertNull(back.curves[EditState.CURVE_RED]);
    }

    @Test
    public void defaultsAreLeftOut() {
        assertEquals("{\"v\":1}", new EditState().toJson());
        EditState s = new EditState();
        s.set(Param.SHADOWS, 30);
        String j = s.toJson();
        assertTrue(j, j.contains("\"shadows\""));
        assertFalse(j, j.contains("\"contrast\""));
        assertFalse(j, j.contains("\"g\""));
    }

    @Test
    public void unknownKeysAreSkippedAndValuesClamped() {
        EditState s = EditState.fromJson("{\"v\":1,\"p\":{\"exposure\":9.5,\"contrast\":-400,\"nonsense\":3,\"sharpenRadius\":0.47},"
                + "\"g\":{\"q\":-1,\"a\":80,\"c\":[-0.5,0.1,0.6,2],\"r\":-5}}");
        assertNotNull(s);
        assertEquals(4f, s.get(Param.EXPOSURE), 0f);
        assertEquals(-100f, s.get(Param.CONTRAST), 0f);
        assertEquals(0.5f, s.get(Param.SHARPEN_RADIUS), 1e-6f);
        assertEquals(3, s.quarterTurns);
        assertEquals(45f, s.straighten, 0f);
        assertEquals(0f, s.cropL, 0f);
        assertEquals(1f, s.cropB, 0f);
        assertEquals(EditState.ASPECT_FREE, s.aspect, 0f);
    }

    @Test
    public void notAStateIsNull() {
        assertNull(EditState.fromJson(null));
        assertNull(EditState.fromJson(""));
        assertNull(EditState.fromJson("not json"));
        assertNull(EditState.fromJson("{\"v\":2}"));
    }

    @Test
    public void snapToStep() {
        EditState s = new EditState();
        s.set(Param.EXPOSURE, 0.12345f);
        assertEquals(0.12f, s.get(Param.EXPOSURE), 1e-6f);
        s.set(Param.CLARITY, 12.6f);
        assertEquals(13f, s.get(Param.CLARITY), 0f);
        s.set(Param.SHADOWS, Float.NaN);
        assertEquals(0f, s.get(Param.SHADOWS), 0f);
    }

    @Test
    public void neutralToneKeepsGeometry() {
        EditState s = sample();
        EditState n = s.withNeutralTone();
        assertTrue(n.isDefaultTone());
        assertEquals(s.quarterTurns, n.quarterTurns);
        assertEquals(s.cropR, n.cropR, 0f);
        EditState t = new EditState();
        t.copyTone(s);
        assertTrue(t.isDefaultGeometry());
        assertEquals(s.get(Param.EXPOSURE), t.get(Param.EXPOSURE), 0f);
        assertArrayEquals(s.curves[0], t.curves[0], 0f);
    }

    @Test
    public void historyUndoRedo() {
        EditHistory h = new EditHistory(new EditState());
        assertFalse(h.canUndo());
        assertFalse(h.changed());
        EditState a = new EditState();
        a.set(Param.EXPOSURE, 1);
        assertTrue(h.commit(a));
        assertFalse("same state twice", h.commit(a));
        EditState b = a.copy();
        b.set(Param.CONTRAST, 20);
        h.commit(b);
        assertTrue(h.changed());
        assertEquals(a, h.undo());
        assertTrue(h.canRedo());
        assertEquals(b, h.redo());
        h.undo();
        EditState c = a.copy();
        c.set(Param.SHADOWS, 10);
        h.commit(c);
        assertFalse("a commit after undo drops the redo branch", h.canRedo());
        assertEquals(new EditState(), h.initial());
        a.set(Param.EXPOSURE, 2); // the history keeps copies
        assertNotEquals(a, h.undo());
    }

    @Test
    public void historyIsBounded() {
        EditHistory h = new EditHistory(new EditState());
        for (int i = 1; i <= EditHistory.MAX + 20; i++) {
            EditState s = new EditState();
            s.set(Param.CONTRAST, (i % 200) - 100);
            s.set(Param.EXPOSURE, i * 0.01f);
            h.commit(s);
        }
        int undos = 0;
        while (h.canUndo()) {
            h.undo();
            undos++;
        }
        assertEquals(EditHistory.MAX - 1, undos);
    }
}
