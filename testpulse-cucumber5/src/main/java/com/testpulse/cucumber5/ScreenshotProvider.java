package com.testpulse.cucumber5;

/**
 * Strategy for capturing a screenshot when a scenario fails. Decoupled from
 * any specific WebDriver setup so this module compiles cleanly without
 * Selenium on the classpath — consumers wire their own driver via
 * {@link Cucumber5Config#setScreenshotProvider}.
 *
 * <pre>{@code
 * // Once during framework startup, after WebDriver is initialized:
 * Cucumber5Config.setScreenshotProvider(() ->
 *     ((TakesScreenshot) DriverFactory.getDriver())
 *         .getScreenshotAs(OutputType.BYTES));
 * }</pre>
 *
 * <p>Returning {@code null} (or throwing) is fine — the scenario still
 * reports its FAILED status, just without a thumbnail on the dashboard.
 */
public interface ScreenshotProvider {
    byte[] capture();
}
