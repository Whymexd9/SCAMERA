package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * P277: a lab shot's burst (ScamLab.BURST, the NCH v11 / v12 transport the worker got) merged again: whole - its file goes to
 * the worker as it is, mapped - or a fragment of it ({@link #crop}): a v11 transport of that window of every frame (whole colour
 * periods, so the CFA and the Quad / Tetra phase stay). The tuning file is the settings' part of now (the lab's overlay) and the
 * burst's own lines of the shot.
 */
final class ScamLabBurst implements ScamTransport {
    static final int MAGIC = 0x3143484e, HEADER = 128, ENTRY = 32;
    /** A fragment's sides and corner are multiples of this (Tetra's 8 x 8 colour period, the 2x grid, GPU tiles). */
    static final int ALIGN = 32;

    final File file;
    final int version, width, height, cfa, frames, flags, grid, block;
    /** The fragment of the frame merged ({x, y, w, h}), null: the whole frame. */
    final int[] crop;
    private final String burstTuning;
    private final boolean half;

    ScamLabBurst(File file, int[] crop, String burstTuning) throws IOException {
        this.file = file;
        final ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        // P363: a burst of an earlier installation through the folder grant
        try (FileChannel in = LabFiles.channel(file)) {
            while (h.hasRemaining()) if (in.read(h, h.position()) < 0) throw new IOException("short NCH header");
        }
        if (h.getInt(0) != MAGIC) throw new IOException("not an NCH burst");
        version = h.getInt(4);
        if (version != 11 && version != 12) throw new IOException("NCH v" + version + " is not a SCAM Hybrid burst");
        width = h.getInt(8);
        height = h.getInt(12);
        cfa = h.getInt(16);
        frames = h.getInt(20);
        flags = h.getInt(44);
        grid = Math.max(1, h.getInt(52));
        block = h.getInt(56);
        if (width <= 0 || height <= 0 || frames < 1 || frames > 64) throw new IOException("bad NCH header");
        this.crop = crop == null ? null : aligned(crop, width, height);
        this.burstTuning = burstTuning == null ? "" : burstTuning;
        half = ScamHybridBurst.transportHalf(outputWidth(), outputHeight(), ScamHybridBurst.halfTransport());
    }

    /** {x, y, w, h} inside a w x h frame, each a multiple of {@link #ALIGN}, at least ALIGN a side. */
    static int[] aligned(int[] c, int w, int h) {
        final int cw = Math.max(ALIGN, Math.min(w, c[2]) / ALIGN * ALIGN), ch = Math.max(ALIGN, Math.min(h, c[3]) / ALIGN * ALIGN);
        final int x = Math.max(0, Math.min(w - cw, c[0])) / ALIGN * ALIGN, y = Math.max(0, Math.min(h - ch, c[1])) / ALIGN * ALIGN;
        return new int[]{x, y, cw, ch};
    }

    /** Byte offset of frame {@code i}'s plane: v12 its page of the arena, v11 after the table. */
    static long planeOffset(int version, ByteBuffer table, int frames, int i, int w, int h) {
        if (version == 12) return (long) table.getInt(i * ENTRY + 28) * 4096;
        return HEADER + (long) ENTRY * frames + (long) i * w * h * 2;
    }

    @Override public int width() { return crop == null ? width : crop[2]; }
    @Override public int height() { return crop == null ? height : crop[3]; }
    @Override public int outputWidth() { return width() * grid; }
    @Override public int outputHeight() { return height() * grid; }
    @Override public int cfa() { return cfa; }
    @Override public boolean mergedDng() { return false; }
    @Override public boolean diagnostics() { return false; }
    @Override public boolean clipFlags() { return (flags & 4) != 0; }
    @Override public boolean effectiveMap() {
        // P306: a fragment as its shot: a 200 MP shot has no map (flag 16), its 200 MP denoise takes the flat strength instead
        return (flags & 16) == 0 && (crop == null || !ScamHdrDenoise.huge(outputWidth(), outputHeight()));
    }
    @Override public boolean hybrid() { return true; }
    @Override public boolean rgbHalfTransport() { return half; }
    @Override public String burstTuning() { return burstTuning; }
    @Override public boolean keepable() { return false; }
    @Override public boolean stages() { return true; }

    /** The whole frame: the file itself (ScamLab cleared its DNG and diagnostics flags), mapped by the worker. */
    @Override public android.os.ParcelFileDescriptor sharedBurst() {
        if (crop != null || (flags & 3) != 0) return null;
        try {
            return LabFiles.open(file);
        } catch (IOException e) {
            return null;
        }
    }

    @Override public void write(File out) throws IOException {
        try (FileChannel o = new FileOutputStream(out).getChannel()) { write(o); }
    }

    @Override public void write(FileChannel out) throws IOException {
        try (FileChannel in = LabFiles.channel(file)) { write(in, out); }
    }

    /**
     * The transport the worker reads: v11 with the fragment's size (or the whole frame), flags without the DNG and the
     * diagnostics, the frame table as shot (v11: no pages).
     */
    void write(FileChannel in, FileChannel out) throws IOException {
        final ByteBuffer head = ByteBuffer.allocate(HEADER + ENTRY * frames).order(ByteOrder.LITTLE_ENDIAN);
        while (head.hasRemaining()) if (in.read(head, head.position()) < 0) throw new IOException("short NCH table");
        final ByteBuffer table = ByteBuffer.allocate(ENTRY * frames).order(ByteOrder.LITTLE_ENDIAN);
        head.position(HEADER);
        table.put(head);
        table.flip();
        final ByteBuffer h = ByteBuffer.allocate(HEADER + ENTRY * frames).order(ByteOrder.LITTLE_ENDIAN);
        head.position(0).limit(HEADER);
        h.put(head);
        h.putInt(4, 11).putInt(8, width()).putInt(12, height());
        h.putInt(44, (flags & ~(3 | 16)) | (effectiveMap() ? 0 : 16));
        for (int i = 0; i < frames; i++) {
            for (int k = 0; k < ENTRY; k += 4) h.putInt(HEADER + i * ENTRY + k, table.getInt(i * ENTRY + k));
            h.putInt(HEADER + i * ENTRY + 28, 0);
        }
        h.position(0);
        long at = 0;
        while (h.hasRemaining()) at += out.write(h, at);
        final int cx = crop == null ? 0 : crop[0], cy = crop == null ? 0 : crop[1], cw = width(), ch = height();
        final ByteBuffer row = ByteBuffer.allocateDirect(crop == null ? 1 << 22 : cw * 2);
        for (int i = 0; i < frames; i++) {
            final long plane = planeOffset(version, table, frames, i, width, height);
            if (crop == null) {
                // the whole plane in 4 MB pieces
                final long bytes = (long) width * height * 2;
                for (long done = 0; done < bytes; ) {
                    row.clear();
                    row.limit((int) Math.min(row.capacity(), bytes - done));
                    while (row.hasRemaining()) if (in.read(row, plane + done + row.position()) < 0) throw new IOException("short NCH plane");
                    row.flip();
                    while (row.hasRemaining()) at += out.write(row, at);
                    done += row.limit();
                }
                continue;
            }
            for (int y = 0; y < ch; y++) {
                row.clear();
                final long from = plane + ((long) (cy + y) * width + cx) * 2;
                while (row.hasRemaining()) if (in.read(row, from + row.position()) < 0) throw new IOException("short NCH plane");
                row.flip();
                while (row.hasRemaining()) at += out.write(row, at);
            }
        }
    }
}
