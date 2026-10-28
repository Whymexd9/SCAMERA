package com.particlesdevs.photoncamera.gallery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;

import com.particlesdevs.photoncamera.gallery.ui.GalleryActivity;
import com.particlesdevs.photoncamera.ui.SplashActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * The app claims only intents it can answer: no PICK / GET_CONTENT (no pick mode returns a photo), no video VIEW / REVIEW (no
 * video playback), no IMAGE_CAPTURE / STILL_IMAGE_CAMERA_SECURE (no capture-for-result or lock-screen mode); the caller got
 * RESULT_CANCELED or a still frame. Photos and the camera itself are still claimed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryManifestTest {
    private static boolean offers(Intent intent, Class<?> activity) {
        PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();
        List<ResolveInfo> l = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo r : l) if (r.activityInfo != null && activity.getName().equals(r.activityInfo.name)) return true;
        return false;
    }

    private static boolean offersAny(Intent intent) {
        String own = RuntimeEnvironment.getApplication().getPackageName();
        for (ResolveInfo r : RuntimeEnvironment.getApplication().getPackageManager().queryIntentActivities(intent, 0))
            if (r.activityInfo != null && own.equals(r.activityInfo.packageName)) return true;
        return false;
    }

    @Test
    public void photosAreStillViewed() {
        Uri photo = Uri.parse("content://media/external/images/media/1");
        assertTrue(offers(new Intent(Intent.ACTION_VIEW).setDataAndType(photo, "image/jpeg"), GalleryActivity.class));
        assertTrue(offers(new Intent("com.android.camera.action.REVIEW").setDataAndType(photo, "image/jpeg"), GalleryActivity.class));
        assertTrue(offers(new Intent(Intent.ACTION_EDIT).setDataAndType(photo, "image/jpeg"), GalleryActivity.class));
    }

    @Test
    public void noPickWithoutAResult() {
        assertFalse(offers(new Intent(Intent.ACTION_PICK).setType("image/*"), GalleryActivity.class));
        assertFalse(offers(new Intent(Intent.ACTION_PICK).setType("video/*"), GalleryActivity.class));
        assertFalse(offers(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), GalleryActivity.class));
        assertFalse(offers(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), GalleryActivity.class));
    }

    @Test
    public void noVideoViewer() {
        Uri video = Uri.parse("content://media/external/video/media/1");
        assertFalse(offers(new Intent(Intent.ACTION_VIEW).setDataAndType(video, "video/mp4"), GalleryActivity.class));
        assertFalse(offers(new Intent("com.android.camera.action.REVIEW").setDataAndType(video, "video/mp4"), GalleryActivity.class));
    }

    @Test
    public void noCaptureForResultWithoutAResult() {
        assertTrue("the camera itself", offers(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), SplashActivity.class));
        assertFalse(offersAny(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)));
        assertFalse(offersAny(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)));
    }
}
