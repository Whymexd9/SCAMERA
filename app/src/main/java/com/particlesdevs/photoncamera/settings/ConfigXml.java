package com.particlesdevs.photoncamera.settings;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * SCAMERA config file: every exported SharedPreferences file as typed entries, in the element names of Android's own
 * shared_prefs XML (string, boolean, int, long, float, set), so a value comes back with exactly the type it was saved with.
 * <pre>
 * &lt;scamera-config version="1" device="vivo/PD2505" model="..." app="..." created="..."&gt;
 *   &lt;prefs file="main"&gt; &lt;boolean name="k" value="true" /&gt; &lt;string name="k"&gt;v&lt;/string&gt; ... &lt;/prefs&gt;
 *   &lt;prefs file="module_profile_v2_common"&gt; ... &lt;/prefs&gt;
 * &lt;/scamera-config&gt;
 * </pre>
 * A plain shared_prefs file (root {@code <map>}, the old PhotonCamera XML backup) reads as the "main" file.
 */
public final class ConfigXml {
    public static final String ROOT = "scamera-config";
    public static final String MAIN = "main";
    public static final int VERSION = 1;

    /** Parsed config: header attributes and the prefs files by name, in file order. */
    public static final class Config {
        public final Map<String, String> attributes = new LinkedHashMap<>();
        public final Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        public boolean legacyMap;
    }

    private ConfigXml() {}

    public static void write(OutputStream out, Map<String, String> attributes, Map<String, ? extends Map<String, ?>> files) throws IOException {
        XmlSerializer s = Xml.newSerializer();
        s.setOutput(out, StandardCharsets.UTF_8.name());
        s.startDocument(StandardCharsets.UTF_8.name(), true);
        s.text("\n");
        s.startTag(null, ROOT);
        s.attribute(null, "version", String.valueOf(VERSION));
        for (Map.Entry<String, String> a : attributes.entrySet()) if (a.getValue() != null) s.attribute(null, a.getKey(), a.getValue());
        for (Map.Entry<String, ? extends Map<String, ?>> file : files.entrySet()) {
            s.text("\n  ");
            s.startTag(null, "prefs");
            s.attribute(null, "file", file.getKey());
            // sorted keys: two configs of the same settings compare line by line
            for (Map.Entry<String, ?> e : new TreeMap<>(file.getValue()).entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                s.text("\n    ");
                writeEntry(s, e.getKey(), e.getValue());
            }
            s.text("\n  ");
            s.endTag(null, "prefs");
        }
        s.text("\n");
        s.endTag(null, ROOT);
        s.text("\n");
        s.endDocument();
        s.flush();
    }

    private static void writeEntry(XmlSerializer s, String key, Object v) throws IOException {
        if (v instanceof Set) {
            s.startTag(null, "set");
            s.attribute(null, "name", key);
            for (Object item : (Set<?>) v) {
                if (item == null) continue;
                s.startTag(null, "string");
                s.text(item.toString());
                s.endTag(null, "string");
            }
            s.endTag(null, "set");
            return;
        }
        String tag = v instanceof Boolean ? "boolean" : v instanceof Integer ? "int" : v instanceof Long ? "long"
                : v instanceof Float ? "float" : "string";
        s.startTag(null, tag);
        s.attribute(null, "name", key);
        if (tag.equals("string")) s.text(v.toString());
        else s.attribute(null, "value", v.toString());
        s.endTag(null, tag);
    }

    public static Config read(InputStream in) throws IOException {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(in, StandardCharsets.UTF_8.name());
            int ev = p.next();
            while (ev != XmlPullParser.START_TAG && ev != XmlPullParser.END_DOCUMENT) ev = p.next();
            if (ev != XmlPullParser.START_TAG) throw new IOException("empty file");
            Config config = new Config();
            if ("map".equals(p.getName())) {
                config.legacyMap = true;
                config.files.put(MAIN, readEntries(p, "map"));
                return config;
            }
            if (!ROOT.equals(p.getName())) throw new IOException("not a SCAMERA config: <" + p.getName() + ">");
            for (int i = 0; i < p.getAttributeCount(); i++) config.attributes.put(p.getAttributeName(i), p.getAttributeValue(i));
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.END_TAG && ROOT.equals(p.getName())) break;
                if (ev != XmlPullParser.START_TAG) continue;
                if (!"prefs".equals(p.getName())) { skip(p); continue; }
                String file = p.getAttributeValue(null, "file");
                if (file == null || file.isEmpty()) throw new IOException("<prefs> without file");
                config.files.put(file, readEntries(p, "prefs"));
            }
            return config;
        } catch (XmlPullParserException e) {
            throw new IOException("XML: " + e.getMessage(), e);
        }
    }

    /** Typed entries until the end tag {@code end}; the parser stands on its start tag. */
    private static Map<String, Object> readEntries(XmlPullParser p, String end) throws IOException, XmlPullParserException {
        Map<String, Object> values = new LinkedHashMap<>();
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.END_TAG && end.equals(p.getName())) return values;
            if (ev != XmlPullParser.START_TAG) continue;
            String tag = p.getName();
            String name = p.getAttributeValue(null, "name");
            if (name == null) { skip(p); continue; }
            String value = p.getAttributeValue(null, "value");
            try {
                switch (tag) {
                    case "boolean": values.put(name, Boolean.parseBoolean(value)); skip(p); break;
                    case "int": values.put(name, Integer.parseInt(value)); skip(p); break;
                    case "long": values.put(name, Long.parseLong(value)); skip(p); break;
                    case "float": values.put(name, Float.parseFloat(value)); skip(p); break;
                    case "string": values.put(name, p.nextText()); break;
                    case "set": {
                        Set<String> set = new HashSet<>();
                        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                            if (ev == XmlPullParser.END_TAG && "set".equals(p.getName())) break;
                            if (ev == XmlPullParser.START_TAG && "string".equals(p.getName())) set.add(p.nextText());
                        }
                        values.put(name, set);
                        break;
                    }
                    default: skip(p);
                }
            } catch (NumberFormatException | NullPointerException e) {
                throw new IOException("bad value of " + name + ": " + value);
            }
        }
        throw new IOException("unterminated <" + end + ">");
    }

    private static void skip(XmlPullParser p) throws IOException, XmlPullParserException {
        if (p.getEventType() != XmlPullParser.START_TAG) return;
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.END_DOCUMENT) return;
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
        }
    }
}
