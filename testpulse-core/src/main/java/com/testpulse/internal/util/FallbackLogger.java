package com.testpulse.internal.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only JSONL writer for events that couldn't be delivered. Path is
 * supplied by the active config; if a write fails (disk full, permission
 * denied) we log once and move on — the dispatcher must never throw.
 */
public final class FallbackLogger {

    private static final Logger LOG = Logger.getLogger(FallbackLogger.class.getName());

    private final Path file;

    public FallbackLogger(String path) {
        this.file = Paths.get(path != null && !path.isEmpty()
                ? path : "target/testpulse-fallback.jsonl");
    }

    public synchronized void write(String path, String jsonBody) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = "{\"path\":\"" + escape(path) + "\",\"body\":" + jsonBody + "}\n";
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Fallback write failed: " + e.getMessage());
        }
    }

    public Path path() {
        return file;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
