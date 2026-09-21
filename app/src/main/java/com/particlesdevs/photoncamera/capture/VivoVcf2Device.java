package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.hardware.camera2.CaptureResult;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

public final class VivoVcf2Device implements AutoCloseable {
    public interface Listener {
        void onBuffer(Buffer buffer);
        void onResult(long captureId, CaptureResult result, boolean partial);
        void onCaptureFrameDone(long captureId);
        void onDeviceUpdate(int status);
        void onError(int error);
        void onInfo(long captureId, int type, int value);
        void onVopCaptureDone(int readerId, long captureId, int first, int second, int third);
        void onNotify(int type, int subtype);
    }

    public static final class Buffer implements AutoCloseable {
        public final long captureId;
        public final int size, width, height, stride, scanlines, format;
        private ParcelFileDescriptor descriptor;

        private Buffer(Object[] args) {
            captureId = (Long) args[0];
            descriptor = (ParcelFileDescriptor) args[1];
            size = (Integer) args[2]; width = (Integer) args[3]; height = (Integer) args[4];
            stride = (Integer) args[5]; scanlines = (Integer) args[6]; format = (Integer) args[7];
        }

        // A consumer retaining a buffer beyond its callback must own a duplicate.
        public synchronized ParcelFileDescriptor duplicateDescriptor() throws IOException {
            if (descriptor == null) throw new IOException("VCF2 buffer is closed");
            return ParcelFileDescriptor.dup(descriptor.getFileDescriptor());
        }

        public byte[] copyJpegBytes() throws IOException {
            // VCF2 reports HAL BLOB (33), not Camera2 ImageFormat.JPEG (256).
            if (format != 33) throw new IOException("VCF2 output is not a JPEG BLOB: " + format);
            try (ParcelFileDescriptor owned = duplicateDescriptor()) {
                return NativeReader.copy(owned.getFd(), size);
            }
        }

        @Override public synchronized void close() {
            ParcelFileDescriptor owned = descriptor;
            descriptor = null;
            if (owned != null) try { owned.close(); } catch (IOException ignored) { }
        }
    }

    private static final class NativeReader {
        static { System.loadLibrary("vivoVcfBuffer"); }
        static native byte[] copy(int fd, int size) throws IOException;
    }

    private final Handler handler;
    private final Listener listener;
    private final Method closeMethod, captureIdMethod;
    private final Class<?> resultClass;
    private final Set<Buffer> pending = new HashSet<>();
    private volatile boolean closed;
    private Object device;

    private VivoVcf2Device(Handler handler, Listener listener, Class<?> deviceClass,
                           Class<?> resultClass) throws ReflectiveOperationException {
        this.handler = handler;
        this.listener = listener;
        this.resultClass = resultClass;
        closeMethod = deviceClass.getMethod("close");
        captureIdMethod = resultClass.getMethod("getCaptureId");
        if (!CaptureResult.class.isAssignableFrom(resultClass)
                || captureIdMethod.getReturnType() != long.class
                || closeMethod.getReturnType() != void.class)
            throw new NoSuchMethodException("Unsupported VCF2 result/device ABI");
    }

