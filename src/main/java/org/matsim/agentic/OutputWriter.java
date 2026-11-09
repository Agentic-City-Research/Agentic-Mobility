package org.matsim.agentic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes artifacts under the supplied output directory. JSONL files are
 * appended line-by-line, optionally through a bounded-buffer GZIP stream;
 * single-document files (manifest, roster, progress,
 * status, api-usage) are overwritten in place. Thread-safe: QSim may dispatch
 * event handlers in parallel.
 */
public final class OutputWriter implements AutoCloseable {

    public static final String MANIFEST = "manifest.json";
    public static final String ROSTER = "roster.json";
    public static final String ATTEMPTS = "attempts.jsonl";
    public static final String DECISIONS = "decisions.jsonl";
    public static final String EXPERIENCES = "experiences.jsonl";
    public static final String POLICIES = "policies.jsonl";
    public static final String TOLL_OBSERVATIONS = "toll-observations.jsonl";
    public static final String METRICS = "metrics.jsonl";
    public static final String PROGRESS = "progress.json";
    public static final String STATUS = "status.json";
    public static final String API_USAGE = "api-usage.json";
    public static final String INTEGRITY = "integrity.json";

    private final Path dir;
    private final boolean compressJsonl;
    private final Map<String, BufferedWriter> lines = new HashMap<>();
    private final Map<String, Long> lineCounts = new TreeMap<>();
    private boolean closed;

    public OutputWriter(Path agenticDir) throws IOException {
        this(agenticDir, false);
    }

    /** Compression changes only the physical suffix (.jsonl.gz), never row contents. */
    public OutputWriter(Path agenticDir, boolean compressJsonl) throws IOException {
        this.dir = agenticDir;
        this.compressJsonl = compressJsonl;
        Files.createDirectories(agenticDir);
        // A reused directory must never retain a previous run's successful status.
        atomicDoc(STATUS, Map.of("complete", false, "integrityVerified", false, "phase", "running"));
    }

