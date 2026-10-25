package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lab stage view: the maps the worker wrote for one merge (job marker stage-dump, scam-hybrid.h hybridWriteStages), on the
 * sensor's cells; {@link #bitmap} turns one as the photo.
 */
public final class ScamMergeStages {
    /** Stage kinds in the order of the merge. */
    public static final String BASE = "base", RAW = "raw", ROBUST = "robust", FLOW = "flow", BENTO = "bento", BLEND = "blend",
            ACCEPTED = "accepted", MERGED = "merged";
    static final String[] ORDER = {BASE, RAW, ROBUST, FLOW, BENTO, BLEND, ACCEPTED, MERGED};
    private static final int MAGIC = 0x31475453;

    public static final class Map {
        public final String name;
        /** Burst frame of a donor's map, -1: the merge's own. */
        public final int frame, role, width, height, channels;
        final byte[] px;

        Map(String name, int frame, int role, int width, int height, int channels, byte[] px) {
            this.name = name;
            this.frame = frame;
            this.role = role;
            this.width = width;
            this.height = height;
            this.channels = channels;
            this.px = px;
        }
    }

    public final List<Map> maps;
    /** The shot's orientation (ScamLabOrient), set by the lab. */
    public int orient;

    ScamMergeStages(List<Map> maps) {
        this.maps = Collections.unmodifiableList(maps);
    }

    static ScamMergeStages read(File file) throws IOException {
        final List<Map> maps = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            for (;;) {
                final int magic;
                try {
                    magic = Integer.reverseBytes(in.readInt());
                } catch (EOFException end) {
                    break;
                }
                if (magic != MAGIC) throw new IOException("bad stage record");
                final int n = Integer.reverseBytes(in.readInt());
                if (n <= 0 || n > 64) throw new IOException("bad stage name");
                final byte[] name = new byte[n];
                in.readFully(name);
                final int frame = Integer.reverseBytes(in.readInt()), role = Integer.reverseBytes(in.readInt());
                final int w = Integer.reverseBytes(in.readInt()), h = Integer.reverseBytes(in.readInt()), ch = Integer.reverseBytes(in.readInt());
                if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || (ch != 1 && ch != 3)) throw new IOException("bad stage size");
                final byte[] px = new byte[w * h * ch];
                in.readFully(px);
                maps.add(new Map(new String(name, StandardCharsets.US_ASCII), frame, role, w, h, ch, px));
            }
        }
        return new ScamMergeStages(maps);
    }

    /** The kinds present, in merge order. */
    public List<String> kinds() {
        final List<String> out = new ArrayList<>();
        for (String k : ORDER) for (Map m : maps) if (m.name.equals(k)) { out.add(k); break; }
        return out;
    }

    /** The burst frames of a per-donor kind, in merge order (empty for the merge's own maps). */
    public List<Integer> frames(String kind) {
        final List<Integer> out = new ArrayList<>();
        for (Map m : maps) if (m.name.equals(kind) && m.frame >= 0) out.add(m.frame);
        return out;
    }

    @Nullable
    public Map find(String kind, int frame) {
        for (Map m : maps) if (m.name.equals(kind) && (m.frame < 0 || m.frame == frame)) return m;
        return null;
    }

    public static boolean perDonor(String kind) {
        return RAW.equals(kind) || ROBUST.equals(kind) || FLOW.equals(kind);
    }

    /** One map turned as the photo: RGB previews as they are, the weights and the F6 offset on the frame map's ramp. */
    public static Bitmap bitmap(Map m, int orient) {
        final int w = m.width, h = m.height;
        final boolean swap = ScamLabOrient.swaps(orient);
        final int pw = swap ? h : w, ph = swap ? w : h;
        final int[] out = new int[pw * ph];
        for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
            final float[] uv = ScamLabOrient.toSensor(orient, (x + .5f) / pw, (y + .5f) / ph);
            final int i = Math.min(h - 1, (int) (uv[1] * h)) * w + Math.min(w - 1, (int) (uv[0] * w));
            out[y * pw + x] = m.channels == 3
                    ? 0xFF000000 | (m.px[i * 3] & 255) << 16 | (m.px[i * 3 + 1] & 255) << 8 | (m.px[i * 3 + 2] & 255)
                    : ScamLabRun.colour((m.px[i] & 255) / 255f);
        }
        return Bitmap.createBitmap(out, pw, ph, Bitmap.Config.ARGB_8888);
    }
}
