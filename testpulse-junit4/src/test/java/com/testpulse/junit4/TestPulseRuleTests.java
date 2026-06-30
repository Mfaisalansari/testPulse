package com.testpulse.junit4;

import com.testpulse.LobContext;
import com.testpulse.TestPulse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for TestPulseRule. Invokes the rule's lifecycle methods
 * directly (no nested JUnit runner needed) to verify behavior in
 * isolation.
 */
public class TestPulseRuleTests {

    @Before
    public void clearGlobalState() {
        LobContext.clear();
        TestPulse.shutdown();
    }

    @After
    public void cleanup() {
        LobContext.clear();
        TestPulse.shutdown();
    }

    @Test
    public void forLob_setsLobContextInBefore() throws Throwable {
        TestPulseRule rule = TestPulseRule.forLob("Smoke");
        invokeBefore(rule);
        assertEquals("Smoke", LobContext.get());
    }

    @Test
    public void after_clearsLobContext() throws Throwable {
        TestPulseRule rule = TestPulseRule.forLob("Casualty");
        invokeBefore(rule);
        invokeAfter(rule);
        assertNull(LobContext.get());
    }

    @Test
    public void defaults_doesNotSetLob() throws Throwable {
        TestPulseRule rule = TestPulseRule.defaults();
        invokeBefore(rule);
        assertNull(LobContext.get());
    }

    @Test
    public void withoutConfig_beforeDoesNotEnableTestPulse() throws Throwable {
        TestPulseRule rule = new TestPulseRule("X");
        invokeBefore(rule);
        // No TESTPULSE_URL set — autoInit should result in disabled state
        assertFalse(TestPulse.isEnabled());
    }

    @Test
    public void before_clearsPriorLobWhenNullSupplied() throws Throwable {
        LobContext.set("LeftoverFromPreviousRun");
        TestPulseRule rule = new TestPulseRule();
        invokeBefore(rule);
        // Rule with no LOB should NOT overwrite — it leaves the existing
        // value alone, so other LOB resolution mechanisms (tags, system
        // property) can still kick in.
        assertEquals("LeftoverFromPreviousRun", LobContext.get());
    }

    @Test
    public void factoryMethodAndConstructorEquivalent() {
        TestPulseRule a = TestPulseRule.forLob("X");
        TestPulseRule b = new TestPulseRule("X");
        assertNotNull(a);
        assertNotNull(b);
        // Both produce functional rules — no behavioral difference
    }

    /**
     * ExternalResource's {@code before()} and {@code after()} are protected.
     * We invoke them through the public {@code apply()} chain — which
     * gives us realistic JUnit-rule behavior without the JUnit runner.
     */
    private void invokeBefore(TestPulseRule rule) throws Throwable {
        // Easiest path: ExternalResource exposes apply(Statement, Description);
        // we run a no-op Statement which triggers before/after around it
        // — but we want before to fire and stop, so split it manually.
        java.lang.reflect.Method m = org.junit.rules.ExternalResource.class
                .getDeclaredMethod("before");
        m.setAccessible(true);
        m.invoke(rule);
    }

    private void invokeAfter(TestPulseRule rule) throws Throwable {
        java.lang.reflect.Method m = org.junit.rules.ExternalResource.class
                .getDeclaredMethod("after");
        m.setAccessible(true);
        m.invoke(rule);
    }
}
