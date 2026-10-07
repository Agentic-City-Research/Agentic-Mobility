package org.matsim.agentic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Streaming UTF-8 reader for both historical .jsonl and compressed .jsonl.gz files. */
public final class JsonlIO {
    private JsonlIO() { }

    public static BufferedReader newReader(Path file) throws IOException {
        var stream = new BufferedInputStream(Files.newInputStream(file), 65536);
        try {
            InputStream decoded = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")
                    ? new GZIPInputStream(stream, 65536) : stream;
            return new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8), 16384);
        } catch (IOException | RuntimeException error) {
            try { stream.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }
}
