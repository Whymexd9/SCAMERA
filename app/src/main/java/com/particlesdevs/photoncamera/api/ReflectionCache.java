package com.particlesdevs.photoncamera.api;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P214a: Method and Field lookups of {@link NativeEngine}, kept for the process. Every {@code CameraReflectionApi.set(...)}
 * looked its field and its setter up again, and each native lookup started a thread and (before P214a) left about nine JNI
 * global references behind; the OPPO Find X7 Ultra aborted on "global reference table overflow (max=51200)" twice on
 * 2026-10-17. With this cache a member is resolved once per (class, name, parameter types). A failed lookup (null) is not
 * stored, so the next call tries again exactly as before. Pure Java so the unit test runs without the native library.
 */
final class ReflectionCache {
    interface MethodLookup {
        Method find(Class<?> owner, String name, Class<?>[] parameterTypes);
    }

    interface FieldLookup {
        Field find(Class<?> owner, String name);
    }

    private static final Class<?>[] NO_PARAMETERS = new Class<?>[0];

    private final ConcurrentHashMap<Key, Method> methods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Field> fields = new ConcurrentHashMap<>();

    /** The cached method, or the lookup's result (stored when not null). A null owner or name is the caller's check. */
    Method method(Class<?> owner, String name, Class<?>[] parameterTypes, MethodLookup lookup) {
        Key key = new Key(owner, name, parameterTypes == null ? NO_PARAMETERS : parameterTypes.clone());
        Method cached = methods.get(key);
        if (cached != null) return cached;
        // The lookup runs outside the map's lock (it waits for a native thread); two racing callers may both resolve,
        // the first stored object wins and both return it.
        Method found = lookup.find(owner, name, parameterTypes);
        if (found == null) return null;
        Method raced = methods.putIfAbsent(key, found);
        return raced != null ? raced : found;
    }

    /** The cached field, or the lookup's result (stored when not null). */
    Field field(Class<?> owner, String name, FieldLookup lookup) {
        Key key = new Key(owner, name, null);
        Field cached = fields.get(key);
        if (cached != null) return cached;
        Field found = lookup.find(owner, name);
        if (found == null) return null;
        Field raced = fields.putIfAbsent(key, found);
        return raced != null ? raced : found;
    }

    int methodCount() {
        return methods.size();
    }

    int fieldCount() {
        return fields.size();
    }

    private static final class Key {
        final Class<?> owner;
        final String name;
        final Class<?>[] parameterTypes;
        final int hash;

        Key(Class<?> owner, String name, Class<?>[] parameterTypes) {
            this.owner = owner;
            this.name = name;
            this.parameterTypes = parameterTypes;
            this.hash = (owner.hashCode() * 31 + name.hashCode()) * 31 + Arrays.hashCode(parameterTypes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return owner == k.owner && name.equals(k.name) && Arrays.equals(parameterTypes, k.parameterTypes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