    private BufferedWriter lineWriter(String name) throws IOException {
        BufferedWriter w = lines.get(name);
        if (w == null) {
            boolean gzip = compressJsonl && name.endsWith(".jsonl");
            Path file = dir.resolve(gzip ? name + ".gz" : name);
            if (gzip) {
                var stream = new BufferedOutputStream(Files.newOutputStream(file,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), 65536);
                try {
                    // syncFlush makes each explicit flush visible without closing/reopening
                    // the compressor or collecting a day's records in memory.
                    w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(stream, 65536, true),
                            StandardCharsets.UTF_8), 16384);
                } catch (IOException | RuntimeException error) {
                    try { stream.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
                    throw error;
                }
            } else w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            lines.put(name, w);
        }
        return w;
    }

    public synchronized void appendLine(String name, Object json) {
        requireOpen();
        try {
            BufferedWriter w = lineWriter(name);
            w.write(Json.write(json));
            w.newLine();
            lineCounts.merge(name, 1L, Long::sum);
        } catch (IOException e) {
            throw new LlmException("write failed: " + name, e);
        }
    }

    public synchronized void writeDoc(String name, Object json) {
        requireOpen();
        try {
            Files.write(dir.resolve(name), Json.write(json).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException("write failed: " + name, e);
        }
    }

    public synchronized void flush() {
        requireOpen();
        for (var entry : lines.entrySet()) {
            try {
                entry.getValue().flush();
            } catch (IOException error) {
                throw new LlmException("flush failed: " + entry.getKey(), error);
            }
        }
    }

    public void manifest(Object j) { writeDoc(MANIFEST, j); }
    public void roster(Object j) { writeDoc(ROSTER, j); }
    public void attempt(Object j) { appendLine(ATTEMPTS, j); }
    public void decision(Object j) { appendLine(DECISIONS, j); }
    public void experience(Object j) { appendLine(EXPERIENCES, j); }
    public void policy(Object j) { appendLine(POLICIES, j); }
    public void tollObservation(Object j) { appendLine(TOLL_OBSERVATIONS, j); }
    public void metric(Object j) { appendLine(METRICS, j); }
    public void progress(Object j) { writeDoc(PROGRESS, j); }
    public void status(Object j) { writeDoc(STATUS, j); }
    public void apiUsage(Object j) { writeDoc(API_USAGE, j); }

    /** Close all streams, verify the stored bytes and row-count contract, then publish status last. */
    public synchronized void sealAndVerify(Map<String, Object> status, Map<String, Long> expectedRows) {
        requireOpen();
        try {
            close();
            var files = new ArrayList<Map<String, Object>>();
            for (var entry : lineCounts.entrySet()) {
                String name = entry.getKey();
                boolean gzip = compressJsonl && name.endsWith(".jsonl");
                String physical = gzip ? name + ".gz" : name;
                var checked = inspectJsonl(dir.resolve(physical), gzip);
                if (((Number) checked.get("records")).longValue() != entry.getValue())
                    throw new IOException("Record count differs for " + physical);
                var row = new LinkedHashMap<String,Object>(checked);
                row.put("name", physical); row.put("logicalName", name); row.put("writtenRecords", entry.getValue());
                files.add(row);
            }
            for (var expected : expectedRows.entrySet()) {
                long actual = lineCounts.getOrDefault(expected.getKey(), 0L);
                if (actual != expected.getValue()) throw new IOException("Expected " + expected.getValue() + " records in "
                        + expected.getKey() + ", found " + actual);
            }
            var integrity = new LinkedHashMap<String,Object>();
            integrity.put("schemaVersion", 1); integrity.put("verified", true);
            integrity.put("scope", "All writer-produced JSONL streams: full gzip CRC/trailer, newline and written-row count; SHA-256 covers stored bytes. Expected counts do not prove unique identities or semantic correctness.");
            integrity.put("expectedRows", new TreeMap<>(expectedRows)); integrity.put("files", files);
            atomicDoc(INTEGRITY, integrity);
            var finalStatus = new LinkedHashMap<>(status);
            finalStatus.put("integrityVerified", true); finalStatus.put("integrityFile", INTEGRITY);
            atomicDoc(STATUS, finalStatus);
        } catch (Exception failure) {
            var failed = new LinkedHashMap<>(status);
            if (failed.containsKey("complete")) failed.put("complete", false);
            if (failed.containsKey("validated")) failed.put("validated", false);
            failed.put("integrityVerified", false); failed.put("integrityError", failure.toString());
            try { atomicDoc(STATUS, failed); } catch (Exception writeFailure) { failure.addSuppressed(writeFailure); }
            throw new LlmException("Output integrity verification failed: " + dir, failure);
        }
    }

    static Map<String,Object> inspectJsonl(Path file, boolean gzip) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        long records = 0, uncompressedBytes = 0;
        int last = -1;
        try (var raw = new DigestInputStream(new BufferedInputStream(Files.newInputStream(file), 65536), digest);
             var data = gzip ? new GZIPInputStream(raw, 65536) : raw) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = data.read(buffer)) != -1) {
                for (int i=0; i<n; i++) if (buffer[i]=='\n') records++;
                if (n > 0) last = buffer[n-1] & 0xff;
                uncompressedBytes += n;
            }
            // Digest the entire physical file, including any trailing bytes not consumed by the decoder.
            if (gzip) while (raw.read(buffer) != -1) { /* drain */ }
        }
        if (uncompressedBytes > 0 && last != '\n') throw new IOException("JSONL is missing its final newline: " + file);
        return Map.of("compressed", gzip, "bytes", Files.size(file), "uncompressedBytes", uncompressedBytes,
                "records", records, "sha256", HexFormat.of().formatHex(digest.digest()));
    }

    private void atomicDoc(String name, Object value) throws IOException {
        Path temp = Files.createTempFile(dir, "." + name + "-", ".tmp");
        try {
            Files.writeString(temp, Json.write(value), StandardCharsets.UTF_8);
            try { Files.move(temp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (BufferedWriter w : lines.values()) {
            try {
                w.close();
            } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        lines.clear();
        if (failure != null) throw new LlmException("close failed: " + dir, failure);
    }

    private void requireOpen() {
        if (closed) throw new LlmException("output writer is closed: " + dir);
    }
}
