package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;

/** P119 «Название фото»: SCA instead of IMG by default, any template with date / time variables like WhyCam's. */
public class FileNamePatternTest {
    private static long at(int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        c.set(Calendar.MILLISECOND, ms);
        return c.getTimeInMillis();
    }

    @Test public void defaultIsScaWithDateAndTime() {
        final long t = at(2026, 10, 13, 18, 20, 18, 227);
        assertEquals("SCA_20261013_182018", FileNamePattern.format(FileNamePattern.DEFAULT, t));
        assertEquals("SCA_20261013_182018_227", FileNamePattern.format("SCA_yyyyMMdd_HHmmss_SSS", t));
        assertEquals("IMG_20261013_182018", FileNamePattern.format("IMG_yyyyMMdd_HHmmss", t));
        assertEquals("26-10-13 18.20", FileNamePattern.format("yy-MM-dd HH.mm", t));
    }

    @Test public void textIsKeptAndBadCharactersDropped() {
        final long t = at(2026, 1, 2, 3, 4, 5, 6);
        assertEquals("Отпуск_2026", FileNamePattern.format("Отпуск_yyyy", t));
        assertEquals("WhyCam_20260102", FileNamePattern.format("WhyCam_yyyyMMdd", t));
        assertEquals("ab_2026", FileNamePattern.format("a/b:*?_yyyy", t));
        assertEquals("name", FileNamePattern.clean("..name.."));
        assertEquals(FileNamePattern.DEFAULT, FileNamePattern.clean("  /\\:  "));
        assertEquals(FileNamePattern.DEFAULT, FileNamePattern.clean(null));
        assertEquals(96, FileNamePattern.clean(new String(new char[200]).replace('\0', 'x')).length());
    }
}