    public static VivoVcf2Device open(Context context, Handler handler, Listener listener)
            throws ReflectiveOperationException {
        if (context == null || handler == null || listener == null)
            throw new IllegalArgumentException("VCF2 requires context, handler and listener");
        Class<?> managerClass = Class.forName("android.hardware.vivocamera.VivoCameraManager");
        Class<?> deviceClass = Class.forName("android.hardware.vivocamera.VivoCameraDevice");
        Class<?> callbackClass = Class.forName("android.hardware.vivocamera.IVivoCameraDeviceCb");
        Class<?> resultClass = Class.forName("android.hardware.VIFResult");
        verifyCallbacks(callbackClass, resultClass);
        Method initialize = deviceClass.getMethod("initialize");
        if (initialize.getReturnType() != void.class)
            throw new NoSuchMethodException("Unsupported VCF2 initialize ABI");
        VivoVcf2Device connection = new VivoVcf2Device(handler, listener, deviceClass, resultClass);
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass}, connection::callback);
        try {
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            Object device = managerClass.getMethod("open", Context.class, callbackClass)
                    .invoke(manager, context, callback);
            if (!deviceClass.isInstance(device)) throw new IllegalStateException("VCF2 service unavailable");
            boolean accepted;
            synchronized (connection.pending) {
                accepted = !connection.closed;
                if (accepted) connection.device = device;
            }
            if (!accepted) {
                connection.closeMethod.invoke(device);
                throw new IllegalStateException("VCF2 closed while opening");
            }
            initialize.invoke(device);
            if (connection.closed) throw new IllegalStateException("VCF2 closed while initializing");
            return connection;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            try { connection.close(); } catch (ReflectiveOperationException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    private static void verifyCallbacks(Class<?> type, Class<?> result) throws NoSuchMethodException {
        if (!type.isInterface()) throw new NoSuchMethodException("VCF2 callback is not an interface");
        Object[][] signatures = {
                {"onBufferCallback", long.class, ParcelFileDescriptor.class, int.class, int.class,
                        int.class, int.class, int.class, int.class},
                {"onVIFResultCallback", result}, {"onVIFPartialResultCallback", result},
                {"onCaptureFrameDone", long.class}, {"onDeviceUpdate", int.class}, {"onError", int.class},
                {"onVIFInfoUpdate", long.class, int.class, int.class},
                {"onVOPCaptureDone", int.class, long.class, int.class, int.class, int.class},
                {"onVivoNotifyCallback", int.class, int.class}
        };
        for (Object[] signature : signatures) {
            Class<?>[] parameters = new Class<?>[signature.length - 1];
            for (int i = 0; i < parameters.length; i++) parameters[i] = (Class<?>) signature[i + 1];
            if (type.getMethod((String) signature[0], parameters).getReturnType() != void.class)
                throw new NoSuchMethodException("Unsupported VCF2 callback return type");
        }
        if (type.getMethods().length != signatures.length)
            throw new NoSuchMethodException("Unsupported VCF2 callback interface version");
    }

    private Object callback(Object proxy, Method method, Object[] args) throws ReflectiveOperationException {
        switch (method.getName()) {
            case "equals": return proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "SCAMERA VCF2 callback";
            case "onBufferCallback":
                Buffer buffer = new Buffer(args);
                synchronized (pending) {
                    if (closed) { buffer.close(); return null; }
                    pending.add(buffer);
                }
                boolean posted = false;
                try {
                    posted = handler.post(() -> {
                        try { if (!closed) listener.onBuffer(buffer); }
                        finally { release(buffer); }
                    });
                } finally { if (!posted) release(buffer); }
                return null;
            case "onVIFResultCallback":
            case "onVIFPartialResultCallback":
                Object result = args[0];
                if (!resultClass.isInstance(result)) throw new IllegalArgumentException("Invalid VIF result");
                long id = (Long) captureIdMethod.invoke(result);
                boolean partial = method.getName().equals("onVIFPartialResultCallback");
                post(() -> listener.onResult(id, (CaptureResult) result, partial));
                return null;
            case "onCaptureFrameDone": post(() -> listener.onCaptureFrameDone((Long) args[0])); return null;
            case "onDeviceUpdate": post(() -> listener.onDeviceUpdate((Integer) args[0])); return null;
            case "onError": post(() -> listener.onError((Integer) args[0])); return null;
            case "onVIFInfoUpdate":
                post(() -> listener.onInfo((Long) args[0], (Integer) args[1], (Integer) args[2])); return null;
            case "onVOPCaptureDone":
                post(() -> listener.onVopCaptureDone((Integer) args[0], (Long) args[1],
                        (Integer) args[2], (Integer) args[3], (Integer) args[4])); return null;
            case "onVivoNotifyCallback":
                post(() -> listener.onNotify((Integer) args[0], (Integer) args[1])); return null;
            default: throw new IllegalArgumentException("Unknown VCF2 callback " + method.getName());
        }
    }

    private void post(Runnable event) {
        if (!closed) handler.post(() -> { if (!closed) event.run(); });
    }

    private void release(Buffer buffer) {
        synchronized (pending) { pending.remove(buffer); }
        buffer.close();
    }

    @Override public void close() throws ReflectiveOperationException {
        Object owned;
        synchronized (pending) {
            if (closed) return;
            closed = true;
            owned = device;
            device = null;
            for (Buffer buffer : pending) buffer.close();
            pending.clear();
        }
        // Framework callbacks run under its device lock; never call close on the Binder callback thread.
        if (owned != null) closeMethod.invoke(owned);
    }
}
