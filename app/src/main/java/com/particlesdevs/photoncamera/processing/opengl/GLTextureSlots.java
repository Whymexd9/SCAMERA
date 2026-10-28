package com.particlesdevs.photoncamera.processing.opengl;

/**
 * The table of the GL texture names {@link GLTexture} made and has not deleted yet ({@link GLTexture#closeAll} deletes what is
 * left at the end of a run). Each texture keeps the slot it took and frees that slot, not the slot its name would index: names
 * and slots part as soon as a texture of the context comes from elsewhere (a raw glGenTextures, e.g. a film LUT), and a release
 * by name then untracked another texture - P369 film grain's full-size output was never deleted on the kept context (P353), and
 * every later shot leaked one texture more. A driver that never reuses names indexed the table past its end. Textures are made
 * on several threads (processing, gallery, lab), so the table is locked.
 */
final class GLTextureSlots {
    private final boolean[] used;
    private final int[] names;

    GLTextureSlots(int size) {
        used = new boolean[size];
        names = new int[size];
    }

    /** The first free slot (from 1) now holds {@code name}; 0 when the table is full (only its own close deletes the texture). */
    synchronized int take(int name) {
        for (int i = 1; i < used.length; i++) {
            if (!used[i]) {
                names[i] = name;
                used[i] = true;
                return i;
            }
        }
        return 0;
    }

    /** Frees {@code slot} when it still holds {@code name}: a slot closeAll cleared and another texture took is left alone. */
    synchronized void release(int slot, int name) {
        if (slot > 0 && slot < used.length && used[slot] && names[slot] == name) used[slot] = false;
    }

    /** The names still tracked, for deletion; the table is empty afterwards. */
    synchronized int[] takeAll() {
        int n = 0;
        for (boolean u : used) if (u) n++;
        final int[] out = new int[n];
        n = 0;
        for (int i = 0; i < used.length; i++) {
            if (used[i]) {
                out[n++] = names[i];
                used[i] = false;
            }
        }
        return out;
    }

    /** The slots in use, separated by spaces (the notClosed line). */
    synchronized String inUse() {
        final StringBuilder str = new StringBuilder();
        for (int i = 0; i < used.length; i++) {
            if (used[i]) {
                str.append(i);
                str.append(" ");
            }
        }
        return str.toString();
    }
}
