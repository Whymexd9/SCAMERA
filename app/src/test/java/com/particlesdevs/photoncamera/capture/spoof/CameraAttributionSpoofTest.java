package com.particlesdevs.photoncamera.capture.spoof;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CameraAttributionSpoofTest {
    @Test
    public void preservesIdentityTokenAndParcelableBoundary() throws Exception {
        Class<?> type = Class.forName("android.content.AttributionSourceState");
        Parcelable source = (Parcelable) type.getConstructor().newInstance();
        Binder token = new Binder();
        type.getField("uid").setInt(source, 10349);
        type.getField("pid").setInt(source, 1234);
        type.getField("packageName").set(source, "org.codeaurora.snapcam");
        type.getField("attributionTag").set(source, "raw-preview");
        type.getField("token").set(source, token);

        Parcelable copy = BinderCameraPackageSpoof.rewriteAttribution(source, "com.android.camera");
        assertEquals("org.codeaurora.snapcam", type.getField("packageName").get(source));
        assertEquals(10349, type.getField("uid").getInt(copy));
        assertEquals(1234, type.getField("pid").getInt(copy));
        assertSame(token, type.getField("token").get(copy));
        assertEquals("raw-preview", type.getField("attributionTag").get(copy));

        Parcel wire = Parcel.obtain();
        try {
            wire.writeTypedObject(copy, 0);
            wire.writeInt(0x12345678);
            wire.setDataPosition(0);
            Parcelable.Creator<?> creator = (Parcelable.Creator<?>) type.getField("CREATOR").get(null);
            Object received = wire.readTypedObject(creator);
            assertEquals("com.android.camera", type.getField("packageName").get(received));
            assertEquals(0x12345678, wire.readInt());
            assertEquals(0, wire.dataAvail());
        } finally {
            wire.recycle();
        }
    }

    @Test
    public void contextAttributionFollowsPackageSwitchAndKeepsUid() {
        CameraPackageSpoof.typedForTest = true; // the vivo Qualcomm experiment (dev switch vivo_qcom_spoof)
        try {
            SpoofCameraContext context = new SpoofCameraContext(RuntimeEnvironment.getApplication(), "com.android.camera");
            AttributionSource original = RuntimeEnvironment.getApplication().getAttributionSource();
            assertEquals("com.android.camera", context.getAttributionSource().getPackageName());
            assertEquals(original.getUid(), context.getAttributionSource().getUid());
            context.setPackageName("org.codeaurora.snapcam");
            assertEquals("org.codeaurora.snapcam", context.getAttributionSource().getPackageName());
            assertEquals(original.getUid(), context.getAttributionSource().getUid());
        } finally {
            CameraPackageSpoof.typedForTest = null;
        }
    }

    /**
     * The owner's spoof keeps the app's real attribution: a foreign package in it failed every request of the OPPO Find X7
     * Ultra (-38, onError 4, 2026-10-15); the package names are rewritten at the raw Binder boundary as in build 30799.
     */
    @Test
    public void ownersSpoofKeepsTheRealAttribution() {
        CameraPackageSpoof.typedForTest = null;
        assertFalse("off on a non-vivo phone", CameraPackageSpoof.typedAttribution());
        SpoofCameraContext context = new SpoofCameraContext(RuntimeEnvironment.getApplication(), "com.ss.android.ugc.aweme");
        AttributionSource original = RuntimeEnvironment.getApplication().getAttributionSource();
        assertEquals("the package still spoofed for getOpPackageName", "com.ss.android.ugc.aweme", context.getOpPackageName());
        assertEquals(original.getPackageName(), context.getAttributionSource().getPackageName());
        assertEquals(original.getUid(), context.getAttributionSource().getUid());
    }
}
