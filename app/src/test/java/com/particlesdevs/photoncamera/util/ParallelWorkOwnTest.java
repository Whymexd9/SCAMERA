package com.particlesdevs.photoncamera.util;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P314 (review): a pool of its own (the viewfinder's RAW bins) runs every item once and never waits behind a pass that holds
 * every thread of the shared pool (a shot's CPU pass).
 */
public class ParallelWorkOwnTest {
    @Test
    public void everyItemOnce() {
        ParallelWork.Own own = new ParallelWork.Own("test-own", 3);
        assertTrue(own.threads() >= 1 && own.threads() <= 3);
        AtomicIntegerArray seen = new AtomicIntegerArray(1000);
        for (int round = 0; round < 20; round++) own.forEach(1000, seen::incrementAndGet);
        for (int i = 0; i < 1000; i++) assertEquals(20, seen.get(i));
    }

    @Test
    public void doesNotWaitForTheSharedPool() throws Exception {
        final CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        ExecutorService shot = Executors.newSingleThreadExecutor();
        // a "shot pass": every thread of the shared pool (and its caller) blocked until released
        Future<?> pass = shot.submit(() -> ParallelWork.forEach(ParallelWork.threads() * 4, i -> {
            started.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            ParallelWork.Own own = new ParallelWork.Own("test-own-busy", 3);
            AtomicIntegerArray seen = new AtomicIntegerArray(256);
            ExecutorService camera = Executors.newSingleThreadExecutor();
            try {
                Future<?> bin = camera.submit(() -> own.forEach(256, seen::incrementAndGet));
                bin.get(10, TimeUnit.SECONDS); // a TimeoutException fails the test: the bin waited for the pass
                assertEquals(1, release.getCount()); // the pass still holds the shared pool
                for (int i = 0; i < 256; i++) assertEquals(1, seen.get(i));
            } finally {
                camera.shutdownNow();
            }
        } finally {
            release.countDown();
            pass.get(30, TimeUnit.SECONDS);
            shot.shutdownNow();
        }
    }
}
