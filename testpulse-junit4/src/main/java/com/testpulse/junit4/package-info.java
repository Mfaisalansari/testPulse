/**
 * JUnit 4 integration for TestPulse — a single
 * {@link com.testpulse.junit4.TestPulseRule} that brackets a runner class
 * with the TestPulse run lifecycle.
 *
 * <p>Designed for sequential test packs, including Cucumber-JUnit runners
 * ({@code @RunWith(Cucumber.class)}). Combine with the {@code testpulse-cucumber3}
 * module to get hook-based scenario tracking; without it, you can still
 * log scenarios and steps through the {@link com.testpulse.TestPulse}
 * facade directly.
 */
package com.testpulse.junit4;
