package com.particlesdevs.photoncamera.capture;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MotionMeterReplay {
    private record Frame(float orderMs, long offset) {}

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            try (RandomAccessFile file = new RandomAccessFile(name, "r")) {
                byte[] header = new byte[128];
                file.readFully(header);
                ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (h.getInt(0) != 0x3143484e) throw new IllegalArgumentException("Not NCH: " + name);
                int version = h.getInt(4), w = h.getInt(8), height = h.getInt(12), count = h.getInt(20);
                int block = h.getInt(56);
                if (version < 10 || version > 12 || count < 1 || count > 128 || w <= 0 || height <= 0)
                    throw new IllegalArgumentException("Unsupported burst: " + name);
                float black = 0;
                for (int i = 0; i < 4; i++) black += h.getFloat(28 + 4 * i) / 4;
                List<Frame> frames = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    byte[] item = new byte[32];
                    file.readFully(item);
                    ByteBuffer f = ByteBuffer.wrap(item).order(ByteOrder.LITTLE_ENDIAN);
                    long offset = version == 12 ? Integer.toUnsignedLong(f.getInt(28)) * 4096
                            : 128L + 32L * count + (long) i * w * height * 2;
                    if (f.getInt(0) == 1) frames.add(new Frame(f.getFloat(20), offset));
                }
                frames.sort(Comparator.comparingDouble(Frame::orderMs));
                MotionMeter.Thumb previous = null;
                List<Double> subject = new ArrayList<>(), camera = new ArrayList<>();
                int unavailable = 0;
                for (Frame frame : frames) {
                    long bytes = (long) w * height * 2;
                    if (frame.offset < 0 || bytes > file.length() - frame.offset)
                        throw new IllegalArgumentException("Truncated RAW: " + name);
                    ByteBuffer raw = file.getChannel().map(FileChannel.MapMode.READ_ONLY, frame.offset, bytes)
                            .order(ByteOrder.LITTLE_ENDIAN);
                    long timestamp = Math.round(frame.orderMs * 1e6);
                    MotionMeter.Thumb current = MotionMeter.thumb(raw.asShortBuffer(), w, height, w, black, timestamp, block);
                    if (current != null && previous != null) {
                        MotionMeter.Sample sample = MotionMeter.compare(previous, current, current.ts - previous.ts, 0);
                        if (sample == null) unavailable++;
                        else { subject.add(sample.subjectPxPerS); camera.add(sample.cameraPxPerS); }
                    }
                    previous = current;
                }
                System.out.println("{\"burst\":\"" + Path.of(name).getFileName() + "\",\"normals\":" + frames.size()
                        + ",\"unavailable_pairs\":" + unavailable + ",\"subject_px_s\":" + subject
                        + ",\"camera_px_s\":" + camera + "}");
            }
        }
    }
}
