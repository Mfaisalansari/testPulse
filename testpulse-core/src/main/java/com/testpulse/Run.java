package com.testpulse;

import com.testpulse.internal.client.AsyncDispatcher;
import com.testpulse.internal.client.TestPulseClient;
import com.testpulse.internal.dto.Dtos.RunSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A reporting run — typically one per TestNG suite or JUnit run. Holds the
 * server-assigned run ID, the per-thread "current scenario" lookup that
 * auto-wire modules use, and atomic counters that feed the final
 * {@link RunSummary}.
 *
 * <p>Created by {@link TestPulse#startRun()}, finished by {@link #finish()}.
 * Safe for concurrent scenario starts from multiple threads.
 */
public final class Run {

    private final TestPulseClient client;
    private final AsyncDispatcher dispatcher;
    private final String serverId;
    private final Instant startedAt;

    private final ThreadLocal<Scenario> currentScenario = new ThreadLocal<Scenario>();
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();

    private volatile boolean finished = false;

    Run(TestPulseClient client, AsyncDispatcher dispatcher, String serverId, Instant startedAt) {
        this.client = client;
        this.dispatcher = dispatcher;
        this.serverId = serverId;
        this.startedAt = startedAt;
    }

    public String id() {
        return serverId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /**
     * Convenience: start a scenario with just a name. For more metadata use
     * {@link #newScenario()}.
     */
    public Scenario startScenario(String name) {
        return newScenario(name).start();
    }

    /**
     * Build a scenario fluently before starting. Call {@code .start()} when
     * all metadata has been set.
     */
    public Scenario newScenario(String name) {
        return new Scenario(this, client, dispatcher, name);
    }

    /**
     * The scenario currently active on this thread, or null. Auto-wire
     * modules (Cucumber hooks, AspectJ aspects) use this to find their target
     * without passing handles around.
     */
    public Scenario currentScenario() {
        return currentScenario.get();
    }

    /**
     * Finish the run: flush the async dispatcher, post the run summary,
     * mark complete. Safe to call multiple times — only the first call has
     * effect.
     */
    public synchronized void finish() {
        if (finished) return;
        finished = true;

        dispatcher.flush(Duration.ofSeconds(60));

        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(startedAt, finishedAt).toMillis();
        RunSummary summary = new RunSummary(
                finishedAt, durationMs,
                total.get(), passed.get(), failed.get(), skipped.get());
        client.finalizeRun(serverId, summary);
    }

    public boolean isFinished() {
        return finished;
    }

    public int scenariosTotal() { return total.get(); }
    public int scenariosPassed() { return passed.get(); }
    public int scenariosFailed() { return failed.get(); }
    public int scenariosSkipped() { return skipped.get(); }

    void registerActiveScenario(Scenario s) {
        currentScenario.set(s);
    }

    void unregisterActiveScenario(Scenario s) {
        if (currentScenario.get() == s) {
            currentScenario.remove();
        }
    }

    void recordOutcome(Status status) {
        total.incrementAndGet();
        switch (status) {
            case PASSED:
                passed.incrementAndGet();
                break;
            case FAILED:
                failed.incrementAndGet();
                break;
            default:
                skipped.incrementAndGet();
                break;
        }
    }
}
