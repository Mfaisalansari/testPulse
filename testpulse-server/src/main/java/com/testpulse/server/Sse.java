package com.testpulse.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-Sent Events broadcaster. Each subscriber listens on a channel —
 * either a specific run ID or the {@link #GLOBAL} channel for cross-run
 * notifications.
 *
 * <p>Dead clients (TCP error on write) are detected lazily on the next
 * delivery; we never block the request thread waiting for a slow consumer.
 * A keepalive comment goes out every 25 seconds to defeat reverse-proxy
 * idle timeouts.
 */
public final class Sse {

    private static final Logger LOG = Logger.getLogger(Sse.class.getName());
    public static final String GLOBAL = "*";

    private final Map<String, Set<Client>> channels = new ConcurrentHashMap<String, Set<Client>>();
    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "sse-keepalive");
            t.setDaemon(true);
            return t;
        }
    });

    public Sse() {
        keepalive.scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                try { pingAll(); } catch (Throwable t) { /* never fail the scheduler */ }
            }
        }, 25, 25, TimeUnit.SECONDS);
    }

    /**
     * Register a client. Call this from the SSE HTTP handler, then DO NOT
     * close the HttpExchange — the client lives until the connection breaks
     * or the server stops.
     */
    public void register(String channel, HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        Client c = new Client(ex);
        Set<Client> set = channels.get(channel);
        if (set == null) {
            set = ConcurrentHashMap.newKeySet();
            Set<Client> existing = channels.putIfAbsent(channel, set);
            if (existing != null) set = existing;
        }
        set.add(c);

        // Send initial 'connected' marker so clients can render "live" indicator
        c.send(": connected\n\n");
    }

    public void broadcast(String channel, String type, Map<String, Object> payload) {
        Map<String, Object> ev = new java.util.LinkedHashMap<String, Object>();
        ev.put("type", type);
        if (payload != null) ev.putAll(payload);
        String frame = "event: " + type + "\ndata: " + Json.write(ev) + "\n\n";
        deliver(channel, frame);
        // Also broadcast to global watchers
        if (!GLOBAL.equals(channel)) {
            deliver(GLOBAL, frame);
        }
    }

    private void deliver(String channel, String frame) {
        Set<Client> set = channels.get(channel);
        if (set == null) return;
        for (Client c : set) {
            if (!c.send(frame)) {
                set.remove(c);
            }
        }
    }

    private void pingAll() {
        for (Set<Client> set : channels.values()) {
            for (Client c : set) {
                if (!c.send(": ping\n\n")) {
                    set.remove(c);
                }
            }
        }
    }

    public void shutdown() {
        keepalive.shutdownNow();
        for (Set<Client> set : channels.values()) {
            for (Client c : set) c.close();
        }
        channels.clear();
    }

    private static final class Client {
        final HttpExchange ex;
        final OutputStream out;
        volatile boolean alive = true;

        Client(HttpExchange ex) {
            this.ex = ex;
            this.out = ex.getResponseBody();
        }

        boolean send(String frame) {
            if (!alive) return false;
            try {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                alive = false;
                close();
                return false;
            }
        }

        void close() {
            try { out.close(); } catch (Exception ignored) {}
            try { ex.close(); } catch (Exception ignored) {}
        }
    }
}
