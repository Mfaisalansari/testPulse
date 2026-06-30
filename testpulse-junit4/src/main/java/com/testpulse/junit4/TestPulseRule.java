package com.testpulse.junit4;

import com.testpulse.LobContext;
import com.testpulse.Run;
import com.testpulse.TestPulse;
import org.junit.rules.ExternalResource;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 4 ClassRule that brackets a runner class with the TestPulse
 * lifecycle. Suitable for sequential smoke packs and Cucumber-JUnit
 * runners (one feature pack per class).
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @RunWith(Cucumber.class)
 * @CucumberOptions(
 *     features = "src/test/resources/features/smoke",
 *     glue = {"com.acme.steps", "com.testpulse.cucumber3"}
 * )
 * public class SmokeRunner {
 *     @ClassRule
 *     public static final TestPulseRule REPORTING = TestPulseRule.forLob("Smoke");
 * }
 * }</pre>
 *
 * <p>For pure-JUnit runners (no Cucumber), the same rule works — the
 * cucumber3 hooks just don't fire because there are no scenarios. Steps
 * can be logged manually through the {@code TestPulse} facade.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@code @ClassRule.before()} — auto-init TestPulse, set LOB if
 *       supplied, eagerly create the Run on the server</li>
 *   <li>Test methods run</li>
 *   <li>{@code @ClassRule.after()} — clear LOB, flush dispatcher, finalize
 *       the Run on the server</li>
 * </ol>
 *
 * <h2>Multiple classes in one JVM</h2>
 *
 * <p>If Surefire is configured to run multiple JUnit classes in the same
 * JVM, each {@code @ClassRule} on a class creates its own independent Run
 * on the server (after each class, the previous run is finalized; before
 * the next, a new one is created). To group multiple JUnit classes into
 * one Run, wrap them in a {@code @RunWith(Suite.class)} class and put the
 * {@code @ClassRule} on the suite class only.
 *
 * <h2>Rule ordering with other rules</h2>
 *
 * <p>If you combine this with other ClassRules (e.g. a WebDriver lifecycle
 * rule), put TestPulseRule on the OUTSIDE so it brackets everything else:
 *
 * <pre>{@code
 * @ClassRule
 * public static final RuleChain CHAIN = RuleChain
 *     .outerRule(TestPulseRule.forLob("Smoke"))
 *     .around(new WebDriverRule());
 * }</pre>
 */
public final class TestPulseRule extends ExternalResource {

    private static final Logger LOG = Logger.getLogger(TestPulseRule.class.getName());

    private final String lob;

    /**
     * Construct a rule with no LOB tagging. The cucumber3 module's default
     * LOB resolver will fall through to its other strategies (scenario tags
     * or {@code -Dtestpulse.lob} system property).
     */
    public TestPulseRule() {
        this(null);
    }

    /**
     * Construct a rule that tags every scenario under this class with the
     * given LOB.
     */
    public TestPulseRule(String lob) {
        this.lob = lob;
    }

    /**
     * Static factory equivalent to {@code new TestPulseRule(lob)} — reads
     * a bit more naturally at the use site.
     */
    public static TestPulseRule forLob(String lob) {
        return new TestPulseRule(lob);
    }

    /**
     * Static factory equivalent to {@code new TestPulseRule()}.
     */
    public static TestPulseRule defaults() {
        return new TestPulseRule();
    }

    @Override
    protected void before() {
        try {
            if (!TestPulse.isEnabled()) {
                TestPulse.autoInit();
            }
            if (lob != null && !lob.isEmpty()) {
                LobContext.set(lob);
            }
            if (TestPulse.isEnabled()) {
                Run run = TestPulse.startRun();
                if (run != null) {
                    LOG.info("TestPulse run started: runId=" + run.id()
                            + (lob != null ? " lob=" + lob : ""));
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "TestPulse rule before() failed (continuing): " + t.getMessage());
        }
    }

    @Override
    protected void after() {
        try {
            LobContext.clear();
        } catch (Throwable ignored) {
        }
        try {
            TestPulse.shutdown();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "TestPulse rule after() failed (continuing): " + t.getMessage());
        }
    }
}
