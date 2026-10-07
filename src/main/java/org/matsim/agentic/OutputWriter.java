package org.matsim.agentic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
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

    private final Path dir;
    private final boolean compressJsonl;
    private final Map<String, BufferedWriter> lines = new HashMap<>();
    private boolean closed;

    public OutputWriter(Path agenticDir) throws IOException {
        this(agenticDir, false);
    }

    /** Compression changes only the physical suffix (.jsonl.gz), never row contents. */
    public OutputWriter(Path agenticDir, boolean compressJsonl) throws IOException {
        this.dir = agenticDir;
        this.compressJsonl = compressJsonl;
        Files.createDirectories(agenticDir);
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
