package com.testpulse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Immutable configuration for a TestPulse instance.
 *
 * <p>Build one with {@link #builder()}, or load defaults from system
 * properties / environment / classpath via the static factories. The builder
 * supports composing sources — typical CI usage:
 *
 * <pre>{@code
 * TestPulseConfig config = TestPulseConfig.builder()
 *     .url("http://testpulse.internal:8080")
 *     .environment("QA")
 *     .mergeSystemProperties()   // -D values win over the above
 *     .mergeEnvironment()        // env vars win over -D
 *     .build();
 * }</pre>
 */
public final class TestPulseConfig {

    private final boolean enabled;
    private final String url;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int queueCapacity;
    private final int maxRetries;
    private final String division;
    private final String release;
    private final String user;
    private final String environment;
    private final String branch;
    private final String triggerSource;
    private final String fallbackFile;

    private TestPulseConfig(Builder b) {
        this.enabled = b.enabled;
        this.url = b.url;
        this.apiKey = b.apiKey;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.queueCapacity = b.queueCapacity;
        this.maxRetries = b.maxRetries;
        this.division = b.division;
        this.release = b.release;
        this.user = b.user;
        this.environment = b.environment;
        this.branch = b.branch;
        this.triggerSource = b.triggerSource;
        this.fallbackFile = b.fallbackFile;
    }

    public boolean isEnabled() { return enabled; }
    public String url() { return url; }
    public String apiKey() { return apiKey; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public int queueCapacity() { return queueCapacity; }
    public int maxRetries() { return maxRetries; }
    public String division() { return division; }
    public String release() { return release; }
    public String user() { return user; }
    public String environment() { return environment; }
    public String branch() { return branch; }
    public String triggerSource() { return triggerSource; }
    public String fallbackFile() { return fallbackFile; }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience: build a config from system properties only.
     */
    public static TestPulseConfig fromSystemProperties() {
        return builder().mergeSystemProperties().build();
    }

    /**
     * Convenience: build a config from environment variables only.
     */
    public static TestPulseConfig fromEnvironment() {
        return builder().mergeEnvironment().build();
    }

    /**
     * Auto-discover config: classpath properties file (testpulse.properties),
     * then environment, then system properties — each source overlays on top.
     * Designed for the auto-wire path where no programmatic config is given.
     */
    public static TestPulseConfig autoDiscover() {
        return builder()
                .mergeClasspath("testpulse.properties")
                .mergeEnvironment()
                .mergeSystemProperties()
                .build();
    }

    public static final class Builder {

        private boolean enabled = false;
        private String url = "";
        private String apiKey = "";
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs = 15_000;
        private int queueCapacity = 10_000;
        private int maxRetries = 3;
        private String division;
        private String release;
        private String user;
        private String environment = "QA";
        private String branch;
        private String triggerSource;
        private String fallbackFile = "target/testpulse-fallback.jsonl";

        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder url(String v) { this.url = trimSlash(v); return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v) { this.readTimeoutMs = v; return this; }
        public Builder queueCapacity(int v) { this.queueCapacity = v; return this; }
        public Builder maxRetries(int v) { this.maxRetries = v; return this; }
        public Builder division(String v) { this.division = v; return this; }
        public Builder release(String v) { this.release = v; return this; }
        public Builder user(String v) { this.user = v; return this; }
        public Builder environment(String v) { this.environment = v; return this; }
        public Builder branch(String v) { this.branch = v; return this; }
        public Builder triggerSource(String v) { this.triggerSource = v; return this; }
        public Builder fallbackFile(String v) { this.fallbackFile = v; return this; }

        /**
         * Overlay values from {@code -Dtestpulse.*} system properties on top
         * of whatever's currently set. Useful for programmatic defaults that
         * CI can override per run.
         */
        public Builder mergeSystemProperties() {
            applyMap(new PropertySource() {
                @Override public String get(String key) {
                    return System.getProperty(key);
                }
            });
            return this;
        }

        /**
         * Overlay values from {@code TESTPULSE_*} environment variables.
         * Naming convention: {@code testpulse.url} → {@code TESTPULSE_URL}.
         */
        public Builder mergeEnvironment() {
            applyMap(new PropertySource() {
                @Override public String get(String key) {
                    return System.getenv(key.toUpperCase().replace('.', '_'));
                }
            });
            return this;
        }

        /**
         * Overlay values from a {@code .properties} file on the classpath.
         * Silently no-ops if the file isn't found — designed for optional
         * default-config files shipped inside consumer jars.
         */
        public Builder mergeClasspath(final String resourceName) {
            try (InputStream in = Thread.currentThread()
                    .getContextClassLoader().getResourceAsStream(resourceName)) {
                if (in == null) {
                    return this;
                }
                final Properties props = new Properties();
                props.load(in);
                applyMap(new PropertySource() {
                    @Override public String get(String key) {
                        return props.getProperty(key);
                    }
                });
            } catch (IOException ignored) {
            }
            return this;
        }

        public TestPulseConfig build() {
            return new TestPulseConfig(this);
        }

        private void applyMap(PropertySource src) {
            String v;
            if ((v = src.get("testpulse.enabled")) != null) enabled = Boolean.parseBoolean(v);
            if ((v = src.get("testpulse.url")) != null) url = trimSlash(v);
            if ((v = src.get("testpulse.apiKey")) != null) apiKey = v;
            if ((v = src.get("testpulse.connectTimeoutMs")) != null) connectTimeoutMs = Integer.parseInt(v);
            if ((v = src.get("testpulse.readTimeoutMs")) != null) readTimeoutMs = Integer.parseInt(v);
            if ((v = src.get("testpulse.queueCapacity")) != null) queueCapacity = Integer.parseInt(v);
            if ((v = src.get("testpulse.maxRetries")) != null) maxRetries = Integer.parseInt(v);
            if ((v = src.get("testpulse.division")) != null) division = v;
            if ((v = src.get("testpulse.release")) != null) release = v;
            if ((v = src.get("testpulse.user")) != null) user = v;
            if ((v = src.get("testpulse.environment")) != null) environment = v;
            if ((v = src.get("testpulse.branch")) != null) branch = v;
            if ((v = src.get("testpulse.triggerSource")) != null) triggerSource = v;
            if ((v = src.get("testpulse.fallbackFile")) != null) fallbackFile = v;
        }

        private static String trimSlash(String s) {
            if (s == null || s.isEmpty()) return "";
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
    }

    private interface PropertySource {
        String get(String key);
    }
}
