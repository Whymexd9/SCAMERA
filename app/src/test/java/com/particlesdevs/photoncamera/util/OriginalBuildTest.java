package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P206: the SCAMERA certificate digest and the every-signer rule. */
public class OriginalBuildTest {
    @Test public void onlyTheScameraCertificatePasses() {
        assertTrue(OriginalBuild.matches(new String[]{OriginalBuild.CERT_SHA256}));
        assertFalse("another key", OriginalBuild.matches(new String[]{"00" + OriginalBuild.CERT_SHA256.substring(2)}));
        assertFalse("ours and another signer", OriginalBuild.matches(new String[]{OriginalBuild.CERT_SHA256, "ab"}));
        assertFalse("no signature", OriginalBuild.matches(new String[0]));
        assertFalse(OriginalBuild.matches(null));
    }

    @Test public void theDigestIsLowerHexSha256() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", OriginalBuild.sha256(new byte[0]));
        assertEquals(64, OriginalBuild.CERT_SHA256.length());
    }
}
