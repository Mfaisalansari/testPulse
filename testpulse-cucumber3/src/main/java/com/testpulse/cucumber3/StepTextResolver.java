package com.testpulse.cucumber3;

import cucumber.api.java.en.And;
import cucumber.api.java.en.But;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconstructs a human-readable step text from a method-level Cucumber
 * annotation plus the runtime arguments. Used by {@link StepLoggingAspect}
 * where we don't have access to Cucumber's resolved step text (that would
 * require the EventListener path, which the user explicitly opted out of).
 *
 * <p>Strategy:
 * <ul>
 *   <li>Cucumber expression placeholders ({@code {string}}, {@code {int}},
 *       etc.) are substituted with the argument values in declaration order</li>
 *   <li>If the pattern has no placeholders but the method has args (regex-
 *       style patterns), arguments are appended as a suffix</li>
 *   <li>If reflection can't find an annotation, returns just the method name</li>
 * </ul>
 *
 * <p>Result format: {@code "Given user enters \"alice\" as username"}
 */
final class StepTextResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]+}");

    private StepTextResolver() {
    }

    static String resolve(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        StepInfo info = extractStepInfo(method);
        if (info == null) {
            return method.getName();
        }

        Object[] args = joinPoint.getArgs();
        String filled = substitutePlaceholders(info.pattern, args);

        // If no placeholders were substituted but there are args, append them —
        // happens for regex-style patterns like @Given("^user (.*) clicks (.*)$")
        if (filled.equals(info.pattern) && args.length > 0) {
            filled = info.pattern + " " + Arrays.toString(args);
        }

        return info.keyword + " " + filled;
    }

    private static String substitutePlaceholders(String pattern, Object[] args) {
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find() && idx < args.length) {
            String replacement = String.valueOf(args[idx++]);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static StepInfo extractStepInfo(Method method) {
        if (method.isAnnotationPresent(Given.class)) {
            return new StepInfo("Given", method.getAnnotation(Given.class).value());
        }
        if (method.isAnnotationPresent(When.class)) {
            return new StepInfo("When", method.getAnnotation(When.class).value());
        }
        if (method.isAnnotationPresent(Then.class)) {
            return new StepInfo("Then", method.getAnnotation(Then.class).value());
        }
        if (method.isAnnotationPresent(And.class)) {
            return new StepInfo("And", method.getAnnotation(And.class).value());
        }
        if (method.isAnnotationPresent(But.class)) {
            return new StepInfo("But", method.getAnnotation(But.class).value());
        }
        return null;
    }

    private static final class StepInfo {
        final String keyword;
        final String pattern;

        StepInfo(String keyword, String pattern) {
            this.keyword = keyword;
            this.pattern = pattern;
        }
    }
}
