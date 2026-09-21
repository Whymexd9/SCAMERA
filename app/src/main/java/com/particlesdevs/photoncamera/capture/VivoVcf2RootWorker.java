package com.particlesdevs.photoncamera.capture;

import android.content.ContextWrapper;
import android.hardware.camera2.CaptureResult;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

@androidx.annotation.Keep
public final class VivoVcf2RootWorker implements VivoVcf2Device.Listener {
    private final DataOutputStream output = new DataOutputStream(System.out);
    private VivoVcf2Device device;
    private long armed;
    private boolean jpegSent, resultSent;

    private static void verify(String path, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] chunk = new byte[65536];
            int n;
            while ((n = in.read(chunk)) != -1) digest.update(chunk, 0, n);
        }
        StringBuilder actual = new StringBuilder();
        for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!expected.contentEquals(actual)) throw new IOException("VCF2 firmware mismatch: " + path);
    }

    public static void main(String[] args) {
        VivoVcf2RootWorker worker = new VivoVcf2RootWorker();
        HandlerThread thread = new HandlerThread("SCAMERA-VCF2-root");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        try {
            if (android.os.Process.myUid() != 0 || args.length != 1
                    || !args[0].matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
                throw new IOException("VCF2 worker requires root and the app package name");
            worker.output.writeInt(VivoVcf2Wire.MAGIC);
            worker.output.writeInt(VivoVcf2Wire.VERSION);
            worker.output.flush();
            handler.post(() -> {
                try {
                    verify("/system/framework/vivo-camera-framework.jar",
                            "5da51afefe3c2902697f57d466525f83ea62c221303f0c664cac15c3c0ef685b");
                    verify("/system/lib64/libvivocameraservice.so",
                            "5fbb51c02ca0c8f968c5367ceffd28782cc25a2d0e4b6d30e08e10be4dbfe438");
                    worker.device = VivoVcf2Device.open(new ClientContext(args[0]), handler, worker);
                    worker.event(VivoVcf2Wire.READY, 0);
                } catch (Exception | LinkageError failure) { worker.error(0, failure.toString()); }
            });
            DataInputStream input = new DataInputStream(System.in);
            while (true) {
                int command = input.readInt();
                long id = input.readLong();
                if (id <= 0 || (command != VivoVcf2Wire.ARM && command != VivoVcf2Wire.RETIRE))
                    throw new IOException("Invalid VCF2 worker command");
                if (!handler.post(() -> {
                    if (command == VivoVcf2Wire.RETIRE) {
                        if (worker.armed == id) worker.armed = 0;
                    } else if (worker.device == null || worker.armed != 0) {
                        worker.error(id, "VCF2 worker is not available");
                    } else {
                        worker.armed = id;
                        worker.jpegSent = worker.resultSent = false;
                        worker.event(VivoVcf2Wire.ARMED, id);
                    }
                })) throw new IOException("VCF2 worker handler stopped");
            }
        } catch (EOFException finished) {
            // Parent closes stdin on session teardown or dies: unregister below.
        } catch (Exception | LinkageError failure) {
            System.err.println("VCF2 worker: " + failure);
        } finally {
            Thread deadline = new Thread(() -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                System.exit(1);
            }, "VCF2-close-deadline");
            deadline.setDaemon(true);
            deadline.start();
            handler.post(() -> {
                try { if (worker.device != null) worker.device.close(); }
                catch (Exception ignored) { }
                finally { System.exit(0); }
            });
        }
    }

    @androidx.annotation.Keep
    public static final class ClientContext extends ContextWrapper {
        private final String packageName;
        ClientContext(String packageName) { super(null); this.packageName = packageName; }
        @Override public String getPackageName() { return packageName; }
        // The inspected framework open() reads only this method. Keep the real
        // SCAMERA name; the Binder caller remains UID 0, never the stock app UID.
        public String getOpPackageName() { return packageName; }
    }

    private void event(int type, long id) {
        try { output.writeInt(type); output.writeLong(id); output.flush(); }
        catch (IOException lostParent) { System.exit(1); }
    }

    private void error(long id, String message) {
        try {
            output.writeInt(VivoVcf2Wire.ERROR);
            output.writeLong(id);
            output.writeUTF(message.substring(0, Math.min(message.length(), 2048)));
            output.flush();
        } catch (IOException lostParent) { System.exit(1); }
    }

    @Override public void onBuffer(VivoVcf2Device.Buffer buffer) {
        if (armed == 0 || buffer.captureId != armed || jpegSent) return;
        try {
            VivoVcf2Wire.jpeg(output, armed, buffer.copyJpegBytes());
            jpegSent = true;
        } catch (IOException | RuntimeException | LinkageError failure) { error(armed, failure.toString()); }
    }

    @Override public void onResult(long id, CaptureResult result, boolean partial) {
        if (id != armed || armed == 0 || partial || resultSent) return;
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (timestamp == null || timestamp <= 0) { error(id, "VCF2 final timestamp missing"); return; }
        try {
            output.writeInt(VivoVcf2Wire.RESULT);
            output.writeLong(id);
            output.writeLong(timestamp);
            output.flush();
            resultSent = true;
        } catch (IOException lostParent) { System.exit(1); }
    }

    @Override public void onError(int error) { error(0, "VCF2 device error " + error); }
    @Override public void onCaptureFrameDone(long id) { }
    @Override public void onDeviceUpdate(int status) { }
    @Override public void onInfo(long id, int type, int value) { }
    @Override public void onVopCaptureDone(int reader, long id, int a, int b, int c) { }
    @Override public void onNotify(int type, int subtype) { }
}
