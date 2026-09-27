package net.kroet.timberella.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Snapshots may reach the writer in any order; the file must always end up with
 * the newest one.
 */
class LatestWriteGateTest {

    @Test
    void olderSnapshotArrivingLateIsSkipped() {
        LatestWriteGate gate = new LatestWriteGate();
        List<String> written = new ArrayList<>();
        assertTrue(gate.writeIfNewer(2, () -> written.add("{A,B}")));
        assertFalse(gate.writeIfNewer(1, () -> written.add("{A}")));
        assertEquals(List.of("{A,B}"), written);
    }

    @Test
    void sameVersionIsWrittenOnlyOnce() {
        LatestWriteGate gate = new LatestWriteGate();
        assertTrue(gate.writeIfNewer(1, () -> {
        }));
        assertFalse(gate.writeIfNewer(1, () -> {
        }));
    }

    @Test
    void concurrentWritesLeaveTheNewestVersion() throws InterruptedException {
        LatestWriteGate gate = new LatestWriteGate();
        AtomicLong onDisk = new AtomicLong();
        int writes = 200;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (long version = 1; version <= writes; version++) {
            long v = version;
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                gate.writeIfNewer(v, () -> onDisk.set(v));
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(writes, onDisk.get());
    }
}
