package com.testpulse.testng;

import com.testpulse.LobContext;
import com.testpulse.TestPulse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the testpulse-testng module pieces. The BaseRunner is
 * verified by subclassing it directly (no TestNG runtime needed —
 * we just call the {@code @BeforeClass}/{@code @AfterClass} methods).
 *
 * <p>SuiteListener can't be fully verified here because that requires a
 * real ISuite — that's covered by integration tests in the consumer's
 * framework. We test only that it doesn't blow up with a null suite.
 */
public class TestNGTests {

    @Before
    public void clearGlobalState() {
        LobContext.clear();
        TestPulse.shutdown();
    }

    @After
    public void cleanupAfter() {
        LobContext.clear();
        TestPulse.shutdown();
    }

    @Test
    public void baseRunner_setsLobInBeforeClass() {
        TestRunner runner = new TestRunner("Casualty");
        runner.__registerLob();
        assertEquals("Casualty", LobContext.get());
    }

    @Test
    public void baseRunner_clearsLobInAfterClass() {
        TestRunner runner = new TestRunner("Property");
        runner.__registerLob();
        runner.__clearLob();
        assertNull(LobContext.get());
    }

    @Test
    public void baseRunner_skipsNullOrEmptyLob() {
        LobContext.set("PreExistingValue");
        new TestRunner(null).__registerLob();
        // Null/empty getLob() should NOT clear an existing value —
        // useful for runners that don't declare their LOB and rely on tags
        assertEquals("PreExistingValue", LobContext.get());
    }

    @Test
    public void suiteListener_onStartWithoutConfig_doesNotThrow() {
        SuiteListener listener = new SuiteListener();
        // No TestPulse config — should gracefully no-op
        listener.onStart(null);
        assertFalse(TestPulse.isEnabled());
    }

    @Test
    public void suiteListener_onFinishWithoutConfig_doesNotThrow() {
        SuiteListener listener = new SuiteListener();
        listener.onFinish(null);
        // No assertion — just verifying it doesn't throw
    }

    /**
     * Test fixture — concrete subclass of BaseRunner so we can call the
     * package-private hook methods directly. BaseRunner's actual parent
     * class (AbstractTestNGCucumberTests) isn't on the test classpath
     * for unit tests; we work around by making this a minimal stub
     * subclass that only exercises the LOB methods.
     */
    private static class TestRunner extends BaseRunner {
        private final String lob;

        TestRunner(String lob) {
            this.lob = lob;
        }

        @Override
        protected String getLob() {
            return lob;
        }
    }
}
