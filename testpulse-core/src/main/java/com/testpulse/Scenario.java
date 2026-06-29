package com.testpulse;

import com.testpulse.internal.client.AsyncDispatcher;
import com.testpulse.internal.client.TestPulseClient;
import com.testpulse.internal.dto.Dtos.ScenarioFinish;
import com.testpulse.internal.dto.Dtos.ScenarioRequest;
import com.testpulse.internal.dto.Dtos.StepEvent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A scenario being reported to TestPulse. Returned from
 * {@link Run#startScenario(String)} or {@link Run#newScenario()}.
 *
 * <p>Step logging is fire-and-forget through the async dispatcher; the
 * scenario itself caches the per-step results so {@link #finish()} can
 * compute the right rolled-up status if the caller didn't specify one.
 *
 * <p>Not thread-safe — each scenario is owned by the thread that started it.
 * If you need to log from multiple threads, externally synchronize.
 */
public final class Scenario {

    private final Run run;
    private final TestPulseClient client;
    private final AsyncDispatcher dispatcher;

    private final String name;
    private String cucumberId;
    private String featurePath;
    private List<String> tags = Collections.emptyList();
    private String lob;
    private String threadName;

    private String serverId;
    private Status rollupStatus = Status.PASSED;
    private boolean finished = false;

    Scenario(Run run, TestPulseClient client, AsyncDispatcher dispatcher, String name) {
        this.run = run;
        this.client = client;
        this.dispatcher = dispatcher;
        this.name = name;
        this.threadName = Thread.currentThread().getName();
    }

    public Scenario withCucumberId(String id) { ensureNotStarted(); this.cucumberId = id; return this; }
    public Scenario withFeaturePath(String path) { ensureNotStarted(); this.featurePath = path; return this; }
    public Scenario withLob(String lob) { ensureNotStarted(); this.lob = lob; return this; }
    public Scenario withThreadName(String name) { ensureNotStarted(); this.threadName = name; return this; }

    public Scenario withTags(String... tags) {
        ensureNotStarted();
        this.tags = tags == null ? Collections.<String>emptyList() : Arrays.asList(tags);
        return this;
    }

    public Scenario withTags(Collection<String> tags) {
        ensureNotStarted();
        this.tags = tags == null ? Collections.<String>emptyList() : new ArrayList<String>(tags);
        return this;
    }

    /**
     * Send the scenario-start event to the server. Returns this for chaining.
     * Called automatically by {@link Run#startScenario(String)}; only call
     * manually if you built the scenario via {@link Run#newScenario()}.
     */
    public Scenario start() {
        if (serverId != null) return this;
        ScenarioRequest req = new ScenarioRequest(
                name, cucumberId, featurePath,
                tags, lob, threadName, Instant.now());
        this.serverId = client.startScenario(run.id(), req);
        if (serverId != null) {
            run.registerActiveScenario(this);
        }
        return this;
    }

    public String id() {
        return serverId;
    }

    public String name() {
        return name;
    }

    public Run run() {
        return run;
    }

    /**
     * Log a passed step.
     */
    public Scenario logStep(String text, Status status, Duration duration) {
        return logStep(text, status, duration, null);
    }

    /**
     * Log a step with optional error. The dispatcher posts asynchronously;
     * this method returns immediately. Updates the rollup status: a single
     * FAILED step makes the whole scenario fail unless explicitly overridden
     * at {@link #finish(Status)} time.
     */
    public Scenario logStep(String text, Status status, Duration duration, Throwable error) {
        if (finished) return this;
        if (serverId == null) {
            return this;
        }
        long ms = duration == null ? 0L : duration.toMillis();
        String stack = error == null ? null : stackTrace(error);

        dispatcher.postAsync(
                "/api/runs/" + run.id() + "/scenarios/" + serverId + "/steps",
                new StepEvent(text, status.name(), ms, stack, Instant.now()));

        if (status == Status.FAILED && rollupStatus != Status.FAILED) {
            rollupStatus = Status.FAILED;
        } else if (status == Status.SKIPPED && rollupStatus == Status.PASSED) {
            rollupStatus = Status.SKIPPED;
        }
        return this;
    }

    /**
     * Finish the scenario using its rollup status (FAILED if any step failed,
     * SKIPPED if any step skipped and none failed, else PASSED).
     */
    public void finish() {
        finish(rollupStatus);
    }

    /**
     * Finish with an explicit status — useful when the caller knows the
     * scenario outcome from outside the step log (e.g. Cucumber 3's
     * {@code Scenario.getStatus()}).
     */
    public void finish(Status status) {
        finish(status, null);
    }

    /**
     * Finish with an explicit status and an optional screenshot.
     */
    public void finish(Status status, byte[] screenshot) {
        if (finished) return;
        finished = true;
        String b64 = screenshot == null ? null
                : java.util.Base64.getEncoder().encodeToString(screenshot);
        dispatcher.postAsync(
                "/api/runs/" + run.id() + "/scenarios/" + serverId + "/finish",
                new ScenarioFinish(status.name(), b64, Instant.now()));
        run.recordOutcome(status);
        run.unregisterActiveScenario(this);
    }

    private void ensureNotStarted() {
        if (serverId != null) {
            throw new IllegalStateException(
                    "Cannot modify scenario metadata after start() has been called");
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
