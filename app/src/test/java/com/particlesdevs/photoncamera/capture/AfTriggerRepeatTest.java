package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owner, 2026-10-18 («оппо не фокусируется», on every camera): unlockFocus - run at every session start - left
 * CONTROL_AF_TRIGGER_CANCEL in the preview builder and set the repeating request with it, so every frame cancelled the AF.
 * A trigger goes in a one-shot; the repeating preview request carries IDLE.
 */
public class AfTriggerRepeatTest {
    private static String body(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue(signature, i >= 0);
        int depth = 0, start = src.indexOf('{', i);
        for (int j = start; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(start, j + 1);
        }
        throw new AssertionError(signature);
    }

    /** The AF trigger value last written before each repeating-request call of {@code body}. */
    private static void idleBeforeEveryRepeat(String name, String body) {
        Pattern p = Pattern.compile("CONTROL_AF_TRIGGER_(START|CANCEL|IDLE)|rebuildPreviewBuilder\\(\\)|setRepeatingRequest\\(");
        // the code only: block and line comments out (old commented-out triggers stay in the file)
        Matcher m = p.matcher(body.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", ""));
        String last = "IDLE";
        int repeats = 0;
        while (m.find()) {
            if (m.group(1) != null) last = m.group(1);
            else {
                repeats++;
                assertEquals(name + ": the trigger in the repeating request", "IDLE", last);
            }
        }
        assertTrue(name + " sets a repeating request", repeats > 0);
    }

    @Test public void noTriggerStaysInTheRepeatingPreview() throws Exception {
        String src = new String(Files.readAllBytes(new File(
                "src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java").toPath()), StandardCharsets.UTF_8);
        idleBeforeEveryRepeat("unlockFocus", body(src, "public void unlockFocus()"));
        idleBeforeEveryRepeat("lockFocus", body(src, "private void lockFocus()"));
        idleBeforeEveryRepeat("releaseShotAfLock", body(src, "void releaseShotAfLock()"));
        assertTrue("a SCAM-routed shot releases its AF lock", src.contains("unlockHandler.post(CaptureController.this::releaseShotAfLock)"));
    }
}
