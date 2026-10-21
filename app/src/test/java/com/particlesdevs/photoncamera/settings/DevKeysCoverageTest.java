package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Owner, 2026-10-13: every hidden scam_dev.txt setting goes to «Для разработчика», now and later. Every key the sources read
 * from scam_dev.txt (scamDevSwitch / scamDevNumber / scamDevValue / scamDevOverrides) and every hybrid tuning key without a
 * pref_scam_hybrid_* row in preferences.xml must be in DevKeys (or DevKeys.MENU_BACKED), and DevKeys lists nothing stale.
 */
public class DevKeysCoverageTest {
    private static final Pattern DEV = Pattern.compile("scamDev(?:Switch|Number|Value|Overrides)\\(\"([a-z0-9_]+)\"");
    private static final Pattern TUNE = Pattern.compile("\\{\"[A-Za-z0-9]+\", *\"([a-z0-9_]+)\"}");
    // P128: hybrid settings read by literal key (scam_dev.txt "hybrid_<key>" overrides each); ArkTone.value adds "ark_"
    private static final Pattern HYBRID = Pattern.compile("(?:hybridValue|hybridSwitch|hybridString|routeInternalValue)\\(\"([a-z0-9_]+)\"\\s*[,)]");
    private static final Pattern ARK = Pattern.compile("(?<![A-Za-z])value\\(\"([a-z0-9_]+)\"\\s*[,)]");

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    private static Set<String> codeKeys() throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<java.nio.file.Path> files = Files.walk(src("src/main/java").toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                Matcher m = DEV.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                while (m.find()) if (!m.group(1).endsWith("_")) out.add(m.group(1)); // "hybrid_" + key: a prefix
            }
        }
        return out;
    }

    /** "hybrid_<key>" of the hybrid tuning table (PreferenceKeys) whose pref_scam_hybrid_<key> is not in preferences.xml. */
    private static Set<String> hiddenTuningKeys() throws IOException {
        String keys = new String(Files.readAllBytes(src("src/main/java/com/particlesdevs/photoncamera/settings/PreferenceKeys.java").toPath()), StandardCharsets.UTF_8);
        int start = keys.indexOf("String[][] TUNING_KEYS");
        int end = keys.indexOf("};", start);
        assertTrue("the hybrid tuning table of PreferenceKeys", start >= 0 && end > start);
        String xml = new String(Files.readAllBytes(src("src/main/res/xml/preferences.xml").toPath()), StandardCharsets.UTF_8);
        Set<String> out = new TreeSet<>();
        Matcher m = TUNE.matcher(keys.substring(start, end));
        while (m.find()) if (!xml.contains("\"pref_scam_hybrid_" + m.group(1) + "\"")) out.add("hybrid_" + m.group(1));
        return out;
    }

    /** "hybrid_<key>" of a hybrid setting read by literal key whose pref_scam_hybrid_<key> row is not in preferences.xml. */
    private static Set<String> hiddenHybridKeys() throws IOException {
        String xml = new String(Files.readAllBytes(src("src/main/res/xml/preferences.xml").toPath()), StandardCharsets.UTF_8);
        Set<String> out = new TreeSet<>();
        try (Stream<java.nio.file.Path> files = Files.walk(src("src/main/java").toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String code = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Set<String> keys = new TreeSet<>();
                Matcher m = HYBRID.matcher(code);
                while (m.find()) keys.add(m.group(1).startsWith("hybrid_") ? m.group(1).substring("hybrid_".length()) : m.group(1));
                if (p.getFileName().toString().equals("ArkTone.java")) {
                    m = ARK.matcher(code);
                    while (m.find()) keys.add("ark_" + m.group(1));
                }
                for (String k : keys) if (!xml.contains("\"pref_scam_hybrid_" + k + "\"")) out.add("hybrid_" + k);
            }
        }
        return out;
    }

    @Test
    public void everyHiddenScamDevKeyIsOnTheDeveloperPage() throws IOException {
        Set<String> listed = DevKeys.keys();
        List<String> missing = new ArrayList<>();
        Set<String> wanted = new TreeSet<>(codeKeys());
        wanted.addAll(hiddenTuningKeys());
        wanted.addAll(hiddenHybridKeys());
        for (String k : wanted) if (!listed.contains(k) && !DevKeys.MENU_BACKED.contains(k)) missing.add(k);
        assertTrue("scam_dev keys missing from DevKeys (add them to «Для разработчика»): " + missing, missing.isEmpty());
    }

    @Test
    public void developerPageListsNoStaleKey() throws IOException {
        Set<String> used = new TreeSet<>(codeKeys());
        used.addAll(hiddenTuningKeys());
        used.addAll(hiddenHybridKeys());
        List<String> stale = new ArrayList<>();
        for (String k : DevKeys.keys()) if (!used.contains(k)) stale.add(k);
        assertTrue("DevKeys lists keys the code no longer reads: " + stale, stale.isEmpty());
        assertEquals(DevKeys.all().size(), DevKeys.keys().size()); // no key twice
    }

    /** P190: «Кадров в снимке 50 / 200 Мп» are read by literal key ("hybrid_fullres50_frames" overrides) and are menu rows. */
    @Test
    public void fullResFrameCountsAreMenuRowsWithDevOverrides() throws IOException {
        String keys = new String(Files.readAllBytes(src("src/main/java/com/particlesdevs/photoncamera/settings/PreferenceKeys.java").toPath()), StandardCharsets.UTF_8);
        Set<String> hidden = hiddenHybridKeys();
        for (String k : new String[]{"fullres50_frames", "fullres200_frames"}) {
            assertTrue(k, keys.contains("hybridValue(\"" + k + "\""));
            assertTrue("a menu row, not a hidden key: " + k, !hidden.contains("hybrid_" + k) && !DevKeys.keys().contains("hybrid_" + k));
        }
    }

    @Test
    public void fileEditKeepsOtherLines() {
        List<String> lines = Arrays.asList("# comment", "dcg14 0", "zsl_reader 20");
        assertEquals(Arrays.asList("# comment", "dcg14 1", "zsl_reader 20"), ScamDevFile.edit(lines, "dcg14", "1"));
        assertEquals(Arrays.asList("# comment", "zsl_reader 20"), ScamDevFile.edit(lines, "dcg14", null));
        assertEquals(Arrays.asList("# comment", "dcg14 0", "zsl_reader 20", "ois_force 0"), ScamDevFile.edit(lines, "ois_force", "0"));
        assertEquals("20", ScamDevFile.valueOf(lines, "zsl_reader"));
        assertEquals("1.5", ScamDevFile.number(1.5f));
        assertEquals("-1", ScamDevFile.number(-1f));
    }
}
