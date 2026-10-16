package com.particlesdevs.photoncamera.capture;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class VivoSdkRawSessionTest {
    static final class Provider implements VivoSdkRawSession.Provider {
        final Map<Integer, Integer> values = new HashMap<>();
        int failKey = -1;
        int writes;
        boolean ignoreSdk;
        Provider() { values.put(40, 0); values.put(33, 1); }
        public int get(int key) { return values.get(key); }
        public void set(int key, int value) throws Exception {
            writes++;
            if (!(ignoreSdk && key == 40)) values.put(key, value);
            if (key == failKey) { failKey = -1; throw new Exception("partial Binder failure"); }
        }
        public void settle() {}
    }

    @Test public void restoresThePreviousValuesOnce() throws Exception {
        Provider provider = new Provider();
        provider.values.put(40, 3);
        VivoSdkRawSession session = VivoSdkRawSession.open(provider);
        assertEquals(Integer.valueOf(1), provider.values.get(40));
        session.close();
        int writes = provider.writes;
        session.close();
        assertEquals(writes, provider.writes);
        assertEquals(Integer.valueOf(3), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    @Test public void rollsBackAMutationThatThrows() throws Exception {
        Provider provider = new Provider();
        provider.failKey = 40;
        try { VivoSdkRawSession.open(provider); fail(); } catch (Exception expected) {}
        assertEquals(Integer.valueOf(0), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    @Test public void rejectsAnIgnoredSdkWrite() throws Exception {
        Provider provider = new Provider();
        provider.ignoreSdk = true;
        try { VivoSdkRawSession.open(provider); fail(); } catch (IllegalStateException expected) {}
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    static final class Journal implements VivoSdkRawSession.Journal {
        int[] lease;
        public int[] read() { return lease == null ? null : lease.clone(); }
        public void write(int pid, int sdk, int vcf) { lease = new int[]{pid, sdk, vcf}; }
        public void clear() { lease = null; }
    }

    /** P179: the lease is on disk while it is held and gone once the values are back. */
    @Test public void journalsTheLeaseWhileItIsHeld() throws Exception {
        Provider provider = new Provider();
        Journal journal = new Journal();
        VivoSdkRawSession session = VivoSdkRawSession.open(provider, journal, 100);
        assertArrayEquals(new int[]{100, 0, 1}, journal.lease);
        assertEquals("our own live lease is left alone", "none",
                VivoSdkRawSession.recoverStale(provider, journal, 100, () -> true));
        session.close();
        assertNull(journal.lease);
        assertEquals(Integer.valueOf(0), provider.values.get(40));
    }

    /** P179: killed while holding it (no close ran): the next start gives the values found back, only while they are ours. */
    @Test public void aDeadProcessLeaseIsGivenBackOnlyWhileOursAndIdle() throws Exception {
        Provider provider = new Provider();
        provider.values.put(40, 3);
        Journal journal = new Journal();
        VivoSdkRawSession.open(provider, journal, 100);   // the process dies here
        assertEquals(Integer.valueOf(1), provider.values.get(40));
        assertEquals("a camera in use: wait", "busy", VivoSdkRawSession.recoverStale(provider, journal, 200, () -> false));
        assertNotNull(journal.lease);
        assertEquals(Integer.valueOf(1), provider.values.get(40));
        assertEquals("restored", VivoSdkRawSession.recoverStale(provider, journal, 200, () -> true));
        assertEquals(Integer.valueOf(3), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
        assertNull(journal.lease);
        assertEquals("none", VivoSdkRawSession.recoverStale(provider, journal, 200, () -> true));
        // another client set its own values after we died: nothing is reset, the journal goes
        VivoSdkRawSession.open(provider, journal, 300);
        provider.values.put(40, 5);
        assertEquals("not ours", VivoSdkRawSession.recoverStale(provider, journal, 400, () -> true));
        assertEquals(Integer.valueOf(5), provider.values.get(40));
        assertNull(journal.lease);
    }

    /** P179: a new lease after a dead one keeps the values the dead process found, not its own leftovers. */
    @Test public void aNewLeaseAfterADeadOneRemembersTheOriginalValues() throws Exception {
        Provider provider = new Provider();
        provider.values.put(40, 3);
        Journal journal = new Journal();
        VivoSdkRawSession.open(provider, journal, 100);   // dies
        VivoSdkRawSession next = VivoSdkRawSession.open(provider, journal, 200);
        assertArrayEquals(new int[]{200, 3, 1}, journal.lease);
        next.close();
        assertEquals(Integer.valueOf(3), provider.values.get(40));
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }

    @Test public void attemptsBothRestoresAndCanRetryAFailedClose() throws Exception {
        Provider provider = new Provider();
        VivoSdkRawSession session = VivoSdkRawSession.open(provider);
        provider.failKey = 33;
        try { session.close(); fail(); } catch (Exception expected) {}
        assertEquals(Integer.valueOf(0), provider.values.get(40));
        try { session.apply(null, true, 0); fail(); } catch (IllegalStateException expected) {}
        session.close();
        assertEquals(Integer.valueOf(1), provider.values.get(33));
    }
}
