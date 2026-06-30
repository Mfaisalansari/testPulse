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
 * <p>Handles both styles of Cucumber 3 step patterns:
 * <ul>
 *   <li><b>Cucumber expressions</b> with {@code {string}}, {@code {int}}
 *       placeholders → replaced with the matching argument value</li>
 *   <li><b>Regex patterns</b> with {@code (\w+)}, {@code ([^"]*)} capture
 *       groups → replaced with the matching argument value, anchors
 *       ({@code ^}, {@code $}) and common backslash escapes stripped</li>
 * </ul>
 *
 * <p>For a step defined as {@code @When("^user enters \"([^\"]*)\"$")}
 * called from {@code When user enters "alice"}, this returns
 * {@code When user enters "alice"} — the resolved Gherkin text, not the raw pattern.
 */
final class StepTextResolver {

    /** Matches either a Cucumber expression placeholder OR a regex capture group. */
    private static final Pattern PLACEHOLDER_OR_GROUP =
            Pattern.compile("\\{[^}]+}|\\([^)]*\\)");

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

        // Strip regex anchors that look ugly in display text
        filled = stripRegexCruft(filled);

        // If nothing was substituted and there ARE args, append them so at
        // least the values are visible (last-resort fallback for exotic patterns)
        if (filled.equals(stripRegexCruft(info.pattern)) && args.length > 0) {
            filled = filled + " " + formatArgs(args);
        }

        return info.keyword + " " + filled.trim();
    }

    private static String substitutePlaceholders(String pattern, Object[] args) {
        Matcher m = PLACEHOLDER_OR_GROUP.matcher(pattern);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find()) {
            String token = m.group();
            // Non-capturing groups (?:...), lookaheads (?=...), lookbehinds (?<...)
            // don't consume an argument — leave them in place.
            if (token.startsWith("(?")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(token));
                continue;
            }
            // Out of arguments — leave the placeholder as-is rather than crashing.
            if (idx >= args.length) {
                m.appendReplacement(sb, Matcher.quoteReplacement(token));
                continue;
            }
            String replacement = String.valueOf(args[idx++]);
            // Cucumber's {string} type strips the surrounding quotes from the
            // matched text. Add them back when the placeholder was {string}
            // so the rendered output matches the original Gherkin line.
            if (token.equals("{string}")) {
                replacement = "\"" + replacement + "\"";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripRegexCruft(String s) {
        // Strip leading ^ and trailing $ (regex anchors)
        if (s.startsWith("^")) s = s.substring(1);
        if (s.endsWith("$"))   s = s.substring(0, s.length() - 1);
        // Unescape commonly-seen sequences. Conservative list — only the ones
        // that occur in step patterns. We deliberately don't touch \d, \w, \s
        // since those almost always come from un-substituted patterns.
        s = s.replace("\\\"", "\"")
             .replace("\\.", ".")
             .replace("\\(", "(")
             .replace("\\)", ")")
             .replace("\\?", "?")
             .replace("\\+", "+");
        return s;
    }

    private static String formatArgs(Object[] args) {
        if (args.length == 1) return "[" + args[0] + "]";
        return Arrays.toString(args);
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
