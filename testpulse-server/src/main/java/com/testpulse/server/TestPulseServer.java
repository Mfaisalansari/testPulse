package com.testpulse.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * TestPulse server bootstrap. Reads configuration from environment
 * variables, wires the HTTP server, registers handlers, and starts
 * listening. Designed to run as a single jar with no external runtime
 * dependencies — pure JDK.
 *
 * <p>Configuration via environment variables, all optional:
 * <ul>
 *   <li>{@code TESTPULSE_PORT}  — listen port (default {@code 8080})</li>
 *   <li>{@code TESTPULSE_DATA}  — data directory (default {@code data})</li>
 *   <li>{@code TESTPULSE_KEYS}  — API keys CSV (default {@code config/api-keys.csv})</li>
 *   <li>{@code TESTPULSE_THREADS} — handler pool size (default {@code 16})</li>
 * </ul>
 *
 * <p>Start with: {@code java -jar testpulse-server.jar}
 */
public final class TestPulseServer {

    private static final Logger LOG = Logger.getLogger(TestPulseServer.class.getName());

    public static void main(String[] args) throws Exception {
        configureLogging();

        int port = parseInt(env("TESTPULSE_PORT", "8080"), 8080);
        Path dataDir = Paths.get(env("TESTPULSE_DATA", "data"));
        Path keysFile = Paths.get(env("TESTPULSE_KEYS", "config/api-keys.csv"));
        int threads = parseInt(env("TESTPULSE_THREADS", "16"), 16);

        // Initialise subsystems
        ApiKeys keys = new ApiKeys(keysFile);
        Store store = new Store(dataDir);
        Sse sse = new Sse();

        // HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(threads, new java.util.concurrent.ThreadFactory() {
            int counter = 0;
            @Override
            public java.lang.Thread newThread(Runnable r) {
                java.lang.Thread t = new java.lang.Thread(r, "testpulse-worker-" + (++counter));
                t.setDaemon(false);
                return t;
            }
        }));

        // Handler chain — order matters: /api/* matched before catch-all /
        server.createContext("/api/", new ApiHandler(store, sse, keys));
        server.createContext("/", new StaticHandler());

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread("testpulse-shutdown") {
            @Override
            public void run() {
                LOG.info("Shutdown signal received, stopping server...");
                sse.shutdown();
                server.stop(2);
            }
        });

        server.start();

        // Startup banner
        LOG.info("");
        LOG.info("  TestPulse server " + version());
        LOG.info("  listening on   http://localhost:" + port + "/");
        LOG.info("  data dir:      " + dataDir.toAbsolutePath());
        LOG.info("  keys file:     " + keysFile.toAbsolutePath());
        LOG.info("  worker pool:   " + threads + " threads");
        LOG.info("  api keys:      " + keys.count() + " loaded");
        LOG.info("");
        LOG.info("  Ingest endpoints (library):");
        LOG.info("    POST   /api/runs");
        LOG.info("    POST   /api/runs/{id}/scenarios");
        LOG.info("    POST   /api/runs/{id}/scenarios/{sid}/steps");
        LOG.info("    PATCH  /api/runs/{id}/scenarios/{sid}/finish");
        LOG.info("    PATCH  /api/runs/{id}/finalize");
        LOG.info("");
        LOG.info("  Dashboard:     http://localhost:" + port + "/");
        LOG.info("");
    }

    private static void configureLogging() {
        // Reset default formatter to something readable; the JDK default is verbose.
        java.util.logging.LogManager.getLogManager().reset();
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.setLevel(java.util.logging.Level.INFO);
        java.util.logging.ConsoleHandler h = new java.util.logging.ConsoleHandler();
        h.setLevel(java.util.logging.Level.INFO);
        h.setFormatter(new java.util.logging.Formatter() {
            private final java.time.format.DateTimeFormatter DT = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
            @Override
            public String format(java.util.logging.LogRecord r) {
                String time = java.time.LocalTime.now().format(DT);
                return time + "  " + r.getLevel().getName() + "  " + r.getMessage() + "\n";
            }
        });
        root.addHandler(h);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static String version() {
        Package p = TestPulseServer.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "(dev)" : v;
    }
}
