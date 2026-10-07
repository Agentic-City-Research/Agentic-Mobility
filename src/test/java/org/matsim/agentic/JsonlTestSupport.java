package org.matsim.agentic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class JsonlTestSupport {
    private JsonlTestSupport() { }

    static List<String> readLines(Path file) throws IOException {
        try (var reader = JsonlIO.newReader(file)) { return reader.lines().toList(); }
    }
}
