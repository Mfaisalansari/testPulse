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
        SubstitutionResult sub = substitutePlaceholders(info.pattern, args);

        // Strip regex cruft from the pattern line BEFORE appending leftover args
        // (DataTables on new lines), so the $ / ^ anchors get cleaned correctly.
        String patternLine = stripRegexCruft(sub.text);

        // If nothing was substituted and there ARE simple args, append them so at
        // least the values are visible (last-resort fallback for exotic patterns)
        if (patternLine.equals(stripRegexCruft(info.pattern)) && args.length > 0 && sub.leftovers.isEmpty()) {
            patternLine = patternLine + " " + formatArgs(args);
        }

        // Append complex leftover args (DataTables, doc strings) on new lines —
        // these become structured markers like [TABLE:...] the UI renders specially.
        StringBuilder out = new StringBuilder(info.keyword).append(" ").append(patternLine.trim());
        for (String leftover : sub.leftovers) {
            out.append("\n").append(leftover);
        }
        return out.toString();
    }

    private static SubstitutionResult substitutePlaceholders(String pattern, Object[] args) {
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
            String replacement = formatArg(args[idx++], token);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);

        // Collect leftover args (datatables, doc strings — these typically don't
        // have placeholders in the pattern, they're attached to the step in Gherkin).
        java.util.List<String> leftovers = new java.util.ArrayList<String>();
        while (idx < args.length) {
            leftovers.add(formatArg(args[idx++], null));
        }
        return new SubstitutionResult(sb.toString(), leftovers);
    }

    private static final class SubstitutionResult {
        final String text;
        final java.util.List<String> leftovers;

        SubstitutionResult(String text, java.util.List<String> leftovers) {
            this.text = text;
            this.leftovers = leftovers;
        }
    }

    /**
     * Format a single argument for display. Handles DataTable (Cucumber 3),
     * List-of-List (raw table form), and plain values. DataTable and list-of-
     * list are emitted as a {@code [TABLE:[[...],[...]]]} marker that the UI
     * picks up and renders as a real HTML table.
     */
    @SuppressWarnings("unchecked")
    private static String formatArg(Object arg, String placeholder) {
        if (arg == null) return "null";

        // Cucumber 3 DataTable — use reflection to avoid a hard dependency on
        // the DataTable class (testpulse-cucumber3 has Cucumber at provided
        // scope; if it ever changes to optional, this still works).
        String className = arg.getClass().getName();
        if ("cucumber.api.DataTable".equals(className)
                || "io.cucumber.datatable.DataTable".equals(className)) {
            try {
                java.lang.reflect.Method rawMethod = arg.getClass().getMethod("raw");
                Object raw = rawMethod.invoke(arg);
                if (raw instanceof java.util.List) {
                    String marker = tableMarker((java.util.List<?>) raw);
                    if (marker != null) return marker;
                }
            } catch (Throwable ignored) {
                // Fall through to default toString
            }
        }

        // List<List<String>> — the form Cucumber gives you if the step def
        // declares the arg as List<List<String>> instead of DataTable.
        if (arg instanceof java.util.List) {
            java.util.List<?> outer = (java.util.List<?>) arg;
            if (!outer.isEmpty() && outer.get(0) instanceof java.util.List) {
                String marker = tableMarker(outer);
                if (marker != null) return marker;
            }
            // List<Map<String,String>> — row-as-map form. Convert to rows of
            // [header, value] pairs flattened to a uniform table.
            if (!outer.isEmpty() && outer.get(0) instanceof java.util.Map) {
                String marker = mapListMarker((java.util.List<java.util.Map<String, ?>>) outer);
                if (marker != null) return marker;
            }
        }

        // Map<String, String> — Cucumber gives you a Map directly for vertical
        // 2-column tables. Render as a 2-column key-value table.
        if (arg instanceof java.util.Map) {
            String marker = singleMapMarker((java.util.Map<?, ?>) arg);
            if (marker != null) return marker;
        }

        // String value — add quotes back for {string} placeholders so the
        // displayed text matches the original Gherkin line.
        String s = String.valueOf(arg);
        if ("{string}".equals(placeholder)) {
            return "\"" + s + "\"";
        }
        return s;
    }

    private static String tableMarker(java.util.List<?> rows) {
        if (rows.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[TABLE:[");
        for (int r = 0; r < rows.size(); r++) {
            Object row = rows.get(r);
            if (!(row instanceof java.util.List)) return null;
            if (r > 0) sb.append(",");
            sb.append("[");
            java.util.List<?> cells = (java.util.List<?>) row;
            for (int c = 0; c < cells.size(); c++) {
                if (c > 0) sb.append(",");
                sb.append("\"").append(jsonEscape(String.valueOf(cells.get(c)))).append("\"");
            }
            sb.append("]");
        }
        sb.append("]]");
        return sb.toString();
    }

    private static String mapListMarker(java.util.List<java.util.Map<String, ?>> rows) {
        if (rows.isEmpty()) return null;
        // Use the first row's keys as the header
        java.util.Set<String> headers = rows.get(0).keySet();
        StringBuilder sb = new StringBuilder("[TABLE:[[");
        boolean first = true;
        for (String h : headers) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEscape(h)).append("\"");
        }
        sb.append("]");
        for (java.util.Map<String, ?> row : rows) {
            sb.append(",[");
            first = true;
            for (String h : headers) {
                if (!first) sb.append(",");
                first = false;
                Object v = row.get(h);
                sb.append("\"").append(jsonEscape(v == null ? "" : v.toString())).append("\"");
            }
            sb.append("]");
        }
        sb.append("]]");
        return sb.toString();
    }

    private static String singleMapMarker(java.util.Map<?, ?> map) {
        if (map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[TABLE:[");
        boolean first = true;
        for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("[\"")
              .append(jsonEscape(String.valueOf(e.getKey())))
              .append("\",\"")
              .append(jsonEscape(e.getValue() == null ? "" : String.valueOf(e.getValue())))
              .append("\"]");
        }
        sb.append("]]");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
