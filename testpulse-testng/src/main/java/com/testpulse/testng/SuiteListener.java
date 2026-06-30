package com.testpulse.testng;

import com.testpulse.Run;
import com.testpulse.TestPulse;
import org.testng.ISuite;
import org.testng.ISuiteListener;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TestNG suite-scope listener that boots TestPulse at suite start and
 * finalizes at suite end. Auto-registered via {@code META-INF/services/}
 * — consumers do not need to edit {@code suite.xml}.
 *
 * <p>{@link #onStart(ISuite)} eagerly calls {@link TestPulse#startRun()}
 * to lock in the suite's start time on the server. Without this, the Run
 * would be created lazily by whichever scenario fires first, which can
 * drift the start time by several seconds in slow-warmup environments.
 *
 * <p>{@link #onFinish(ISuite)} calls {@link TestPulse#shutdown()} which
 * flushes the async dispatcher (up to 60s) and finalizes the run on the
 * server. Safe to call when reporting was never enabled — TestPulse
 * gracefully no-ops.
 */
public class SuiteListener implements ISuiteListener {

    private static final Logger LOG = Logger.getLogger(SuiteListener.class.getName());

    @Override
    public void onStart(ISuite suite) {
        try {
            if (!TestPulse.isEnabled()) {
                TestPulse.autoInit();
            }
            if (TestPulse.isEnabled()) {
                Run run = TestPulse.startRun();
                if (run != null) {
                    LOG.info("TestPulse run started for TestNG suite '"
                            + suite.getName() + "': runId=" + run.id());
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "TestPulse suite-start failed (continuing): " + t.getMessage());
        }
    }

    @Override
    public void onFinish(ISuite suite) {
        try {
            TestPulse.shutdown();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "TestPulse suite-finish failed (continuing): " + t.getMessage());
        }
    }
}
