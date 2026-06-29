/**
 * TestPulse public API.
 *
 * <p>The entry point is {@link com.testpulse.TestPulse}, which exposes static
 * methods to initialise the library, start a run, and shut down. A
 * {@link com.testpulse.Run} is the suite-scope container; a
 * {@link com.testpulse.Scenario} is the unit of work. Step status uses the
 * {@link com.testpulse.Status} enum.
 *
 * <p>Configuration goes through {@link com.testpulse.TestPulseConfig} via a
 * fluent builder. Sources can be composed: programmatic defaults plus system
 * properties plus environment variables plus a classpath properties file.
 *
 * <p>Everything under {@code com.testpulse.internal} is implementation detail
 * and not part of the public API; do not depend on those classes.
 */
package com.testpulse;
