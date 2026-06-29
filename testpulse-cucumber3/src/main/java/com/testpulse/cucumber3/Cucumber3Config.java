package com.testpulse.cucumber3;

/**
 * Module-scope configuration for testpulse-cucumber3. All values are
 * pluggable strategies with sensible defaults; consumers override during
 * framework startup if they need different behavior.
 *
 * <pre>{@code
 * Cucumber3Config.setLobResolver(LobResolvers.fixed("Casualty"));
 * Cucumber3Config.setScreenshotProvider(() -> myDriver.captureBytes());
 * }</pre>
 *
 * <p>Configuration is JVM-wide and read by {@link ReportingHooks} on every
 * scenario, so changing it mid-suite affects subsequent scenarios.
 */
public final class Cucumber3Config {

    private static volatile LobResolver lobResolver = LobResolvers.defaultChain();
    private static volatile ScreenshotProvider screenshotProvider = new ScreenshotProvider() {
        @Override
        public byte[] capture() {
            return null;
        }
    };

    private Cucumber3Config() {
    }

    public static void setLobResolver(LobResolver resolver) {
        if (resolver != null) {
            lobResolver = resolver;
        }
    }

    public static LobResolver getLobResolver() {
        return lobResolver;
    }

    public static void setScreenshotProvider(ScreenshotProvider provider) {
        if (provider != null) {
            screenshotProvider = provider;
        }
    }

    public static ScreenshotProvider getScreenshotProvider() {
        return screenshotProvider;
    }
}
