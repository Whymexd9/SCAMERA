package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 2026-10-27 (owner: «я разрешаю тебе полностью поменять расположение настроек», «но функционал теряться не должен при
 * упрощении»): the settings may move anywhere, but every row keeps its stored key, its type, its default and range, its
 * entries, its dependency, its keyboard and whether it is a tap. The frozen rows (src/test/resources/settings_rows_frozen.tsv)
 * are the value rows of preferences.xml before the new layout; each must still exist with identical attributes - only «Стиль
 * снимка → Плёночный LUT» (photo_style_lut, a link that stored nothing; its page sits in the same group now) is gone. New rows
 * are allowed.
 * The file is regenerated only for an owner-approved change of a default or a range.
 */
public class SettingsMovesKeepValuesTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android", APP = "http://schemas.android.com/apk/res-auto";
    /**
     * The frozen columns after the key and the tag: {attribute, namespace} (inputType: the text rows' keyboard; selectable: the
     * notes that are no tap).
     */
    private static final String[][] COLUMNS = {{"defaultValue", ANDROID}, {"minValue", APP}, {"maxValue", APP}, {"stepPerUnit", APP},
            {"isFloat", APP}, {"entries", ANDROID}, {"entryValues", ANDROID}, {"dependency", ANDROID}, {"persistent", ANDROID},
            {"inputType", ANDROID}, {"selectable", ANDROID}};
    /**
     * The rows removed on purpose: the 2026-10-27 layout's empty LUT link (no stored value, no action left); P419 the «Цвет» shadow /
     * light vibrance and its skin protection, duplicates of the reference app's vibrance rows (SettingsMigration moves their values
     * into why_vibrance_shadows / why_vibrance_hl_str once; why_skin_protect is the one protection).
     */
    private static final List<String> REMOVED = java.util.Arrays.asList("photo_style_lut", "pref_scam_hybrid_colour_vib_shadows",
            "pref_scam_hybrid_colour_vib_highlights", "pref_scam_hybrid_colour_skin_protect");

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    /** key -> the frozen line (tag and attributes, "-" for an absent attribute). */
    private static Map<String, String> frozen() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = SettingsMovesKeepValuesTest.class.getClassLoader().getResourceAsStream("settings_rows_frozen.tsv")) {
            assertNotNull("settings_rows_frozen.tsv", in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] cells = line.split("\t", -1);
                assertEquals(line, 2 + COLUMNS.length, cells.length);
                assertFalse("frozen twice: " + cells[0], out.containsKey(cells[0]));
                out.put(cells[0], line.substring(cells[0].length() + 1));
            }
        }
        return out;
    }

    /** The keys written as @string/… resolve through res/values (the stored key is the string's value). */
    private static Map<String, String> strings() throws Exception {
        Map<String, String> out = new HashMap<>();
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(src("src/main/res/values/preference_keys.xml")).getDocumentElement();
        NodeList list = root.getElementsByTagName("string");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            out.put(e.getAttribute("name"), e.getTextContent());
        }
        return out;
    }

    /** key -> the same line built from today's preferences.xml (every keyed element but a page or a category). */
    private static Map<String, String> current() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element root = factory.newDocumentBuilder().parse(src("src/main/res/xml/preferences.xml")).getDocumentElement();
        Map<String, String> names = strings(), out = new LinkedHashMap<>();
        List<Element> all = new ArrayList<>();
        collect(root, all);
        for (Element e : all) {
            if (e == root || !e.hasAttributeNS(ANDROID, "key")) continue;
            String tag = e.getTagName();
            if (tag.equals("PreferenceScreen") || tag.equals("PreferenceCategory")) continue;
            String key = e.getAttributeNS(ANDROID, "key");
            if (key.startsWith("@string/")) {
                String name = key.substring("@string/".length());
                assertTrue("key string " + name, names.containsKey(name));
                key = names.get(name);
            }
            StringBuilder line = new StringBuilder(tag);
            for (String[] c : COLUMNS) line.append('\t').append(e.hasAttributeNS(c[1], c[0]) ? e.getAttributeNS(c[1], c[0]) : "-");
            assertFalse("key used twice in preferences.xml: " + key, out.containsKey(key));
            out.put(key, line.toString());
        }
        return out;
    }

    private static void collect(Node node, List<Element> out) {
        if (node instanceof Element) out.add((Element) node);
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) collect(c, out);
    }

    @Test public void everyFrozenRowKeepsItsKeyTypeDefaultAndRange() throws Exception {
        Map<String, String> before = frozen(), now = current();
        assertTrue("frozen rows: " + before.size(), before.size() > 350);
        List<String> lost = new ArrayList<>(), changed = new ArrayList<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (REMOVED.contains(e.getKey())) continue;
            String today = now.get(e.getKey());
            if (today == null) lost.add(e.getKey());
            else if (!today.equals(e.getValue())) changed.add(e.getKey() + "\n  was " + e.getValue() + "\n  now " + today);
        }
        assertTrue("rows lost: " + lost, lost.isEmpty());
        assertTrue("rows changed: " + changed, changed.isEmpty());
    }

    @Test public void onlyTheEmptyLutLinkWasRemoved() throws Exception {
        Map<String, String> before = frozen(), now = current();
        for (String key : REMOVED) {
            assertTrue("frozen " + key, before.containsKey(key));
            assertFalse("still in preferences.xml: " + key, now.containsKey(key));
        }
    }
}
