package com.testpulse;

/**
 * Per-thread "current LOB" holder used to coordinate metadata between
 * integration modules without forcing them to depend on each other.
 *
 * <p>Typical usage flow:
 * <ul>
 *   <li>{@code testpulse-testng}'s {@code BaseRunner} sets the value in
 *       {@code @BeforeClass}, clears it in {@code @AfterClass}</li>
 *   <li>{@code testpulse-cucumber3}'s default {@code LobResolver} reads the
 *       value in the scenario {@code @Before} hook</li>
 * </ul>
 *
 * <p>Consumers using the explicit API don't need this — they pass the LOB
 * directly via {@link Scenario#withLob(String)}.
 *
 * <p>Lives in core (not in cucumber3 or testng) so neither integration
 * module needs a dependency on the other.
 */
public final class LobContext {

    private static final ThreadLocal<String> LOB = new ThreadLocal<String>();

    private LobContext() {
    }

    public static void set(String lob) {
        LOB.set(lob);
    }

    public static String get() {
        return LOB.get();
    }

    public static void clear() {
        LOB.remove();
    }
}
