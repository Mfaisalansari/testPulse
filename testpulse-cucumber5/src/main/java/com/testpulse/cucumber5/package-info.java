/**
 * Cucumber 3 integration for TestPulse.
 *
 * <p>Consumer setup is a three-line change:
 * <ol>
 *   <li>Add the {@code testpulse-cucumber3} dependency</li>
 *   <li>Add {@code com.testpulse.cucumber5} to the {@code glue} array</li>
 *   <li>Set {@code -Dtestpulse.enabled=true -Dtestpulse.url=…} on the
 *       test command line</li>
 * </ol>
 *
 * <p>Step-level detail requires AspectJ load-time weaving — add
 * {@code -javaagent:aspectjweaver.jar} to the Surefire {@code argLine}.
 * Without the agent, scenario-level boundaries still work via the hooks.
 *
 * <p>{@link com.testpulse.cucumber5.Cucumber5Config} exposes pluggable
 * strategies for LOB resolution and screenshot capture. Defaults work for
 * most teams; override during framework startup if you need different
 * behavior.
 *
 * <p>The {@link com.testpulse.cucumber5.ReportingHooks},
 * {@link com.testpulse.cucumber5.StepLoggingAspect}, and
 * {@link com.testpulse.cucumber5.Cucumber5Config} are the only public
 * types — the rest are implementation detail.
 */
package com.testpulse.cucumber5;
