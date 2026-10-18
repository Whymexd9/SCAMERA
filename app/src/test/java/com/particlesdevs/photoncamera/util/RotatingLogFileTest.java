package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P214b (engineering.md A3): the debug and timings logs are one file per phone that every process appends to, rotated by
 * size. Before, each process inserted a new MediaStore entry ("SCAMERA-debug.log (1).txt" ... (31) on the OPPO Find X7
 * Ultra) because it looked the entry up by a name MediaStore had changed.
 */
public class RotatingLogFileTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static RotatingLogFile log(File dir, long cap, int generations, String alternate, List<String> opened) {
        return new RotatingLogFile("SCAMERA-debug", ".txt", cap, generations, () -> dir, () -> alternate,
                (file, rotated) -> {
                    if (opened != null) opened.add(file.getName() + (rotated ? " rotated" : ""));
                    return rotated ? "--- continued ---\n" : null;
                });
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<String> names(File dir) {
        String[] list = dir.list();
        List<String> out = new ArrayList<>(Arrays.asList(list == null ? new String[0] : list));
        java.util.Collections.sort(out);
        return out;
    }

    @Test public void namesCarryTheTextExtension() {
        assertEquals("SCAMERA-debug.txt", RotatingLogFile.name("SCAMERA-debug", ".txt", 0));
        assertEquals("SCAMERA-debug.1.txt", RotatingLogFile.name("SCAMERA-debug", ".txt", 1));
        assertEquals("SCAMERA-crash.1.log", RotatingLogFile.name("SCAMERA-crash", ".log", 1));
    }

    @Test public void everyProcessAppendsToTheSameFile() throws Exception {
        File dir = tmp.newFolder("SCAMERA");
        for (int process = 0; process < 3; process++) {
            RotatingLogFile log = log(dir, RotatingLogFile.CAP_BYTES, 3, null, null);
            assertTrue(log.write("session " + process + "\n"));
            assertEquals(new File(dir, "SCAMERA-debug.txt"), log.file());
            log.close();
        }
        assertEquals("one file, no (N) copies", List.of("SCAMERA-debug.txt"), names(dir));
        assertEquals("session 0\nsession 1\nsession 2\n", read(new File(dir, "SCAMERA-debug.txt")));
    }

    @Test public void rotatesAtTheCapAndKeepsOnlyTheGenerations() throws Exception {
        File dir = tmp.newFolder("SCAMERA");
        List<String> opened = new ArrayList<>();
        RotatingLogFile log = log(dir, 100, 3, null, opened);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            String line = String.format(java.util.Locale.ROOT, "line %02d .........\n", i); // 20 bytes
            all.append(line);
            assertTrue(log.write(line));
        }
        log.close();
        assertEquals(List.of("SCAMERA-debug.1.txt", "SCAMERA-debug.2.txt", "SCAMERA-debug.txt"), names(dir));
        String oldest = read(new File(dir, "SCAMERA-debug.2.txt"));
        String older = read(new File(dir, "SCAMERA-debug.1.txt"));
        String newest = read(new File(dir, "SCAMERA-debug.txt"));
        for (String f : new String[]{oldest, older, newest}) {
            assertTrue("a file stops at the line that reaches the cap: " + f.length(), f.length() < 100 + 20 + 18);
        }
        assertTrue(older.startsWith("--- continued ---\n"));
        assertTrue(newest.startsWith("--- continued ---\n"));
        String kept = (oldest + older + newest).replace("--- continued ---\n", "");
        assertTrue("the kept lines are the newest ones, in order", all.toString().endsWith(kept));
        assertTrue(kept.endsWith("line 39 .........\n"));
        assertFalse("the oldest generations are gone", kept.contains("line 00 "));
        assertEquals("first open plain, every later one a continuation", "SCAMERA-debug.txt", opened.get(0));
        assertTrue(opened.size() > 3);
        for (String o : opened.subList(1, opened.size())) assertEquals("SCAMERA-debug.txt rotated", o);
    }

    @Test public void aFileAlreadyAtTheCapIsRotatedBeforeTheNextProcessAppends() throws Exception {
        File dir = tmp.newFolder("SCAMERA");
        byte[] full = new byte[150];
        Arrays.fill(full, (byte) 'a');
        Files.write(new File(dir, "SCAMERA-debug.txt").toPath(), full);
        RotatingLogFile log = log(dir, 100, 3, null, null);
        assertTrue(log.write("new session\n"));
        log.close();
        assertArrayEquals(full, Files.readAllBytes(new File(dir, "SCAMERA-debug.1.txt").toPath()));
        assertEquals("--- continued ---\nnew session\n", read(new File(dir, "SCAMERA-debug.txt")));
    }

    @Test public void aFileOfAnEarlierInstallSendsTheLogToThisInstallsName() throws Exception {
        File dir = tmp.newFolder("SCAMERA");
        // stands for SCAMERA-debug.txt of an uninstalled build: there, but not openable by this one
        assertTrue(new File(dir, "SCAMERA-debug.txt").mkdir());
        String alternate = RotatingLogFile.installStem("SCAMERA-debug", 1_792_000_000_000L);
        assertTrue(alternate, alternate.matches("SCAMERA-debug-\\d{8}-\\d{6}"));
        for (int process = 0; process < 2; process++) {
            RotatingLogFile log = log(dir, RotatingLogFile.CAP_BYTES, 3, alternate, null);
            assertTrue(log.write("process " + process + "\n"));
            assertEquals(alternate + ".txt", log.file().getName());
            log.close();
        }
        assertEquals("every process of this install finds the same file", "process 0\nprocess 1\n",
                read(new File(dir, alternate + ".txt")));
    }

    @Test public void withoutAFolderNothingIsWrittenAndTheOpenWaits() throws Exception {
        File notADir = tmp.newFile("Download-is-a-file");
        AtomicInteger asked = new AtomicInteger();
        RotatingLogFile log = new RotatingLogFile("SCAMERA-debug", ".txt", 100, 3,
                () -> { asked.incrementAndGet(); return notADir; }, null, null);
        assertFalse(log.write("a\n"));
        assertFalse(log.write("b\n"));
        assertFalse(log.isOpen());
        assertEquals("one failed open per 5 s, not one per line", 1, asked.get());
    }

    @Test public void aTargetThatCannotBeTakenDropsThatGeneration() throws Exception {
        File dir = tmp.newFolder("SCAMERA");
        Files.write(new File(dir, "SCAMERA-crash.log").toPath(), "old report\n".getBytes(StandardCharsets.UTF_8));
        // SCAMERA-crash.1.log is not the app's (here a non-empty folder): it can be neither replaced nor deleted
        File blocked = new File(dir, "SCAMERA-crash.1.log");
        assertTrue(blocked.mkdir());
        assertTrue(new File(blocked, "x").createNewFile());
        RotatingLogFile.rotate(dir, "SCAMERA-crash", ".log", 2);
        assertFalse("the full file went instead of being kept", new File(dir, "SCAMERA-crash.log").exists());
        assertTrue(blocked.isDirectory());
    }

    @Test public void sizeIsCountedInUtf8Bytes() {
        String text = "Полный журнал € 📷 ok\n";
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, RotatingLogFile.utf8Length(text));
    }
}
