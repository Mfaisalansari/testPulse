package com.testpulse.cucumber4;

import com.testpulse.Run;
import com.testpulse.Status;
import com.testpulse.TestPulse;
import io.cucumber.core.api.Scenario;
import io.cucumber.java.After;
import io.cucumber.java.Before;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cucumber 3 hooks that bracket every scenario with TestPulse API calls.
 *
 * <p>Register by adding {@code com.testpulse.cucumber4} to the {@code glue}
 * array in your runner's {@code @CucumberOptions}:
 *
 * <pre>{@code
 * @CucumberOptions(
 *     glue = {"com.acme.steps", "com.testpulse.cucumber4"},
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

            String lob = Cucumber4Config.getLobResolver().resolve(cucumberScenario);

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

    /**
     * Screenshot capture runs FIRST in the @After phase (Cucumber 3 reverses
     * @After ordering — higher = earlier). MAX_VALUE ensures we beat any
     * user-defined @After that closes the WebDriver. We split capture from
     * status reporting so the bytes are grabbed while the driver is alive,
     * then handed to the report hook via a ThreadLocal.
     */
    @After(order = Integer.MAX_VALUE)
    public void captureScreenshotEarly(Scenario cucumberScenario) {
        if (!cucumberScenario.isFailed()) {
            return;
        }
        try {
            byte[] bytes = Cucumber4Config.getScreenshotProvider().capture();
            if (bytes != null && bytes.length > 0) {
                EARLY_SCREENSHOT.set(bytes);
                LOG.fine("Captured screenshot for failed scenario: " + bytes.length + " bytes");
            } else {
                LOG.warning("Screenshot provider returned " + (bytes == null ? "null" : "empty bytes") +
                        " for failed scenario '" + cucumberScenario.getName() + "' — did you call " +
                        "Cucumber4Config.setScreenshotProvider() during framework startup?");
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Screenshot capture failed for '" + cucumberScenario.getName() +
                    "': " + t.getClass().getSimpleName() + " " + t.getMessage(), t);
        }
    }

    private static final ThreadLocal<byte[]> EARLY_SCREENSHOT = new ThreadLocal<byte[]>();

    @After(order = Integer.MIN_VALUE)
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
            byte[] screenshot = EARLY_SCREENSHOT.get();

            scenario.finish(status, screenshot);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Scenario-finish reporting failed: " + t.getMessage());
            try {
                if (scenario != null) {
                    scenario.finish(Status.UNDEFINED);
                }
            } catch (Throwable ignored) {
            }
        } finally {
            EARLY_SCREENSHOT.remove();
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
     * {@code io.cucumber.core.event.Status} enum (changed from String in
     * Cucumber 2). The enum names ("PASSED", "FAILED", "SKIPPED", "PENDING",
     * "UNDEFINED", "AMBIGUOUS") align with our {@link Status} enum, so we
     * round-trip via {@code .name()} for a clean mapping.
     */
    static Status normalizeStatus(io.cucumber.core.event.Status cucumberStatus) {
        if (cucumberStatus == null) {
            return Status.UNDEFINED;
        }
        return Status.fromString(cucumberStatus.name());
    }
}
