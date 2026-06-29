package com.testpulse.internal.client;

import com.testpulse.TestPulseConfig;
import com.testpulse.internal.util.FallbackLogger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fire-and-forget event dispatcher. Step events are offered to a bounded queue
 * and drained by a single daemon worker thread; failed sends are retried with
 * exponential backoff, then written to the fallback file for offline replay.
 *
 * <p>Each instance is bound to a {@link TestPulseClient} (and through it to a
 * {@link TestPulseConfig}). Lifecycle is owned by the caller — typically
 * {@code TestPulse.init} starts one and {@code TestPulse.shutdown} stops it.
 */
public final class AsyncDispatcher {

    private static final Logger LOG = Logger.getLogger(AsyncDispatcher.class.getName());
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final TestPulseConfig config;
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final FallbackLogger fallback;
    private final BlockingQueue<Event> queue;
    private final ExecutorService worker;
    private volatile boolean started = false;
    private volatile boolean draining = false;

    public AsyncDispatcher(TestPulseClient client) {
        this.config = client.config();
        this.http = client.httpClient();
        this.mapper = client.mapper();
        this.fallback = new FallbackLogger(config.fallbackFile());
        this.queue = new LinkedBlockingQueue<Event>(config.queueCapacity());
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "testpulse-dispatcher");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        worker.submit(new Runnable() {
            @Override
            public void run() {
                drain();
            }
        });
        LOG.info("AsyncDispatcher started, queue capacity " + config.queueCapacity());
    }

    public void postAsync(String path, Object body) {
        if (!started) return;
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Could not serialize event for " + path + ": " + e.getMessage());
            return;
        }
        if (!queue.offer(new Event(path, json))) {
            LOG.warning("Dispatcher queue full, writing to fallback: " + path);
            fallback.write(path, json);
        }
    }

    public void flush(Duration timeout) {
        if (!started) return;
        draining = true;
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!queue.isEmpty()) {
            LOG.warning("Flush timed out with " + queue.size() + " events; writing to fallback");
            Event e;
            while ((e = queue.poll()) != null) {
                fallback.write(e.path, e.json);
            }
        }
    }

    public synchronized void shutdown() {
        if (!started) return;
        draining = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
        started = false;
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Event e = queue.poll(1, TimeUnit.SECONDS);
                if (e != null) {
                    sendWithRetry(e);
                } else if (draining && queue.isEmpty()) {
                    return;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void sendWithRetry(Event e) {
        int attempts = config.maxRetries();
        long backoffMs = 1000L;
        for (int i = 1; i <= attempts; i++) {
            try {
                send(e);
                return;
            } catch (IOException ex) {
                if (i == attempts) {
                    LOG.log(Level.WARNING,
                            "Event failed after " + attempts + " attempts ("
                                    + ex.getMessage() + "), writing to fallback: " + e.path);
                    fallback.write(e.path, e.json);
                    return;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    fallback.write(e.path, e.json);
                    return;
                }
                backoffMs *= 4;
            }
        }
    }

    private void send(Event e) throws IOException {
        Request req = new Request.Builder()
                .url(config.url() + e.path)
                .header("X-Api-Key", config.apiKey())
                .post(RequestBody.create(JSON, e.json))
                .build();
        Response resp = null;
        try {
            resp = http.newCall(req).execute();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " from " + e.path);
            }
        } finally {
            if (resp != null) resp.close();
        }
    }

    public int queueDepth() {
        return queue.size();
    }

    private static final class Event {
        final String path;
        final String json;

        Event(String path, String json) {
            this.path = path;
            this.json = json;
        }
    }
}
