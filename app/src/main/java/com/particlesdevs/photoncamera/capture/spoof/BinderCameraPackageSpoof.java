package com.particlesdevs.photoncamera.capture.spoof;

import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.particlesdevs.photoncamera.api.NativeEngine;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Experimental package rewrite at the raw ICameraService Binder boundary.
 *
 * Unlike a java.lang.reflect.Proxy around ICameraService, this keeps Android's generated AIDL
 * proxy intact. Only its mRemote IBinder is wrapped, so callbacks, death recipients, transaction
 * codes and vendor-specific interface methods continue to behave exactly as before.
 */
public final class BinderCameraPackageSpoof {
    private static final String TAG = "BinderPkgSpoof";
    private static volatile Context appContext;

    private BinderCameraPackageSpoof() {}

    public static void initialize(Context context) {
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
        try {
            // SCAMERA: our own JNI hidden-API exemption instead of LSPosed HiddenApiBypass.
            NativeEngine.initialize();
            Log.i(TAG, "Hidden API access requested");
        } catch (Throwable error) {
            Log.e(TAG, "Hidden API access request failed", error);
        }
    }

    public static boolean apply() {
        Context context = appContext;
        if (context == null) return false;

        String nativePackage = context.getPackageName();
        String targetPackage = CameraPackageSpoofSettings.getBinderPackage(context);

        try {
            Class<?> globalClass =
                    Class.forName("android.hardware.camera2.CameraManager$CameraManagerGlobal");
            Method get = globalClass.getDeclaredMethod("get");
            get.setAccessible(true);
            Object global = get.invoke(null);

            // With the default value there is deliberately no hook and no camera-service touch.
            if (nativePackage.equals(targetPackage)) {
                updateExistingHook(globalClass, global, nativePackage, targetPackage);
                return true;
            }

            // Force creation of Android's real generated ICameraService Stub.Proxy.
            try {
                Method getCameraService = globalClass.getDeclaredMethod("getCameraService");
                getCameraService.setAccessible(true);
                getCameraService.invoke(global);
            } catch (Throwable ignored) {
                // Some vendor frameworks initialize the cached proxy through a different path.
            }

            Field serviceField = findCameraServiceField(globalClass, global);
            if (serviceField == null) {
                Log.w(TAG, "ICameraService field is not initialized yet");
                return false;
            }
            serviceField.setAccessible(true);
            Object cameraService = serviceField.get(global);
            Field remoteField = findRemoteField(cameraService);
            if (remoteField == null) {
                Log.w(TAG, "ICameraService mRemote IBinder field not found");
                return false;
            }
            remoteField.setAccessible(true);
            IBinder remote = (IBinder) remoteField.get(cameraService);
            if (remote instanceof CameraBinder) {
                ((CameraBinder) remote).setPackages(nativePackage, targetPackage);
                Log.i(TAG, "Existing raw Binder hook updated: " + targetPackage);
                return true;
            }

            remoteField.set(cameraService,
                    new CameraBinder(remote, nativePackage, targetPackage));
            Log.i(TAG, "Raw ICameraService Binder hook installed: " + targetPackage);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Raw ICameraService Binder hook failed", error);
            return false;
        }
    }

    private static void updateExistingHook(
            Class<?> globalClass, Object global, String nativePackage, String targetPackage) {
        try {
            Field serviceField = findCameraServiceField(globalClass, global);
            if (serviceField == null) return;
            serviceField.setAccessible(true);
            Object cameraService = serviceField.get(global);
            Field remoteField = findRemoteField(cameraService);
            if (remoteField == null) return;
            remoteField.setAccessible(true);
            Object remote = remoteField.get(cameraService);
            if (remote instanceof CameraBinder) {
                ((CameraBinder) remote).setPackages(nativePackage, targetPackage);
            }
        } catch (Throwable ignored) {
            // Default mode must never interfere with normal camera startup.
        }
    }

