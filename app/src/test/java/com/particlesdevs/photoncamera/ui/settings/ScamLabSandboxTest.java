package com.particlesdevs.photoncamera.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.TreeMap;

/** P355: the lab's favourites (starred in order) and a shot's sandbox file (typed values, unset ones). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamLabSandboxTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void favouritesKeepTheirOrder() {
        final Context c = RuntimeEnvironment.getApplication();
        assertTrue(ScamLabFragment.toggleFavourite(c, "pref_scam_hybrid_why_weight_dark2"));
        assertTrue(ScamLabFragment.toggleFavourite(c, "dev:hybrid_rej_pool"));
        assertEquals(Arrays.asList("pref_scam_hybrid_why_weight_dark2", "dev:hybrid_rej_pool"), ScamLabFragment.favourites(c));
        assertFalse(ScamLabFragment.toggleFavourite(c, "pref_scam_hybrid_why_weight_dark2"));
        assertEquals(Arrays.asList("dev:hybrid_rej_pool"), ScamLabFragment.favourites(c));
    }

    @Test
    public void aSandboxFileReadsTyped() throws Exception {
        final File f = tmp.newFile("sandbox.json");
        Files.write(f.toPath(), ("{\"pref_a\":[\"s\",\"0.25\"],\"pref_b\":[\"b\",true],\"pref_c\":[\"n\",\"\"]}").getBytes(StandardCharsets.UTF_8));
        final TreeMap<String, Object> box = ScamLabFragment.readSandbox(f);
        assertEquals(3, box.size());
        assertEquals("0.25", box.get("pref_a"));
        assertEquals(Boolean.TRUE, box.get("pref_b"));
        assertTrue(box.containsKey("pref_c"));
        assertNull(box.get("pref_c"));
        assertTrue(ScamLabFragment.readSandbox(new File(tmp.getRoot(), "none.json")).isEmpty());
    }
}
