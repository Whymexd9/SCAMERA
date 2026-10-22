package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Map;

/** P303: the developer page reads scam_dev.txt once; its values are the per-key reads' (first line of a key wins). */
public class ScamDevSnapshotTest {
    @Test
    public void oneReadGivesEveryKeysValue() {
        final String text = "vf_why 0\r\n  worker_gpu_normal_priority   1  \nbroken line here\n\nvf_why 1\nhybrid_why_fusion_mono 0.04\n";
        final java.util.List<String> lines = ScamDevFile.lines(text);
        final Map<String, String> m = new java.util.HashMap<>();
        for (String l : lines) {
            String[] p = l.trim().split("\\s+");
            if (p.length == 2 && !m.containsKey(p[0])) m.put(p[0], p[1]);
        }
        for (String key : new String[]{"vf_why", "worker_gpu_normal_priority", "hybrid_why_fusion_mono", "broken", "missing"})
            assertEquals(key, ScamDevFile.valueOf(lines, key), m.get(key));
        assertEquals("0", m.get("vf_why"));
        assertNull(m.get("broken"));
        assertEquals(m, ScamDevFile.snapshotOf(text));
    }
}
