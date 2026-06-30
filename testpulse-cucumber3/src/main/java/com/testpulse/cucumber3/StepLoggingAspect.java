package com.testpulse.cucumber3;

import com.testpulse.Run;
import com.testpulse.Scenario;
import com.testpulse.Status;
import com.testpulse.TestPulse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AspectJ aspect that intercepts every Cucumber 3 step definition method
 * ({@code @Given/@When/@Then/@And/@But}), times its execution, captures any
 * thrown exception, and logs a {@code StepEvent} to the active scenario.
 *
 * <p>Activation requires AspectJ load-time weaving — consumer adds
 * {@code -javaagent:aspectjweaver.jar} to their Surefire {@code argLine}
 * and a {@code META-INF/aop.xml} that picks up this aspect. The shipped
 * {@code aop.xml} in this jar provides sensible default scope
 * ({@code *..steps..*}); consumers can extend.
 *
 * <p>Without the agent activated, this aspect compiles but isn't woven —
 * the framework still runs normally, just without step-level detail on the
 * dashboard. Scenario-level boundaries still work because those come from
 * {@link ReportingHooks}.
 */
@Aspect("pertypewithin(*)")
public class StepLoggingAspect {

    private static final Logger LOG = Logger.getLogger(StepLoggingAspect.class.getName());

    @Pointcut("@annotation(cucumber.api.java.en.Given) || " +
             "@annotation(cucumber.api.java.en.When)  || " +
             "@annotation(cucumber.api.java.en.Then)  || " +
             "@annotation(cucumber.api.java.en.And)   || " +
             "@annotation(cucumber.api.java.en.But)")
    public void cucumberStep() {
    }

    @Around("cucumberStep()")
    public Object aroundStep(ProceedingJoinPoint joinPoint) throws Throwable {
        Run run = TestPulse.currentRun();
        Scenario scenario = run == null ? null : run.currentScenario();

        // Defensive: aspect can fire before the @Before hook in edge cases
        // (e.g. step definitions invoked during Cucumber's dry-run mode).
        // In that case, just let the step proceed without logging.
        if (scenario == null) {
            return joinPoint.proceed();
        }

        String stepText;
        try {
            stepText = StepTextResolver.resolve(joinPoint);
        } catch (Throwable t) {
            // Reflection failure shouldn't fail the test. Fall back to method name.
            stepText = joinPoint.getSignature().getName();
        }

        long start = System.currentTimeMillis();
        Throwable thrown = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            try {
                scenario.logStep(
                        stepText,
                        thrown == null ? Status.PASSED : Status.FAILED,
                        Duration.ofMillis(elapsed),
                        thrown);
            } catch (Throwable t) {
                // Logging must never fail the test
                LOG.log(Level.FINE, "Step log failed (continuing): " + t.getMessage());
            }
        }
    }
}
