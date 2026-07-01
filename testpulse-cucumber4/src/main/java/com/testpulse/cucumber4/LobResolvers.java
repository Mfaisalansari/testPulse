package com.testpulse.cucumber4;

import com.testpulse.LobContext;
import io.cucumber.core.api.Scenario;

/**
 * Built-in {@link LobResolver} implementations. Compose with
 * {@link #fallback(LobResolver...)} or override per-team via
 * {@link Cucumber4Config#setLobResolver}.
 */
public final class LobResolvers {

    private LobResolvers() {
    }

    /**
     * Reads from a tag like {@code @lob:Casualty}. The prefix is configurable;
     * {@code byTagPrefix("env")} would match {@code @env:QA}.
     */
    public static LobResolver byTagPrefix(final String tagPrefix) {
        final String needle = "@" + tagPrefix + ":";
        return new LobResolver() {
            @Override
            public String resolve(Scenario scenario) {
                if (scenario == null) return null;
                for (String tag : scenario.getSourceTagNames()) {
                    if (tag != null && tag.startsWith(needle)) {
                        return tag.substring(needle.length());
                    }
                }
                return null;
            }
        };
    }

    /**
     * Reads from the thread-local {@link LobContext} — typically set by
     * a TestNG {@code BaseRunner}'s {@code @BeforeClass}.
     */
    public static LobResolver fromLobContext() {
        return new LobResolver() {
            @Override
            public String resolve(Scenario scenario) {
                return LobContext.get();
            }
        };
    }

    /**
     * Reads from a system property.
     */
    public static LobResolver fromSystemProperty(final String key) {
        return new LobResolver() {
            @Override
            public String resolve(Scenario scenario) {
                return System.getProperty(key);
            }
        };
    }

    /**
     * A fixed value, regardless of scenario or context.
     */
    public static LobResolver fixed(final String value) {
        return new LobResolver() {
            @Override
            public String resolve(Scenario scenario) {
                return value;
            }
        };
    }

    /**
     * Tries each resolver in order; returns the first non-null result.
     */
    public static LobResolver fallback(final LobResolver... resolvers) {
        return new LobResolver() {
            @Override
            public String resolve(Scenario scenario) {
                for (LobResolver r : resolvers) {
                    String v = r.resolve(scenario);
                    if (v != null && !v.isEmpty()) return v;
                }
                return null;
            }
        };
    }

    /**
     * The default resolver chain — tag → ThreadLocal context → system
     * property. Used unless overridden via
     * {@link Cucumber4Config#setLobResolver}.
     */
    public static LobResolver defaultChain() {
        return fallback(
                byTagPrefix("lob"),
                fromLobContext(),
                fromSystemProperty("testpulse.lob"));
    }
}
