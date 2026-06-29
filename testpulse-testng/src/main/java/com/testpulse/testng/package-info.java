/**
 * TestNG integration for TestPulse.
 *
 * <p>Two pieces:
 * <ul>
 *   <li>{@link com.testpulse.testng.SuiteListener} — auto-registered via
 *       {@code META-INF/services/org.testng.ITestNGListener}. Boots
 *       TestPulse at suite start, finalizes at suite end. No code or
 *       {@code suite.xml} changes required.</li>
 *   <li>{@link com.testpulse.testng.BaseRunner} — abstract class for
 *       Cucumber+TestNG runners. Concrete subclasses implement
 *       {@code getLob()} and declare {@code @CucumberOptions}; everything
 *       else is inherited. Requires {@code cucumber-testng} on the
 *       classpath (not pulled transitively — consumers already have it
 *       if they use Cucumber+TestNG).</li>
 * </ul>
 *
 * <p>For pure-TestNG consumers (no Cucumber), only the {@code SuiteListener}
 * applies — they set the LOB themselves via
 * {@link com.testpulse.LobContext#set} from their own {@code @BeforeClass}
 * or skip LOB tagging entirely.
 */
package com.testpulse.testng;
