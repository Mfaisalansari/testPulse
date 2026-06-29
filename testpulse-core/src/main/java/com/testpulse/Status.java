package com.testpulse;

/**
 * Outcome of a step or scenario.
 *
 * <p>Values mirror Cucumber's vocabulary so framework integrations can map
 * directly without translation. {@link #fromString(String)} accepts any case
 * and returns {@link #UNDEFINED} for unrecognized input rather than throwing,
 * which keeps the wire format defensive when servers add new values.
 */
public enum Status {

    PASSED,
    FAILED,
    SKIPPED,
    PENDING,
    UNDEFINED,
    AMBIGUOUS;

    /**
     * Parse a status string case-insensitively. Returns {@link #UNDEFINED}
     * for null or unrecognized values rather than throwing.
     */
    public static Status fromString(String value) {
        if (value == null) {
            return UNDEFINED;
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNDEFINED;
        }
    }

    /**
     * Whether this status represents a failure (FAILED only — SKIPPED,
     * UNDEFINED, PENDING, AMBIGUOUS are treated as non-failures).
     */
    public boolean isFailure() {
        return this == FAILED;
    }
}
