package org.matsim.agentic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class OutputIntegrityTest {
    @TempDir Path directory;

    @Test void sealedCompressedStreamsAreHashedAndStatusPublishedLast() throws Exception {
        try (var writer = new OutputWriter(directory, true)) {
            writer.metric(Map.of("text", "a\nb"));
            writer.metric(Map.of("value", 2));
            writer.sealAndVerify(Map.of("complete", true), Map.of("metrics.jsonl", 2L));
        }
        var status = Json.readTree(Files.readString(directory.resolve("status.json")));
        assertTrue(status.get("complete").asBoolean());
        assertTrue(status.get("integrityVerified").asBoolean());
        var file = Json.readTree(Files.readString(directory.resolve("integrity.json"))).get("files").get(0);
        byte[] bytes = Files.readAllBytes(directory.resolve("metrics.jsonl.gz"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), file.get("sha256").asText());
        assertEquals(2, file.get("records").asInt());
        Files.write(directory.resolve("truncated.gz"), Arrays.copyOf(bytes, bytes.length - 5));
        assertThrows(java.io.IOException.class, () -> OutputWriter.inspectJsonl(directory.resolve("truncated.gz"), true));
    }

    @Test void failedCountAndReusedDirectoryCannotReportSuccess() throws Exception {
        Files.writeString(directory.resolve("status.json"), "{\"complete\":true}");
        try (var writer = new OutputWriter(directory, true)) {
            assertFalse(Json.readTree(Files.readString(directory.resolve("status.json"))).get("complete").asBoolean());
            writer.metric(Map.of("day", 1));
            assertThrows(LlmException.class, () -> writer.sealAndVerify(Map.of("complete", true), Map.of("metrics.jsonl", 2L)));
        }
        var status = Json.readTree(Files.readString(directory.resolve("status.json")));
        assertFalse(status.get("complete").asBoolean());
        assertFalse(status.get("integrityVerified").asBoolean());
    }
}
