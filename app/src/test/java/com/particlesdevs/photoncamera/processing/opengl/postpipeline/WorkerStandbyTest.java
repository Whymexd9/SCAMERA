package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * P371 (worker_prespawn): the lines of a worker spawned at the shutter reach its job in their order - those before the job
 * took it first, then every later one - and the end of its output once, whenever it came.
 */
public class WorkerStandbyTest {
    @Test public void earlyLinesComeFirstThenTheLaterOnesInOrder() throws Exception {
        final ScamNeuralClient.LineRoute route = new ScamNeuralClient.LineRoute();
        // fewer than EARLY_LIMIT in all: whenever the job attaches (the reader goes on beside it), none may be lost
        final int total = ScamNeuralClient.LineRoute.EARLY_LIMIT - 100;
        final CountDownLatch half = new CountDownLatch(1);
        final Thread reader = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                route.line("line " + i);
                if (i == 50) half.countDown();
            }
            route.ended();
        });
        reader.start();
        assertTrue(half.await(5, TimeUnit.SECONDS));
        final List<String> got = new ArrayList<>();
        final AtomicInteger ends = new AtomicInteger();
        final CountDownLatch end = new CountDownLatch(1);
        route.attach(l -> { synchronized (got) { got.add(l); } }, () -> { ends.incrementAndGet(); end.countDown(); });
        assertTrue(end.await(10, TimeUnit.SECONDS));
        reader.join(5000);
        assertEquals(1, ends.get());
        synchronized (got) {
            // every line once, in its order (those before the attach first)
            assertEquals(total, got.size());
            for (int i = 0; i < total; i++) assertEquals("line " + i, got.get(i));
        }
    }

    @Test public void beyondTheEarlyLimitOnlyTheFirstLinesAreKeptThenEveryLaterOne() {
        final ScamNeuralClient.LineRoute route = new ScamNeuralClient.LineRoute();
        final int limit = ScamNeuralClient.LineRoute.EARLY_LIMIT;
        for (int i = 0; i < limit + 10; i++) route.line("line " + i);
        final List<String> got = new ArrayList<>();
        route.attach(got::add, () -> { });
        for (int i = limit + 10; i < limit + 15; i++) route.line("line " + i);
        assertEquals(limit + 5, got.size());
        for (int i = 0; i < limit; i++) assertEquals("line " + i, got.get(i));
        for (int i = 0; i < 5; i++) assertEquals("line " + (limit + 10 + i), got.get(limit + i));
    }

    @Test public void anOutputThatEndedBeforeTheJobEndsAtOnceAtAttach() {
        final ScamNeuralClient.LineRoute route = new ScamNeuralClient.LineRoute();
        route.line("SCAM Neural native executable v31");
        route.line("STOP: SCAM burst was not delivered by the app");
        route.ended();
        final List<String> got = new ArrayList<>();
        final AtomicInteger ends = new AtomicInteger();
        route.attach(got::add, ends::incrementAndGet);
        assertEquals(2, got.size());
        assertEquals("STOP: SCAM burst was not delivered by the app", got.get(1));
        assertEquals(1, ends.get());
    }

    @Test public void aDiscardedStandbyGivesItsLinesForTheLog() {
        final ScamNeuralClient.LineRoute route = new ScamNeuralClient.LineRoute();
        for (int i = 0; i < ScamNeuralClient.LineRoute.EARLY_LIMIT + 10; i++) route.line("HYBRID PROGRESS " + i);
        final List<String> dropped = route.drop();
        assertEquals(ScamNeuralClient.LineRoute.EARLY_LIMIT, dropped.size());
        assertEquals("HYBRID PROGRESS 0", dropped.get(0));
        assertTrue(route.drop().isEmpty());
    }
}
