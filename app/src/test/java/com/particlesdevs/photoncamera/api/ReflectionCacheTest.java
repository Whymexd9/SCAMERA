package com.particlesdevs.photoncamera.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** P214a: NativeEngine resolves each method and field once per process; failures are retried, keys stay apart. */
public class ReflectionCacheTest {
    @SuppressWarnings("unused")
    private static final class Target {
        private int mResults;
        private String mProperties;

        private void set(Object key, Object value) {
        }

        private void set(Object key, Object value, int extra) {
        }
    }

    @SuppressWarnings("unused")
    private static final class OtherTarget {
        private int mResults;

        private void set(Object key, Object value) {
        }
    }

    private static ReflectionCache.MethodLookup countingMethods(AtomicInteger calls) {
        return (owner, name, params) -> {
            calls.incrementAndGet();
            try {
                return owner.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException e) {
                return null;
            }
        };
    }

    private static ReflectionCache.FieldLookup countingFields(AtomicInteger calls) {
        return (owner, name) -> {
            calls.incrementAndGet();
            try {
                return owner.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                return null;
            }
        };
    }

    @Test
    public void aRepeatedSetLooksItsMembersUpOnce() {
        // CameraReflectionApi.set(...) asks for the same field and setter on every call (per result on the OPPO viewfinder).
        ReflectionCache cache = new ReflectionCache();
        AtomicInteger methodCalls = new AtomicInteger(), fieldCalls = new AtomicInteger();
        ReflectionCache.MethodLookup methods = countingMethods(methodCalls);
        ReflectionCache.FieldLookup fields = countingFields(fieldCalls);
        Method first = cache.method(Target.class, "set", new Class<?>[]{Object.class, Object.class}, methods);
        Field firstField = cache.field(Target.class, "mResults", fields);
        assertNotNull(first);
        assertNotNull(firstField);
        for (int i = 0; i < 100_000; i++) {
            assertSame(first, cache.method(Target.class, "set", new Class<?>[]{Object.class, Object.class}, methods));
            assertSame(firstField, cache.field(Target.class, "mResults", fields));
        }
        assertEquals(1, methodCalls.get());
        assertEquals(1, fieldCalls.get());
        assertEquals(1, cache.methodCount());
        assertEquals(1, cache.fieldCount());
    }

    @Test
    public void classNameAndParameterTypesAreSeparateEntries() {
        ReflectionCache cache = new ReflectionCache();
        AtomicInteger calls = new AtomicInteger(), fieldCalls = new AtomicInteger();
        ReflectionCache.MethodLookup methods = countingMethods(calls);
        ReflectionCache.FieldLookup fields = countingFields(fieldCalls);
        Method two = cache.method(Target.class, "set", new Class<?>[]{Object.class, Object.class}, methods);
        Method three = cache.method(Target.class, "set", new Class<?>[]{Object.class, Object.class, int.class}, methods);
        Method other = cache.method(OtherTarget.class, "set", new Class<?>[]{Object.class, Object.class}, methods);
        assertEquals(2, two.getParameterCount());
        assertEquals(3, three.getParameterCount());
        assertSame(OtherTarget.class, other.getDeclaringClass());
        assertSame(Target.class, cache.field(Target.class, "mResults", fields).getDeclaringClass());
        assertSame(OtherTarget.class, cache.field(OtherTarget.class, "mResults", fields).getDeclaringClass());
        assertEquals("mProperties", cache.field(Target.class, "mProperties", fields).getName());
        assertEquals(3, calls.get());
        assertEquals(3, fieldCalls.get());
        assertEquals(3, cache.methodCount());
        assertEquals(3, cache.fieldCount());
    }

    @Test
    public void aFailedLookupIsTriedAgainAndNotStored() {
        // Before P214a every call looked up again, so a member missing once (null) must not stay missing for the process.
        ReflectionCache cache = new ReflectionCache();
        AtomicInteger calls = new AtomicInteger(), fieldCalls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertNull(cache.method(Target.class, "setBase", new Class<?>[]{Object.class, Object.class}, countingMethods(calls)));
            assertNull(cache.field(Target.class, "mLogicalCameraSettings", countingFields(fieldCalls)));
        }
        assertEquals(3, calls.get());
        assertEquals(3, fieldCalls.get());
        assertEquals(0, cache.methodCount());
        assertEquals(0, cache.fieldCount());
        // A lookup that fails first and then succeeds is kept from then on.
        AtomicInteger flaky = new AtomicInteger();
        ReflectionCache.MethodLookup failsOnce = (owner, name, params) -> {
            if (flaky.incrementAndGet() == 1) return null;
            return countingMethods(new AtomicInteger()).find(owner, name, params);
        };
        assertNull(cache.method(Target.class, "set", new Class<?>[]{Object.class, Object.class}, failsOnce));
        Method found = cache.method(Target.class, "set", new Class<?>[]{Object.class, Object.class}, failsOnce);
        assertNotNull(found);
        assertSame(found, cache.method(Target.class, "set", new Class<?>[]{Object.class, Object.class}, failsOnce));
        assertEquals(2, flaky.get());
    }

    @Test
    public void varargsWithoutParametersAndAMutatedArrayKeepTheirKey() {
        ReflectionCache cache = new ReflectionCache();
        AtomicInteger calls = new AtomicInteger();
        ReflectionCache.MethodLookup lookup = (owner, name, params) -> {
            calls.incrementAndGet();
            try {
                return Object.class.getDeclaredMethod("hashCode");
            } catch (NoSuchMethodException e) {
                return null;
            }
        };
        Method none = cache.method(Object.class, "hashCode", new Class<?>[0], lookup);
        assertSame(none, cache.method(Object.class, "hashCode", null, lookup));
        assertEquals(1, calls.get());
        // The caller's array is copied into the key: changing it afterwards does not alias another signature.
        Class<?>[] params = {Object.class};
        Method withOne = cache.method(Object.class, "equals", params, lookup);
        params[0] = String.class;
        assertSame(withOne, cache.method(Object.class, "equals", new Class<?>[]{Object.class}, lookup));
        assertEquals(2, calls.get());
    }

    @Test
    public void racingCallersGetOneObject() throws Exception {
        ReflectionCache cache = new ReflectionCache();
        AtomicInteger calls = new AtomicInteger();
        ReflectionCache.MethodLookup methods = countingMethods(calls);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        Method[] seen = new Method[threads];
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int slot = t;
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Method last = null;
                for (int i = 0; i < 10_000; i++) last = cache.method(Target.class, "set", new Class<?>[]{Object.class, Object.class}, methods);
                seen[slot] = last;
            });
            workers[t].start();
        }
        start.countDown();
        for (Thread w : workers) w.join();
        for (Method m : seen) assertSame(seen[0], m);
        assertEquals(1, cache.methodCount());
        // Racing first calls may each resolve, but never more than once per thread.
        org.junit.Assert.assertTrue(calls.get() >= 1 && calls.get() <= threads);
    }
}
