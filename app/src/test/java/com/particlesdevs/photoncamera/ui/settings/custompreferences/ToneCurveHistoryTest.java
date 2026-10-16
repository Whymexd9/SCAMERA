package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.color.ToneCurve;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** P178: the own curve's steps - every finished edit is one, undo / redo walk them and store the curve shown. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w400dp-h880dp-xhdpi")
public class ToneCurveHistoryTest {
    private final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);

    @Test
    public void undoAndRedoWalkTheEdits() {
        ToneCurveView graph = new ToneCurveView(context);
        List<String> stored = new ArrayList<>();
        graph.setOnCommit(c -> stored.add(c.serialize()));
        LinearLayout pill = ToneCurveView.historyButtons(context, graph);
        View undo = pill.findViewWithTag("tone_curve_undo"), redo = pill.findViewWithTag("tone_curve_redo");
        String start = ToneCurve.defaults().serialize();
        graph.setCurve(ToneCurve.defaults());
        assertFalse("nothing to undo at first", graph.canUndo());
        assertFalse(undo.isEnabled());
        assertFalse(redo.isEnabled());
        // edit 1: a point deleted (the graph's own gesture path stores it)
        graph.select(1);
        assertTrue(graph.deleteSelected());
        String one = graph.curve().serialize();
        assertNotEquals(start, one);
        // edit 2: the selected point moved by a slider, stored by the editor (which records the step)
        graph.select(1);
        graph.moveSelected(graph.curve().get(1).x, graph.curve().get(1).y + 0.08f);
        graph.record();
        String two = graph.curve().serialize();
        assertNotEquals(one, two);
        assertTrue(undo.isEnabled());
        // back twice, through the button, each step stored
        undo.performClick();
        assertEquals(one, graph.curve().serialize());
        assertEquals(one, stored.get(stored.size() - 1));
        graph.undo();
        assertEquals(start, graph.curve().serialize());
        assertEquals(start, stored.get(stored.size() - 1));
        assertFalse(graph.canUndo());
        assertFalse(undo.isEnabled());
        assertTrue(redo.isEnabled());
        // forth again
        redo.performClick();
        assertEquals(one, graph.curve().serialize());
        assertTrue(graph.redo());
        assertEquals(two, graph.curve().serialize());
        assertFalse(graph.redo());
        // a new edit after a step back drops the steps after it
        graph.undo();
        graph.setCurve(ToneCurve.identity());
        graph.record();
        assertFalse("the old step forth is gone", graph.canRedo());
        graph.undo();
        assertEquals(one, graph.curve().serialize());
        // recording the shown step again is no step
        graph.record();
        assertTrue(graph.canRedo());
        // at most MAX_STEPS kept
        for (int i = 0; i < ToneCurveView.MAX_STEPS + 20; i++) {
            graph.select(1);
            graph.moveSelected(graph.curve().get(1).x, 0.05f + (i % 50) / 100f);
            graph.record();
        }
        int back = 0;
        while (graph.undo()) back++;
        assertEquals(ToneCurveView.MAX_STEPS - 1, back);
        // off: the buttons do nothing
        graph.redo();
        graph.setEnabled(false);
        assertFalse(undo.isEnabled());
    }
}
