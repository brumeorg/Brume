package com.fungle.brume.extraction;

import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.extraction.model.ExtractionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for {@link ExtractionResult}.
 *
 * <p>Phase 2 fix: verifies that {@link ExtractionResult#tryAddWithPk} prevents duplicate rows
 * when many threads simultaneously try to insert the same primary key.
 *
 * <p>Tests are {@link RepeatedTest @RepeatedTest} to exercise different scheduling interleavings.
 */
class ExtractionResultConcurrencyTest {

    private static final int THREAD_COUNT = 100;

    /**
     * Simulates the race condition from FkParentResolver:
     * 100 threads each try to insert the same row (same table + same PK).
     * Expected: exactly ONE insertion succeeds.
     */
    @RepeatedTest(5)
    @DisplayName("tryAddWithPk — 100 concurrent threads inserting same PK → exactly 1 row stored")
    void tryAddWithPkPreventsAllDuplicatesUnderContention() throws InterruptedException {
        ExtractionResult result = new ExtractionResult();
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<?>> futures = new ArrayList<>(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startGate.await(); // all threads start simultaneously
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ExtractedRow row = new ExtractedRow("users", Map.of("id", 42L, "name", "Alice"));
                if (result.tryAddWithPk(row, "id")) {
                    successCount.incrementAndGet();
                }
            }));
        }

        startGate.countDown(); // release all threads at once
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successCount.get())
                .as("Exactly ONE thread must win the tryAddWithPk race")
                .isEqualTo(1);
        assertThat(result.getRows("users"))
                .as("Exactly ONE row must be stored in the result")
                .hasSize(1);
        assertThat(result.totalRowCount()).isEqualTo(1);
    }

    /**
     * Simulates the real FK resolution scenario: multiple FK columns (user_id, creator_id)
     * pointing to the same parent table (users). Each FK worker resolves different child rows
     * but some of them reference the same parent PK (42).
     * Expected: parent row 42 is stored exactly once.
     */
    @Test
    @DisplayName("tryAddWithPk — two FK workers resolving same parent PK → exactly 1 parent row")
    void twoFkWorkersResolvingSameParentProduceNoDuplicate() throws InterruptedException {
        ExtractionResult result = new ExtractionResult();
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Simulate two FK resolution workers both deciding to fetch users.id = 42
        Runnable worker = () -> {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            ExtractedRow parentRow = new ExtractedRow("users", Map.of("id", 42L, "email", "alice@example.com"));
            result.tryAddWithPk(parentRow, "id");
        };

        pool.submit(worker);
        pool.submit(worker);
        latch.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(result.getRows("users"))
                .as("Parent row 42 must appear exactly once despite two concurrent inserts")
                .hasSize(1);
    }

    /**
     * Stress test: 100 threads insert 100 distinct rows in parallel.
     * Expected: all 100 rows are stored (no false-positive deduplication).
     */
    @Test
    @DisplayName("tryAddWithPk — 100 threads with 100 DISTINCT PKs → all 100 rows stored")
    void tryAddWithPkStoresAllDistinctRows() throws InterruptedException {
        ExtractionResult result = new ExtractionResult();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final long pk = i;
            pool.submit(() -> {
                try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                result.tryAddWithPk(new ExtractedRow("orders", Map.of("id", pk)), "id");
            });
        }

        startGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(result.getRows("orders"))
                .as("All 100 distinct rows must be stored")
                .hasSize(THREAD_COUNT);
    }
}

