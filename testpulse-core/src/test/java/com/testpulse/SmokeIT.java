package com.testpulse;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

/**
 * Integration test against a real TestPulse server. Skipped unless the
 * {@code TESTPULSE_URL} environment variable is set, so unit-test runs in
 * isolated environments don't fail because the server isn't reachable.
 *
 * <p>Runs via Failsafe (name ends in {@code IT}):
 *
 * <pre>{@code
 * mvn -pl testpulse-core verify \
 *     -DTESTPULSE_URL=http://testpulse.internal:8080 \
 *     -DTESTPULSE_API_KEY=YOUR_KEY
 * }</pre>
 *
 * <p>This is also the canonical example of the explicit API in action.
 */
public class SmokeIT {

    @BeforeClass
    public static void initTestPulse() {
        String url = System.getenv("TESTPULSE_URL");
        assumeNotNull("Set TESTPULSE_URL to run this integration test", url);

        TestPulse.init(TestPulseConfig.builder()
                .enabled(true)
                .url(url)
                .apiKey(System.getenv("TESTPULSE_API_KEY"))
                .division("Europe")
                .release("R-SMOKE")
                .user(System.getProperty("user.name", "smoke"))
                .environment("TEST")
                .triggerSource("testpulse-core/SmokeIT")
                .build());
    }

    @AfterClass
    public static void shutdownTestPulse() {
        TestPulse.shutdown();
    }

    @Test
    public void fullRunWithOneScenarioAndFiveSteps() throws Exception {
        Run run = TestPulse.startRun();
        assertNotNull("Run should be created", run);
        assertNotNull("Run id should be assigned by server", run.id());

        Scenario s = run.startScenario("Smoke scenario from testpulse-core")
                .withCucumberId("smoke.feature:1")
                .withFeaturePath("src/test/resources/features/smoke.feature")
                .withTags("@smoke", "@core")
                .withLob("CoreSmoke");

        assertNotNull("Scenario id should be assigned by server", s.id());

        s.logStep("Given testpulse-core is on the classpath", Status.PASSED, Duration.ofMillis(412));
        s.logStep("And dispatcher is started", Status.PASSED, Duration.ofMillis(87));
        s.logStep("When a passing step runs", Status.PASSED, Duration.ofMillis(1240));
        s.logStep("When another passing step runs", Status.PASSED, Duration.ofMillis(320));
        s.logStep("Then a step deliberately fails", Status.FAILED, Duration.ofMillis(95),
                new AssertionError("expected confirmation panel to be visible"));

        Thread.sleep(2000);
        s.finish();

        run.finish();

        assertTrue("Run should be marked finished", run.isFinished());
        assertTrue("Should record at least one failed scenario", run.scenariosFailed() >= 1);
    }
}
