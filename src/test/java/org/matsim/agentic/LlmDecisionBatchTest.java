package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmDecisionBatchTest {
    @Test void collectsOutOfOrderAndReturnsOriginalOrder() {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var releaseFirst = new CountDownLatch(1);
            var progress = new ArrayList<Integer>();
            var result = LlmDecisionBatch.run(pool, List.<Callable<String>>of(
                    () -> { releaseFirst.await(); return "first"; }, () -> "second"),
                    List.of("a", "b"), Duration.ofSeconds(2), 33, "travelers", n -> {
                        progress.add(n);
                        if (n == 1) releaseFirst.countDown();
                    });
            assertEquals(List.of("first", "second"), result);
            assertEquals(List.of(0, 1, 2), progress);
        }
    }

    @Test void stuckTravelerIsCancelledAndIdentified() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var interrupted = new CountDownLatch(1);
            var error = assertThrows(LlmException.class, () -> LlmDecisionBatch.run(pool,
                    List.<Callable<String>>of(() -> {
                        try { new CountDownLatch(1).await(); return "never"; }
                        catch (InterruptedException e) { interrupted.countDown(); throw e; }
                    }, () -> "done"), List.of("stuck-person", "completed-person"),
                    Duration.ofMillis(200), 33, "travelers", n -> { }));
            assertTrue(error.getMessage().contains("day 33"));
            assertTrue(error.getMessage().contains("completed=1/2"));
            assertTrue(error.getMessage().contains("pending=[stuck-person]"));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void managerIsAlsoBounded() {
        try (var pool = Executors.newSingleThreadExecutor()) {
            var error = assertThrows(LlmException.class, () -> LlmDecisionBatch.run(pool,
                    List.<Callable<String>>of(() -> { new CountDownLatch(1).await(); return "never"; }),
                    List.of("manager"), Duration.ofMillis(100), 36, "manager", n -> { }));
            assertTrue(error.getMessage().contains("(manager)"));
            assertTrue(error.getMessage().contains("pending=[manager]"));
        }
    }

    @Test void continuousCompletionsAllowLongerThanStallWindow() {
        try (var pool = Executors.newSingleThreadExecutor()) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int value = i;
                tasks.add(() -> { Thread.sleep(100); return value; });
            }
            var result = LlmDecisionBatch.run(pool, tasks,
                    List.of("0", "1", "2", "3", "4", "5", "6", "7"),
                    Duration.ofMillis(500), 33, "travelers", n -> { });
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), result);
        }
    }

    @Test void failureBehindHungFirstTaskIsPropagatedImmediately() {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var failure = new LlmException("transport failed");
            var error = assertThrows(LlmException.class, () -> LlmDecisionBatch.run(pool,
                    List.<Callable<String>>of(() -> { new CountDownLatch(1).await(); return "never"; },
                            () -> { throw failure; }), List.of("stuck", "failed"),
                    Duration.ofSeconds(2), 33, "travelers", n -> { }));
            assertSame(failure, error);
        }
    }
}
