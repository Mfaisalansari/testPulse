package com.testpulse.cucumber3;

import com.testpulse.Run;
import com.testpulse.Status;
import com.testpulse.TestPulse;
import cucumber.api.Scenario;
import cucumber.api.java.After;
import cucumber.api.java.Before;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cucumber 3 hooks that bracket every scenario with TestPulse API calls.
 *
 * <p>Register by adding {@code com.testpulse.cucumber3} to the {@code glue}
 * array in your runner's {@code @CucumberOptions}:
 *
 * <pre>{@code
 * @CucumberOptions(
 *     glue = {"com.acme.steps", "com.testpulse.cucumber3"},
 *     features = "src/test/resources/features/casualty"
 * )
 * public class CasualtyRunner extends BaseRunner { ... }
 * }</pre>
 *
 * <p>Hook ordering is deliberate:
 * <ul>
 *   <li>{@code @Before(order = 0)} runs <b>before</b> driver setup, so a
 *       driver-init failure is still captured as scenario-start on the server</li>
 *   <li>{@code @After(order = 10_000)} runs <b>after</b> all tear-downs,
 *       so the screenshot capture and final status reflect true end state</li>
 * </ul>
 *
 * <p>All exception handling is defensive: any failure in reporting is
 * logged and swallowed. A TestPulse server outage must never fail a test.
 */
public class ReportingHooks {

    private static final Logger LOG = Logger.getLogger(ReportingHooks.class.getName());

    @Before(order = 0)
    public void onScenarioStart(Scenario cucumberScenario) {
        try {
            // Auto-init if the consumer hasn't called TestPulse.init() themselves.
            // No-op if already initialized or if config isn't present.
            if (!TestPulse.isEnabled()) {
                TestPulse.autoInit();
            }
            if (!TestPulse.isEnabled()) {
                return;
            }

            Run run = TestPulse.startRun();
            if (run == null) {
                return;
            }

            String lob = Cucumber3Config.getLobResolver().resolve(cucumberScenario);

            run.newScenario(cucumberScenario.getName())
                    .withCucumberId(cucumberScenario.getId())
                    .withFeaturePath(extractFeaturePath(cucumberScenario.getId()))
                    .withTags(new ArrayList<String>(cucumberScenario.getSourceTagNames()))
                    .withLob(lob)
                    .start();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Scenario-start reporting failed: " + t.getMessage());
        }
    }

    @After(order = 10_000)
    public void onScenarioFinish(Scenario cucumberScenario) {
        com.testpulse.Scenario scenario = null;
        try {
            Run run = TestPulse.currentRun();
            if (run == null) {
                return;
            }
            scenario = run.currentScenario();
            if (scenario == null) {
                return;
            }

            Status status = normalizeStatus(cucumberScenario.getStatus());
            byte[] screenshot = null;
            if (cucumberScenario.isFailed()) {
                try {
                    screenshot = Cucumber3Config.getScreenshotProvider().capture();
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "Screenshot capture failed (continuing): " + t.getMessage());
                }
            }

            scenario.finish(status, screenshot);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Scenario-finish reporting failed: " + t.getMessage());
            // Ensure the scenario is marked finished even on reporting failure
            // so the next scenario on this thread doesn't see a stale reference.
            try {
                if (scenario != null) {
                    scenario.finish(Status.UNDEFINED);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Cucumber 3's {@code Scenario.getId()} is {@code path/file.feature:lineNo}.
     * Strip the line number for the feature-path field.
     */
    static String extractFeaturePath(String scenarioId) {
        if (scenarioId == null) {
            return null;
        }
        int colon = scenarioId.lastIndexOf(':');
        return colon > 0 ? scenarioId.substring(0, colon) : scenarioId;
    }

    /**
     * Cucumber 3's {@code Scenario.getStatus()} returns the
     * {@code cucumber.api.Result.Type} enum (changed from String in
     * Cucumber 2). The enum names ("PASSED", "FAILED", "SKIPPED", "PENDING",
     * "UNDEFINED", "AMBIGUOUS") align with our {@link Status} enum, so we
     * round-trip via {@code .name()} for a clean mapping.
     */
    static Status normalizeStatus(cucumber.api.Result.Type cucumberStatus) {
        if (cucumberStatus == null) {
            return Status.UNDEFINED;
        }
        return Status.fromString(cucumberStatus.name());
    }
}
