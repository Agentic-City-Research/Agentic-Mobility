package org.matsim.agentic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Collect in completion order, retry failed transport calls, and return in submission order. */
final class LlmDecisionBatch {
    static final int MAX_TASK_RETRIES = 3;
    private static final long MAX_RETRY_BACKOFF_MILLIS = 60_000L;

    private record Result<T>(int index, T value) { }

    static <T> List<T> run(ExecutorService pool, List<Callable<T>> tasks, List<String> labels,
                           Duration stall, int day, String phase, IntConsumer progress) {
        if (stall.isZero() || stall.isNegative()) throw new IllegalArgumentException("stall must be positive");
        if (tasks.size() != labels.size()) throw new IllegalArgumentException("task labels must match");
        List<T> results = new ArrayList<>(Collections.nCopies(tasks.size(), null));
        boolean[] succeeded = new boolean[tasks.size()];
        Throwable[] lastTransportFailures = new Throwable[tasks.size()];
        List<Integer> toRun = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) toRun.add(i);

        int completedCount = 0;
        int retryRound = 0;
        progress.accept(0);
        while (!toRun.isEmpty()) {
            if (retryRound > 0) {
                sleepBeforeRetry(retryRound, day, phase);
                // Refresh the progress timestamp while the batch is recovering.
                progress.accept(completedCount);
            }

            var completed = new ExecutorCompletionService<Result<T>>(pool);
            List<Future<Result<T>>> pending = new ArrayList<>(toRun.size());
            List<Integer> retryNextRound = new ArrayList<>();
            try {
                for (int index : toRun) {
                    pending.add(completed.submit(() -> new Result<>(index, tasks.get(index).call())));
                }

                for (int remaining = toRun.size(); remaining > 0; remaining--) {
                    Future<Result<T>> ready = completed.poll(stall.toNanos(), TimeUnit.NANOSECONDS);
                    if (ready == null) {
                        List<String> waiting = new ArrayList<>();
                        for (int i = 0; i < labels.size(); i++) if (!succeeded[i]) waiting.add(labels.get(i));
                        throw new LlmException("LLM stall on day " + day + " (" + phase + "): no decision completed for "
                                + stall.toSeconds() + " seconds; completed=" + completedCount + "/" + tasks.size()
                                + "; pending=" + waiting);
                    }

                    try {
                        Result<T> result = ready.get();
                        results.set(result.index(), result.value());
                        if (!succeeded[result.index()]) {
                            succeeded[result.index()] = true;
                            completedCount++;
                            progress.accept(completedCount);
                        }
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof LlmTransportException) {
                            // Let the other travelers finish; retry only this failed call later.
                            int completedIndex = pending.indexOf(ready);
                            if (completedIndex < 0) throw new IllegalStateException("completed LLM task was not submitted");
                            int failedIndex = toRun.get(completedIndex);
                            retryNextRound.add(failedIndex);
                            lastTransportFailures[failedIndex] = cause;
                        } else if (cause instanceof RuntimeException re) {
                            throw re;
                        } else {
                            throw new LlmException("LLM decision failed on day " + day + " (" + phase + ")", cause);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted collecting LLM decisions on day " + day + " (" + phase + ")", e);
            } finally {
                for (Future<?> task : pending) if (!task.isDone()) task.cancel(true);
            }

            if (retryNextRound.isEmpty()) return results;
            if (retryRound >= MAX_TASK_RETRIES) {
                List<String> failedLabels = new ArrayList<>();
                for (int index : retryNextRound) failedLabels.add(labels.get(index));
                Throwable cause = lastTransportFailures[retryNextRound.getFirst()];
                throw new LlmException("LLM transport retries exhausted on day " + day + " (" + phase
                        + "); failed=" + failedLabels, cause);
            }
            toRun = retryNextRound;
            retryRound++;
        }
        return results;
    }

    private static void sleepBeforeRetry(int retryRound, int day, String phase) {
        long base = Math.min(MAX_RETRY_BACKOFF_MILLIS, 5_000L << Math.min(retryRound - 1, 4));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 2L + 1L));
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted before retrying LLM decisions on day " + day + " (" + phase + ")", e);
        }
    }
}
