package com.testpulse.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and writer. Self-contained so the server has zero
 * runtime dependencies — Jackson would otherwise be the largest dep.
 *
 * <p>Supports the subset needed for the TestPulse wire format:
 * strings, numbers (long + double), booleans, null, objects ({@code Map}),
 * arrays ({@code List}). No streaming, no schema binding — the caller
 * walks the resulting Map/List structure.
 *
 * <p>Not a general-purpose JSON library — does the job for our DTOs and stops.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (p.i < p.s.length()) {
            throw new IllegalArgumentException("Unexpected trailing data at " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Expected object, got " + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (Map<String, Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString((String) v, sb);
        } else if (v instanceof Number) {
            // Avoid NaN/Infinity which aren't valid JSON
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (v instanceof Long || v instanceof Integer) {
                sb.append(((Number) v).longValue());
            } else {
                sb.append(v.toString());
            }
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            // Treat as Iterable
            sb.append('[');
            int len = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                writeValue(java.lang.reflect.Array.get(v, i), sb);
            }
            sb.append(']');
        } else {
            // Fall back to string representation
            writeString(v.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /* -------- Helper accessors -------- */

    public static String str(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : v.toString();
    }

    public static long lng(Map<String, Object> obj, String key, long def) {
        Object v = obj.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<String> strList(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof List)) return new ArrayList<String>();
        List<Object> raw = (List<Object>) v;
        List<String> out = new ArrayList<String>(raw.size());
        for (Object item : raw) out.add(item == null ? null : item.toString());
        return out;
    }

    /* -------- Parser -------- */

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
            this.i = 0;
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    return;
                }
            }
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of input");
            }
            char c = s.charAt(i);
            if (c == '{') return parseObj();
            if (c == '[') return parseArr();
            if (c == '"') return parseStr();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') return parseNull();
            if (c == '-' || (c >= '0' && c <= '9')) return parseNum();
            throw new IllegalArgumentException("Unexpected char '" + c + "' at " + i);
        }

        Map<String, Object> parseObj() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String key = parseStr();
                skipWs();
                expect(':');
                Object val = parseValue();
                m.put(key, val);
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new IllegalArgumentException("Expected ',' or '}' at " + i);
            }
        }

        List<Object> parseArr() {
            expect('[');
            List<Object> a = new ArrayList<Object>();
            skipWs();
            if (peek() == ']') { i++; return a; }
            while (true) {
                a.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return a; }
                throw new IllegalArgumentException("Expected ',' or ']' at " + i);
            }
        }

        String parseStr() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("Unterminated escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("Truncated \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        Object parseNum() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean isFloat = false;
            if (i < s.length() && s.charAt(i) == '.') {
                isFloat = true; i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isFloat = true; i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            return isFloat ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
        }

        Boolean parseBool() {
            if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE;  }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("Expected boolean at " + i);
        }

        Object parseNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new IllegalArgumentException("Expected null at " + i);
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at " + i);
            }
            i++;
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }
    }
}