    private static Field findCameraServiceField(Class<?> type, Object global)
            throws IllegalAccessException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(global);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null) continue;
                String fieldType = field.getType().getName();
                String valueType = value.getClass().getName();
                if (fieldType.contains("ICameraService") || valueType.contains("ICameraService")) {
                    return field;
                }
                for (Class<?> iface : value.getClass().getInterfaces()) {
                    if (iface.getName().contains("ICameraService")) return field;
                }
            }
        }
        return null;
    }

    private static Field findRemoteField(Object cameraService) {
        if (cameraService == null) return null;
        for (Class<?> current = cameraService.getClass();
                current != null;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals("mRemote")
                        && IBinder.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
        }
        return null;
    }

    /** Equivalent to ChickenHook BinderHook.FakeBinder, with camera Parcel rewriting. */
    private static final class CameraBinder implements IBinder {
        private final IBinder original;
        private volatile String nativePackage;
        private volatile String targetPackage;

        CameraBinder(IBinder original, String nativePackage, String targetPackage) {
            this.original = original;
            setPackages(nativePackage, targetPackage);
        }

        void setPackages(String nativePackage, String targetPackage) {
            this.nativePackage = nativePackage;
            this.targetPackage = targetPackage;
        }

        @Override
        public String getInterfaceDescriptor() throws RemoteException {
            return original.getInterfaceDescriptor();
        }

        @Override
        public boolean pingBinder() {
            return original.pingBinder();
        }

        @Override
        public boolean isBinderAlive() {
            return original.isBinderAlive();
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return original.queryLocalInterface(descriptor);
        }

        @Override
        public void dump(FileDescriptor fd, String[] args) throws RemoteException {
            original.dump(fd, args);
        }

        @Override
        public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
            original.dumpAsync(fd, args);
        }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            String from = nativePackage;
            String to = targetPackage;
            if (from == null || to == null || from.equals(to)) {
                return original.transact(code, data, reply, flags);
            }

            Parcel rewritten = null;
            try {
                rewritten = rewriteStrings(data, from, to);
                if (rewritten != null) {
                    Log.d(TAG, "CameraService transaction " + code
                            + " package rewritten: " + from + " -> " + to);
                    return original.transact(code, rewritten, reply, flags);
                }
            } catch (Throwable error) {
                // A failed rewrite must degrade to an untouched transaction, never a frozen camera.
                Log.e(TAG, "Parcel rewrite skipped for transaction " + code, error);
            } finally {
                if (rewritten != null) rewritten.recycle();
            }
            return original.transact(code, data, reply, flags);
        }

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            original.linkToDeath(recipient, flags);
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return original.unlinkToDeath(recipient, flags);
        }
    }

    /**
     * Finds exact writeString() values on 4-byte Parcel boundaries and splices a replacement
     * Parcel. appendFrom() is intentional: unlike marshall()/unmarshall(), it preserves embedded
     * strong Binder objects and fixes their object offsets when the new string has another length.
     */
    private static Parcel rewriteStrings(Parcel source, String from, String to) {
        if (source == null || from.length() == 0) return null;
        int originalPosition = source.dataPosition();
        int size = source.dataSize();
        List<int[]> matches = new ArrayList<int[]>();

        try {
            for (int position = 0; position <= size - 4; position += 4) {
                source.setDataPosition(position);
                if (source.readInt() != from.length()) continue;
                source.setDataPosition(position);
                String candidate;
                try {
                    candidate = source.readString();
                } catch (Throwable ignored) {
                    continue;
                }
                int end = source.dataPosition();
                if (from.equals(candidate) && end > position && end <= size) {
                    matches.add(new int[]{position, end});
                    position = end - 4;
                }
            }
        } finally {
            source.setDataPosition(originalPosition);
        }

        if (matches.isEmpty()) return null;

        Parcel result = Parcel.obtain();
        int cursor = 0;
        try {
            for (int[] match : matches) {
                int start = match[0];
                int end = match[1];
                if (start > cursor) result.appendFrom(source, cursor, start - cursor);
                result.writeString(to);
                cursor = end;
            }
            if (cursor < size) result.appendFrom(source, cursor, size - cursor);
            result.setDataPosition(0);
            return result;
        } catch (Throwable error) {
            result.recycle();
            throw error;
        }
    }
}
