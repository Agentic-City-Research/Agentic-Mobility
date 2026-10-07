package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.agentic.JsonlTestSupport.readLines;

class OutputWriterGzipTest {
    @TempDir Path directory;

    @Test void gzipPreservesEveryUtf8RowAndFlushesWhileOpenWithPlainStatusDocuments() throws Exception {
        Path plain = directory.resolve("plain"), compressed = directory.resolve("compressed");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", i / 64); row.put("tripId", "出行者/" + i); row.put("carSeconds", i % 4 == 0 ? null : i + .25);
            row.put("completedOd", i % 4 != 0); row.put("routeFingerprint", "[起点, 中间路段, 终点]".repeat(40));
            row.put("reason", "保留中文、换行\n与引号\"以及 emoji 🚗"); rows.add(row);
        }
        var gzip = new OutputWriter(compressed, true);
        try (var original = new OutputWriter(plain); gzip) {
            original.appendLine("trips.jsonl", rows.getFirst()); gzip.appendLine("trips.jsonl", rows.getFirst());
            gzip.flush();
            assertFalse(Files.exists(compressed.resolve("trips.jsonl")));
            long flushedSize = Files.size(compressed.resolve("trips.jsonl.gz"));
            assertTrue(flushedSize > 18, "Explicit flush must write compressed data, not only the gzip header");
            for (int i = 1; i < rows.size(); i++) {
                original.appendLine("trips.jsonl", rows.get(i)); gzip.appendLine("trips.jsonl", rows.get(i));
                if (i % 64 == 0) gzip.flush();
            }
            assertTrue(Files.size(compressed.resolve("trips.jsonl.gz")) > flushedSize, "Periodic flushes must grow the file during the run");
            gzip.status(Map.of("complete", true));
            assertTrue(Json.readTree(Files.readString(compressed.resolve("status.json"))).get("complete").asBoolean());
            assertFalse(Files.exists(compressed.resolve("status.json.gz")));
        }
        assertEquals(readLines(plain.resolve("trips.jsonl")), readLines(compressed.resolve("trips.jsonl.gz")));
        assertEquals(rows.size(), readLines(compressed.resolve("trips.jsonl.gz")).size());
        assertTrue(Files.size(compressed.resolve("trips.jsonl.gz")) < Files.size(plain.resolve("trips.jsonl")) / 5);
        long before = Files.size(compressed.resolve("trips.jsonl.gz"));
        gzip.close(); // Main finally and controller shutdown can both close the writer.
        assertThrows(LlmException.class, () -> gzip.appendLine("trips.jsonl", Map.of("late", true)));
        assertEquals(before, Files.size(compressed.resolve("trips.jsonl.gz")), "Late writes must not reopen and truncate a completed gzip file");
    }

    @Test void concurrentAppendPreservesAllRecordsAndCompleteGzipTrailer() throws Exception {
        Path compressed = directory.resolve("concurrent");
        try (var output = new OutputWriter(compressed, true); var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                final int id = worker;
                tasks.add(() -> {
                    for (int i = 0; i < 500; i++) output.attempt(Map.of("id", id + "/" + i, "response", "完整回复 🚗"));
                    return null;
                });
            }
            for (var result : pool.invokeAll(tasks)) result.get();
        }
        var rows = readLines(compressed.resolve("attempts.jsonl.gz"));
        assertEquals(2000, rows.size());
        Set<String> ids = new HashSet<>();
        for (var line : rows) {
            var row = Json.readTree(line); assertEquals("完整回复 🚗", row.get("response").asText());
            assertTrue(ids.add(row.get("id").asText()));
        }
    }
}
