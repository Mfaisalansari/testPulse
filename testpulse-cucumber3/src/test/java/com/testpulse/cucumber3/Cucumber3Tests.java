package com.testpulse.cucumber3;

import com.testpulse.LobContext;
import cucumber.api.Scenario;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for the pure-logic pieces of the cucumber3 module. The hooks
 * and aspect need a real Cucumber runtime to test meaningfully — those
 * are covered by integration tests in the consumer's framework.
 */
public class Cucumber3Tests {

    @After
    public void cleanup() {
        LobContext.clear();
    }

    @Test
    public void extractFeaturePath_stripsLineNumber() {
        assertEquals("features/casualty/login.feature",
                ReportingHooks.extractFeaturePath("features/casualty/login.feature:12"));
    }

    @Test
    public void extractFeaturePath_handlesNoColon() {
        assertEquals("login.feature",
                ReportingHooks.extractFeaturePath("login.feature"));
    }

    @Test
    public void lobResolver_byTagPrefix_findsMatchingTag() {
        Scenario fake = fakeScenario("Login test",
                Arrays.asList("@smoke", "@lob:Casualty", "@regression"));
        LobResolver r = LobResolvers.byTagPrefix("lob");
        assertEquals("Casualty", r.resolve(fake));
    }

    @Test
    public void lobResolver_byTagPrefix_returnsNullIfNoMatch() {
        Scenario fake = fakeScenario("Login test", Arrays.asList("@smoke"));
        LobResolver r = LobResolvers.byTagPrefix("lob");
        assertNull(r.resolve(fake));
    }

    @Test
    public void lobResolver_fromLobContext_readsThreadLocal() {
        LobContext.set("Property");
        assertEquals("Property", LobResolvers.fromLobContext().resolve(null));
    }

    @Test
    public void lobResolver_fallback_returnsFirstNonNull() {
        Scenario fake = fakeScenario("X", Arrays.asList("@smoke"));
        LobResolver r = LobResolvers.fallback(
                LobResolvers.byTagPrefix("lob"),     // returns null — no @lob tag
                LobResolvers.fixed("Motor"),         // returns "Motor"
                LobResolvers.fixed("ShouldNotReach"));
        assertEquals("Motor", r.resolve(fake));
    }

    @Test
    public void lobResolver_defaultChain_prefersTagOverContext() {
        LobContext.set("Property");
        Scenario fake = fakeScenario("X", Arrays.asList("@lob:Casualty"));
        // Tag should win over the ThreadLocal context
        assertEquals("Casualty", LobResolvers.defaultChain().resolve(fake));
    }

    private static Scenario fakeScenario(final String name, final List<String> tags) {
        // Minimal stub — only the methods our resolvers use.
        return new Scenario() {
            @Override public Collection<String> getSourceTagNames() { return tags; }
            @Override public cucumber.api.Result.Type getStatus() { return null; }
            @Override public boolean isFailed() { return false; }
            @Override public void embed(byte[] data, String mimeType) {}
            @Override public void write(String text) {}
            @Override public String getName() { return name; }
            @Override public String getId() { return name + ":1"; }
            @Override public String getUri() { return name; }
            @Override public java.util.List<Integer> getLines() { return Collections.singletonList(1); }
        };
    }
}
