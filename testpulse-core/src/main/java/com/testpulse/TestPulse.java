package com.testpulse;

import com.testpulse.internal.client.AsyncDispatcher;
import com.testpulse.internal.client.TestPulseClient;
import com.testpulse.internal.dto.Dtos.RunRequest;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Public facade for TestPulse. Holds a JVM-wide instance — initialise once,
 * start a {@link Run}, then either use the explicit API to log scenarios and
 * steps, or rely on the framework auto-wire modules to do it for you.
 *
 * <p>The two integration modes call the same methods. Auto-wire modules
 * (testpulse-cucumber3, testpulse-testng, testpulse-junit4) just wire
 * framework lifecycle events to these calls.
 *
 * <pre>{@code
 * // Explicit
 * TestPulse.init(TestPulseConfig.builder()
 *     .url("http://testpulse.internal:8080")
 *     .apiKey(System.getenv("TESTPULSE_API_KEY"))
 *     .enabled(true)
 *     .build());
 *
 * Run run = TestPulse.startRun();
 * Scenario s = run.startScenario("Login").withLob("Casualty");
 * s.logStep("Given user opens login page", Status.PASSED, Duration.ofMillis(412));
 * s.finish();
 * run.finish();
 * TestPulse.shutdown();
 * }</pre>
 */
public final class TestPulse {

    private static final Logger LOG = Logger.getLogger(TestPulse.class.getName());

    private static final AtomicReference<Instance> INSTANCE = new AtomicReference<Instance>();
    private static final AtomicReference<Run> CURRENT_RUN = new AtomicReference<Run>();

    private TestPulse() {
    }

    /**
     * Initialise TestPulse with the given configuration. Idempotent — a
     * second call with a different config is logged and ignored. To replace
     * the config, call {@link #shutdown()} first.
     *
     * <p>When {@code config.isEnabled()} is false this is a no-op: no client,
     * no dispatcher, no thread pool. Every other method is also a no-op
     * until enabled.
     */
    public static synchronized void init(TestPulseConfig config) {
        if (INSTANCE.get() != null) {
            LOG.fine("TestPulse already initialised, ignoring init call");
            return;
        }
        if (config == null || !config.isEnabled()) {
            INSTANCE.set(Instance.DISABLED);
            return;
        }
        if (config.url() == null || config.url().isEmpty()) {
            LOG.warning("TestPulse enabled but url is empty; reporting will be inactive");
            INSTANCE.set(Instance.DISABLED);
            return;
        }
        TestPulseClient client = new TestPulseClient(config);
        AsyncDispatcher dispatcher = new AsyncDispatcher(client);
        dispatcher.start();
        Instance active = new Instance(config, client, dispatcher);
        INSTANCE.set(active);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }, "testpulse-jvm-shutdown"));
    }

    /**
     * Initialise from auto-discovered config (classpath, env, system props).
     * Convenience for framework integrations that don't have a programmatic
     * config to pass in.
     */
    public static void autoInit() {
        init(TestPulseConfig.autoDiscover());
    }

    public static boolean isEnabled() {
        Instance i = INSTANCE.get();
        return i != null && i != Instance.DISABLED;
    }

    /**
     * Start a new {@link Run}. Idempotent — calling this twice returns the
     * same run, so framework integrations and the explicit API can coexist
     * without creating duplicates server-side.
     */
    public static Run startRun() {
        Run existing = CURRENT_RUN.get();
        if (existing != null) {
            return existing;
        }
        Instance i = INSTANCE.get();
        if (i == null || i == Instance.DISABLED) {
            return null;
        }
        synchronized (CURRENT_RUN) {
            existing = CURRENT_RUN.get();
            if (existing != null) {
                return existing;
            }
            Instant startedAt = Instant.now();
            String runId = i.client.createRun(buildRunRequest(i.config, startedAt));
            if (runId == null) {
                return null;
            }
            Run run = new Run(i.client, i.dispatcher, runId, startedAt);
            CURRENT_RUN.set(run);
            return run;
        }
    }

    /**
     * The active run for this JVM, or null if none is in progress.
     */
    public static Run currentRun() {
        return CURRENT_RUN.get();
    }

    /**
     * Finish the active run (if any) and shut down the dispatcher. After
     * this, {@link #init(TestPulseConfig)} can be called again to start
     * fresh — useful in long-lived processes and unit tests.
     */
    public static synchronized void shutdown() {
        Run run = CURRENT_RUN.getAndSet(null);
        if (run != null && !run.isFinished()) {
            run.finish();
        }
        Instance i = INSTANCE.getAndSet(null);
        if (i != null && i != Instance.DISABLED) {
            i.dispatcher.shutdown();
        }
    }

    private static RunRequest buildRunRequest(TestPulseConfig c, Instant startedAt) {
        return new RunRequest(
                c.division(),
                c.release(),
                c.user(),
                c.environment(),
                startedAt,
                c.branch(),
                c.triggerSource());
    }

    private static final class Instance {
        static final Instance DISABLED = new Instance(null, null, null);

        final TestPulseConfig config;
        final TestPulseClient client;
        final AsyncDispatcher dispatcher;

        Instance(TestPulseConfig config, TestPulseClient client, AsyncDispatcher dispatcher) {
            this.config = config;
            this.client = client;
            this.dispatcher = dispatcher;
        }
    }
}
