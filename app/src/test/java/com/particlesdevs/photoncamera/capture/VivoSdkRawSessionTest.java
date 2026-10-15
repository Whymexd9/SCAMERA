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
