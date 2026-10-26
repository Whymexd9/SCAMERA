package com.particlesdevs.photoncamera.gallery.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * P351: undo / redo of the editor - a list of committed states (a slider let go, a crop drag ended, a button) and the index of
 * the current one. Committing after an undo drops the redo branch; at most {@link #MAX} states are kept. Pure Java.
 */
public final class EditHistory {
    static final int MAX = 100;
    private final List<EditState> states = new ArrayList<>();
    private int index = -1;

    public EditHistory(EditState initial) {
        states.add(initial.copy());
        index = 0;
    }

    /** Records {@code s} as the newest state (nothing when it equals the current one); true when recorded. */
    public boolean commit(EditState s) {
        if (states.get(index).equals(s)) return false;
        while (states.size() > index + 1) states.remove(states.size() - 1);
        states.add(s.copy());
        if (states.size() > MAX) states.remove(0);
        index = states.size() - 1;
        return true;
    }

    public boolean canUndo() {
        return index > 0;
    }

    public boolean canRedo() {
        return index < states.size() - 1;
    }

    /** The state before the current one (a copy), or the current one when there is none. */
    public EditState undo() {
        if (index > 0) index--;
        return states.get(index).copy();
    }

    public EditState redo() {
        if (index < states.size() - 1) index++;
        return states.get(index).copy();
    }

    public EditState current() {
        return states.get(index).copy();
    }

    /** True when the current state differs from the first one (unsaved edits). */
    public boolean changed() {
        return !states.get(index).equals(states.get(0));
    }

    /** The first state (the photo as it was opened). */
    public EditState initial() {
        return states.get(0).copy();
    }
}
