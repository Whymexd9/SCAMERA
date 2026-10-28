package com.particlesdevs.photoncamera.settings;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** P385: the stored settings order - its text, how it meets a page as built (new, gone and hidden rows), saving and reset. */
public class SettingsLayoutTest {
    /** A page with two groups that share rows, a closed one and a loose row. */
    private static SettingsLayout.Page page() {
        SettingsLayout.Page p = new SettingsLayout.Page("page")
                .add("page", Arrays.asList("colour", "shoot", "app", "loose"))
                .add("colour", Arrays.asList("c1", "c2", "c3"))
                .add("shoot", Arrays.asList("s1", "s2"))
                .add("app", Arrays.asList("a1", "a2"));
        p.open.addAll(Arrays.asList("colour", "shoot"));
        return p;
    }

    private static Map<String, List<String>> map(Object... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], Arrays.asList((String[]) kv[i + 1]));
        return m;
    }

    private static String[] l(String... s) {
        return s;
    }

    @Test public void textRoundTripsAndSkipsWhatItCannotRead() {
        Map<String, List<String>> m = map("page", l("shoot", "colour"), "colour", l("c2", "c1"));
        String text = SettingsLayout.text(m);
        assertEquals("page=shoot,colour;colour=c2,c1", text);
        assertEquals(m, SettingsLayout.parse(text));
        assertEquals(map("a", l("x", "y")), SettingsLayout.parse("=x;a=x,,y,x; ;b"));
        assertTrue(SettingsLayout.parse(null).isEmpty());
        assertFalse(SettingsLayout.storable("a=b"));
        assertFalse(SettingsLayout.storable("a,b"));
        assertFalse(SettingsLayout.storable("a;b"));
        assertTrue(SettingsLayout.storable("#page.3"));
    }

    @Test public void nothingStoredIsTheAppsOrder() {
        SettingsLayout.Page p = page();
        assertEquals(p.containers, SettingsLayout.apply(p, ""));
        assertTrue(SettingsLayout.isDefault(p, null));
    }

    @Test public void theStoredOrderWinsAndRowsMoveBetweenOpenGroupsOnly() {
        SettingsLayout.Page p = page();
        LinkedHashMap<String, List<String>> out = SettingsLayout.apply(p,
                "page=loose,app,colour,shoot;colour=c3,s2,c1,a1;shoot=s1,c2;app=a2,a1");
        assertEquals(Arrays.asList("loose", "app", "colour", "shoot"), out.get("page"));
        // s2 came over from the other open group; a1 of the closed group stays home
        assertEquals(Arrays.asList("c3", "s2", "c1"), out.get("colour"));
        assertEquals(Arrays.asList("s1", "c2"), out.get("shoot"));
        assertEquals(Arrays.asList("a2", "a1"), out.get("app"));
        // a group never goes into a group, a loose row never leaves the page
        out = SettingsLayout.apply(p, "colour=loose,shoot,c1");
        assertEquals(Arrays.asList("c1", "c2", "c3"), out.get("colour"));
        assertEquals(p.containers.get("page"), out.get("page"));
        assertFalse(p.moves("app", "colour"));
        assertTrue(p.moves("app", "app"));
    }

    @Test public void aNewRowKeepsItsPlaceAndAGoneOneIsDropped() {
        SettingsLayout.Page p = page();
        // stored before c2 existed (and with a row that is gone): c2 after c1, which it follows in the app's order
        assertEquals(Arrays.asList("c3", "c1", "c2"), SettingsLayout.apply(p, "colour=c3,gone,c1").get("colour"));
        // the first row of the app's order, unknown: at the start
        assertEquals(Arrays.asList("c1", "c3", "c2"), SettingsLayout.apply(p, "colour=c3,c2").get("colour"));
        // two new rows in a row stay together, in their order
        assertEquals(Arrays.asList("c1", "c2", "c3"), SettingsLayout.apply(p, "colour=c3").get("colour"));
        // new groups follow the group they follow in the app's order
        assertEquals(Arrays.asList("shoot", "app", "loose", "colour"), SettingsLayout.apply(p, "page=shoot,colour").get("page"));
        // the row before a new one went to another group: the new one stays in its own group, at the start
        assertEquals(Arrays.asList("c2", "c3"), SettingsLayout.apply(p, "shoot=s1,c1,s2;colour=c3").get("colour").subList(0, 2));
        assertEquals(Arrays.asList("s1", "c1", "s2"), SettingsLayout.apply(p, "shoot=s1,c1,s2;colour=c3").get("shoot"));
    }

    @Test public void storeWritesOnlyWhatDiffersAndKeepsOtherPages() {
        SettingsLayout.Page p = page();
        LinkedHashMap<String, List<String>> edited = SettingsLayout.apply(p, "");
        edited.put("colour", Arrays.asList("c2", "c1", "c3"));
        String stored = SettingsLayout.store("other=x,y", p, edited);
        assertEquals("other=x,y;colour=c2,c1,c3", stored);
        assertEquals(Arrays.asList("c2", "c1", "c3"), SettingsLayout.apply(p, stored).get("colour"));
        // the app's order again: the line goes, the other page's stays
        assertEquals("other=x,y", SettingsLayout.store(stored, p, SettingsLayout.apply(p, "")));
        assertEquals("other=x,y", SettingsLayout.reset(stored, p));
        assertFalse(SettingsLayout.isDefault(p, stored));
        assertTrue(SettingsLayout.isDefault(p, "other=x,y"));
        // a move between groups writes both
        edited = SettingsLayout.apply(p, "");
        edited.put("colour", Arrays.asList("c1", "c2", "c3", "s1"));
        edited.put("shoot", Collections.singletonList("s2"));
        stored = SettingsLayout.store("", p, edited);
        assertEquals("colour=c1,c2,c3,s1;shoot=s2", stored);
        assertEquals(edited, SettingsLayout.apply(p, stored));
    }

    @Test public void aMoveAmongShownRowsKeepsTheHiddenOnesInPlace() {
        HashSet<String> hidden = new HashSet<>(Arrays.asList("h0", "h2"));
        List<String> old = Arrays.asList("h0", "r1", "r2", "h2", "r3");
        // r2 to the top: h2 still follows r2, h0 stays at the start
        assertEquals(Arrays.asList("h0", "r2", "h2", "r1", "r3"), SettingsLayout.arrange(old, Arrays.asList("r2", "r1", "r3"), hidden));
        // r2 moved out (to another group), x moved in: h2 follows r1, the shown row before it that stayed
        assertEquals(Arrays.asList("h0", "x", "r1", "h2", "r3"), SettingsLayout.arrange(old, Arrays.asList("x", "r1", "r3"), hidden));
        // nothing shown is left: the hidden rows stay
        assertEquals(Arrays.asList("h0", "h2"), SettingsLayout.arrange(old, Collections.emptyList(), hidden));
    }
}
