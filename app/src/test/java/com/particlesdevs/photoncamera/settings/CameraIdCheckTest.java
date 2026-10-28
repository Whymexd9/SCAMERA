package com.particlesdevs.photoncamera.settings;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.*;

/**
 * P384: the Camera ID sheet's check of what is typed (CameraIdCheck) - a phone that lists cameras 0, 1, 2 and 4, describes the
 * hidden 5 too, and has camera 3 as a physical camera of logical 0.
 */
public class CameraIdCheckTest {
    private static final List<String> LISTED = Arrays.asList("0", "1", "2", "4");
    private static final Map<String, String> DESCRIBED = new HashMap<>();

    static {
        DESCRIBED.put("0", "back 6.9 mm");
        DESCRIBED.put("1", "front 3.2 mm");
        DESCRIBED.put("2", "back 2.2 mm");
        DESCRIBED.put("3", "back 9.1 mm");
        DESCRIBED.put("4", "back 18 mm");
        DESCRIBED.put("5", "back 18 mm");
    }

    private static final Function<String, String> LOGICAL_OF = id -> "3".equals(id) ? "0" : null;

    private static CameraIdCheck.Result check(String typed) {
        return CameraIdCheck.check(typed, LISTED, DESCRIBED::get, LOGICAL_OF);
    }

    @Test
    public void emptyIsEmpty() {
        assertEquals(CameraIdCheck.State.EMPTY, check("").state);
        assertEquals(CameraIdCheck.State.EMPTY, check(" ").state);
        assertEquals(CameraIdCheck.State.EMPTY, check(null).state);
        assertEquals("", check("  ").id);
    }

    @Test
    public void notAnIdIsInvalid() {
        for (String typed : new String[]{"3a!", "1 2", "-3", "0--1", "0-", "0-1-2", "12345678901234567"})
            assertEquals(typed, CameraIdCheck.State.INVALID, check(typed).state);
        assertEquals("16 characters still are an id", CameraIdCheck.State.UNKNOWN, check("1234567890123456").state);
        assertEquals(17, "12345678901234567".length());
        assertEquals(CameraIdCheck.MAX_LENGTH + 1, "12345678901234567".length());
    }

    @Test
    public void aListedOrDescribedCameraIsKnown() {
        CameraIdCheck.Result two = check("2");
        assertEquals(CameraIdCheck.State.KNOWN, two.state);
        assertEquals("2", two.id);
        assertEquals("back 2.2 mm", two.detail);
        CameraIdCheck.Result four = check("  4 ");
        assertEquals(CameraIdCheck.State.KNOWN, four.state);
        assertEquals("trimmed", "4", four.id);
        // a hidden camera the list leaves out but the phone still opens
        assertEquals(CameraIdCheck.State.KNOWN, check("5").state);
        assertEquals("back 18 mm", check("5").detail);
    }

    @Test
    public void aPhysicalCameraIsSavedThroughItsLogicalOne() {
        CameraIdCheck.Result three = check("3");
        assertEquals(CameraIdCheck.State.PHYSICAL, three.state);
        assertEquals("0-3", three.id);
        assertEquals("back 9.1 mm", three.detail);
        CameraIdCheck.Result pair = check("0-3");
        assertEquals(CameraIdCheck.State.KNOWN, pair.state);
        assertEquals("0-3", pair.id);
        assertEquals("the physical part's description", "back 9.1 mm", pair.detail);
    }

    @Test
    public void aCameraThePhoneDoesNotOfferIsUnknown() {
        CameraIdCheck.Result seven = check("7");
        assertEquals(CameraIdCheck.State.UNKNOWN, seven.state);
        assertEquals("7", seven.id);
        assertNull(seven.detail);
        assertEquals(CameraIdCheck.State.UNKNOWN, check("0-9").state);
        assertEquals("letters too: some HALs name cameras", CameraIdCheck.State.UNKNOWN, check("tele_a").state);
    }
}
