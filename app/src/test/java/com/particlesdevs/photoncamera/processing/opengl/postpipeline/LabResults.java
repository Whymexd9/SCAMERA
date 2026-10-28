package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;

/** Tests of the lab's pages: a rendered comparison without a merge (the sides' constructor is this package's). */
public final class LabResults {
    private LabResults() {}

    /** A and B, small pictures of {@code a} and {@code b} grey. */
    public static ScamLabRun.Result of(int a, int b) {
        final ScamLabRun.Result r = new ScamLabRun.Result();
        r.a = side(a);
        r.b = side(b);
        return r;
    }

    private static ScamLabRun.Side side(int grey) {
        final Bitmap photo = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
        photo.eraseColor(0xFF000000 | grey * 0x010101);
        return new ScamLabRun.Side(photo, null, null, 1, 1, "");
    }

    /** A lab shot folder that ScamLabRun.Shot.load reads: a burst of {@link ScamLabBurstTest} and its metadata. */
    public static java.io.File shot(java.io.File dir, java.io.File burst) throws java.io.IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no folder " + dir);
        java.nio.file.Files.copy(burst.toPath(), new java.io.File(dir, ScamLab.BURST).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        java.nio.file.Files.write(new java.io.File(dir, ScamLab.META).toPath(), "{\"prefs\":{},\"params\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return dir;
    }

    /** An NCH v12 burst (ScamLabBurstTest's) in {@code tmp}. */
    public static java.io.File burst(org.junit.rules.TemporaryFolder folder) throws Exception {
        return new ScamLabBurstTest() {{ tmp = folder; }}.burst(12);
    }
}
