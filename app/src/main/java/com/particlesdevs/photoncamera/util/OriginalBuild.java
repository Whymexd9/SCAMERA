package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * P206 (owner, 2026-10-17: «сделай защиту в нашем апк чтобы редактировать его могли только мы»): the APK must carry the SCAMERA
 * signing certificate (CN=SCAMERA, the CI and local builds alike). A copy re-signed with another key after it was edited fails the
 * check: the camera does not open and the merge worker is not started. Android cannot keep an APK from being unpacked and edited;
 * the check makes an edited copy useless unless it is also patched out, in each place it is made.
 */
public final class OriginalBuild {
    private OriginalBuild() {}

    /** SHA-256 of the SCAMERA signing certificate (apksigner verify --print-certs). */
    static final String CERT_SHA256 = "7b12875e8172311fecf217e0aa4b4d86d090ae01c50964047d2bae104d91e9d5";

    private static volatile Boolean cached;

    /** True when the installed APK is signed with the SCAMERA certificate (cached for the process). */
    public static boolean check(@Nullable Context context) {
        Boolean c = cached;
        if (c != null) return c;
        if (context == null) return true; // nothing to check against yet; the next call with a context decides
        final boolean ok = robolectric() || matches(certificates(context));
        cached = ok;
        if (!ok) Log.e("OriginalBuild", "the APK is not signed with the SCAMERA certificate");
        return ok;
    }

    /** Unit tests (Robolectric) run without an installed, signed APK. */
    private static boolean robolectric() {
        return "robolectric".equals(Build.FINGERPRINT);
    }

    static boolean matches(@Nullable String[] digests) {
        if (digests == null || digests.length == 0) return false;
        for (String d : digests) if (!CERT_SHA256.equals(d)) return false; // every signer must be ours
        return true;
    }

    @Nullable
    private static String[] certificates(Context context) {
        try {
            final PackageManager pm = context.getPackageManager();
            final String name = context.getPackageName();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) return null;
                signatures = info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
                // a rotated key keeps its lineage: the current signer is the last of the history
                if (!info.signingInfo.hasMultipleSigners() && signatures != null && signatures.length > 1)
                    signatures = new Signature[]{signatures[signatures.length - 1]};
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null) return null;
            final String[] out = new String[signatures.length];
            for (int i = 0; i < signatures.length; i++) out[i] = sha256(signatures[i].toByteArray());
            return out;
        } catch (Exception e) {
            Log.e("OriginalBuild", "signature unreadable: " + e);
            return null;
        }
    }

    static String sha256(byte[] data) throws Exception {
        final byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        final StringBuilder s = new StringBuilder(d.length * 2);
        for (byte b : d) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }
}
