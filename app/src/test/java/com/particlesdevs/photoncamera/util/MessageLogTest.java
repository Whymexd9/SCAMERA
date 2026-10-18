package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * P214f (speed-ux.md C3): the history of the camera screen's one message channel. Before, a message was a ~2 s system
 * toast, a 1.5 s card or a short snackbar whatever its length (a FULLRES row failure of ~120 characters could not be read)
 * and nothing was kept.
 */
public class MessageLogTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static String chars(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append((char) ('a' + i % 26));
        return s.toString();
    }

    /** SIMPLE_UI: 1.3 s + 35 ms per character, at most 7 s (the band's short texts). */
    @Test public void durationIsOnePointThreeSecondsPlusThirtyFiveMsPerCharacterAtMostSeven() {
        assertEquals(1300, MessageLog.durationMs(""));
        assertEquals(1300, MessageLog.durationMs(null));
        assertEquals(1335, MessageLog.durationMs("a"));
        assertEquals(2700, MessageLog.durationMs(chars(40)));
        assertEquals(6970, MessageLog.durationMs(chars(162)));
        assertEquals(7000, MessageLog.durationMs(chars(163)));
        // a long text (~120 characters) still gets 5.5 s
        assertEquals(5500, MessageLog.durationMs(chars(120)));
        assertEquals(7000, MessageLog.durationMs(chars(5000)));
    }

    @Test public void aRepeatIsOneEntryWithACounterAtTheNewestPlace() {
        MessageLog log = new MessageLog();
        MessageLog.Entry a = log.add("Camera failure (code 4), restarting", true, 1_000);
        log.add("Camera 2: RAW 4096x3072", false, 2_000);
        MessageLog.Entry again = log.add("  Camera failure (code 4), restarting ", true, 3_000);
        assertEquals(2, log.size());
        assertEquals(2, again.count);
        assertEquals(1_000, again.firstMs);
        assertEquals(3_000, again.lastMs);
        assertTrue(again.error);
        assertSame(again, log.newestFirst().get(0));
        assertEquals("Camera 2: RAW 4096x3072", log.newestFirst().get(1).text);
        assertEquals(1, a.count); // entries are immutable: the merged one is new
        assertEquals(3, log.add("Camera failure (code 4), restarting", true, 4_000).count);
    }

    @Test public void theSameTextOfAnotherKindOrMuchLaterIsANewEntry() {
        MessageLog log = new MessageLog();
        log.add("FULLRES: mode 11 does not work", true, 0);
        assertEquals(1, log.add("FULLRES: mode 11 does not work", false, 10).count);
        assertEquals(2, log.size());
        assertEquals(2, log.add("FULLRES: mode 11 does not work", true, MessageLog.MERGE_WINDOW_MS).count);
        assertEquals(1, log.add("FULLRES: mode 11 does not work", true, 3 * MessageLog.MERGE_WINDOW_MS).count);
        assertEquals(3, log.size());
        // a clock that went back does not merge into the "future" entry
        assertEquals(1, log.add("FULLRES: mode 11 does not work", true, 5).count);
    }

    @Test public void blankTextRecordsNothing() {
        MessageLog log = new MessageLog();
        assertNull(log.add(null, false, 0));
        assertNull(log.add("   ", true, 0));
        assertEquals(0, log.size());
    }

    @Test public void theLastTwentyAreKept() {
        MessageLog log = new MessageLog();
        for (int i = 0; i < 25; i++) log.add("message " + i, i % 3 == 0, i);
        assertEquals(MessageLog.KEEP, log.size());
        List<MessageLog.Entry> list = log.newestFirst();
        assertEquals("message 24", list.get(0).text);
        assertEquals("message 5", list.get(MessageLog.KEEP - 1).text);
        // a repeat of a kept one does not grow the list
        log.add("message 10", false, 30);
        assertEquals(MessageLog.KEEP, log.size());
        assertEquals("message 10", log.newestFirst().get(0).text);
        assertEquals(2, log.newestFirst().get(0).count);
    }

    @Test public void serializedTextComesBackExactly() {
        MessageLog log = new MessageLog();
        log.add("two\nlines\twith a tab and a \\ backslash", true, 1_700_000_000_000L);
        log.add("Снято 7 из 10 кадров: свободно 812 МБ", false, 1_700_000_001_000L);
        log.add("Снято 7 из 10 кадров: свободно 812 МБ", false, 1_700_000_002_000L);
        List<MessageLog.Entry> back = MessageLog.parse(log.serialize());
        assertEquals(2, back.size());
        assertEquals("two\nlines\twith a tab and a \\ backslash", back.get(0).text);
        assertTrue(back.get(0).error);
        assertEquals(1_700_000_000_000L, back.get(0).firstMs);
        assertEquals("Снято 7 из 10 кадров: свободно 812 МБ", back.get(1).text);
        assertFalse(back.get(1).error);
        assertEquals(2, back.get(1).count);
        assertEquals(1_700_000_001_000L, back.get(1).firstMs);
        assertEquals(1_700_000_002_000L, back.get(1).lastMs);
        // one entry per line: no raw newline or tab inside a serialized text
        assertEquals(3, log.serialize().split("\n").length);
        assertEquals("ab", MessageLog.escape("a\u0000\u0007b"));
    }

    @Test public void aDamagedFileIsAnEmptyOrShorterHistory() {
        assertTrue(MessageLog.parse(null).isEmpty());
        assertTrue(MessageLog.parse("").isEmpty());
        assertTrue(MessageLog.parse("1\t2\t1\tI\tno header\n").isEmpty());
        List<MessageLog.Entry> some = MessageLog.parse("scam-messages 1\n"
                + "x\t2\t1\tI\tbad number\n"
                + "1\t2\t1\tQ\tbad kind\n"
                + "1\t2\t1\tI\t   \n"
                + "1\t2\tshort\n"
                + "1\t2\t1\tI\tkept\n");
        assertEquals(1, some.size());
        assertEquals("kept", some.get(0).text);
    }

    @Test public void anEarlierRunGoesBeforeThisRunsMessages() {
        List<MessageLog.Entry> older = new ArrayList<>();
        for (int i = 0; i < 18; i++) older.add(new MessageLog.Entry("old " + i, false, 1, i, i));
        MessageLog log = new MessageLog();
        log.add("new 0", false, 100);
        log.add("new 1", true, 101);
        log.add("new 2", false, 102);
        log.prependOlder(older);
        List<MessageLog.Entry> list = log.newestFirst();
        assertEquals(MessageLog.KEEP, list.size());
        assertEquals("new 2", list.get(0).text);
        assertEquals("new 0", list.get(2).text);
        assertEquals("old 17", list.get(3).text);
        assertEquals("old 1", list.get(MessageLog.KEEP - 1).text); // "old 0" is the 21st
    }

    @Test public void theFileKeepsTheHistoryOverARestart() throws Exception {
        File file = new File(tmp.getRoot(), "scam_messages.txt");
        MessageLog before = new MessageLog();
        before.add("The camera is not responding. Choose another module or restart the app", true, 5_000);
        before.add("HDR: on", false, 6_000);
        before.save(file);
        assertFalse(new File(file.getPath() + ".tmp").exists());
        MessageLog after = new MessageLog();
        after.add("after the restart", false, 9_000);
        after.loadOlder(file);
        List<MessageLog.Entry> list = after.newestFirst();
        assertEquals(3, list.size());
        assertEquals("after the restart", list.get(0).text);
        assertEquals("HDR: on", list.get(1).text);
        assertTrue(list.get(2).error);
        // missing or foreign files: nothing
        MessageLog none = new MessageLog();
        none.loadOlder(new File(tmp.getRoot(), "missing.txt"));
        File foreign = tmp.newFile("foreign.txt");
        Files.write(foreign.toPath(), "<map/>".getBytes(StandardCharsets.UTF_8));
        none.loadOlder(foreign);
        assertEquals(0, none.size());
    }
}
