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
    public static final String BASE = "base", RAW = "raw", ROBUST = "robust", CUTOFF = "cutoff", FLOW = "flow", BENTO = "bento",
            BLEND = "blend", ACCEPTED = "accepted", MERGED = "merged";
    /**
     * P344 «Отсечение» (owner, 2026-10-26: our «Реджект» next to ArkCam's «после · отсечение», «займись картой реджектов»): the
     * worker's "cutoff" record (frame -1) is per cell the smallest block-mean final merge weight over the normal donors (the
     * bracket's long frames, cut where they clip by design, left out), 255 = every frame merged, 0 = at least one frame cut. It
     * follows the frame weights it is the minimum of; an older worker writes none and the view
     * simply lists no such stage (kinds()).
     */
    static final String[] ORDER = {BASE, RAW, ROBUST, CUTOFF, FLOW, BENTO, BLEND, ACCEPTED, MERGED};
    /**
     * P344: a weight at or above this reads as accepted on the weight ramp ({@link #weight}): 247/255 = 0.969. Below it the
     * rejection's exp2(-dist * boost) of a static cell is noise (the P342 maps of a tripod burst sit at 0.85..1), and viridis
     * painted that band green/yellow next to the real yellow, so a noise-level difference read as a rejection.
     */
    static final int ACCEPT = 247;
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

    /**
     * One map turned as the photo: RGB previews as they are, the cutoff in plain grey, the rejection / frame weight / accepted
     * weights on the weight ramp ({@link #weight}), the F6 offset and the Bento / blend masks on the frame map's ramp.
     */
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
                    : pixel(m.name, m.px[i] & 255);
        }
        return Bitmap.createBitmap(out, pw, ph, Bitmap.Config.ARGB_8888);
    }

    /** A one-channel map's byte (0..255) as a colour, by the map's kind. */
    static int pixel(String kind, int v) {
        if (CUTOFF.equals(kind)) return 0xFF000000 | v << 16 | v << 8 | v; // ArkCam's «после · отсечение»: white kept, black cut
        if (RAW.equals(kind) || ROBUST.equals(kind) || ACCEPTED.equals(kind)) return weight(v);
        return ScamLabRun.colour(v / 255f);
    }

    /** The weight ramp's stops below {@link #ACCEPT}: black (cut), dark red, red-orange, amber, pale cream (noise-level dip). */
    private static final int[] WEIGHT_AT = {0, 80, 150, 200, ACCEPT - 1};
    private static final int[] WEIGHT_RGB = {0xFF000000, 0xFF8E1616, 0xFFE0541C, 0xFFF3B23A, 0xFFFBEFC8};

    /**
     * P344 (the P342 maps: viridis put 0.85..1 on green / yellow, a static cell's noise looked rejected): a weight byte on a ramp
     * with a plateau. From {@link #ACCEPT} (0.97) up it is plain white, accepted, as the cutoff view shows a kept cell; below it
     * a weight runs pale cream, amber, red-orange, dark red to black at 0. The 0.85..0.97 band of a static cell's noise stays
     * within cream and light amber, a real rejection (under ~0.6) is red to black.
     */
    static int weight(int v) {
        if (v >= ACCEPT) return 0xFFFFFFFF;
        int i = 0;
        while (i < WEIGHT_AT.length - 2 && v > WEIGHT_AT[i + 1]) i++;
        final float f = Math.min(1f, Math.max(0f, (v - WEIGHT_AT[i]) / (float) (WEIGHT_AT[i + 1] - WEIGHT_AT[i])));
        final int a = WEIGHT_RGB[i], b = WEIGHT_RGB[i + 1];
        int out = 0xFF000000;
        for (int s = 0; s <= 16; s += 8) out |= Math.round(((a >> s) & 255) * (1 - f) + ((b >> s) & 255) * f) << s;
        return out;
    }
}
