package com.testpulse.testng;

import com.testpulse.LobContext;
import cucumber.api.testng.AbstractTestNGCucumberTests;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

/**
 * Abstract base for Cucumber+TestNG runner classes — one per LOB in a
 * multi-LOB regression suite. Tags the TestNG worker thread with the LOB
 * so the cucumber3 module's {@link com.testpulse.cucumber3.LobResolver
 * default resolver chain} picks it up.
 *
 * <p>Concrete subclass — only three things change between LOB runners:
 *
 * <pre>{@code
 * @CucumberOptions(
 *     features = "src/test/resources/features/casualty",
 *     glue = {"com.acme.steps", "com.testpulse.cucumber3"}
 * )
 * public class CasualtyRunner extends BaseRunner {
 *     @Override
 *     protected String getLob() {
 *         return "Casualty";
 *     }
 * }
 * }</pre>
 *
 * <p>Why ThreadLocal works here: TestNG with {@code parallel="classes"}
 * runs each Runner class on its own thread, and within that thread
 * scenarios execute sequentially. The {@code @BeforeClass}/{@code @AfterClass}
 * pair guarantees the value is set when scenarios start and cleared when
 * the runner finishes — critical because TestNG reuses workers across
 * runner classes.
 *
 * <p>Method names start with double underscore so they sort early
 * alphabetically among {@code @BeforeClass}/{@code @AfterClass} methods
 * a subclass might add; the LOB context must be set before any other
 * suite setup that might depend on it.
 */
public abstract class BaseRunner extends AbstractTestNGCucumberTests {

    @BeforeClass(alwaysRun = true)
    public void __registerLob() {
        String lob = getLob();
        if (lob != null && !lob.isEmpty()) {
            LobContext.set(lob);
        }
    }

    @AfterClass(alwaysRun = true)
    public void __clearLob() {
        LobContext.clear();
    }

    /**
     * The LOB this runner is responsible for, as it should appear on the
     * dashboard. Returned exactly once per runner instance; safe to make
     * static and just return a constant.
     */
    protected abstract String getLob();
}
